//! Selected-person association for dance comparison. This deliberately does
//! not use or change the privacy protection tracker. All returned indices
//! denote a current detector instance; no synthetic or retained mask is output.
use crate::geometry::{Rect, Transform, overlap_iou};
use crate::instances::InstanceDetection;

pub const APPEARANCE_BINS: usize = 29;
const MAX_GAP_US: i64 = 4_000_000;
const CONTINUITY_US: i64 = 160_000;
const MIN_CONF: f32 = 0.20;
const STRONG_CONF: f32 = 0.25;
type Appearance = [f32; APPEARANCE_BINS];

/// Low-dimensional, mask-scoped clothing/visible color evidence, not a
/// face/biometric embedding. The mask grid and model input share coordinates.
fn color_bin([r, g, b]: [u8; 3]) -> usize {
    let (r, g, b) = (r as f32, g as f32, b as f32);
    let v = r.max(g).max(b);
    let delta = v - r.min(g).min(b);
    if v < 48. {
        return 0;
    }
    if delta * 255. / v < 45. {
        return 1 + (v as usize / 64).min(3);
    }
    let hue = if v == r {
        (g - b) / delta
    } else if v == g {
        2. + (b - r) / delta
    } else {
        4. + (r - g) / delta
    };
    let h = (hue * 30.).rem_euclid(180.);
    5 + (h as usize / 23).min(7) * 3 + (v as usize / 86).min(2)
}

/// Build the exact same descriptor from native model RGB on both platforms.
/// A zero vector means insufficient reliable pixels. Never sample padding.
pub fn appearance_features(
    transform: Transform,
    masks: &[u8],
    count: usize,
    mut rgb: impl FnMut(usize, usize) -> [u8; 3],
    output: &mut [f32],
) -> Option<()> {
    if !transform.valid() || count > 256 || transform.input_size > 1024 {
        return None;
    }
    let n = transform.proto_size as usize;
    let input = transform.input_size as usize;
    let area = n.checked_mul(n)?;
    if masks.len() != area.checked_mul(count)?
        || output.len() != count.checked_mul(APPEARANCE_BINS)?
    {
        return None;
    }
    if count == 0 {
        return Some(());
    }
    let bins = (0..area)
        .map(|i| {
            let x = i % n;
            let y = i / n;
            let px = ((2 * x + 1) * input / (2 * n)).min(input - 1);
            let py = ((2 * y + 1) * input / (2 * n)).min(input - 1);
            let sx = (px as f32 - transform.pad_left) / transform.scale;
            let sy = (py as f32 - transform.pad_top) / transform.scale;
            if sx < 0.
                || sy < 0.
                || sx >= transform.source_width as f32
                || sy >= transform.source_height as f32
            {
                None
            } else {
                Some(color_bin(rgb(px, py)))
            }
        })
        .collect::<Vec<_>>();
    for (mask, out) in masks
        .chunks_exact(area)
        .zip(output.chunks_exact_mut(APPEARANCE_BINS))
    {
        let mut high = [0u32; APPEARANCE_BINS];
        let mut low = [0u32; APPEARANCE_BINS];
        for (&m, &bin) in mask.iter().zip(&bins) {
            if let Some(b) = bin {
                if m >= 128 {
                    low[b] += 1;
                }
                if m >= 160 {
                    high[b] += 1;
                }
            }
        }
        let n: u32 = high.iter().sum();
        let (hist, n) = if n >= 12 {
            (high, n)
        } else {
            let n = low.iter().sum();
            (low, n)
        };
        for (value, c) in out.iter_mut().zip(hist) {
            *value = if n >= 8 { c as f32 / n as f32 } else { 0. };
        }
    }
    Some(())
}
fn similarity(a: &Appearance, b: &Appearance) -> f32 {
    a.iter()
        .zip(b)
        .map(|(x, y)| (x * y).sqrt())
        .sum::<f32>()
        .clamp(0., 1.)
}
fn feature(v: &[f32]) -> Option<Appearance> {
    let a: Appearance = v.try_into().ok()?;
    let sum = a.iter().sum::<f32>();
    (a.iter().all(|x| x.is_finite() && (0. ..=1.).contains(x))
        && (sum == 0. || (0.99..=1.01).contains(&sum)))
    .then_some(a)
}
fn present(a: &Appearance) -> bool {
    a.iter().any(|x| *x > 0.)
}
fn center(b: Rect) -> [f32; 2] {
    [(b.left + b.right) * 0.5, (b.top + b.bottom) * 0.5]
}
fn distance(a: Rect, b: Rect) -> f32 {
    let a = center(a);
    let b = center(b);
    (a[0] - b[0]).hypot(a[1] - b[1])
}
fn shift(b: Rect, v: [f32; 2], dt: f32) -> Rect {
    Rect {
        left: b.left + v[0] * dt,
        right: b.right + v[0] * dt,
        top: b.top + v[1] * dt,
        bottom: b.bottom + v[1] * dt,
    }
}
fn mask_iou(a: &[u8], b: &[u8]) -> f32 {
    let (mut inter, mut union) = (0u32, 0u32);
    for (&a, &b) in a.iter().zip(b) {
        let a = a >= 128;
        let b = b >= 128;
        inter += u32::from(a && b);
        union += u32::from(a || b);
    }
    inter as f32 / union.max(1) as f32
}
#[derive(Clone)]
struct Pending {
    at: i64,
    bounds: Rect,
    appearance: Appearance,
}
#[derive(Clone)]
struct Identity {
    transform: Transform,
    last_step: i64,
    direction: i64,
    last_seen: i64,
    bounds: Rect,
    mask: Vec<u8>,
    velocity: [f32; 2],
    seed: Appearance,
    recent: Appearance,
    bank: Vec<Appearance>,
    negatives: Vec<Appearance>,
    pending: Option<Pending>,
}
#[derive(Default)]
pub struct DanceCutout {
    identity: Option<Identity>,
}
struct Ranked {
    index: usize,
    score: f32,
    app: f32,
    geom: f32,
    bank: f32,
    contrast: f32,
}

impl DanceCutout {
    /// A selected lane has one immutable user seed and a bounded, cautiously
    /// learned appearance bank. Negative anchors are retained even after
    /// their detections disappear. After a long gap a uniquely supported
    /// candidate must repeat on two consecutive observations. A gap beyond
    /// four seconds requires explicit new user selection; no scene-wide
    /// color-only reacquisition. Invalid inputs do not change state.
    pub fn step(
        &mut self,
        at: i64,
        seed: i32,
        detections: &[InstanceDetection],
        masks: &[u8],
        transform: Transform,
        features: &[f32],
    ) -> Option<i32> {
        if at < 0
            || seed < -1
            || !transform.valid()
            || detections.len() > 256
            || detections
                .iter()
                .any(|d| !d.bounds.valid() || !(0. ..=1.).contains(&d.confidence))
        {
            return None;
        }
        let area = (transform.proto_size as usize).checked_mul(transform.proto_size as usize)?;
        if masks.len() != detections.len().checked_mul(area)?
            || features.len() != detections.len().checked_mul(APPEARANCE_BINS)?
        {
            return None;
        }
        let values = features
            .chunks_exact(APPEARANCE_BINS)
            .map(feature)
            .collect::<Option<Vec<_>>>()?;
        let mask = |j: usize| &masks[j * area..(j + 1) * area];
        if seed >= 0 {
            let j = usize::try_from(seed).ok()?;
            let d = detections.get(j)?;
            if self.identity.is_some()
                || d.confidence < STRONG_CONF
                || !mask(j).iter().any(|x| *x >= 128)
            {
                return None;
            }
            let f = values[j];
            self.identity = Some(Identity {
                transform,
                last_step: at,
                direction: 0,
                last_seen: at,
                bounds: d.bounds,
                mask: mask(j).to_vec(),
                velocity: [0.; 2],
                seed: f,
                recent: f,
                bank: vec![f],
                negatives: values
                    .iter()
                    .enumerate()
                    .filter_map(|(i, a)| (i != j && present(a)).then_some(*a))
                    .collect(),
                pending: None,
            });
            return Some(seed);
        }
        let state = self.identity.as_mut()?;
        let delta = at.checked_sub(state.last_step)?;
        if delta == 0
            || (state.direction != 0 && delta.signum() != state.direction)
            || transform != state.transform
        {
            return None;
        }
        state.last_step = at;
        state.direction = delta.signum();
        let elapsed = at.abs_diff(state.last_seen);
        if elapsed > MAX_GAP_US as u64 || !present(&state.seed) {
            state.pending = None;
            return Some(-1);
        }
        let gap = elapsed as f32 / 1_000_000.;
        let b = state.bounds;
        let scale = (b.right - b.left)
            .max(b.bottom - b.top)
            .max(transform.source_width.max(transform.source_height) as f32 / 32.)
            .max(1.);
        let pred = shift(b, state.velocity, gap.min(0.18));
        let mut ranked = Vec::new();
        for (j, d) in detections.iter().enumerate() {
            let f = &values[j];
            if d.confidence < MIN_CONF || !present(f) {
                continue;
            }
            let bb = d.bounds;
            let seed_match = similarity(&state.seed, f);
            let recent_match = similarity(&state.recent, f);
            let bank = state
                .bank
                .iter()
                .map(|x| similarity(x, f))
                .fold(0., f32::max);
            let negative = state
                .negatives
                .iter()
                .map(|x| similarity(x, f))
                .fold(0., f32::max);
            let contrast = bank - negative;
            // Refutation is independent of geometric continuity: a spectator
            // does not inherit the chosen identity by occupying its old box.
            if contrast < -0.035 {
                continue;
            }
            let app = 0.20 * seed_match + 0.25 * recent_match + 0.55 * bank;
            let dist = distance(bb, pred) / scale;
            let bi = overlap_iou(b, bb);
            let mi = mask_iou(&state.mask, mask(j));
            let geom = bi.max(overlap_iou(pred, bb)).max(mi);
            if d.confidence < STRONG_CONF
                && (elapsed > 150_000
                    || bi < 0.55
                    || mi < 0.50
                    || detections.iter().enumerate().any(|(k, x)| {
                        k != j
                            && x.confidence >= MIN_CONF
                            && overlap_iou(b, x.bounds) >= 0.25
                            && mask_iou(&state.mask, mask(k)) >= 0.20
                    }))
            {
                continue;
            }
            let score = if elapsed <= CONTINUITY_US as u64 {
                if app < 0.66 || dist > 1.25 || (geom < 0.06 && dist > 0.50) {
                    continue;
                }
                0.52 * app + 0.29 * geom + 0.15 * (1. - dist).max(0.) + 0.04 * contrast
            } else {
                if d.confidence < STRONG_CONF
                    || bank < 0.82
                    || app < 0.74
                    || dist > 1.8_f32.min(0.55 + gap * 0.35)
                    || (contrast < -0.03 && (geom < 0.35 || gap > 0.4))
                {
                    continue;
                }
                0.65 * app + 0.17 * geom + 0.13 * (1. - dist).max(0.) + 0.05 * contrast
            };
            ranked.push(Ranked {
                index: j,
                score,
                app,
                geom,
                bank,
                contrast,
            });
        }
        ranked.sort_by(|a, b| b.score.total_cmp(&a.score).then(a.index.cmp(&b.index)));
        let Some(best) = ranked.first() else {
            state.pending = None;
            return Some(-1);
        };
        if ranked
            .get(1)
            .is_some_and(|other| best.score - other.score < 0.045)
        {
            state.pending = None;
            return Some(-1);
        }
        let j = best.index;
        let d = &detections[j];
        let f = values[j];
        if elapsed > CONTINUITY_US as u64 {
            let confirmed = state.pending.as_ref().is_some_and(|p| {
                at.abs_diff(p.at) <= 150_000
                    && (overlap_iou(p.bounds, d.bounds) > 0.25
                        || distance(p.bounds, d.bounds) < scale * 0.35)
                    && similarity(&p.appearance, &f) > 0.8
            });
            if !confirmed {
                state.pending = Some(Pending {
                    at,
                    bounds: d.bounds,
                    appearance: f,
                });
                return Some(-1);
            }
        }
        state.pending = None;
        let old = center(b);
        let new = center(d.bounds);
        for k in 0..2 {
            let v = ((new[k] - old[k]) / gap.max(0.04)).clamp(-4. * scale, 4. * scale);
            state.velocity[k] = 0.6 * state.velocity[k] + 0.4 * v;
        }
        if elapsed <= CONTINUITY_US as u64
            && best.app > 0.75
            && best.geom > 0.23
            && d.confidence >= STRONG_CONF
            && best.contrast > 0.01
            && similarity(&state.seed, &f) > 0.70
        {
            for (r, x) in state.recent.iter_mut().zip(f) {
                *r = 0.90 * *r + 0.10 * x;
            }
            if state.bank.len() < 12 && best.bank < 0.96 {
                state.bank.push(f);
            }
        }
        state.bounds = d.bounds;
        state.mask = mask(j).to_vec();
        state.last_seen = at;
        Some(j as i32)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn t() -> Transform {
        Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 0.,
            source_width: 100,
            source_height: 100,
            input_size: 100,
            proto_size: 4,
        }
    }
    fn d(left: f32, confidence: f32) -> InstanceDetection {
        InstanceDetection {
            bounds: Rect {
                left,
                top: 0.,
                right: left + 30.,
                bottom: 80.,
            },
            confidence,
        }
    }
    fn f(bin: usize) -> Appearance {
        let mut a = [0.; APPEARANCE_BINS];
        a[bin] = 1.;
        a
    }
    fn step(
        lane: &mut DanceCutout,
        at: i64,
        seed: i32,
        ds: &[InstanceDetection],
        fs: &[Appearance],
    ) -> Option<i32> {
        let features = fs.iter().flatten().copied().collect::<Vec<_>>();
        lane.step(at, seed, ds, &vec![255; ds.len() * 16], t(), &features)
    }
    #[test]
    fn distractor_cannot_take_identity_at_the_old_location() {
        let mut lane = DanceCutout::default();
        assert_eq!(
            step(&mut lane, 0, 0, &[d(0., 0.9), d(45., 0.9)], &[f(0), f(4)]),
            Some(0)
        );
        assert_eq!(
            step(&mut lane, 40_000, -1, &[d(0., 0.9)], &[f(4)]),
            Some(-1)
        );
        assert_eq!(step(&mut lane, 80_000, -1, &[d(1., 0.9)], &[f(0)]), Some(0));
    }
    #[test]
    fn reacquisition_requires_two_current_observations_and_is_bounded() {
        let mut lane = DanceCutout::default();
        assert_eq!(step(&mut lane, 0, 0, &[d(0., 0.9)], &[f(0)]), Some(0));
        assert_eq!(
            step(&mut lane, 300_000, -1, &[d(3., 0.9)], &[f(0)]),
            Some(-1)
        );
        assert_eq!(
            step(&mut lane, 340_000, -1, &[d(4., 0.9)], &[f(0)]),
            Some(0)
        );
        assert_eq!(step(&mut lane, 340_000, -1, &[d(5., 0.9)], &[f(0)]), None);
        assert_eq!(
            step(&mut lane, 5_000_000, -1, &[d(5., 0.9)], &[f(0)]),
            Some(-1)
        );
        assert_eq!(
            step(&mut lane, 5_040_000, -1, &[d(5., 0.9)], &[f(0)]),
            Some(-1)
        );
    }
    #[test]
    fn reverse_weak_requires_unique_current_match() {
        let mut lane = DanceCutout::default();
        assert_eq!(step(&mut lane, 400_000, 0, &[d(0., 0.9)], &[f(0)]), Some(0));
        assert_eq!(
            step(&mut lane, 360_000, -1, &[d(0., 0.22)], &[f(0)]),
            Some(0)
        );
        assert_eq!(
            step(
                &mut lane,
                320_000,
                -1,
                &[d(0., 0.22), d(1., 0.22)],
                &[f(0), f(0)]
            ),
            Some(-1)
        );
        assert_eq!(step(&mut lane, 340_000, -1, &[], &[]), None);
    }
    #[test]
    fn features_are_mask_scoped_and_zero_for_too_few_pixels() {
        let mut output = vec![0.; 2 * APPEARANCE_BINS];
        let mut mask = vec![255; 32];
        mask[16..].fill(0);
        mask[16] = 255;
        appearance_features(t(), &mask, 2, |_, _| [10, 10, 10], &mut output).unwrap();
        assert_eq!(output[0], 1.);
        assert!(output[APPEARANCE_BINS..].iter().all(|v| *v == 0.));
    }
}
