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

/// A raw proposal is still a rival until same-face, same-instance evidence
/// proves it redundant. Missing faces are represented by a zero ellipse.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct DuplicateProposal {
    pub body: Rect,
    pub face: Ellipse,
    pub confidence: f32,
    pub foreign: u32,
}

fn same_face(a: Ellipse, b: Ellipse) -> bool {
    valid_seed(a)
        && valid_seed(b)
        && ((a.center_x - b.center_x) / a.radius_x.min(b.radius_x))
            .hypot((a.center_y - b.center_y) / a.radius_y.min(b.radius_y))
            <= 0.35
        && (0.72..=1.39).contains(&(a.radius_x / b.radius_x))
        && (0.72..=1.39).contains(&(a.radius_y / b.radius_y))
}

/// Cheap current mask/box preflight; no face inference needed until a
/// geometrically plausible duplicate pair of unowned observations exists.
pub fn duplicate_geometry(
    masks: &[u8],
    spec: HeadMaskSpec,
    p: &[DuplicateProposal],
) -> Result<Vec<[usize; 2]>, Error> {
    let n = (spec.width as usize)
        .checked_mul(spec.height as usize)
        .ok_or(Error::Invalid)?;
    if p.len() > 32
        || n == 0
        || n.checked_mul(p.len()) != Some(masks.len())
        || (!p.is_empty() && !spec.valid(&masks[..n]))
        || spec.width != spec.transform.proto_size
        || spec.height != spec.transform.proto_size
        || p.iter().any(|q| {
            !q.body.valid()
                || !q.confidence.is_finite()
                || !(0.0..=1.0).contains(&q.confidence)
                || q.foreign > 1
        })
    {
        return Err(Error::Invalid);
    }
    let area = |b: Rect| (b.right - b.left) * (b.bottom - b.top);
    let mut pairs = Vec::new();
    for i in 0..p.len() {
        for j in i + 1..p.len() {
            let (a, b) = if area(p[i].body) >= area(p[j].body) {
                (i, j)
            } else {
                (j, i)
            };
            let (x, y) = (p[a], p[b]);
            if x.foreign != 0 || y.foreign != 0 || x.confidence < 0.4 || y.confidence < 0.3 {
                continue;
            }
            let overlap = (x.body.right.min(y.body.right) - x.body.left.max(y.body.left)).max(0.0)
                * (x.body.bottom.min(y.body.bottom) - x.body.top.max(y.body.top)).max(0.0);
            if overlap / area(y.body) < 0.80 {
                continue;
            }
            let m = &masks[a * n..(a + 1) * n];
            let k = &masks[b * n..(b + 1) * n];
            let (mut inter, mut na, mut nb) = (0usize, 0usize, 0usize);
            for (&v, &w) in m.iter().zip(k) {
                na += usize::from(v >= 128);
                nb += usize::from(w >= 128);
                inter += usize::from(v >= 128 && w >= 128);
            }
            if na.min(nb) < 8 || inter * 5 < na.min(nb) * 4 {
                continue;
            }
            pairs.push([a, b]);
        }
    }
    Ok(pairs)
}

/// Coalesce just one unambiguous duplicate pair for this coverage-only lane.
/// Both detector measurements must be current, independently owned by their
/// raw masks; the smaller proposal must mostly lie inside the larger one.
/// Any third raw mask at the measured face center or committed foreign owner
/// prevents this lane. The canonical tracker still sees all raw proposals.
pub fn duplicate_pair(
    masks: &[u8],
    spec: HeadMaskSpec,
    p: &[DuplicateProposal],
    out: &mut [u8],
) -> Result<[usize; 2], Error> {
    let pairs = duplicate_geometry(masks, spec, p)?;
    let n = (spec.width as usize)
        .checked_mul(spec.height as usize)
        .ok_or(Error::Invalid)?;
    if out.len() != n || !spec.valid(out) {
        return Err(Error::Invalid);
    }
    let mut found = None;
    for [a, b] in pairs {
        let (x, y) = (p[a], p[b]);
        if !same_face(x.face, y.face) {
            continue;
        }
        let inside = |f: Ellipse, r: Rect| {
            f.center_x >= r.left
                && f.center_x <= r.right
                && f.center_y >= r.top
                && f.center_y <= r.top + (r.bottom - r.top) * 0.60
        };
        if !inside(x.face, x.body) || !inside(y.face, y.body) {
            continue;
        }
        let m = &masks[a * n..(a + 1) * n];
        let k = &masks[b * n..(b + 1) * n];
        if ![(m, x.face), (k, y.face)]
            .into_iter()
            .all(|(m, f)| mask_support(m, spec, f).is_some_and(|s| s[0] >= 4 && s[1] >= 12))
        {
            continue;
        }
        if masks.chunks_exact(n).enumerate().any(|(q, m)| {
            q != a
                && q != b
                && [x.face, y.face]
                    .into_iter()
                    .any(|f| mask_support(m, spec, f).is_none_or(|s| s[1] > 0))
        }) {
            continue;
        }
        if found.replace([a, b]).is_some() {
            return Err(Error::NoEvidence);
        }
    }
    let [a, b] = found.ok_or(Error::NoEvidence)?;
    for (i, v) in out.iter_mut().enumerate() {
        *v = masks[a * n + i].max(masks[b * n + i]);
    }
    Ok([a, b])
}

/// Local current-face check at a third scale. No historical pixels used.
pub fn duplicate_face_plan(face: Ellipse, t: crate::geometry::Transform) -> Option<[f32; 6]> {
    if !valid_seed(face) || !t.valid() {
        return None;
    }
    let side =
        (5.0 * face.radius_x.max(face.radius_y)).min(t.source_width.min(t.source_height) as f32);
    if side < 4.0 {
        return None;
    }
    let x = (face.center_x - side * 0.5).clamp(0.0, t.source_width as f32 - side);
    let y = (face.center_y - side * 0.5).clamp(0.0, t.source_height as f32 - side);
    Some([
        x,
        y,
        x + side,
        y + side,
        (face.center_x - x) / side,
        (face.center_y - y) / side,
    ])
}

/// Recheck the pair and every raw rival before a fresh local detector can
/// supply a conservative union of the three current face measurements.
/// The appearance decision is separately required by the caller.
pub fn duplicate_sticker(
    masks: &[u8],
    spec: HeadMaskSpec,
    p: &[DuplicateProposal],
    pair: [usize; 2],
    local: Ellipse,
) -> Result<HeadSticker, Error> {
    let n = (spec.width as usize)
        .checked_mul(spec.height as usize)
        .ok_or(Error::Invalid)?;
    if n > 1024 * 1024 || n == 0 || !valid_seed(local) {
        return Err(Error::Invalid);
    }
    let mut union = vec![0; n];
    if duplicate_pair(masks, spec, p, &mut union)? != pair || spec.person != p[pair[0]].body {
        return Err(Error::NoEvidence);
    }
    let [a, b] = pair;
    if !same_face(p[a].face, local)
        || !same_face(p[b].face, local)
        || !mask_support(&union, spec, local).is_some_and(|s| s[0] >= 4 && s[1] >= 12)
        || masks
            .chunks_exact(n)
            .enumerate()
            .any(|(i, m)| i != a && i != b && mask_support(m, spec, local).is_none_or(|s| s[1] > 0))
    {
        return Err(Error::NoEvidence);
    }
    let mut placement = sticker(&union, spec, p[a].face, local, 0).ok_or(Error::Invalid)?;
    let cell = spec.transform.input_size as f32 / (spec.width as f32 * spec.transform.scale);
    let e = p[b].face;
    let bounds = &mut placement.bounds;
    bounds.left = bounds.left.min(e.center_x - e.radius_x - cell);
    bounds.right = bounds.right.max(e.center_x + e.radius_x + cell);
    bounds.top = bounds.top.min(e.center_y - e.radius_y - cell);
    bounds.bottom = bounds.bottom.max(e.center_y + e.radius_y + cell);
    let half = ((bounds.right - bounds.left).max(bounds.bottom - bounds.top) * 0.5).next_up();
    let center_x = (bounds.left + bounds.right) * 0.5;
    let center_y = (bounds.top + bounds.bottom) * 0.5;
    placement.square = Ellipse {
        center_x,
        center_y,
        radius_x: half,
        radius_y: half,
    };
    if !valid_seed(placement.square)
        || p.iter().enumerate().any(|(i, q)| {
            i != a
                && i != b
                && valid_seed(q.face)
                && (q.face.center_x - center_x).abs() <= half * 1.02
                && (q.face.center_y - center_y).abs() <= half * 1.02
        })
    {
        return Err(Error::NoEvidence);
    }
    Ok(placement)
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
    fn duplicate_only_coalesces_current_same_face_and_contained_unowned_masks() {
        let spec = HeadMaskSpec {
            width: 64,
            height: 64,
            transform: crate::geometry::Transform {
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
        let face = Ellipse {
            center_x: 60.0,
            center_y: 44.0,
            radius_x: 16.0,
            radius_y: 18.0,
        };
        let p = [
            DuplicateProposal {
                body: body(),
                face,
                confidence: 0.45,
                foreign: 0,
            },
            DuplicateProposal {
                body: Rect {
                    left: 28.0,
                    top: 20.0,
                    right: 96.0,
                    bottom: 108.0,
                },
                face,
                confidence: 0.35,
                foreign: 0,
            },
        ];
        let mut m = vec![0; 8192];
        for y in 5..16 {
            m[y * 64 + 10..y * 64 + 20].fill(255);
            m[4096 + y * 64 + 10..4096 + y * 64 + 20].fill(255);
        }
        for y in 16..28 {
            m[y * 64 + 7..y * 64 + 24].fill(255);
            m[4096 + y * 64 + 8..4096 + y * 64 + 23].fill(255);
        }
        for y in 28..53 {
            m[y * 64 + 9..y * 64 + 22].fill(255);
        }
        let mut union = vec![0; 4096];
        assert_eq!(duplicate_geometry(&m, spec, &p).unwrap(), vec![[0, 1]]);
        assert_eq!(duplicate_pair(&m, spec, &p, &mut union), Ok([0, 1]));
        assert_eq!(union, &m[..4096]);
        assert!(duplicate_face_plan(face, spec.transform).is_some());
        let sticker = duplicate_sticker(&m, spec, &p, [0, 1], face).unwrap();
        assert!(sticker.bounds.left <= 44.0 && sticker.bounds.right >= 76.0);
        let mut other = p;
        other[1].face.center_x += 15.0;
        assert_eq!(
            duplicate_pair(&m, spec, &other, &mut union),
            Err(Error::NoEvidence)
        );
        other = p;
        other[1].foreign = 1;
        assert!(duplicate_geometry(&m, spec, &other).unwrap().is_empty());
        let mut weak = p;
        weak[0].confidence = 0.39;
        assert!(duplicate_geometry(&m, spec, &weak).unwrap().is_empty());
        let mut third = m.clone();
        third.extend_from_slice(&m[4096..]);
        let third_p = [p[0], p[1], DuplicateProposal { foreign: 1, ..p[1] }];
        assert_eq!(
            duplicate_pair(&third, spec, &third_p, &mut union),
            Err(Error::NoEvidence)
        );
        let mut blank = m.clone();
        blank[4096..].fill(0);
        assert!(duplicate_geometry(&blank, spec, &p).unwrap().is_empty());
        let mut wrong = face;
        wrong.center_x += 20.0;
        assert_eq!(
            duplicate_sticker(&m, spec, &p, [0, 1], wrong),
            Err(Error::NoEvidence)
        );
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
