//! Bounded selected-subject detail observation. A crop is a detection input,
//! never a new identity seed or a predicted render mask.
use crate::{
    composition::Viewport,
    dance_cutout::CutoutPolicy,
    geometry::{Rect, Transform, detail_complete, overlap_iou, reproject_mask},
    instances::InstanceDetection,
    tracking::{Mask, Tracker},
    tracking_mask::raw_mask_iou,
};
use std::sync::Arc;

/// Use the last confirmed subject location, without committing the prediction.
/// Limit search and magnification so this cannot become a scene-wide re-ID.
pub(crate) fn view(tracker: &Tracker, policy: &CutoutPolicy, at: i64) -> Option<Viewport> {
    let clock = policy.clock.as_ref()?;
    let delta = at.checked_sub(clock.last)?;
    if delta == 0 || (clock.direction != 0 && delta.signum() != clock.direction) {
        return None;
    }
    let elapsed = at
        .checked_sub(clock.anchor)?
        .checked_abs()?
        .checked_add(1)?;
    let track = tracker.tracks.iter().find(|t| t.id == clock.chosen)?;
    let age = elapsed.checked_sub(track.appearance.as_ref()?.last_seen)?;
    if age <= 0 || age > 1_200_000 {
        return None;
    }
    let t = clock.transform;
    let (sw, sh) = (t.source_width as f32, t.source_height as f32);
    if sw > 65535. || sh > 65535. {
        return None;
    }
    let b = track.observed_bounds;
    let (bw, bh) = (b.right - b.left, b.bottom - b.top);
    let longest = sw.max(sh);
    if !b.valid() || bw.max(bh) > longest * 0.48 {
        return None;
    }
    let mut filter = track.filter;
    let pred =
        crate::tracking_lifecycle::bound_prediction(b, filter.predict(elapsed), 0.70, 0.65, 1.55);
    let center = |x: f32, y: f32| (x + y) * 0.5;
    let cx = center(b.left, b.right) * 0.5 + center(pred.left, pred.right) * 0.5;
    let cy = center(b.top, b.bottom) * 0.5 + center(pred.top, pred.bottom) * 0.5;
    let grow = 1. + 0.2 * (age as f32 / 1_200_000.);
    let side = (bw.max(bh) * 1.85 * grow)
        .max(longest * 0.32)
        .min(longest * 0.65);
    let w = side.min(sw).round() as i32;
    let h = side.min(sh).round() as i32;
    if w < 2 || h < 2 || (w as f32).max(h as f32) > longest * 0.66 {
        return None;
    }
    let x = (cx - w as f32 * 0.5).round().clamp(0., sw - w as f32) as i32;
    let y = (cy - h as f32 * 0.5).round().clamp(0., sh - h as f32) as i32;
    Some(Viewport {
        x,
        top: y,
        width: w,
        height: h,
    })
}

/// Remap a local CURRENT candidate. Reject crop-edge truncation and strong
/// duplicates of full-frame observations. Never remove original observations;
/// shared global assignment still owns the decision.
#[allow(clippy::too_many_arguments)]
pub fn remap(
    local: InstanceDetection,
    mask: &[u8],
    local_t: Transform,
    scene: Transform,
    roi: Viewport,
    full: &[InstanceDetection],
    full_masks: &[u8],
    out: &mut [u8],
) -> Option<InstanceDetection> {
    if !scene.valid()
        || scene.source_width > 65535
        || scene.source_height > 65535
        || !local_t.valid()
        || roi.x < 0
        || roi.top < 0
        || roi.width <= 0
        || roi.height <= 0
        || i64::from(roi.x) + i64::from(roi.width) > i64::from(scene.source_width)
        || i64::from(roi.top) + i64::from(roi.height) > i64::from(scene.source_height)
        || local_t.source_width != roi.width as u32
        || local_t.source_height != roi.height as u32
        || local.confidence < 0.25
        || !local.confidence.is_finite()
        || local.confidence > 1.
        || !local.bounds.valid()
        || full.len() > 256
    {
        return None;
    }
    let view = Rect {
        left: roi.x as f32,
        top: roi.top as f32,
        right: (roi.x + roi.width) as f32,
        bottom: (roi.top + roi.height) as f32,
    };
    if !detail_complete(local.bounds, view, scene.source_width, scene.source_height) {
        return None;
    }
    let a = (scene.proto_size as usize).pow(2);
    if full_masks.len() != full.len() * a
        || out.len() != a
        || !reproject_mask(mask, local_t, scene, view, out)
        || !out.iter().any(|p| *p >= 128)
    {
        return None;
    }
    let bbox = Rect {
        left: local.bounds.left + view.left,
        top: local.bounds.top + view.top,
        right: local.bounds.right + view.left,
        bottom: local.bounds.bottom + view.top,
    };
    if bbox.left < view.left
        || bbox.top < view.top
        || bbox.right > view.right
        || bbox.bottom > view.bottom
    {
        return None;
    }
    let mapped = Mask {
        width: scene.proto_size as usize,
        height: scene.proto_size as usize,
        pixels: Arc::from(&*out),
        transform: scene,
    };
    for (other, raw) in full.iter().zip(full_masks.chunks_exact(a)) {
        if overlap_iou(bbox, other.bounds) < 0.35 {
            continue;
        }
        let m = Mask {
            width: scene.proto_size as usize,
            height: scene.proto_size as usize,
            pixels: Arc::from(raw),
            transform: scene,
        };
        if raw_mask_iou(&mapped, &m) >= 0.45 {
            return None;
        }
    }
    Some(InstanceDetection {
        bounds: bbox,
        confidence: local.confidence,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn remap_rejects_truncated_and_duplicate_and_keeps_source_coordinates() {
        let t = Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 0.,
            source_width: 100,
            source_height: 100,
            input_size: 100,
            proto_size: 20,
        };
        let l = Transform {
            scale: 2.,
            pad_left: 0.,
            pad_top: 0.,
            source_width: 50,
            source_height: 50,
            input_size: 100,
            proto_size: 20,
        };
        let v = Viewport {
            x: 20,
            top: 30,
            width: 50,
            height: 50,
        };
        let d = InstanceDetection {
            bounds: Rect {
                left: 5.,
                top: 5.,
                right: 45.,
                bottom: 45.,
            },
            confidence: 0.8,
        };
        let mut raw = [0; 400];
        for y in 2..18 {
            raw[y * 20 + 2..y * 20 + 18].fill(255);
        }
        let mut out = [0; 400];
        let mapped = remap(d, &raw, l, t, v, &[], &[], &mut out).unwrap();
        assert_eq!(
            mapped.bounds,
            Rect {
                left: 25.,
                top: 35.,
                right: 65.,
                bottom: 75.
            }
        );
        let before = out;
        assert!(remap(d, &raw, l, t, v, &[mapped], &before, &mut out).is_none());
        let truncated = InstanceDetection {
            bounds: Rect {
                left: 0.,
                ..d.bounds
            },
            ..d
        };
        assert!(remap(truncated, &raw, l, t, v, &[], &[], &mut out).is_none());
    }
}
