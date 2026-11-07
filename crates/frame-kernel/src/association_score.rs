//! Prediction-first region association metrics, independent of track ownership.
use crate::geometry::{Rect, overlap_iou};

#[repr(C)]
#[derive(Clone, Copy)]
pub struct AssociationWeights {
    pub bounds: f32,
    pub mask: f32,
    pub motion: f32,
    pub direction: f32,
    pub gating_threshold: f32,
}
impl Default for AssociationWeights {
    fn default() -> Self {
        Self {
            bounds: 0.35,
            mask: 0.25,
            motion: 0.25,
            direction: 0.15,
            gating_threshold: 16.0,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct AssociationEvidence {
    pub predicted: Rect,
    pub observed: Rect,
    pub candidate: Rect,
    pub mask_iou: f32,
    pub gating_distance: f32,
    pub velocity_x: f32,
    pub velocity_y: f32,
    pub history_x: f32,
    pub history_y: f32,
    pub history_age_us: i64,
    /// Bits 0..1: active/occluded/reacquiring/lost; bit 2: group membership;
    /// bits 3/4: observed/candidate masks are present.
    pub flags: u32,
}

fn dimensions(r: Rect) -> (f32, f32, f32, f32) {
    let w = (r.right - r.left).max(0.0);
    let h = (r.bottom - r.top).max(0.0);
    (w, h, r.left + w / 2.0, r.top + h / 2.0)
}

/// Release a quarantined observation only after a unique reciprocal identity
/// match. A single group's indirect (expanded-detection) support requires
/// recovery-grade agreement in both bounds and mask; current owner support
/// remains quarantined. Flags: strict match, durable non-body identity, current
/// owner support. Historical merged bounds alone are not current owner support.
pub fn reservation_release(
    flags: u32,
    owner_groups: u32,
    bbox_iou: f32,
    mask_iou: f32,
) -> Option<bool> {
    if flags > 7
        || ![bbox_iou, mask_iou]
            .into_iter()
            .all(|v| v.is_finite() && (0.0..=1.0).contains(&v))
    {
        return None;
    }
    Some(
        flags & 3 == 3
            && owner_groups > 0
            && (owner_groups >= 2 || (flags & 4 == 0 && bbox_iou >= 0.50 && mask_iou >= 0.45)),
    )
}
/// A bounding-box edge can reserve an unrelated current observation. Only a
/// strict non-body match may escape that direct reservation, with the existing
/// .50 box/.45 mask evidence, and only after every owner has retained foreground evidence and is refuted by the
/// canonical current identity-evidence gate. Unknown owner evidence keeps it held.
pub fn reservation_release_with_owner_evidence(
    flags: u32,
    owner_groups: u32,
    bbox_iou: f32,
    mask_iou: f32,
    all_owners_refuted: bool,
) -> Option<bool> {
    let ordinary = reservation_release(flags, owner_groups, bbox_iou, mask_iou)?;
    Some(
        ordinary
            || (all_owners_refuted
                && flags & 4 != 0
                && reservation_release(flags & !4, owner_groups, bbox_iou, mask_iou)?),
    )
}

pub fn score(e: AssociationEvidence, c: AssociationWeights) -> Option<f32> {
    let rect_finite = |r: Rect| {
        [r.left, r.top, r.right, r.bottom]
            .iter()
            .all(|v| v.is_finite())
    };
    if ![e.predicted, e.observed, e.candidate]
        .into_iter()
        .all(rect_finite)
        || [
            e.mask_iou,
            e.gating_distance,
            e.velocity_x,
            e.velocity_y,
            e.history_x,
            e.history_y,
            c.bounds,
            c.mask,
            c.motion,
            c.direction,
            c.gating_threshold,
        ]
        .iter()
        .any(|x| !x.is_finite())
        || !(0.0..=1.0).contains(&e.mask_iou)
        || c.gating_threshold <= 0.0
        || e.flags > 31
        || e.history_age_us < 0
        || [c.bounds, c.mask, c.motion, c.direction]
            .iter()
            .any(|&x| x < 0.0)
    {
        return None;
    }
    let (w, h, cx, cy) = dimensions(e.predicted);
    let (_, _, dx, dy) = dimensions(e.candidate);
    let (ow, oh, ox, oy) = dimensions(e.observed);
    let distance = (((dx - cx) * (dx - cx) + (dy - cy) * (dy - cy)) as f64).sqrt() as f32;
    let flexible = e.flags & 3 >= 2 || e.flags & 4 != 0;
    let limit = if flexible {
        (w * 2.5).max(h * 2.0)
    } else {
        (w * 1.5).max(h * 0.8)
    };
    if limit <= 0.0 {
        return Some(0.0);
    }
    let bbox = overlap_iou(e.predicted, e.candidate);
    if bbox <= 0.0 && distance > limit {
        return Some(0.0);
    }
    let dist_score = (1.0 - distance / limit).clamp(0.0, 1.0);
    let motion =
        dist_score.max((1.0 - e.gating_distance / (c.gating_threshold * 2.0)).clamp(0.0, 1.0));
    if e.flags & 3 == 2 {
        let magnitude = e.history_x * e.history_x + e.history_y * e.history_y;
        let reliable = 10.0_f32.max(ow.max(oh) * 0.08);
        if e.history_age_us <= 500_000 && magnitude >= reliable * reliable {
            let progress = ((dx - ox) * e.history_x + (dy - oy) * e.history_y) / magnitude;
            if progress < 0.15 {
                return Some(0.0);
            }
        }
    }
    let direction = |v: f32, d: f32| {
        if v.abs() > 30.0 && d.abs() > 10.0 {
            Some(v * d > 0.0)
        } else {
            None
        }
    };
    let sx = direction(e.velocity_x, dx - ox);
    let sy = direction(e.velocity_y, dy - oy);
    let direction = if sx == Some(false) || sy == Some(false) {
        0.0
    } else if sx == Some(true) || sy == Some(true) {
        1.0
    } else {
        0.5
    };
    let raw = c.bounds * bbox + c.mask * e.mask_iou + c.motion * motion + c.direction * direction;
    let penalty = if e.flags & 24 == 24 && e.mask_iou < 0.05 {
        0.4
    } else {
        1.0
    };
    Some((raw * penalty).clamp(0.0, 1.0))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn indirect_reservation_does_not_erase_a_strong_independent_observation() {
        assert_eq!(reservation_release(3, 1, 0.83730394, 0.8064516), Some(true));
        assert_eq!(
            reservation_release(7, 1, 0.83730394, 0.8064516),
            Some(false)
        );
        assert_eq!(reservation_release(3, 1, 0.9, 0.449), Some(false));
        assert_eq!(reservation_release(3, 1, 0.499, 0.9), Some(false));
        assert_eq!(reservation_release(2, 1, 0.9, 0.9), Some(false));
        assert_eq!(reservation_release(1, 2, 0.9, 0.9), Some(false));
        assert_eq!(reservation_release(7, 2, 0.6, 0.6), Some(true));
        assert_eq!(reservation_release(3, 0, 0.9, 0.9), Some(false));
        assert_eq!(reservation_release(3, 1, f32::NAN, 0.9), None);
    }
    #[test]
    fn expired_motion_history_cannot_lock_a_reacquiring_region() {
        let a = Rect {
            left: 10.0,
            top: 20.0,
            right: 110.0,
            bottom: 220.0,
        };
        let mut e = AssociationEvidence {
            predicted: a,
            observed: a,
            candidate: a,
            mask_iou: 1.0,
            gating_distance: 0.0,
            velocity_x: 0.0,
            velocity_y: 0.0,
            history_x: 30.0,
            history_y: 0.0,
            history_age_us: 0,
            flags: 26,
        };
        assert_eq!(score(e, AssociationWeights::default()), Some(0.0));
        e.history_age_us = 500_001;
        assert!(score(e, AssociationWeights::default()).unwrap() > 0.9);
    }
}

#[cfg(test)]
mod owner_evidence_tests {
    use super::*;
    #[test]
    fn direct_box_touch_does_not_override_an_independent_strong_current_mask() {
        assert_eq!(
            reservation_release(7, 1, 0.7882628, 0.76811594),
            Some(false)
        );
        assert_eq!(
            reservation_release_with_owner_evidence(7, 1, 0.7882628, 0.76811594, true),
            Some(true)
        );
        assert_eq!(
            reservation_release_with_owner_evidence(7, 1, 0.7882628, 0.76811594, false),
            Some(false)
        );
    }
    #[test]
    fn owner_refutation_never_weakens_identity_or_body_selection_gates() {
        for (flags, groups, bounds, mask) in [
            (7, 1, 0.49, 0.9),
            (7, 1, 0.9, 0.44),
            (5, 1, 0.9, 0.9),
            (6, 1, 0.9, 0.9),
            (7, 0, 0.9, 0.9),
        ] {
            assert_eq!(
                reservation_release_with_owner_evidence(flags, groups, bounds, mask, true),
                Some(false)
            );
        }
        assert_eq!(
            reservation_release_with_owner_evidence(7, 1, f32::NAN, 0.9, true),
            None
        );
    }
}
