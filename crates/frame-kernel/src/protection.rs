//! Protection intent, independently of native detection and rasterization.
//!
//! A body segmentation is evidence about a silhouette, not proof that its face
//! is covered. BODY therefore always requests a face supplement. A supplement
//! belongs to the body fill; only an explicit FACE_ONLY layer uses a sticker.
use crate::geometry::Rect;
use std::collections::BTreeSet;

pub const BODY: u32 = 1;
pub const FACE: u32 = 2;
pub const STICKER: u32 = 4;
pub const SCENE_BODY: u32 = 1;
pub const SCENE_FACE: u32 = 2;
pub const SCENE_OTHER_FACES: u32 = 4;

/// Scope follows explicit selection intent. A current observation may be protected
/// independently of its long-lived identity only for these uniform selections.
pub fn scene_scope(roots: &[i64], body: &[i64], face: &[i64], follow: Option<i64>) -> u32 {
    let roots: BTreeSet<_> = roots.iter().copied().collect();
    let body: BTreeSet<_> = body.iter().copied().collect();
    let face: BTreeSet<_> = face.iter().copied().collect();
    if roots.is_empty() {
        return 0;
    }
    if let Some(hero) = follow {
        if roots.len() > 1
            && roots.contains(&hero)
            && body.is_empty()
            && !face.is_empty()
            && face == roots.iter().copied().filter(|&id| id != hero).collect()
        {
            SCENE_OTHER_FACES
        } else {
            0
        }
    } else if face.is_empty() && body.is_superset(&roots) {
        SCENE_BODY
    } else if body.is_empty() && face.is_superset(&roots) {
        SCENE_FACE
    } else {
        0
    }
}

pub fn compositor_targets(selected: &[i64], covered: &[i64], explicit: &[i64]) -> Vec<i64> {
    let covered: BTreeSet<_> = covered.iter().collect();
    let explicit: BTreeSet<_> = explicit.iter().collect();
    selected
        .iter()
        .copied()
        .filter(|id| !covered.contains(id) || explicit.contains(id))
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}

/// A hero exemption needs exactly one current, exact observation. Predictions,
/// missing observations and duplicate geometry never justify an exemption.
pub fn current_hero_index(detections: &[Rect], observed: Option<Rect>) -> Option<usize> {
    let hero = observed?;
    let mut matches = detections.iter().enumerate().filter(|(_, r)| **r == hero);
    let (index, _) = matches.next()?;
    if matches.next().is_none() {
        Some(index)
    } else {
        None
    }
}

pub fn layers(full_body: bool, face_only: bool, sticker: bool) -> u32 {
    if full_body {
        BODY | FACE
    } else if face_only {
        FACE | if sticker { STICKER } else { 0 }
    } else {
        0
    }
}

pub fn face_targets(body_ids: &[i64], face_ids: &[i64]) -> Vec<i64> {
    body_ids
        .iter()
        .chain(face_ids)
        .copied()
        .collect::<BTreeSet<_>>()
        .into_iter()
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn current_scope_never_exempts_an_ambiguous_or_absent_hero() {
        assert_eq!(
            scene_scope(&[0, 1, 2], &[], &[0, 1], Some(2)),
            SCENE_OTHER_FACES
        );
        assert_eq!(scene_scope(&[0, 1, 2], &[], &[0], Some(2)), 0);
        assert_eq!(scene_scope(&[0, 1], &[0], &[1], None), 0);
        assert_eq!(scene_scope(&[0], &[0], &[], None), SCENE_BODY);
        assert_eq!(scene_scope(&[0], &[], &[0], None), SCENE_FACE);
        assert_eq!(compositor_targets(&[0, 1, 2], &[1, 2], &[2]), [0, 2]);
        let r = Rect {
            left: 1.0,
            top: 2.0,
            right: 3.0,
            bottom: 4.0,
        };
        assert_eq!(current_hero_index(&[r], Some(r)), Some(0));
        assert_eq!(current_hero_index(&[r, r], Some(r)), None);
        assert_eq!(current_hero_index(&[r], None), None);
    }

    #[test]
    fn body_keeps_face_coverage_with_a_mixed_sticker_selection() {
        // A global sticker setting affects only explicitly face-only targets.
        assert_eq!(layers(true, false, true), BODY | FACE);
        assert_eq!(layers(true, true, true), BODY | FACE);
        assert_eq!(layers(false, true, true), FACE | STICKER);
        assert_eq!(layers(false, true, false), FACE);
        assert_eq!(layers(false, false, true), 0);
        assert_eq!(face_targets(&[-3, 9, 2], &[2, 5]), [-3, 2, 5, 9]);
        assert!(face_targets(&[], &[]).is_empty());
    }

    #[test]
    fn c_transport_rejects_short_buffers_without_partial_writes() {
        let body = [9_i64, -3];
        let face = [9_i64, 5];
        let mut output = [42_i64; 4];
        // SAFETY: input/output arrays are aligned, valid, disjoint and live.
        unsafe {
            assert_eq!(
                crate::frame_kernel_face_targets(
                    body.as_ptr(),
                    2,
                    face.as_ptr(),
                    2,
                    output.as_mut_ptr(),
                    3
                ),
                -1
            );
            assert_eq!(output, [42; 4]);
            assert_eq!(
                crate::frame_kernel_face_targets(
                    body.as_ptr(),
                    2,
                    face.as_ptr(),
                    2,
                    output.as_mut_ptr(),
                    4
                ),
                3
            );
            assert_eq!(output, [-3, 5, 9, 42]);
            assert_eq!(
                crate::frame_kernel_face_targets(
                    std::ptr::null(),
                    0,
                    std::ptr::null(),
                    0,
                    std::ptr::null_mut(),
                    0
                ),
                0
            );
        }
    }
}
