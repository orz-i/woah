//! Experimental anonymous garment evidence for a face reappearing after
//! prolonged body occlusion. No face appearance/embedding or identity commit.
//! A qualified result is current-frame coverage only; same clothing, weak
//! samples, competing observations or missing current masks must abstain.
use crate::{
    face_geometry::{HeadMaskSpec, valid_seed},
    face_head_topology::{HeadSticker, sticker},
    face_observations::mask_support,
    geometry::Rect,
    temporal_region::Ellipse,
};

pub const BINS: usize = 512;
const MIN_SAMPLES: u32 = 48;
const MAX_AGE_US: i64 = 2_000_000;
const MIN_AGE_US: i64 = 950_000;
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Garment {
    pub rgb: [f32; BINS],
    pub chroma: [f32; BINS],
    pub samples: u32,
}
impl Default for Garment {
    fn default() -> Self {
        Self {
            rgb: [0.0; BINS],
            chroma: [0.0; BINS],
            samples: 0,
        }
    }
}
impl Garment {
    fn valid(&self) -> bool {
        self.samples >= MIN_SAMPLES
            && [&self.rgb, &self.chroma].iter().all(|h| {
                h.iter().all(|v| v.is_finite() && *v >= 0.0)
                    && (h.iter().sum::<f32>() - 1.0).abs() < 0.001
            })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Error {
    Invalid,
    NoEvidence,
}

/// Sample the upper garment, strictly below the detected face, at current
/// exclusively supported instance pixels. No source pixel is retained.
/// Input RGB is top-down and tightly packed. All raw masks share the spec.
pub fn describe(
    rgb: &[u8],
    masks: &[u8],
    count: usize,
    index: usize,
    spec: HeadMaskSpec,
    face: Ellipse,
) -> Result<Garment, Error> {
    let tr = spec.transform;
    let w = tr.source_width as usize;
    let h = tr.source_height as usize;
    let mw = spec.width as usize;
    let mh = spec.height as usize;
    let size = mw.checked_mul(mh).ok_or(Error::Invalid)?;
    if count == 0
        || count > 256
        || index >= count
        || size == 0
        || masks.len() != size.checked_mul(count).ok_or(Error::Invalid)?
        || rgb.len()
            != w.checked_mul(h)
                .and_then(|n| n.checked_mul(3))
                .ok_or(Error::Invalid)?
        || !valid_seed(face)
        || !spec.valid(&masks[index * size..(index + 1) * size])
    {
        return Err(Error::Invalid);
    }
    let b = spec.person;
    // Require current measured head support from the same instance. The
    // caller already passed the unpadded detector ownership check; at this
    // secondary padded center require 12/16 and no competing mask samples.
    // The mask is intentionally not inferred from historical geometry.
    let own = &masks[index * size..(index + 1) * size];
    if face.center_x < b.left
        || face.center_x > b.right
        || face.center_y < b.top
        || face.center_y > b.top + (b.bottom - b.top) * 0.60
        || !mask_support(own, spec, face).is_some_and(|s| s[0] >= 2 && s[1] >= 12)
        || (0..count).any(|j| {
            j != index
                && mask_support(&masks[j * size..(j + 1) * size], spec, face)
                    .is_none_or(|s| s[1] > 0)
        })
    {
        return Err(Error::NoEvidence);
    }
    let cx = (b.left + b.right) * 0.5;
    let l = (cx - 2.0 * face.radius_x).max(b.left).clamp(0.0, w as f32) as usize;
    let r = (cx + 2.0 * face.radius_x).min(b.right).clamp(0.0, w as f32) as usize;
    let t = (face.center_y + 1.8 * face.radius_y)
        .max(b.top)
        .clamp(0.0, h as f32) as usize;
    let bottom = (face.center_y + 5.0 * face.radius_y)
        .min(b.bottom)
        .clamp(0.0, h as f32) as usize;
    if l >= r || t >= bottom {
        return Err(Error::NoEvidence);
    }
    // Keep the scan bounded for large source faces, preserve the original
    // two-pixel grid for the ordinary ROI. Sample positions are source-space.
    let step = 2.max((r - l).max(bottom - t).div_ceil(128));
    let mut out = Garment::default();
    for y in (t..bottom).step_by(step) {
        for x in (l..r).step_by(step) {
            let px =
                ((x as f32 * tr.scale + tr.pad_left) / tr.input_size as f32 * mw as f32).floor();
            let py =
                ((y as f32 * tr.scale + tr.pad_top) / tr.input_size as f32 * mh as f32).floor();
            if px < 0.0 || py < 0.0 || px >= mw as f32 || py >= mh as f32 {
                continue;
            }
            let at = py as usize * mw + px as usize;
            if masks[index * size + at] < 128
                || (0..count).any(|j| j != index && masks[j * size + at] >= 128)
            {
                continue;
            }
            let p = (y * w + x) * 3;
            let (red, green, blue) = (
                i32::from(rgb[p]),
                i32::from(rgb[p + 1]),
                i32::from(rgb[p + 2]),
            );
            let color = ((red / 32) * 64 + (green / 32) * 8 + blue / 32) as usize;
            let chroma = (((red - green + 256) / 64).min(7) * 64
                + ((green - blue + 256) / 64).min(7) * 8
                + ((red + green + blue) / 96).min(7)) as usize;
            out.rgb[color] += 1.0;
            out.chroma[chroma] += 1.0;
            out.samples += 1;
        }
    }
    if out.samples < MIN_SAMPLES {
        return Err(Error::NoEvidence);
    }
    for h in [&mut out.rgb, &mut out.chroma] {
        for v in h {
            *v /= out.samples as f32;
        }
    }
    Ok(out)
}
fn score(a: &Garment, b: &Garment) -> f32 {
    let coefficient = |x: &[f32], y: &[f32]| {
        x.iter()
            .zip(y)
            .map(|(x, y)| (x * y).sqrt())
            .sum::<f32>()
            .clamp(0.0, 1.0)
    };
    // Both color spaces must agree; a coarse chroma match cannot hide a
    // materially different RGB distribution.
    coefficient(&a.rgb, &b.rgb).min(coefficient(&a.chroma, &b.chroma))
}
#[repr(C)]
#[derive(Clone, Copy)]
pub struct Candidate {
    pub garment: Garment,
    pub body: Rect,
    pub face: Ellipse,
    /// Caller checked fresh detector/mask and no current exact foreign owner.
    /// Ineligible rows still participate as rival observations.
    pub eligible: u32,
}
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Decision {
    pub index: u32,
    pub similarity: f32,
    pub rival_root: f32,
    pub rival_current: f32,
}
/// Two or more distinct pre-occlusion target observations, frozen while
/// absent. All available competing roots and all current garment candidates
/// participate. A same-outfit collision or unqualified body cannot win.
pub fn choose(
    root_body: Rect,
    root_face: Ellipse,
    age_us: i64,
    roots: &[Garment],
    rivals: &[Garment],
    current: &[Candidate],
) -> Result<Decision, Error> {
    if !root_body.valid()
        || !valid_seed(root_face)
        || age_us < 0
        || roots.len() > 8
        || rivals.len() > 256
        || current.len() > 256
        || roots.iter().chain(rivals).any(|g| !g.valid())
        || current
            .iter()
            .any(|c| !c.garment.valid() || !c.body.valid() || !valid_seed(c.face) || c.eligible > 1)
    {
        return Err(Error::Invalid);
    }
    if !(MIN_AGE_US + 1..=MAX_AGE_US).contains(&age_us) || roots.len() < 2 || rivals.is_empty() {
        return Err(Error::NoEvidence);
    }
    let evaluate = |g: &Garment| {
        let mut scores = roots.iter().map(|r| score(r, g)).collect::<Vec<_>>();
        scores.sort_by(|a, b| b.total_cmp(a));
        (0.5 * (scores[0] + scores[1]), scores[1])
    };
    let scores: Vec<_> = current.iter().map(|c| evaluate(&c.garment)).collect();
    let Some((index, &(best, second_root))) = scores
        .iter()
        .enumerate()
        .max_by(|a, b| a.1.0.total_cmp(&b.1.0))
    else {
        return Err(Error::NoEvidence);
    };
    let c = &current[index];
    let rival_current = scores
        .iter()
        .enumerate()
        .filter(|(i, _)| *i != index)
        .map(|(_, x)| x.0)
        .fold(0.0, f32::max);
    let rival_root = rivals
        .iter()
        .map(|r| score(r, &c.garment))
        .fold(0.0, f32::max);
    let height = root_body.bottom - root_body.top;
    let travel = (c.face.center_x - root_face.center_x).hypot(c.face.center_y - root_face.center_y);
    if c.eligible != 1
        || c.face.center_x < c.body.left
        || c.face.center_x > c.body.right
        || c.face.center_y < c.body.top
        || c.face.center_y > c.body.top + (c.body.bottom - c.body.top) * 0.60
        || best < 0.80
        || second_root < 0.75
        || best - rival_root < 0.06
        || best - rival_current < 0.10
        || travel > height * 0.65
        || !(0.50..=1.65).contains(&((c.body.bottom - c.body.top) / height))
        || !(0.60..=1.65).contains(&(c.face.radius_x / root_face.radius_x))
        || !(0.60..=1.65).contains(&(c.face.radius_y / root_face.radius_y))
    {
        return Err(Error::NoEvidence);
    }
    Ok(Decision {
        index: index as u32,
        similarity: best,
        rival_root,
        rival_current,
    })
}

/// Reappearance can have a small visible profile while the current silhouette
/// still contains a connected crown. Preserve the original required sticker,
/// and extend only to supported upper rows connected to the measured face.
/// A disconnected fragment is not head evidence.
pub fn cover_reappearance(
    pixels: &[u8],
    spec: HeadMaskSpec,
    face: Ellipse,
) -> Result<HeadSticker, Error> {
    let mut out = sticker(pixels, spec, face, face, 0).ok_or(Error::Invalid)?;
    let tr = spec.transform;
    let mw = spec.width as usize;
    let mh = spec.height as usize;
    let cell_x = tr.input_size as f32 / (spec.width as f32 * tr.scale);
    let cell_y = tr.input_size as f32 / (spec.height as f32 * tr.scale);
    if !cell_x.is_finite() || !cell_y.is_finite() || cell_x <= 0.0 || cell_y <= 0.0 {
        return Err(Error::Invalid);
    }
    let px = |x: f32| {
        ((x * tr.scale + tr.pad_left) / tr.input_size as f32 * mw as f32)
            .floor()
            .clamp(0.0, mw as f32) as usize
    };
    let py = |y: f32| {
        ((y * tr.scale + tr.pad_top) / tr.input_size as f32 * mh as f32)
            .floor()
            .clamp(0.0, mh as f32) as usize
    };
    let sx = |x: usize| {
        ((x as f32 * cell_x) - tr.pad_left / tr.scale).clamp(0.0, tr.source_width as f32)
    };
    let sy = |y: usize| {
        ((y as f32 * cell_y) - tr.pad_top / tr.scale).clamp(0.0, tr.source_height as f32)
    };
    let l = px(face.center_x - 2.0 * face.radius_x);
    let r = px(face.center_x + 2.0 * face.radius_x)
        .saturating_add(1)
        .min(mw);
    let start = py(face.center_y);
    let top = py(face.center_y - 2.6 * face.radius_y);
    if l >= r || start >= mh || top >= start {
        return Ok(out);
    }
    let cx = px(face.center_x);
    let mut prev = (cx, cx.saturating_add(1));
    let mut rows = 0;
    let mut first = start;
    let mut min_x = mw;
    let mut max_x = 0;
    for y in (top..=start).rev() {
        let mut runs = Vec::new();
        let mut x = l;
        while x < r {
            if pixels[y * mw + x] < 96 {
                x += 1;
                continue;
            }
            let a = x;
            while x < r && pixels[y * mw + x] >= 96 {
                x += 1;
            }
            if a < prev.1 && x > prev.0 {
                runs.push((a, x));
            }
        }
        let [(a, b)] = runs.as_slice() else { break };
        // A run spanning the entire search band may be a connected arm or
        // another structure; do not extend using a clipped width.
        if *a == l && *b == r {
            break;
        }
        prev = (*a, *b);
        rows += 1;
        first = y;
        min_x = min_x.min(*a);
        max_x = max_x.max(*b);
    }
    if rows < 3 || first == top {
        return Ok(out);
    }
    let b = Rect {
        left: out.bounds.left.min((sx(min_x) - cell_x).max(0.0)),
        top: out.bounds.top.min((sy(first) - cell_y).max(0.0)),
        right: out
            .bounds
            .right
            .max((sx(max_x) + cell_x).min(tr.source_width as f32)),
        bottom: out.bounds.bottom,
    };
    if !b.valid() {
        return Err(Error::Invalid);
    }
    let half = ((f64::from(b.right) - f64::from(b.left))
        .max(f64::from(b.bottom) - f64::from(b.top))
        * 0.5) as f32;
    let half = half.next_up();
    if !half.is_finite() || half <= 0.0 {
        return Err(Error::Invalid);
    }
    let choose = |a: f32, b: f32, center: f32| {
        f64::from(center).clamp(
            f64::from(b) - f64::from(half),
            f64::from(a) + f64::from(half),
        ) as f32
    };
    let x = choose(b.left, b.right, out.square.center_x);
    let y = choose(b.top, b.bottom, out.square.center_y);
    let half = half
        .max(x - b.left)
        .max(b.right - x)
        .max(y - b.top)
        .max(b.bottom - y)
        .next_up();
    out.bounds = b;
    out.square = Ellipse {
        center_x: x,
        center_y: y,
        radius_x: half,
        radius_y: half,
    };
    Ok(out)
}

/// Offline-only, one adjacent frame before an independently qualified
/// reappearance. The later face determines search windows, not output pixels
/// or identity. Two differently sized current inference views must agree.
pub fn partial_views(later: Ellipse, width: u32, height: u32) -> Option<[Rect; 2]> {
    if !valid_seed(later) || width < 2 || height < 2 || width > 65535 || height > 65535 {
        return None;
    }
    let view = |x: f32, up: f32, down: f32| Rect {
        left: (later.center_x - x * later.radius_x)
            .round()
            .clamp(0.0, width as f32),
        right: (later.center_x + x * later.radius_x)
            .round()
            .clamp(0.0, width as f32),
        top: (later.center_y - up * later.radius_y)
            .round()
            .clamp(0.0, height as f32),
        bottom: (later.center_y + down * later.radius_y)
            .round()
            .clamp(0.0, height as f32),
    };
    let a = view(5.0, 5.0, 13.0);
    let b = view(3.5, 4.5, 10.0);
    (a.valid() && b.valid() && a != b && b.right - b.left >= 32.0 && b.bottom - b.top >= 32.0)
        .then_some([a, b])
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PartialView {
    pub view: Rect,
    pub face: Ellipse,
    pub bounds: Rect,
    pub garment: Garment,
    pub body_confidence: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PartialCoverage {
    pub measured: Ellipse,
    pub bounds: Rect,
    pub square: Ellipse,
    pub min_garment_similarity: f32,
}

/// A coarse mask at the newly detected face must actually have a boundary
/// there, rather than be solid foreground. A mask whose central sample is
/// positive and whose 4 immediate neighbors are all foreground cannot be
/// contradicted by this experimental local recovery.
fn coarse_boundary(
    pixels: &[u8],
    count: usize,
    spec: HeadMaskSpec,
    face: Ellipse,
) -> Result<(), Error> {
    let size = (spec.width as usize)
        .checked_mul(spec.height as usize)
        .ok_or(Error::Invalid)?;
    if count == 0
        || count > 256
        || size == 0
        || pixels.len() != size.checked_mul(count).ok_or(Error::Invalid)?
        || !spec.valid(&pixels[..size])
    {
        return Err(Error::Invalid);
    }
    let tr = spec.transform;
    let px = ((face.center_x * tr.scale + tr.pad_left) / tr.input_size as f32 * spec.width as f32)
        .floor();
    let py = ((face.center_y * tr.scale + tr.pad_top) / tr.input_size as f32 * spec.height as f32)
        .floor();
    if !px.is_finite()
        || !py.is_finite()
        || px < 1.0
        || py < 1.0
        || px >= spec.width as f32 - 1.0
        || py >= spec.height as f32 - 1.0
    {
        return Err(Error::NoEvidence);
    }
    let w = spec.width as usize;
    let at = py as usize * w + px as usize;
    let mut boundary = false;
    for m in pixels.chunks_exact(size) {
        if m[at] < 96 {
            continue;
        }
        if [at - 1, at + 1, at - w, at + w]
            .into_iter()
            .all(|i| m[i] >= 96)
        {
            return Err(Error::NoEvidence);
        }
        boundary = true;
    }
    if boundary {
        Ok(())
    } else {
        Err(Error::NoEvidence)
    }
}

fn encloses(b: Rect, f: Ellipse) -> bool {
    b.valid()
        && b.left <= f.center_x - f.radius_x
        && b.right >= f.center_x + f.radius_x
        && b.top <= f.center_y - f.radius_y
        && b.bottom >= f.center_y + f.radius_y
}
fn face_agrees(a: Ellipse, b: Ellipse, limit: f32, min: f32, max: f32) -> bool {
    valid_seed(a)
        && valid_seed(b)
        && ((b.center_x - a.center_x) / a.radius_x).hypot((b.center_y - a.center_y) / a.radius_y)
            <= limit
        && (min..=max).contains(&(b.radius_x / a.radius_x))
        && (min..=max).contains(&(b.radius_y / a.radius_y))
}

/// Transport fresh detector/garment/mask support from BOTH planned views.
/// The caller must have already required exactly one current owned face in
/// each view and used describe/cover_reappearance on those current raw masks.
/// All full-frame current face observations veto overlap. No output may seed
/// another retrospective recovery or any tracking/direct observation clock.
#[allow(clippy::too_many_arguments)]
pub fn partial_finish(
    later: Ellipse,
    gap_us: i64,
    root_age_us: i64,
    views: &[PartialView],
    roots: &[Garment],
    rivals: &[Garment],
    current_rivals: &[Garment],
    foreign_faces: &[Ellipse],
    coarse: &[u8],
    count: usize,
    spec: HeadMaskSpec,
) -> Result<PartialCoverage, Error> {
    if views.len() != 2
        || roots.len() > 8
        || rivals.len() > 256
        || current_rivals.len() > 256
        || foreign_faces.len() > 256
        || roots
            .iter()
            .chain(rivals)
            .chain(current_rivals)
            .any(|g| !g.valid())
        || foreign_faces.iter().any(|&f| !valid_seed(f))
    {
        return Err(Error::Invalid);
    }
    let plans = partial_views(
        later,
        spec.transform.source_width,
        spec.transform.source_height,
    )
    .ok_or(Error::Invalid)?;
    if !(1..=150_000).contains(&gap_us)
        || !(950_001..=2_000_000).contains(&root_age_us)
        || root_age_us.saturating_add(gap_us) > 2_000_000
        || roots.len() < 2
        || rivals.is_empty()
    {
        return Err(Error::NoEvidence);
    }
    let root_score = |g: &Garment| {
        let mut s = roots.iter().map(|r| score(r, g)).collect::<Vec<_>>();
        s.sort_by(|a, b| b.total_cmp(a));
        (0.5 * (s[0] + s[1]), s[1])
    };
    let current_rival = current_rivals
        .iter()
        .map(|g| root_score(g).0)
        .fold(0.0, f32::max);
    let mut min_similarity = 1.0_f32;
    for (v, plan) in views.iter().zip(plans) {
        if v.view != plan
            || !v.garment.valid()
            || !valid_seed(v.face)
            || !encloses(v.bounds, v.face)
            || !v.body_confidence.is_finite()
            || !(0.0..=1.0).contains(&v.body_confidence)
        {
            return Err(Error::Invalid);
        }
        // No clipped/undersized impostor or outlying second view.
        if v.body_confidence < 0.40
            || !face_agrees(later, v.face, 0.70, 0.75, 1.35)
            || v.bounds.left < plan.left
            || v.bounds.top < plan.top
            || v.bounds.right > plan.right
            || v.bounds.bottom > plan.bottom
            || v.bounds.right - v.bounds.left > 4.5 * later.radius_x
            || v.bounds.bottom - v.bounds.top > 4.5 * later.radius_y
        {
            return Err(Error::NoEvidence);
        }
        let (best, second) = root_score(&v.garment);
        let rival = rivals
            .iter()
            .map(|r| score(r, &v.garment))
            .fold(0.0, f32::max);
        if best < 0.80 || second < 0.75 || best - rival < 0.06 || best - current_rival < 0.10 {
            return Err(Error::NoEvidence);
        }
        min_similarity = min_similarity.min(best);
        coarse_boundary(coarse, count, spec, v.face)?;
    }
    let (a, b) = (views[0], views[1]);
    if a.body_confidence.max(b.body_confidence) < 0.50
        || !face_agrees(a.face, b.face, 0.25, 0.85, 1.18)
        || score(&a.garment, &b.garment) < 0.80
    {
        return Err(Error::NoEvidence);
    }
    let bounds = Rect {
        left: a.bounds.left.min(b.bounds.left),
        top: a.bounds.top.min(b.bounds.top),
        right: a.bounds.right.max(b.bounds.right),
        bottom: a.bounds.bottom.max(b.bounds.bottom),
    };
    let x = ((f64::from(bounds.left) + f64::from(bounds.right)) * 0.5) as f32;
    let y = ((f64::from(bounds.top) + f64::from(bounds.bottom)) * 0.5) as f32;
    let half = (x - bounds.left)
        .max(bounds.right - x)
        .max(y - bounds.top)
        .max(bounds.bottom - y)
        .next_up();
    if !half.is_finite() || half > 2.5 * later.radius_x.max(later.radius_y) {
        return Err(Error::NoEvidence);
    }
    let square = Ellipse {
        center_x: x,
        center_y: y,
        radius_x: half,
        radius_y: half,
    };
    let render_half = (half * 1.02).max(8.0);
    if foreign_faces.iter().any(|f| {
        (f.center_x - x).abs() < render_half + f.radius_x
            && (f.center_y - y).abs() < render_half + f.radius_y
    }) {
        return Err(Error::NoEvidence);
    }
    Ok(PartialCoverage {
        measured: a.face,
        bounds,
        square,
        min_garment_similarity: min_similarity,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn garment(a: usize, b: usize) -> Garment {
        let mut g = Garment {
            samples: 100,
            ..Garment::default()
        };
        g.rgb[a] = 0.6;
        g.rgb[b] = 0.4;
        g.chroma[a] = 0.6;
        g.chroma[b] = 0.4;
        g
    }
    fn body() -> Rect {
        Rect {
            left: 20.0,
            top: 20.0,
            right: 100.0,
            bottom: 220.0,
        }
    }
    fn face() -> Ellipse {
        Ellipse {
            center_x: 60.0,
            center_y: 40.0,
            radius_x: 12.0,
            radius_y: 16.0,
        }
    }
    fn candidate(g: Garment) -> Candidate {
        Candidate {
            garment: g,
            body: body(),
            face: face(),
            eligible: 1,
        }
    }
    #[test]
    fn unique_garment_reappearance_never_overrides_equal_rivals_or_ineligible_owner() {
        let g = garment(1, 2);
        let foreign = garment(3, 4);
        let roots = [g, g];
        let a = candidate(g);
        let b = candidate(foreign);
        assert_eq!(
            choose(body(), face(), 1_300_000, &roots, &[foreign], &[a, b])
                .unwrap()
                .index,
            0
        );
        assert_eq!(
            choose(body(), face(), 1_300_000, &roots, &[g], &[a, b]),
            Err(Error::NoEvidence)
        );
        assert_eq!(
            choose(body(), face(), 1_300_000, &roots, &[foreign], &[a, a]),
            Err(Error::NoEvidence)
        );
        assert_eq!(
            choose(
                body(),
                face(),
                1_300_000,
                &roots,
                &[foreign],
                &[Candidate { eligible: 0, ..a }, b]
            ),
            Err(Error::NoEvidence)
        );
        assert_eq!(
            choose(body(), face(), 2_000_001, &roots, &[foreign], &[a, b]),
            Err(Error::NoEvidence)
        );
        assert_eq!(
            choose(body(), face(), 1_300_000, &roots[..1], &[foreign], &[a, b]),
            Err(Error::NoEvidence)
        );
    }
    #[test]
    fn connected_crown_extends_current_profile_without_disconnected_fragment() {
        use crate::geometry::Transform;
        let spec = HeadMaskSpec {
            width: 64,
            height: 64,
            transform: Transform {
                scale: 1.0,
                pad_left: 0.0,
                pad_top: 0.0,
                source_width: 256,
                source_height: 256,
                input_size: 256,
                proto_size: 64,
            },
            person: Rect {
                left: 20.0,
                top: 50.0,
                right: 120.0,
                bottom: 240.0,
            },
        };
        let face = Ellipse {
            center_x: 64.0,
            center_y: 76.0,
            radius_x: 10.0,
            radius_y: 12.0,
        };
        let mut pixels = vec![0; 64 * 64];
        for y in 13..21 {
            pixels[y * 64 + 13..y * 64 + 18].fill(255);
        }
        for y in 21..58 {
            pixels[y * 64 + 9..y * 64 + 23].fill(255);
        }
        let base = sticker(&pixels, spec, face, face, 0).unwrap();
        let covered = cover_reappearance(&pixels, spec, face).unwrap();
        assert!(covered.bounds.top <= 48.0);
        assert!(covered.bounds.top <= base.bounds.top);
        assert!(
            covered.bounds.left <= base.bounds.left && covered.bounds.right >= base.bounds.right
        );
        assert!(covered.bounds.bottom >= base.bounds.bottom);
        // Isolated foreground above the connected crown is not attached.
        pixels[11 * 64 + 13..18 + 11 * 64].fill(255);
        let isolated = cover_reappearance(&pixels, spec, face).unwrap();
        assert_eq!(isolated.bounds.top, covered.bounds.top);
        assert_eq!(isolated.square, covered.square);
    }

    #[test]
    fn adjacent_partial_recovery_requires_two_current_views_and_coarse_boundary() {
        use crate::geometry::Transform;
        let later = Ellipse {
            center_x: 180.0,
            center_y: 180.0,
            radius_x: 16.0,
            radius_y: 18.0,
        };
        let f = Ellipse {
            center_x: 179.0,
            center_y: 177.0,
            ..later
        };
        let plans = partial_views(later, 640, 480).unwrap();
        let g = garment(1, 2);
        let rival = garment(3, 4);
        let bounds = Rect {
            left: 159.0,
            top: 154.0,
            right: 200.0,
            bottom: 198.0,
        };
        let a = PartialView {
            view: plans[0],
            face: f,
            bounds,
            garment: g,
            body_confidence: 0.61,
        };
        let b = PartialView {
            view: plans[1],
            ..a
        };
        let spec = HeadMaskSpec {
            width: 160,
            height: 160,
            transform: Transform {
                scale: 1.0,
                pad_left: 0.0,
                pad_top: 80.0,
                source_width: 640,
                source_height: 480,
                input_size: 640,
                proto_size: 160,
            },
            person: Rect {
                left: 0.0,
                top: 0.0,
                right: 640.0,
                bottom: 480.0,
            },
        };
        let mut edge = vec![0_u8; 160 * 160];
        // x=179 -> cell 44, y=177 -> cell 64. Positive foreground
        // ends at the current measured center; right neighbor is clear.
        for y in 0..160 {
            edge[y * 160..y * 160 + 45].fill(255);
        }
        let call = |vs: &[PartialView],
                    masks: &[u8],
                    gap: i64,
                    age: i64,
                    fr: &[Ellipse],
                    rivals: &[Garment],
                    current: &[Garment]| {
            partial_finish(
                later,
                gap,
                age,
                vs,
                &[g, g],
                rivals,
                current,
                fr,
                masks,
                1,
                spec,
            )
        };
        let out = call(&[a, b], &edge, 66_667, 1_100_000, &[], &[rival], &[rival]).unwrap();
        assert!(encloses(out.bounds, f));
        assert!(out.square.radius_x >= 22.0);
        assert!(
            call(
                &[a, b],
                &vec![255; 160 * 160],
                66_667,
                1_100_000,
                &[],
                &[rival],
                &[rival]
            )
            .is_err()
        );
        assert!(
            call(
                &[a, b],
                &vec![0; 160 * 160],
                66_667,
                1_100_000,
                &[],
                &[rival],
                &[rival]
            )
            .is_err()
        );
        assert!(call(&[a, b], &edge, 150_001, 1_100_000, &[], &[rival], &[rival]).is_err());
        assert!(call(&[a, b], &edge, 66_667, 1_980_000, &[], &[rival], &[rival]).is_err());
        assert!(call(&[a, b], &edge, 66_667, 1_100_000, &[f], &[rival], &[rival]).is_err());
        assert!(call(&[a, b], &edge, 66_667, 1_100_000, &[], &[g], &[rival]).is_err());
        assert!(call(&[a, b], &edge, 66_667, 1_100_000, &[], &[rival], &[g]).is_err());
        assert!(call(&[a], &edge, 66_667, 1_100_000, &[], &[rival], &[rival]).is_err());
        assert!(call(&[a, a], &edge, 66_667, 1_100_000, &[], &[rival], &[rival]).is_err());
        let small = PartialView {
            face: Ellipse {
                radius_x: 5.0,
                radius_y: 6.0,
                ..f
            },
            ..b
        };
        assert!(
            call(
                &[a, small],
                &edge,
                66_667,
                1_100_000,
                &[],
                &[rival],
                &[rival]
            )
            .is_err()
        );
    }

    #[test]
    fn garment_sampling_excludes_every_competing_raw_mask_and_face_pixels() {
        use crate::geometry::Transform;
        let spec = HeadMaskSpec {
            width: 64,
            height: 64,
            transform: Transform {
                scale: 1.0,
                pad_left: 0.0,
                pad_top: 0.0,
                source_width: 256,
                source_height: 256,
                input_size: 256,
                proto_size: 64,
            },
            person: body(),
        };
        let mut rgb = vec![0; 256 * 256 * 3];
        for p in rgb.chunks_exact_mut(3) {
            p.copy_from_slice(&[224, 32, 32]);
        }
        let mut masks = vec![0; 8192];
        masks[..4096].fill(255);
        let a = describe(&rgb, &masks, 2, 0, spec, face()).unwrap();
        for y in 0..60 {
            rgb[y * 256 * 3..(y + 1) * 256 * 3].fill(255);
        }
        assert_eq!(describe(&rgb, &masks, 2, 0, spec, face()).unwrap(), a);
        for y in 8..13 {
            masks[4096 + y * 64 + 13..4096 + y * 64 + 18].fill(255);
        }
        assert_eq!(
            describe(&rgb, &masks, 2, 0, spec, face()),
            Err(Error::NoEvidence)
        );
        masks[4096..].fill(255);
        assert_eq!(
            describe(&rgb, &masks, 2, 0, spec, face()),
            Err(Error::NoEvidence)
        );
    }
}
