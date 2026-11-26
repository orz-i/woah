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
    pub fn score(&self, f: &Appearance) -> f32 {
        0.20 * similarity(&self.anchor, f)
            + 0.25 * similarity(&self.recent, f)
            + 0.55 * self.best(f)
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
