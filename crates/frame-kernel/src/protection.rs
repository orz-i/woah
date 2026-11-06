//! Protection intent, independently of native detection and rasterization.
//!
//! A body segmentation is evidence about a silhouette, not proof that its face
//! is covered. BODY therefore always requests a face supplement. A supplement
//! belongs to the body fill; only an explicit FACE_ONLY layer uses a sticker.
use std::collections::BTreeSet;

pub const BODY: u32 = 1;
pub const FACE: u32 = 2;
pub const STICKER: u32 = 4;

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
