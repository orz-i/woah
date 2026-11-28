//! Current mask support and ownership of observations from overlapping views.
use crate::{
    face_geometry::{HeadMaskSpec, valid_seed},
    face_localization::Candidate,
    geometry::Rect,
    temporal_region::Ellipse,
};
use std::collections::BTreeSet;

pub fn mask_support(pixels: &[u8], spec: HeadMaskSpec, region: Ellipse) -> Option<[u32; 2]> {
    if !spec.valid(pixels) || !valid_seed(region) {
        return None;
    }
    let mut distinct = BTreeSet::new();
    let mut supported = 0;
    for y in 0..4 {
        for x in 0..4 {
            let sx = region.center_x + (x as f32 - 1.5) * 0.15 * region.radius_x;
            let sy = region.center_y + (y as f32 - 1.5) * 0.15 * region.radius_y;
            let px = ((sx * spec.transform.scale + spec.transform.pad_left)
                / spec.transform.input_size as f32
                * spec.width as f32)
                .floor();
            let py = ((sy * spec.transform.scale + spec.transform.pad_top)
                / spec.transform.input_size as f32
                * spec.height as f32)
                .floor();
            if px < 0.0 || py < 0.0 || px >= spec.width as f32 || py >= spec.height as f32 {
                continue;
            }
            let index = py as usize * spec.width as usize + px as usize;
            distinct.insert(index);
            if pixels[index] >= 128 {
                supported += 1;
            }
        }
    }
    Some([distinct.len() as u32, supported])
}
/// Ownership samples the current detector's measured box. Landmark-centered
/// render padding must not move this evidence window or change exemptions.
/// The caller supplies a current observed person mask, never retained history.
pub fn face_mask_owned(pixels: &[u8], spec: HeadMaskSpec, face: &Candidate, yaw: f32) -> bool {
    if !face.confidence.is_finite() || !(0.0..=1.0).contains(&face.confidence) {
        return false;
    }
    let measured = Candidate {
        bounds: face.bounds,
        confidence: face.confidence,
        points: Vec::new(),
    };
    let Some(region) = crate::face_geometry::detected(&measured, None, yaw) else {
        return false;
    };
    mask_support(pixels, spec, region).is_some_and(|s| s[0] >= 4 && s[1] >= 12)
}
/// Combine ROI-to-scene transport, current-person bounds and current mask
/// ownership. A nearby detected face must not replace another person's head
/// fallback merely because the two person boxes overlap. No history is used.
pub fn current_face_owned(
    pixels: Option<&[u8]>,
    spec: HeadMaskSpec,
    face: &Candidate,
    view: Option<(Rect, f32)>,
    yaw: f32,
) -> Option<bool> {
    if !spec.person.valid()
        || !face.bounds.valid()
        || !face.confidence.is_finite()
        || !(0.0..=1.0).contains(&face.confidence)
        || !yaw.is_finite()
        || face.points.iter().flatten().any(|v| !v.is_finite())
    {
        return None;
    }
    let mapped = if let Some((view, size)) = view {
        if !view.valid() || !size.is_finite() || size <= 0.0 {
            return None;
        }
        let x = |v| view.left + (v / size) * (view.right - view.left);
        let y = |v| view.top + (v / size) * (view.bottom - view.top);
        Candidate {
            bounds: Rect {
                left: x(face.bounds.left),
                top: y(face.bounds.top),
                right: x(face.bounds.right),
                bottom: y(face.bounds.bottom),
            },
            confidence: face.confidence,
            points: face.points.iter().map(|p| [x(p[0]), y(p[1])]).collect(),
        }
    } else {
        face.clone()
    };
    if !mapped.bounds.valid() || mapped.points.iter().flatten().any(|v| !v.is_finite()) {
        return None;
    }
    if let Some(pixels) = pixels
        && !spec.valid(pixels)
    {
        return None;
    }
    let identity_view = Rect {
        left: 0.0,
        top: 0.0,
        right: 1.0,
        bottom: 1.0,
    };
    if !crate::face_localization::owned(&mapped, identity_view, 1.0, spec.person) {
        return Some(false);
    }
    Some(pixels.is_none_or(|p| face_mask_owned(p, spec, &mapped, yaw)))
}

/// Fresh detector ownership for a complete current pair at the top edge.
/// Non-edge owners keep the existing policy. A clipped owner must not attach
/// to the rival mask; a qualified visible edge
/// cap additionally rejects a face outside the actual connected head.
pub fn pair_face_owned(
    masks: &[u8],
    index: usize,
    spec: HeadMaskSpec,
    face: &Candidate,
    view: Rect,
    size: f32,
) -> Option<bool> {
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    if index >= 2 || n == 0 || n.checked_mul(2)? != masks.len() {
        return None;
    }
    let own = &masks[index * n..(index + 1) * n];
    let rival = &masks[(1 - index) * n..(2 - index) * n];
    if !current_face_owned(Some(own), spec, face, Some((view, size)), 0.0)? {
        return Some(false);
    }
    // Only the top-clipped lane is enabled in M3. The independent rear
    // dancer demonstrates that a leaking foreground instance mask is not
    // a reliable universal veto on a non-edge owner's face.
    let cell = spec.transform.input_size as f32 / (spec.height as f32 * spec.transform.scale);
    if spec.person.top > cell || spec.person.top < -cell {
        return Some(true);
    }
    if spec.width != spec.transform.proto_size || spec.height != spec.transform.proto_size {
        return None;
    }
    if rival.iter().filter(|&&v| v >= 128).take(8).count() < 8 {
        return Some(false);
    }
    let measured = Candidate {
        bounds: face.bounds,
        confidence: face.confidence,
        points: Vec::new(),
    };
    let e = crate::face_geometry::detected(&measured, Some((view, size)), 0.0)?;
    if mask_support(rival, spec, e)?.get(1).copied()? > 0 {
        return Some(false);
    }
    if let Some(cap) = crate::face_head_topology::pair_edge_cap(masks, index, spec) {
        let b = cap.bounds;
        if e.center_x < b.left
            || e.center_x > b.right
            || e.center_y < b.top
            || e.center_y > b.bottom
        {
            return Some(false);
        }
    }
    Some(true)
}

/// Compare the same current core samples against every raw mask. The byte
/// scores are evidence of mask conflict, not calibrated probabilities.
fn contested_core(
    masks: &[u8],
    count: usize,
    index: usize,
    spec: HeadMaskSpec,
    face: Ellipse,
) -> Option<(u32, u32, u32, u32)> {
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    if !(2..=32).contains(&count)
        || index >= count
        || n == 0
        || n.checked_mul(count)? != masks.len()
        || !valid_seed(face)
        || !spec.valid(&masks[index * n..(index + 1) * n])
        || spec.width != spec.transform.proto_size
        || spec.height != spec.transform.proto_size
    {
        return None;
    }
    let tr = spec.transform;
    let mut at = Vec::with_capacity(16);
    let mut distinct = BTreeSet::new();
    for y in 0..4 {
        for x in 0..4 {
            let sx = face.center_x + (x as f32 - 1.5) * 0.15 * face.radius_x;
            let sy = face.center_y + (y as f32 - 1.5) * 0.15 * face.radius_y;
            let px =
                ((sx * tr.scale + tr.pad_left) / tr.input_size as f32 * spec.width as f32).floor();
            let py =
                ((sy * tr.scale + tr.pad_top) / tr.input_size as f32 * spec.height as f32).floor();
            if px < 0.0 || py < 0.0 || px >= spec.width as f32 || py >= spec.height as f32 {
                return Some((0, 0, 0, 0));
            }
            let k = py as usize * spec.width as usize + px as usize;
            distinct.insert(k);
            at.push(k);
        }
    }
    if distinct.len() < 4 {
        return Some((0, 0, 0, 0));
    }
    let stat = |m: &[u8]| {
        (
            at.iter().filter(|&&k| m[k] >= 128).count() as u32,
            at.iter().map(|&k| u32::from(m[k])).sum::<u32>(),
        )
    };
    let (own, own_sum) = stat(&masks[index * n..(index + 1) * n]);
    let mut rival = 0;
    let mut rival_sum = 0;
    for (i, m) in masks.chunks_exact(n).enumerate() {
        if i != index {
            let (c, v) = stat(m);
            if (c, v) > (rival, rival_sum) {
                rival = c;
                rival_sum = v;
            }
        }
    }
    Some((own, own_sum, rival, rival_sum))
}

/// Trigger a second look only at a currently contested direct face in a
/// crowded scene when its rival dominates the selected mask. A competing
/// silhouette alone is not a reason to reject or schedule extra inference.
/// Two current square views centered on the observation vary scale; no
/// identity or previous pixels enter the detector check.
pub fn crowded_face_views(
    masks: &[u8],
    count: usize,
    index: usize,
    spec: HeadMaskSpec,
    face: Ellipse,
) -> Option<Option<[[f32; 6]; 2]>> {
    let (own, own_sum, rival, rival_sum) = contested_core(masks, count, index, spec, face)?;
    if count < 3 || own < 12 || rival < 12 || rival_sum < own_sum + 16 * 20 {
        return Some(None);
    }
    let t = spec.transform;
    let plan = |factor: f32| {
        let side = (factor * face.radius_x.max(face.radius_y))
            .min(t.source_width.min(t.source_height) as f32);
        if !side.is_finite() || side < 4.0 {
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
    };
    let views = [plan(5.0)?, plan(7.0)?];
    Some((views[0] != views[1]).then_some(views))
}

/// Require a current local detector corroboration before accepting a
/// strongly disputed direct hit as a trusted observation. Keep the original
/// observation on positive corroboration (no size/placement churn). A mere
/// local miss with the selected mask clearly stronger remains inconclusive.
pub fn crowded_face_accept(
    masks: &[u8],
    count: usize,
    index: usize,
    spec: HeadMaskSpec,
    face: Ellipse,
    local: [Ellipse; 2],
) -> Option<bool> {
    let Some(_) = crowded_face_views(masks, count, index, spec, face)? else {
        return Some(true);
    };
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    let own = &masks[index * n..(index + 1) * n];
    let agrees = |q: Ellipse| {
        valid_seed(q)
            && ((q.center_x - face.center_x) / face.radius_x)
                .hypot((q.center_y - face.center_y) / face.radius_y)
                <= 0.45
            && (0.65..=1.55).contains(&(q.radius_x / face.radius_x))
            && (0.65..=1.55).contains(&(q.radius_y / face.radius_y))
            && mask_support(own, spec, q).is_some_and(|s| s[0] >= 4 && s[1] >= 12)
    };
    // To demote rather than misattribute a valid small/profile face, require
    // strong negative current ownership AND no independent matching hit.
    Some(local.into_iter().any(agrees))
}

/// Following a rejected contested face, a weak body-derived estimate cannot
/// be presented as a current head when its core is strongly rival-dominated.
/// This reports unresolved evidence, NOT a claim that the target is hidden.
pub fn crowded_weak_conflict(
    masks: &[u8],
    count: usize,
    index: usize,
    spec: HeadMaskSpec,
    estimate: Ellipse,
    age_us: i64,
) -> Option<bool> {
    let (_, own_sum, rival, rival_sum) = contested_core(masks, count, index, spec, estimate)?;
    Some(
        count >= 3
            && (0..=150_000).contains(&age_us)
            && rival >= 12
            && rival_sum >= own_sum + 16 * 20,
    )
}

pub fn association_score(face: &Candidate, person: Rect) -> Option<f32> {
    if !face.bounds.valid() || !person.valid() || !face.confidence.is_finite() {
        return None;
    }
    let f = face.bounds;
    let fw = f.right - f.left;
    let fh = f.bottom - f.top;
    let pw = person.right - person.left;
    let ph = person.bottom - person.top;
    if fw <= 1.0
        || fh <= 1.0
        || pw <= 1.0
        || ph <= 1.0
        || !(0.06..=0.85).contains(&(fw / pw))
        || !(0.035..=0.45).contains(&(fh / ph))
    {
        return None;
    }
    let intersection = (f.right.min(person.right) - f.left.max(person.left)).max(0.0)
        * (f.bottom.min(person.bottom) - f.top.max(person.top)).max(0.0);
    let containment = intersection / (fw * fh).max(1.0);
    if containment < 0.80 {
        return None;
    }
    let cx = (f.left + f.right) * 0.5;
    let cy = (f.top + f.bottom) * 0.5;
    if cy > person.top + ph * 0.62 {
        return None;
    }
    let dx = cx - (person.left + person.right) * 0.5;
    let dy = cy - (person.top + ph * 0.18);
    let distance = (dx * dx + dy * dy).sqrt();
    let reference = pw.max(ph * 0.35).max(12.0);
    let proximity = (1.0 - distance / (reference * 1.25)).max(0.0);
    if proximity <= 0.0 {
        return None;
    }
    Some(containment * 0.45 + proximity * 0.45 + face.confidence.clamp(0.0, 1.0) * 0.10)
}
/// Source xywh, retaining the native Float32 origin and side independently.
pub fn upper_body_view(person: Rect) -> Option<[f32; 4]> {
    if !person.valid() {
        return None;
    }
    let w = (person.right - person.left).max(1.0);
    let h = (person.bottom - person.top).max(1.0);
    let side = (w * 1.10).max(h * 0.45);
    let left = (person.left + person.right - side) * 0.5;
    Some([left, person.top, side, side])
}
#[repr(C)]
#[derive(Clone, Copy)]
pub struct ViewObservation {
    pub bounds: Rect,
    pub view: u32,
    pub known_owner: u32,
    pub owner: i64,
}
fn duplicate(a: Rect, b: Rect) -> bool {
    let intersection = (a.right.min(b.right) - a.left.max(b.left)).max(0.0)
        * (a.bottom.min(b.bottom) - a.top.max(b.top)).max(0.0);
    let aa = ((a.right - a.left) * (a.bottom - a.top)).max(1.0);
    let ba = ((b.right - b.left) * (b.bottom - b.top)).max(1.0);
    let dx = (a.left + a.right - b.left - b.right) * 0.5;
    let dy = (a.top + a.bottom - b.top - b.bottom) * 0.5;
    let span = (a.right - a.left)
        .max(a.bottom - a.top)
        .min((b.right - b.left).max(b.bottom - b.top));
    intersection / (aa + ba - intersection).max(1.0) >= 0.20
        && dx * dx + dy * dy <= span * span * 0.25
}
pub fn merge_views(
    input: &[ViewObservation],
    primary: usize,
    require_owner: bool,
) -> Option<Vec<usize>> {
    if input.len() > 4096
        || primary > input.len()
        || input.iter().any(|v| !v.bounds.valid() || v.known_owner > 1)
    {
        return None;
    }
    let mut output: Vec<usize> = (0..primary).collect();
    let mut views: Vec<BTreeSet<u32>> = input[..primary]
        .iter()
        .map(|v| BTreeSet::from([v.view]))
        .collect();
    let mut owners: Vec<Option<i64>> = input[..primary]
        .iter()
        .map(|v| (v.known_owner != 0).then_some(v.owner))
        .collect();
    for (index, current) in input.iter().enumerate().skip(primary) {
        let owner = (current.known_owner != 0).then_some(current.owner);
        let found = output
            .iter()
            .enumerate()
            .find(|(at, previous)| {
                !views[*at].contains(&current.view)
                    && (!require_owner || (owner.is_some() && owners[*at] == owner))
                    && duplicate(current.bounds, input[**previous].bounds)
            })
            .map(|(at, _)| at);
        if let Some(at) = found {
            views[at].insert(current.view);
            let a = current.bounds;
            let b = input[output[at]].bounds;
            if (a.right - a.left) * (a.bottom - a.top) > (b.right - b.left) * (b.bottom - b.top) {
                output[at] = index;
            }
        } else {
            output.push(index);
            views.push(BTreeSet::from([current.view]));
            owners.push(owner);
        }
    }
    Some(output)
}
/// Select a unique best score. Negative entries represent absent evidence.
pub fn unique_best(scores: &[f32], margin: f32) -> Option<usize> {
    if !margin.is_finite()
        || margin < 0.0
        || scores
            .iter()
            .any(|v| !v.is_finite() || !(-1.0..=1.0).contains(v))
    {
        return None;
    }

    let mut ranked: Vec<usize> = scores
        .iter()
        .enumerate()
        .filter(|(_, s)| **s >= 0.0)
        .map(|(i, _)| i)
        .collect();
    ranked.sort_by(|a, b| scores[*b].total_cmp(&scores[*a]));
    let first = *ranked.first()?;
    if ranked.len() > 1 && scores[first] - scores[ranked[1]] < margin {
        return None;
    }
    Some(first)
}
/// Preferred non-exempt choices have already passed the canonical head selector.
/// A hero choice additionally needs a unique score and current-mask validation.
pub fn resolve_owners(
    scores: &[f32],
    people: usize,
    faces: usize,
    preferred: &[i32],
    hero: Option<usize>,
    margin: f32,
) -> Option<Vec<i32>> {
    if people > 4096
        || faces > 4096
        || scores.len() != people.checked_mul(faces)?
        || preferred.len() != people
        || !margin.is_finite()
        || margin < 0.0
        || hero.is_some_and(|h| h >= people)
        || scores
            .iter()
            .any(|v| !v.is_finite() || !(-1.0..=1.0).contains(v))
        || preferred
            .iter()
            .any(|i| *i < -1 || (*i >= 0 && *i as usize >= faces))
    {
        return None;
    }
    let mut output = vec![-1; people];
    for person in 0..people {
        let row = &scores[person * faces..(person + 1) * faces];
        let best = if hero == Some(person) {
            unique_best(row, margin)
        } else {
            (preferred[person] >= 0).then_some(preferred[person] as usize)
        };
        let Some(best) = best else { continue };
        if row[best] < 0.0 {
            continue;
        }
        if (0..people).any(|other| {
            other != person
                && scores[other * faces + best] >= 0.0
                && row[best] - scores[other * faces + best] < margin
        }) {
            continue;
        }
        output[person] = best as i32;
    }
    Some(output)
}
pub fn occlusion_padding(
    pixels: &[u8],
    spec: HeadMaskSpec,
    seed: Ellipse,
    age: i64,
    lease: i64,
) -> Option<Ellipse> {
    let p = spec.person;
    if age < 0
        || lease <= 0
        || age > lease
        || !valid_seed(seed)
        || seed.center_x < p.left
        || seed.center_x > p.right
        || seed.center_y < p.top
        || seed.center_y > p.bottom
    {
        return None;
    }
    let progress = age as f32 / lease as f32;
    let region = Ellipse {
        center_x: seed.center_x,
        center_y: seed.center_y,
        radius_x: seed.radius_x * (1.0 + progress * 0.8),
        radius_y: seed.radius_y * (1.0 + progress * 0.8),
    };
    let support = mask_support(pixels, spec, region)?;
    (support[0] >= 4 && support[1] >= 4).then_some(region)
}
#[cfg(test)]
mod tests {
    use super::*;
    fn rect(x: f32) -> Rect {
        Rect {
            left: x,
            top: 0.0,
            right: x + 20.0,
            bottom: 30.0,
        }
    }
    fn crowded_fixture() -> (HeadMaskSpec, Ellipse, Vec<u8>) {
        let spec = HeadMaskSpec {
            width: 64,
            height: 64,
            person: Rect {
                left: 20.0,
                top: 20.0,
                right: 236.0,
                bottom: 240.0,
            },
            transform: crate::geometry::Transform {
                scale: 1.0,
                pad_left: 0.0,
                pad_top: 0.0,
                source_width: 256,
                source_height: 256,
                input_size: 256,
                proto_size: 64,
            },
        };
        let f = Ellipse {
            center_x: 100.0,
            center_y: 64.0,
            radius_x: 24.0,
            radius_y: 28.0,
        };
        let mut masks = vec![0; 3 * 4096];
        masks[..4096].fill(160);
        masks[4096..8192].fill(220);
        (spec, f, masks)
    }
    #[test]
    fn crowded_direct_requires_strong_rival_and_independent_current_confirmation() {
        let (spec, f, mut masks) = crowded_fixture();
        let miss = Ellipse::default();
        let plans = crowded_face_views(&masks, 3, 0, spec, f).unwrap().unwrap();
        assert_ne!(plans[0], plans[1]);
        assert_eq!(
            crowded_face_accept(&masks, 3, 0, spec, f, [miss, miss]),
            Some(false)
        );
        assert_eq!(
            crowded_face_accept(&masks, 3, 0, spec, f, [f, miss]),
            Some(true)
        );
        let wrong = Ellipse {
            center_x: f.center_x + 30.0,
            ..f
        };
        assert_eq!(
            crowded_face_accept(&masks, 3, 0, spec, f, [wrong, miss]),
            Some(false)
        );
        assert_eq!(
            crowded_face_views(&masks[..8192], 2, 0, spec, f),
            Some(None)
        );
        assert!(crowded_face_views(&masks[..8191], 3, 0, spec, f).is_none());
        masks[..4096].fill(245);
        assert_eq!(crowded_face_views(&masks, 3, 0, spec, f), Some(None));
        assert_eq!(
            crowded_face_accept(&masks, 3, 0, spec, f, [miss, miss]),
            Some(true)
        );
        masks[..4096].fill(201);
        assert_eq!(crowded_face_views(&masks, 3, 0, spec, f), Some(None));
        masks[..4096].fill(200);
        assert!(crowded_face_views(&masks, 3, 0, spec, f).unwrap().is_some());
    }
    #[test]
    fn disputed_weak_region_is_bounded_to_current_rival_support_and_short_age() {
        let (spec, f, mut masks) = crowded_fixture();
        for age in [0, 150_000] {
            assert_eq!(
                crowded_weak_conflict(&masks, 3, 0, spec, f, age),
                Some(true)
            );
        }
        for age in [-1, 150_001] {
            assert_eq!(
                crowded_weak_conflict(&masks, 3, 0, spec, f, age),
                Some(false)
            );
        }
        assert_eq!(
            crowded_weak_conflict(&masks[..8192], 2, 0, spec, f, 0),
            Some(false)
        );
        masks[4096..8192].fill(0);
        assert_eq!(crowded_weak_conflict(&masks, 3, 0, spec, f, 0), Some(false));
        assert!(crowded_weak_conflict(&masks, 3, 3, spec, f, 0).is_none());
    }
    #[test]
    fn ownership_window_does_not_follow_landmark_render_offsets_or_other_people() {
        let spec = HeadMaskSpec {
            width: 160,
            height: 160,
            person: Rect {
                left: 150.0,
                top: 50.0,
                right: 450.0,
                bottom: 600.0,
            },
            transform: crate::geometry::Transform {
                scale: 1.0,
                pad_left: 0.0,
                pad_top: 0.0,
                source_width: 640,
                source_height: 640,
                input_size: 640,
                proto_size: 160,
            },
        };
        let face = Candidate {
            bounds: Rect {
                left: 200.0,
                top: 200.0,
                right: 300.0,
                bottom: 300.0,
            },
            confidence: 0.9,
            points: vec![
                [210.0, 245.0],
                [220.0, 245.0],
                [215.0, 250.0],
                [215.0, 255.0],
            ],
        };
        let mut pixels = vec![0; 25600];
        for y in 55..68 {
            for x in 61..70 {
                pixels[y * 160 + x] = 255;
            }
        }
        let rendered = crate::face_geometry::detected(&face, None, 0.0).unwrap();
        let moved = mask_support(&pixels, spec, rendered).unwrap();
        assert!(
            moved[1] < 12,
            "render offsets must not define the ownership evidence window"
        );
        assert!(face_mask_owned(&pixels, spec, &face, 0.0));
        assert!(!face_mask_owned(&vec![0; 25600], spec, &face, 0.0));
        let other = Candidate {
            bounds: Rect {
                left: 350.0,
                top: 200.0,
                right: 450.0,
                bottom: 300.0,
            },
            ..face
        };
        assert!(
            !face_mask_owned(&pixels, spec, &other, 0.0),
            "a broad person bbox cannot exempt another face"
        );
    }
    #[test]
    fn views_only_merge_across_views_with_known_equal_owners_when_required() {
        let a = ViewObservation {
            bounds: rect(0.0),
            view: 0,
            known_owner: 1,
            owner: 7,
        };
        let b = ViewObservation { view: 1, ..a };
        let c = ViewObservation {
            view: 2,
            known_owner: 0,
            ..a
        };
        assert_eq!(merge_views(&[a, b, c], 1, true), Some(vec![0, 2]));
        assert_eq!(merge_views(&[a, b, c], 1, false), Some(vec![0]));
        assert_eq!(merge_views(&[a, a], 1, false), Some(vec![0, 1]));
    }
    #[test]
    fn ambiguity_and_competing_people_cannot_create_an_exemption() {
        assert_eq!(
            resolve_owners(&[0.9, 0.89, 0.2, 0.8], 2, 2, &[0, 1], Some(0), 0.08),
            Some(vec![-1, -1])
        );
        assert_eq!(
            resolve_owners(&[0.9, 0.1, 0.2, 0.8], 2, 2, &[0, 1], Some(0), 0.08),
            Some(vec![0, 1])
        );
        assert!(resolve_owners(&[f32::NAN], 1, 1, &[0], None, 0.08).is_none());
    }
}
