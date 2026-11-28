//! Shared appearance evidence, independent of tracking or output policy.
use crate::geometry::Transform;

pub const APPEARANCE_BINS: usize = 29;
pub type Appearance = [f32; APPEARANCE_BINS];

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
    // Geometry depends on one coordinate only. Lazily compute the color
    // bin of a cell only if some current mask has >=128 confidence there.
    // Dense spectators may share a cell, so cache its bin across instances.
    let coordinates = |source: u32, pad: f32| {
        (0..n)
            .map(|i| {
                let p = ((2 * i + 1) * input / (2 * n)).min(input - 1);
                let v = (p as f32 - pad) / transform.scale;
                (v >= 0. && v < source as f32).then_some(p)
            })
            .collect::<Vec<_>>()
    };
    let xs = coordinates(transform.source_width, transform.pad_left);
    let ys = coordinates(transform.source_height, transform.pad_top);
    const UNSEEN: u8 = 255;
    const PADDING: u8 = 254;
    let mut bins = vec![UNSEEN; area];
    for (mask, out) in masks
        .chunks_exact(area)
        .zip(output.chunks_exact_mut(APPEARANCE_BINS))
    {
        let mut high = [0u32; APPEARANCE_BINS];
        let mut low = [0u32; APPEARANCE_BINS];
        for (i, (&m, bin)) in mask.iter().zip(&mut bins).enumerate() {
            if m < 128 {
                continue;
            }
            if *bin == UNSEEN {
                *bin = match (xs[i % n], ys[i / n]) {
                    (Some(x), Some(y)) => color_bin(rgb(x, y)) as u8,
                    _ => PADDING,
                };
            }
            if *bin != PADDING {
                let b = *bin as usize;
                low[b] += 1;
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
pub(crate) fn similarity(a: &Appearance, b: &Appearance) -> f32 {
    a.iter()
        .zip(b)
        .map(|(x, y)| (x * y).sqrt())
        .sum::<f32>()
        .clamp(0., 1.)
}
pub(crate) fn feature(v: &[f32]) -> Option<Appearance> {
    let a: Appearance = v.try_into().ok()?;
    let sum = a.iter().sum::<f32>();
    (a.iter().all(|x| x.is_finite() && (0. ..=1.).contains(x))
        && (sum == 0. || (0.99..=1.01).contains(&sum)))
    .then_some(a)
}
pub(crate) fn present(a: &Appearance) -> bool {
    a.iter().any(|x| *x > 0.)
}

/// Optional identity evidence carried by the common Track. Privacy sessions
/// do not populate it and their canonical snapshot/wire format is unchanged.
#[derive(Clone)]
pub(crate) struct AppearanceMemory {
    pub anchor: Appearance,
    pub recent: Appearance,
    // Exact feature of the last canonical observation, separate from the
    // smoothed appearance used for normal identity association.
    pub last_observed: Appearance,
    pub bank: Vec<Appearance>,
    pub last_seen: i64,
}
impl AppearanceMemory {
    pub fn new(value: Appearance, at: i64) -> Self {
        Self {
            anchor: value,
            recent: value,
            last_observed: value,
            bank: vec![value],
            last_seen: at,
        }
    }
    pub fn best(&self, f: &Appearance) -> f32 {
        self.bank
            .iter()
            .map(|v| similarity(v, f))
            .fold(0., f32::max)
    }
    pub fn score_with_best(&self, f: &Appearance, best: f32) -> f32 {
        0.20 * similarity(&self.anchor, f) + 0.25 * similarity(&self.recent, f) + 0.55 * best
    }
    pub fn learn(&mut self, f: Appearance) {
        for (r, x) in self.recent.iter_mut().zip(f) {
            *r = 0.90 * *r + 0.10 * x;
        }
        if self.bank.len() < 12 && self.best(&f) < 0.96 {
            self.bank.push(f);
        }
    }
}

// Crowd privacy uses only masked clothing colors below the uppermost 18%
// of the body. Separate torso and lower-body evidence must both agree.
// No face descriptor, image history, or cross-video identity is retained.
#[derive(Clone)]
pub(crate) struct Clothing {
    hist: [[[f32; 512]; 2]; 2],
}
impl Clothing {
    fn score(&self, other: &Self) -> f32 {
        self.hist
            .iter()
            .zip(&other.hist)
            .flat_map(|(a, b)| a.iter().zip(b))
            .map(|(a, b)| {
                a.iter()
                    .zip(b)
                    .map(|(x, y)| (x * y).sqrt())
                    .sum::<f32>()
                    .clamp(0.0, 1.0)
            })
            .fold(1.0, f32::min)
    }
}
use crate::{
    geometry::{Rect, overlap_iou},
    tracking::{Detection, Track, TrackState, Tracker},
    tracking_update::{Frame, center, commit},
};
use std::{collections::BTreeMap, sync::Arc};
#[derive(Clone)]
pub(crate) struct ClothingRoot {
    feature: Clothing,
    bounds: Rect,
    recent: Option<(i64, Clothing)>,
    bank: Vec<Clothing>,
    initial_ambiguous: bool,
}
#[derive(Clone, Default)]
pub(crate) struct CrowdClothing {
    roots: Arc<BTreeMap<i64, ClothingRoot>>,
    current: Arc<Vec<Option<Clothing>>>,
    adaptive: bool,
    motion: bool,
    previous_pixels: Option<Arc<crate::tracking_motion::BodyPixelFrame>>,
    merges: Arc<Vec<crate::tracking_motion::BodyMerge>>,
}
fn clothing_features(d: &[Detection], rgb: &[u8], tr: Transform) -> Option<Vec<Option<Clothing>>> {
    let w = tr.source_width as usize;
    let h = tr.source_height as usize;
    let n = tr.proto_size as usize;
    if !tr.valid()
        || d.len() > 64
        || n < 2
        || rgb.len() != w.checked_mul(h)?.checked_mul(3)?
        || d.iter().any(|d| {
            d.mask
                .as_ref()
                .is_none_or(|m| m.transform != tr || m.width != n || m.height != n || !m.valid())
        })
    {
        return None;
    }
    let mut count = vec![0u8; n * n];
    for d in d {
        for (v, &m) in count.iter_mut().zip(d.mask.as_ref()?.pixels.iter()) {
            *v = v.saturating_add(u8::from(m >= 128));
        }
    }
    let cell = tr.input_size as f32 / n as f32;
    let coordinates = |pad: f32, dim: usize| {
        (0..n)
            .map(|i| {
                let v = ((i as f32 + 0.5) * cell - pad) / tr.scale;
                (v >= 0.0 && v < dim as f32).then_some(v)
            })
            .collect::<Vec<_>>()
    };
    let xs = coordinates(tr.pad_left, w);
    let ys = coordinates(tr.pad_top, h);
    let mut out = Vec::with_capacity(d.len());
    for d in d {
        let b = d.bounds;
        let bh = b.bottom - b.top;
        let bw = b.right - b.left;
        let mut hist = [[[0.0; 512]; 2]; 2];
        let mut samples = [0u32; 2];
        for (i, &m) in d.mask.as_ref()?.pixels.iter().enumerate() {
            if m < 160 || count[i] != 1 {
                continue;
            }
            let (Some(x), Some(y)) = (xs[i % n], ys[i / n]) else {
                continue;
            };
            if x < b.left + 0.10 * bw || x >= b.right - 0.10 * bw {
                continue;
            }
            let f = (y - b.top) / bh;
            let band = if (0.18..0.50).contains(&f) {
                0
            } else if (0.50..0.83).contains(&f) {
                1
            } else {
                continue;
            };
            let q = (y as usize * w + x as usize) * 3;
            let (r, g, b) = (
                i32::from(rgb[q]),
                i32::from(rgb[q + 1]),
                i32::from(rgb[q + 2]),
            );
            let color = (r / 32 * 64 + g / 32 * 8 + b / 32) as usize;
            let chroma = (((r - g + 256) / 64).min(7) * 64
                + ((g - b + 256) / 64).min(7) * 8
                + ((r + g + b) / 96).min(7)) as usize;
            hist[band][0][color] += 1.0;
            hist[band][1][chroma] += 1.0;
            samples[band] += 1;
        }
        if samples.iter().any(|&s| s < 20) {
            out.push(None);
            continue;
        }
        for (h, s) in hist.iter_mut().zip(samples) {
            for a in h {
                for v in a {
                    *v /= s as f32;
                }
            }
        }
        out.push(Some(Clothing { hist }));
    }
    Some(out)
}
impl CrowdClothing {
    pub(crate) fn seed(d: &[Detection], ids: &[i64], rgb: &[u8], tr: Transform) -> Option<Self> {
        let f = clothing_features(d, rgb, tr)?;
        if ids.len() != d.len() {
            return None;
        }
        let roots = ids
            .iter()
            .zip(d)
            .zip(f)
            .filter_map(|((&id, d), f)| {
                f.map(|feature| {
                    (
                        id,
                        ClothingRoot {
                            feature,
                            bounds: d.bounds,
                            recent: None,
                            bank: vec![],
                            initial_ambiguous: false,
                        },
                    )
                })
            })
            .collect();
        Some(Self {
            roots: Arc::new(roots),
            current: Arc::new(vec![]),
            adaptive: false,
            motion: false,
            previous_pixels: None,
            merges: Arc::new(vec![]),
        })
    }
    pub(crate) fn enable_adaptive(&mut self, at: i64, protected: &std::collections::BTreeSet<i64>) {
        self.adaptive = true;
        // Adapt only initial similar-clothing protected roots. Distinctive
        // roots retain the M4 association profile, and both kinds remain
        // competitors under the same reciprocal association constraints.
        let ambiguous = self
            .roots
            .iter()
            .filter_map(|(&id, a)| {
                if !protected.contains(&id) {
                    return None;
                }
                self.roots
                    .iter()
                    .any(|(&other, b)| {
                        other != id
                            && protected.contains(&other)
                            && Self::height(a.bounds).min(Self::height(b.bounds))
                                / Self::height(a.bounds).max(Self::height(b.bounds))
                                >= 0.65
                            && a.feature.score(&b.feature) >= 0.70
                    })
                    .then_some(id)
            })
            .collect::<Vec<_>>();
        for (id, root) in Arc::make_mut(&mut self.roots).iter_mut() {
            root.recent = Some((at, root.feature.clone()));
            root.initial_ambiguous = ambiguous.contains(id);
        }
    }
    pub(crate) fn enable_motion(&mut self) {
        self.motion = true;
    }
    pub(crate) fn is_motion(&self) -> bool {
        self.motion
    }
    pub(crate) fn motion_merges(&self) -> &[crate::tracking_motion::BodyMerge] {
        &self.merges
    }
    pub(crate) fn prepare_motion(
        &mut self,
        before: &Tracker,
        d: &[Detection],
        rgb: &[u8],
        tr: Transform,
        at: i64,
    ) -> Option<()> {
        if !self.motion {
            return Some(());
        }
        let eligible = self
            .roots
            .iter()
            .filter(|(id, r)| r.initial_ambiguous && before.protected_ids.contains(id))
            .map(|(&id, _)| id)
            .collect::<std::collections::BTreeSet<_>>();
        self.merges = Arc::new(vec![]);
        if eligible.is_empty() {
            self.previous_pixels = None;
            return Some(());
        }
        let current = Arc::new(crate::tracking_motion::BodyPixelFrame::new(at, d, rgb, tr)?);
        if let Some(old) = &self.previous_pixels {
            self.merges = Arc::new(crate::tracking_motion::confirmed_body_merges(
                before, old, &current, &eligible,
            ));
        }
        self.previous_pixels = Some(current);
        Some(())
    }
    pub(crate) fn invalidate_motion(&mut self) {
        self.previous_pixels = None;
        self.merges = Arc::new(vec![]);
    }
    pub(crate) fn is_adaptive(&self) -> bool {
        self.adaptive
    }
    // Update only from an exact canonical commit that was independently
    // supported by recent raw-mask continuity against every rival. Neither
    // a predicted mask, a recovery across a long gap, nor a new ID can learn.
    pub(crate) fn learn_current(
        &mut self,
        before: &Tracker,
        after: &Tracker,
        d: &[Detection],
        at: i64,
    ) {
        if !self.adaptive {
            return;
        }
        let mut updates = Vec::new();
        for t in &after.tracks {
            let Some(root) = self.roots.get(&t.id) else {
                continue;
            };
            if !root.initial_ambiguous {
                continue;
            }
            let Some(old) = before.tracks.iter().find(|q| q.id == t.id) else {
                continue;
            };
            if !t.observed_this_frame
                || t.observed_mask_at_us != Some(at)
                || !old.observed_this_frame
                || t.confidence < 0.5
                || !old
                    .observed_mask_at_us
                    .is_some_and(|v| (1..=100_000).contains(&at.saturating_sub(v)))
            {
                continue;
            }
            let Some(mask) = t.observed_mask.as_ref() else {
                continue;
            };
            let Some(prev) = old.observed_mask.as_ref() else {
                continue;
            };
            let mut matches = d.iter().enumerate().filter(|(_, q)| {
                q.bounds == t.observed_bounds
                    && q.mask
                        .as_ref()
                        .is_some_and(|m| m.transform == mask.transform && m.pixels == mask.pixels)
            });
            let Some((j, q)) = matches.next() else {
                continue;
            };
            if matches.next().is_some()
                || !Self::plausible(root, old, q, at)
                || overlap_iou(old.observed_bounds, q.bounds) < 0.65
            {
                continue;
            }
            let mi = crate::tracking_mask::raw_mask_iou(prev, mask);
            if mi < 0.70
                || d.iter().enumerate().any(|(k, q)| {
                    k != j
                        && q.mask.as_ref().is_some_and(|m| {
                            crate::tracking_mask::raw_mask_iou(prev, m) + 0.20 > mi
                        })
                })
                || before.tracks.iter().any(|q| {
                    q.id != old.id
                        && q.observed_mask_at_us
                            .is_some_and(|v| (1..=150_000).contains(&at.saturating_sub(v)))
                        && q.observed_mask.as_ref().is_some_and(|m| {
                            crate::tracking_mask::raw_mask_iou(m, mask) + 0.20 > mi
                        })
                })
            {
                continue;
            }
            let Some(Some(f)) = self.current.get(j) else {
                continue;
            };
            let seed = root.feature.score(f);
            let last = root
                .recent
                .as_ref()
                .filter(|(ts, _)| (1..=150_000).contains(&at.saturating_sub(*ts)))
                .map_or(0.0, |(_, v)| v.score(f));
            if seed < 0.62 && last < 0.72 {
                continue;
            }
            let add_bank =
                seed >= 0.65 && last >= 0.75 && root.bank.iter().all(|g| g.score(f) < 0.93);
            updates.push((t.id, f.clone(), add_bank));
        }
        if updates.is_empty() {
            return;
        }
        let roots = Arc::make_mut(&mut self.roots);
        for (id, f, add_bank) in updates {
            let root = roots.get_mut(&id).unwrap();
            if add_bank {
                if root.bank.len() == 4 {
                    root.bank.remove(0);
                }
                root.bank.push(f.clone());
            }
            root.recent = Some((at, f));
        }
    }
    fn appearance_score(
        &self,
        root: &ClothingRoot,
        t: &Track,
        d: &Detection,
        f: &Clothing,
        at: i64,
    ) -> f32 {
        let initial = root.feature.score(f);
        if !self.adaptive || !root.initial_ambiguous {
            return initial;
        }
        let mut best = initial;
        // A verified diverse bank can corroborate a changed viewpoint. It
        // is never a substitute for the immutable root on its own.
        if initial >= 0.52 && root.bank.len() >= 2 {
            let mut scores = root.bank.iter().map(|g| g.score(f)).collect::<Vec<_>>();
            scores.sort_by(|a, b| b.total_cmp(a));
            best = best.max((scores[0] * 0.6 + scores[1] * 0.4) * 0.97);
        }
        if let Some((ts, old)) = &root.recent
            && (1..=150_000).contains(&at.saturating_sub(*ts))
            && t.observed_mask
                .as_ref()
                .zip(d.mask.as_ref())
                .is_some_and(|(a, b)| crate::tracking_mask::raw_mask_iou(a, b) >= 0.55)
            && overlap_iou(t.observed_bounds, d.bounds) >= 0.55
        {
            best = best.max(old.score(f));
        }
        best
    }
    pub(crate) fn observe(&mut self, d: &[Detection], rgb: &[u8], tr: Transform) -> Option<()> {
        self.current = Arc::new(clothing_features(d, rgb, tr)?);
        Some(())
    }
    pub(crate) fn clear(&mut self) {
        self.current = Arc::new(vec![]);
    }
    fn height(b: Rect) -> f32 {
        b.bottom - b.top
    }
    fn distance(a: Rect, b: Rect) -> f32 {
        let [x, y] = center(a);
        let [u, v] = center(b);
        (x - u).hypot(y - v)
    }
    fn plausible(root: &ClothingRoot, t: &Track, d: &Detection, at: i64) -> bool {
        let h = Self::height(root.bounds);
        let recent = Self::height(t.observed_bounds);
        let dh = Self::height(d.bounds);
        let age = t
            .observed_mask_at_us
            .and_then(|v| at.checked_sub(v))
            .unwrap_or(i64::MAX);
        if !(1..=2_000_000).contains(&age)
            || !matches!(
                t.state,
                TrackState::Active
                    | TrackState::Occluded
                    | TrackState::Reacquiring
                    | TrackState::Lost
            )
            || t.offscreen_dormant
        {
            return false;
        }
        dh >= 0.55 * h
            && dh <= (1.65 * h).max(1.20 * recent)
            && (0.65..=1.60).contains(&(dh / recent))
            && Self::distance(t.observed_bounds, d.bounds)
                <= recent * (0.42 + 0.40 * (age as f32 / 1_000_000.0)).min(1.2)
    }
    fn candidate(&self, t: &Track, d: &Detection, j: usize, at: i64) -> f32 {
        let (Some(root), Some(Some(f))) = (self.roots.get(&t.id), self.current.get(j)) else {
            return 0.0;
        };
        if d.confidence < 0.50 || !Self::plausible(root, t, d, at) {
            return 0.0;
        }
        let app = self.appearance_score(root, t, d, f, at);
        if app < 0.76 {
            return 0.0;
        }
        let dist = Self::distance(t.observed_bounds, d.bounds) / Self::height(t.observed_bounds);
        0.80 * app
            + 0.20 * overlap_iou(t.observed_bounds, d.bounds).max((1.0 - dist).max(0.0) * 0.65)
    }
    /// A protected root may not learn an obvious wrong-outfit or truncated
    /// body. For a prolonged gap require positive clothing evidence.
    pub(crate) fn allowed(&self, t: &Track, d: &Detection, j: usize, at: i64) -> bool {
        let Some(root) = self.roots.get(&t.id) else {
            return true;
        };
        if !Self::plausible(root, t, d, at) {
            return false;
        }
        let age = t
            .observed_mask_at_us
            .and_then(|v| at.checked_sub(v))
            .unwrap_or(i64::MAX);
        // A large, already observed scale change invalidates the immutable
        // seed's normalized garment sampling locations. Trust only very
        // fresh raw mask continuity here, never an unobserved extrapolation.
        if age <= 150_000
            && Self::height(t.observed_bounds) >= 1.45 * Self::height(root.bounds)
            && overlap_iou(t.observed_bounds, d.bounds) >= 0.65
            && t.observed_mask
                .as_ref()
                .zip(d.mask.as_ref())
                .is_some_and(|(a, b)| crate::tracking_mask::raw_mask_iou(a, b) >= 0.70)
        {
            return true;
        }
        let Some(Some(f)) = self.current.get(j) else {
            return age <= 150_000;
        };
        let own = self.appearance_score(root, t, d, f, at);
        own >= if age <= 150_000 { 0.64 } else { 0.76 }
    }
    /// Reserve only reciprocal, current, clothing-supported matches. Every
    /// plausible live track with an immutable initial feature competes. No
    /// clothing-only matching or learning on an occlusion is allowed.
    pub(crate) fn associate(&self, t: &mut Tracker, d: &[Detection], at: i64, frame: &mut Frame) {
        // Reserve a confirmed merged observation without assigning an ID.
        // It cannot seed a new identity or replace either existing anchor.
        for merge in self.merges.iter() {
            frame.quarantined_detections.insert(merge.index);
            for (i, q) in t.tracks.iter().enumerate() {
                if merge.ids.contains(&q.id) {
                    frame.ambiguous_tracks.insert(i);
                }
            }
        }
        let rows = t
            .tracks
            .iter()
            .map(|tr| {
                d.iter()
                    .enumerate()
                    .map(|(j, d)| {
                        if self.merges.iter().any(|m| m.index == j) {
                            0.0
                        } else {
                            self.candidate(tr, d, j, at)
                        }
                    })
                    .collect::<Vec<_>>()
            })
            .collect::<Vec<_>>();
        let mut accepted = Vec::new();
        for (i, row) in rows.iter().enumerate() {
            if !t.protected_ids.contains(&t.tracks[i].id) {
                continue;
            }
            let Some((j, &score)) = row.iter().enumerate().max_by(|a, b| a.1.total_cmp(b.1)) else {
                continue;
            };
            if score < 0.65
                || row
                    .iter()
                    .enumerate()
                    .any(|(k, &s)| k != j && s + 0.045 > score)
                || rows
                    .iter()
                    .enumerate()
                    .any(|(k, r)| k != i && r[j] + 0.045 > score)
            {
                continue;
            }
            if frame.matched_detections.contains(&j) || frame.quarantined_detections.contains(&j) {
                continue;
            }
            // A different freshly observed raw silhouette remains a veto.
            if t.tracks.iter().enumerate().any(|(k, other)| {
                k != i
                    && other
                        .observed_mask_at_us
                        .is_some_and(|v| (1..=150_000).contains(&at.saturating_sub(v)))
                    && other
                        .observed_mask
                        .as_ref()
                        .zip(d[j].mask.as_ref())
                        .is_some_and(|(a, b)| crate::tracking_mask::raw_mask_iou(a, b) >= 0.65)
                    && overlap_iou(other.observed_bounds, d[j].bounds) >= 0.60
            }) {
                continue;
            }
            accepted.push((i, j));
        }
        for (i, j) in accepted {
            frame.matched_tracks.insert(i);
            frame.matched_detections.insert(j);
            commit(&mut t.tracks[i], &d[j], at);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn crowd_test_detection(x: usize, y: usize, w: usize, h: usize) -> Detection {
        let tr = Transform {
            scale: 1.0,
            pad_left: 0.0,
            pad_top: 0.0,
            source_width: 128,
            source_height: 128,
            input_size: 128,
            proto_size: 128,
        };
        let mut mask = vec![0; 128 * 128];
        for row in y..y + h {
            mask[row * 128 + x..row * 128 + x + w].fill(255);
        }
        Detection {
            bounds: Rect {
                left: x as f32,
                top: y as f32,
                right: (x + w) as f32,
                bottom: (y + h) as f32,
            },
            confidence: 0.9,
            foot_y: (y + h) as f32,
            mask: Some(crate::tracking::Mask {
                width: 128,
                height: 128,
                pixels: mask.into(),
                transform: tr,
            }),
        }
    }
    fn crowd_test_rgb(ds: &[Detection], colors: &[[u8; 3]]) -> Vec<u8> {
        let mut rgb = vec![0; 128 * 128 * 3];
        for (d, c) in ds.iter().zip(colors) {
            for (i, &m) in d.mask.as_ref().unwrap().pixels.iter().enumerate() {
                if m >= 160 {
                    rgb[i * 3..i * 3 + 3].copy_from_slice(c);
                }
            }
        }
        rgb
    }
    #[test]
    fn adaptive_learning_requires_exact_unique_consecutive_current_commit() {
        let a = crowd_test_detection(8, 8, 24, 100);
        let b = crowd_test_detection(75, 8, 24, 100);
        let tr = a.mask.as_ref().unwrap().transform;
        let ds = [a.clone(), b.clone()];
        let rgb = crowd_test_rgb(&ds, &[[24, 24, 24], [24, 24, 24]]);
        let mut model = CrowdClothing::seed(&ds, &[0, 1], &rgb, tr).unwrap();
        let mut before = Tracker::new(crate::tracking::TrackingConfig::default());
        before.protected_ids.extend([0, 1]);
        before.initialize(ds.to_vec(), &[]).unwrap();
        for t in &mut before.tracks {
            t.observed_mask_at_us = Some(0);
        }
        model.enable_adaptive(0, &before.protected_ids);
        assert!(model.roots.values().all(|r| r.initial_ambiguous));
        let current = [crowd_test_detection(9, 8, 24, 100), b.clone()];
        let rgb = crowd_test_rgb(&current, &[[24, 24, 24], [24, 24, 24]]);
        model.observe(&current, &rgb, tr).unwrap();
        let mut after = before.clone();
        commit(&mut after.tracks[0], &current[0], 40_000);
        commit(&mut after.tracks[1], &current[1], 40_000);
        model.learn_current(&before, &after, &current, 40_000);
        assert_eq!(model.roots[&0].recent.as_ref().unwrap().0, 40_000);
        assert_eq!(model.roots[&0].bank.len(), 1);
        let immutable = model.roots[&0].feature.clone();
        // A duplicate raw proposal, an unobserved previous track, a stale
        // timestamp, or an unchanged prediction must not renew memory.
        let mut stale = after.clone();
        commit(&mut stale.tracks[0], &current[0], 240_000);
        model.learn_current(&after, &stale, &current, 240_000);
        assert_eq!(model.roots[&0].recent.as_ref().unwrap().0, 40_000);
        let duplicates = [current[0].clone(), current[0].clone(), b];
        let rgb = crowd_test_rgb(&duplicates, &[[24, 24, 24]; 3]);
        model.observe(&duplicates, &rgb, tr).unwrap();
        let mut next = after.clone();
        commit(&mut next.tracks[0], &duplicates[0], 80_000);
        model.learn_current(&after, &next, &duplicates, 80_000);
        assert_eq!(model.roots[&0].recent.as_ref().unwrap().0, 40_000);
        assert!(model.roots[&0].feature.score(&immutable) > 0.999);
        assert_eq!(model.roots[&0].bank.len(), 1);
    }
    #[test]
    fn distinctive_root_keeps_immutable_profile_under_adaptive_mode() {
        let a = crowd_test_detection(8, 8, 24, 100);
        let b = crowd_test_detection(75, 8, 24, 100);
        let tr = a.mask.as_ref().unwrap().transform;
        let ds = [a.clone(), b];
        let rgb = crowd_test_rgb(&ds, &[[180, 40, 60], [24, 24, 24]]);
        let mut model = CrowdClothing::seed(&ds, &[0, 1], &rgb, tr).unwrap();
        let mut before = Tracker::new(crate::tracking::TrackingConfig::default());
        before.protected_ids.extend([0, 1]);
        before.initialize(ds.to_vec(), &[]).unwrap();
        for t in &mut before.tracks {
            t.observed_mask_at_us = Some(0);
        }
        model.enable_adaptive(0, &before.protected_ids);
        assert!(model.roots.values().all(|r| !r.initial_ambiguous));
        model.observe(&ds, &rgb, tr).unwrap();
        let mut after = before.clone();
        commit(&mut after.tracks[0], &a, 40_000);
        model.learn_current(&before, &after, &ds, 40_000);
        assert_eq!(model.roots[&0].recent.as_ref().unwrap().0, 0);
        assert!(model.roots[&0].bank.is_empty());
    }
    #[test]
    fn clothing_excludes_competing_masks_and_top_body_band() {
        let a = crowd_test_detection(10, 8, 24, 100);
        let b = crowd_test_detection(60, 8, 24, 100);
        let tr = a.mask.as_ref().unwrap().transform;
        let rgb = crowd_test_rgb(&[a.clone(), b.clone()], &[[180, 40, 60], [24, 24, 24]]);
        let f = clothing_features(&[a.clone(), b.clone()], &rgb, tr).unwrap();
        assert!(f[0].as_ref().unwrap().score(f[1].as_ref().unwrap()) < 0.1);
        let mut altered = rgb.clone();
        for y in 8..26 {
            for x in 10..34 {
                altered[(y * 128 + x) * 3..(y * 128 + x) * 3 + 3].fill(255);
            }
        }
        let g = clothing_features(&[a.clone(), b.clone()], &altered, tr).unwrap();
        assert!(f[0].as_ref().unwrap().score(g[0].as_ref().unwrap()) > 0.999);
        let v = clothing_features(&[a.clone(), a], &rgb, tr).unwrap();
        assert!(v.iter().all(Option::is_none));
        assert!(clothing_features(&[b], &rgb[..rgb.len() - 1], tr).is_none());
    }
    #[test]
    fn crowd_clothing_rejects_wrong_outfit_fragment_and_stale_recovery() {
        let a = crowd_test_detection(10, 8, 24, 100);
        let b = crowd_test_detection(60, 8, 24, 100);
        let tr = a.mask.as_ref().unwrap().transform;
        let ds = [a.clone(), b.clone()];
        let rgb = crowd_test_rgb(&ds, &[[180, 40, 60], [24, 24, 24]]);
        let mut c = CrowdClothing::seed(&ds, &[0, 1], &rgb, tr).unwrap();
        let mut track = Track::observed(0, a.clone());
        track.observed_mask_at_us = Some(0);
        c.observe(&ds, &rgb, tr).unwrap();
        assert!(c.allowed(&track, &a, 0, 40_000));
        assert!(!c.allowed(&track, &b, 1, 40_000));
        let fragment = crowd_test_detection(10, 8, 24, 30);
        let rgb2 = crowd_test_rgb(std::slice::from_ref(&fragment), &[[180, 40, 60]]);
        c.observe(std::slice::from_ref(&fragment), &rgb2, tr)
            .unwrap();
        assert!(!c.allowed(&track, &fragment, 0, 40_000));
        c.observe(&ds, &rgb, tr).unwrap();
        assert!(!c.allowed(&track, &a, 0, 2_000_001));
    }
    #[test]
    fn crowd_clothing_never_reserves_same_outfit_tie() {
        let a = crowd_test_detection(8, 8, 24, 100);
        let b = crowd_test_detection(70, 8, 24, 100);
        let d = crowd_test_detection(39, 8, 24, 100);
        let tr = a.mask.as_ref().unwrap().transform;
        let ds = [a.clone(), b.clone()];
        let rgb = crowd_test_rgb(&ds, &[[24, 24, 24], [24, 24, 24]]);
        let mut c = CrowdClothing::seed(&ds, &[0, 1], &rgb, tr).unwrap();
        let mut tracker = Tracker::new(crate::tracking::TrackingConfig::default());
        tracker.protected_ids.extend([0, 1]);
        tracker.initialize(ds.to_vec(), &[]).unwrap();
        for t in &mut tracker.tracks {
            t.observed_mask_at_us = Some(0);
        }
        let rgb2 = crowd_test_rgb(std::slice::from_ref(&d), &[[24, 24, 24]]);
        c.observe(std::slice::from_ref(&d), &rgb2, tr).unwrap();
        let mut frame = Frame::default();
        c.associate(&mut tracker, std::slice::from_ref(&d), 40_000, &mut frame);
        assert!(frame.matched_tracks.is_empty());
    }
    #[test]
    fn lazy_foreground_bins_match_eager_reference_including_padding() {
        let t = Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 2.,
            source_width: 8,
            source_height: 4,
            input_size: 8,
            proto_size: 8,
        };
        let mut state = 19u32;
        let mut random = || {
            state = state.wrapping_mul(1664525).wrapping_add(1013904223);
            (state >> 24) as u8
        };
        let rgb = (0..64)
            .map(|_| [random(), random(), random()])
            .collect::<Vec<_>>();
        let mut masks = (0..5 * 64).map(|_| random()).collect::<Vec<_>>();
        masks[0..64].fill(0);
        masks[64..128].fill(150); // fallback histogram
        let mut output = [0.; 5 * APPEARANCE_BINS];
        appearance_features(t, &masks, 5, |x, y| rgb[y * 8 + x], &mut output).unwrap();
        for (mask, out) in masks
            .chunks_exact(64)
            .zip(output.chunks_exact(APPEARANCE_BINS))
        {
            let mut high = [0u32; APPEARANCE_BINS];
            let mut low = [0u32; APPEARANCE_BINS];
            for (i, &v) in mask.iter().enumerate() {
                let y = i / 8;
                if !(2..6).contains(&y) {
                    continue;
                }
                let b = color_bin(rgb[i]);
                if v >= 128 {
                    low[b] += 1;
                }
                if v >= 160 {
                    high[b] += 1;
                }
            }
            let n: u32 = high.iter().sum();
            let (hist, n) = if n >= 12 {
                (high, n)
            } else {
                let n = low.iter().sum();
                (low, n)
            };
            let expected = hist.map(|c| if n >= 8 { c as f32 / n as f32 } else { 0. });
            assert_eq!(out, expected);
        }
    }
}
