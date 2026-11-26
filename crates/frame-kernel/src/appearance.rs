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
    pub bank: Vec<Appearance>,
    pub last_seen: i64,
}
impl AppearanceMemory {
    pub fn new(value: Appearance, at: i64) -> Self {
        Self {
            anchor: value,
            recent: value,
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

#[cfg(test)]
mod tests {
    use super::*;
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
