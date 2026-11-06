//! Canonical association samples and rendered prediction masks.
//! Floating association evidence must not be quantized through a render mask.
use crate::{geometry::Rect, tracking::Mask};
use std::sync::Arc;

fn source_to_proto(x: f32, scale: f32, pad: f32, input: u32, proto: u32) -> f32 {
    ((x * scale + pad) / input as f32) * proto as f32
}
fn size(r: Rect) -> [f32; 2] {
    [(r.right - r.left).max(0.0), (r.bottom - r.top).max(0.0)]
}
fn center(r: Rect) -> [f32; 2] {
    let [w, h] = size(r);
    [r.left + w / 2.0, r.top + h / 2.0]
}
fn finite(r: Rect) -> bool {
    [r.left, r.top, r.right, r.bottom]
        .into_iter()
        .all(f32::is_finite)
}
#[derive(Clone, Copy)]
struct Warp {
    scale: [f32; 2],
    previous: [f32; 2],
    predicted: [f32; 2],
}
fn warp(mask: &Mask, previous: Rect, predicted: Rect) -> Option<Warp> {
    if !mask.valid() || !finite(previous) || !finite(predicted) {
        return None;
    }
    let p = size(previous);
    let q = size(predicted);
    let a = center(previous);
    let b = center(predicted);
    let t = mask.transform;
    let value = Warp {
        scale: [q[0].max(1.0) / p[0].max(1.0), q[1].max(1.0) / p[1].max(1.0)],
        previous: [
            source_to_proto(a[0], t.scale, t.pad_left, t.input_size, t.proto_size),
            source_to_proto(a[1], t.scale, t.pad_top, t.input_size, t.proto_size),
        ],
        predicted: [
            source_to_proto(b[0], t.scale, t.pad_left, t.input_size, t.proto_size),
            source_to_proto(b[1], t.scale, t.pad_top, t.input_size, t.proto_size),
        ],
    };
    value
        .scale
        .into_iter()
        .chain(value.previous)
        .chain(value.predicted)
        .all(f32::is_finite)
        .then_some(value)
}
fn sample(mask: &Mask, x: isize, y: isize) -> f32 {
    if x >= 0 && y >= 0 && (x as usize) < mask.width && (y as usize) < mask.height {
        mask.pixels[y as usize * mask.width + x as usize] as f32
    } else {
        0.0
    }
}
fn interpolate(mask: &Mask, w: Warp, x: usize, y: usize) -> f32 {
    let fx = (x as f32 - w.predicted[0]) / w.scale[0] + w.previous[0];
    let fy = (y as f32 - w.predicted[1]) / w.scale[1] + w.previous[1];
    let x0 = fx.floor() as isize;
    let y0 = fy.floor() as isize;
    let x1 = x0.saturating_add(1);
    let y1 = y0.saturating_add(1);
    let wx1 = (fx - x0 as f32).clamp(0.0, 1.0);
    let wy1 = (fy - y0 as f32).clamp(0.0, 1.0);
    let wx0 = 1.0 - wx1;
    let wy0 = 1.0 - wy1;
    (sample(mask, x0, y0) * wx0 + sample(mask, x1, y0) * wx1) * wy0
        + (sample(mask, x0, y1) * wx0 + sample(mask, x1, y1) * wx1) * wy1
}
pub fn mask_iou(a: Option<&Mask>, b: Option<&Mask>, stride: usize) -> f32 {
    let (Some(a), Some(b)) = (a, b) else {
        return 0.0;
    };
    if !a.valid() || !b.valid() || a.width != b.width || a.height != b.height {
        return 0.0;
    }
    let mut intersection = 0_u32;
    let mut union = 0_u32;
    for y in (0..a.height).step_by(stride.max(1)) {
        for x in (0..a.width).step_by(stride.max(1)) {
            let index = y * a.width + x;
            let aa = a.pixels[index] > 128;
            let bb = b.pixels[index] > 128;
            intersection += u32::from(aa && bb);
            union += u32::from(aa || bb);
        }
    }
    if union == 0 {
        1.0
    } else {
        intersection as f32 / union as f32
    }
}
pub struct WarpedSamples {
    pub width: usize,
    pub height: usize,
    pub stride: usize,
    pub foreground: Vec<u8>,
}
pub fn prepare_warped_samples(
    mask: &Mask,
    previous: Rect,
    predicted: Rect,
    stride: usize,
) -> Option<WarpedSamples> {
    let w = warp(mask, previous, predicted)?;
    let stride = stride.max(1);
    let mut foreground =
        Vec::with_capacity(mask.width.div_ceil(stride) * mask.height.div_ceil(stride));
    for y in (0..mask.height).step_by(stride) {
        for x in (0..mask.width).step_by(stride) {
            foreground.push(u8::from(interpolate(mask, w, x, y) > 128.0));
        }
    }
    Some(WarpedSamples {
        width: mask.width,
        height: mask.height,
        stride,
        foreground,
    })
}
pub fn prepared_iou(prepared: &WarpedSamples, candidate: Option<&Mask>) -> f32 {
    let Some(candidate) = candidate else {
        return 0.0;
    };
    if !candidate.valid()
        || prepared.width != candidate.width
        || prepared.height != candidate.height
        || prepared.stride == 0
        || prepared.foreground.len()
            != prepared.width.div_ceil(prepared.stride) * prepared.height.div_ceil(prepared.stride)
    {
        return 0.0;
    }
    let mut index = 0;
    let mut intersection = 0_u32;
    let mut union = 0_u32;
    for y in (0..prepared.height).step_by(prepared.stride) {
        for x in (0..prepared.width).step_by(prepared.stride) {
            let a = prepared.foreground[index] != 0;
            let b = candidate.pixels[y * candidate.width + x] > 128;
            intersection += u32::from(a && b);
            union += u32::from(a || b);
            index += 1;
        }
    }
    if union == 0 {
        1.0
    } else {
        intersection as f32 / union as f32
    }
}
pub fn warped_iou(
    mask: Option<&Mask>,
    previous: Rect,
    predicted: Rect,
    candidate: Option<&Mask>,
    stride: usize,
) -> f32 {
    let (Some(mask), Some(candidate)) = (mask, candidate) else {
        return 0.0;
    };
    if mask.width != candidate.width || mask.height != candidate.height {
        return 0.0;
    }
    prepare_warped_samples(mask, previous, predicted, stride)
        .map_or(0.0, |p| prepared_iou(&p, Some(candidate)))
}
pub fn warp_mask(mask: &Mask, previous: Rect, predicted: Rect, missed: u32) -> Option<Mask> {
    let transform = warp(mask, previous, predicted)?;
    let mut pixels = vec![0_u8; mask.pixels.len()];
    for y in 0..mask.height {
        for x in 0..mask.width {
            pixels[y * mask.width + x] = (interpolate(mask, transform, x, y) + 0.5)
                .floor()
                .clamp(0.0, 255.0) as u8;
        }
    }
    let radius = if missed > 10 {
        2
    } else if missed > 0 {
        1
    } else {
        0
    };
    if radius > 0 {
        let source = pixels.clone();
        for y in 0..mask.height {
            for x in 0..mask.width {
                let mut max = source[y * mask.width + x];
                for yy in y.saturating_sub(radius)..=(y + radius).min(mask.height - 1) {
                    for xx in x.saturating_sub(radius)..=(x + radius).min(mask.width - 1) {
                        max = max.max(source[yy * mask.width + xx]);
                    }
                }
                pixels[y * mask.width + x] = max;
            }
        }
    }
    Some(Mask {
        pixels: Arc::from(pixels),
        ..mask.clone()
    })
}
pub fn conservative_mask(mask: &Mask, predicted: Rect, missed: u32) -> Option<Mask> {
    if !mask.valid() || !finite(predicted) {
        return None;
    }
    let margin = if missed <= 10 { 0.15 } else { 0.25 };
    let [w, h] = size(predicted);
    let t = mask.transform;
    let x = |x| {
        (source_to_proto(x, t.scale, t.pad_left, t.input_size, t.proto_size) + 0.5)
            .floor()
            .clamp(0.0, mask.width as f32) as usize
    };
    let y = |y| {
        (source_to_proto(y, t.scale, t.pad_top, t.input_size, t.proto_size) + 0.5)
            .floor()
            .clamp(0.0, mask.height as f32) as usize
    };
    let (x1, x2) = (
        x(predicted.left - w * margin),
        x(predicted.right + w * margin),
    );
    let (y1, y2) = (
        y(predicted.top - h * margin),
        y(predicted.bottom + h * margin),
    );
    let mut pixels = vec![0_u8; mask.pixels.len()];
    for yy in y1.min(y2)..y1.max(y2) {
        for xx in x1.min(x2)..x1.max(x2) {
            pixels[yy * mask.width + xx] = 255;
        }
    }
    Some(Mask {
        pixels: Arc::from(pixels),
        ..mask.clone()
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::geometry::Transform;
    fn mask(data: [u8; 4]) -> Mask {
        Mask {
            width: 2,
            height: 2,
            pixels: Arc::from(data),
            transform: Transform {
                scale: 1.0,
                pad_left: 0.0,
                pad_top: 0.0,
                source_width: 2,
                source_height: 2,
                input_size: 2,
                proto_size: 2,
            },
        }
    }
    fn bounds() -> Rect {
        Rect {
            left: 0.0,
            top: 0.0,
            right: 2.0,
            bottom: 2.0,
        }
    }
    #[test]
    fn null_is_distinct_from_empty_and_prediction_does_not_change_canonical_pixels() {
        let source = mask([0, 128, 129, 255]);
        assert_eq!(mask_iou(Some(&source), Some(&source), 1), 1.0);
        assert_eq!(mask_iou(None, None, 1), 0.0);
        assert_eq!(
            &*warp_mask(&source, bounds(), bounds(), 0).unwrap().pixels,
            &[0, 128, 129, 255]
        );
        assert_eq!(
            &*warp_mask(&source, bounds(), bounds(), 1).unwrap().pixels,
            &[255; 4]
        );
        assert_eq!(&*source.pixels, &[0, 128, 129, 255]);
        let mut shifted = bounds();
        shifted.left += 0.5;
        shifted.right += 0.5;
        assert_eq!(
            &*warp_mask(&source, bounds(), shifted, 0).unwrap().pixels,
            &[0, 64, 65, 192]
        );
    }
    #[test]
    fn association_samples_keep_fractional_foreground_before_render_rounding() {
        let source = mask([128, 129, 128, 129]);
        let candidate = mask([255; 4]);
        let mut predicted = bounds();
        predicted.left += 0.6;
        predicted.right += 0.6;
        let prepared = prepare_warped_samples(&source, bounds(), predicted, 1).unwrap();
        assert_eq!(prepared.foreground, [0, 1, 0, 1]);
        assert_eq!(prepared_iou(&prepared, Some(&candidate)), 0.5);
        let rendered = warp_mask(&source, bounds(), predicted, 0).unwrap();
        assert_eq!(mask_iou(Some(&rendered), Some(&candidate), 1), 0.0);
    }
}
