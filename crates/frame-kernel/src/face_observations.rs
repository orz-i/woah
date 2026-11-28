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
