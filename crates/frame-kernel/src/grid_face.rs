//! Dense 48x48 single-anchor face decoder and shared RGBA tensor preparation.
//! Contract: 192x192 NHWC RGB [-1,1], 2304 xywh + six xy keypoints,
//! sigmoid logits, weighted IoU suppression. Native adapters execute the model.
use crate::pixels::{self, PixelSpec};
pub const INPUT: usize = 192;
pub const ANCHORS: usize = 48 * 48;
pub const COORDS: usize = 16;
pub const RECORD: usize = 17;

pub fn prepare(
    rgba: &[u8],
    width: u32,
    height: u32,
    stride: u32,
    output: &mut [f32],
) -> Option<()> {
    if output.len() != INPUT * INPUT * 3 {
        return None;
    }
    let mut resized = vec![0; INPUT * INPUT * 4];
    pixels::resize_rgba(
        rgba,
        width,
        height,
        stride,
        &mut resized,
        INPUT as u32,
        INPUT as u32,
        0,
    )?;
    pixels::normalize(
        &resized,
        PixelSpec {
            width: INPUT as u32,
            height: INPUT as u32,
            row_stride: INPUT as u32 * 4,
            channels: 4,
            red: 0,
            green: 1,
            blue: 2,
            flip_rows: 0,
            flip_columns: 0,
            interleaved: 1,
            scale: 2.0 / 255.0,
            bias: -1.0,
        },
        output,
    )
}

fn iou(a: &[f32; RECORD], b: &[f32; RECORD]) -> f32 {
    let w = (a[2].min(b[2]) - a[0].max(b[0])).max(0.0);
    let h = (a[3].min(b[3]) - a[1].max(b[1])).max(0.0);
    let area = |r: &[f32; RECORD]| (r[2] - r[0]) * (r[3] - r[1]);
    let intersection = w * h;
    intersection / (area(a) + area(b) - intersection)
}

/// Output rows: source-pixel xyxy, confidence, six source-pixel xy landmarks.
/// Suppression happens before unletterboxing; source bounds are not clipped.
/// Bad tensors reject the call instead of masquerading as no detected faces.
pub fn decode(
    boxes: &[f32],
    logits: &[f32],
    width: u32,
    height: u32,
    minimum: f32,
    overlap: f32,
) -> Option<Vec<[f32; RECORD]>> {
    if boxes.len() != ANCHORS * COORDS
        || logits.len() != ANCHORS
        || !minimum.is_finite()
        || !(0.0..=1.0).contains(&minimum)
        || minimum == 0.0
        || !overlap.is_finite()
        || !(0.0..1.0).contains(&overlap)
        || boxes.iter().chain(logits).any(|v| !v.is_finite())
    {
        return None;
    }
    let plan = pixels::letterbox(width, height, INPUT as u32, INPUT as u32)?;
    let mut candidates = Vec::new();
    for (index, &logit) in logits.iter().enumerate() {
        let score = 1.0 / (1.0 + (-logit.clamp(-100.0, 100.0)).exp());
        if score < minimum {
            continue;
        }
        let b = &boxes[index * COORDS..(index + 1) * COORDS];
        if b[2] <= 0.0 || b[3] <= 0.0 {
            continue;
        }
        let ax = (index % 48) as f32 * 4.0 + 2.0;
        let ay = (index / 48) as f32 * 4.0 + 2.0;
        let mut row = [0.0; RECORD];
        row[..5].copy_from_slice(&[
            b[0] + ax - b[2] * 0.5,
            b[1] + ay - b[3] * 0.5,
            b[0] + ax + b[2] * 0.5,
            b[1] + ay + b[3] * 0.5,
            score,
        ]);
        for k in 0..6 {
            row[5 + k * 2] = b[4 + k * 2] + ax;
            row[6 + k * 2] = b[5 + k * 2] + ay;
        }
        candidates.push(row);
    }
    // Stable ties preserve the original anchor order across both adapters.
    candidates.sort_by(|a, b| b[4].total_cmp(&a[4]));
    let mut output = Vec::new();
    while let Some(first) = candidates.first().copied() {
        let mut weighted = [0.0; RECORD];
        let mut total = 0.0;
        candidates.retain(|row| {
            if iou(&first, row) > overlap {
                total += row[4];
                for i in 0..RECORD {
                    if i != 4 {
                        weighted[i] += row[i] * row[4];
                    }
                }
                false
            } else {
                true
            }
        });
        // A finite positive box always suppresses itself at overlap < 1.
        if total <= 0.0 || !total.is_finite() {
            return None;
        }
        weighted[4] = first[4];
        for (i, value) in weighted.iter_mut().enumerate() {
            if i == 4 {
                continue;
            }
            *value /= total;
            let pad = if i < 4 {
                if i % 2 == 0 { plan.left } else { plan.top }
            } else if (i - 5) % 2 == 0 {
                plan.left
            } else {
                plan.top
            };
            *value = (*value - pad) / plan.scale;
            if !value.is_finite() {
                return None;
            }
        }
        output.push(weighted);
    }
    Some(output)
}

#[cfg(test)]
mod tests {
    use super::*;
    fn tensors() -> (Vec<f32>, Vec<f32>) {
        (vec![0.0; ANCHORS * COORDS], vec![-100.0; ANCHORS])
    }
    #[test]
    fn anchor_offsets_landmarks_and_portrait_unpadding() {
        let (mut b, mut s) = tensors();
        let n = 25 * 48 + 23;
        s[n] = 0.0;
        b[n * 16..n * 16 + 16].copy_from_slice(&[
            2.0, 4.0, 20.0, 30.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0,
        ]);
        let r = decode(&b, &s, 96, 192, 0.35, 0.3).unwrap();
        assert_eq!(r.len(), 1);
        assert_eq!(r[0][..7], [38.0, 91.0, 58.0, 121.0, 0.5, 47.0, 104.0]);
    }
    #[test]
    fn weighted_duplicates_keep_top_score_and_distinct_face() {
        let (mut b, mut s) = tensors();
        for (n, x, w) in [(0, 50.0, 0.0), (1, 48.0, 1.0), (47, 0.0, 2.0)] {
            s[n] = w;
            b[n * 16] = x;
            b[n * 16 + 1] = 50.0;
            b[n * 16 + 2] = 20.0;
            b[n * 16 + 3] = 20.0;
        }
        let out = decode(&b, &s, 192, 192, 0.35, 0.3).unwrap();
        assert_eq!(out.len(), 2);
        assert!((out[0][4] - 1.0 / (1.0 + (-2.0_f32).exp())).abs() < 1e-6);
        assert!(
            (out[1][0]
                - (42.0 * 0.5 + 44.0 / (1.0 + (-1.0_f32).exp()))
                    / (0.5 + 1.0 / (1.0 + (-1.0_f32).exp())))
            .abs()
                < 1e-5
        );
    }
    #[test]
    fn invalid_tensors_are_not_empty_success() {
        let (mut b, s) = tensors();
        b[16] = f32::NAN;
        assert!(decode(&b, &s, 192, 192, 0.35, 0.3).is_none());
        assert!(decode(&[], &s, 192, 192, 0.35, 0.3).is_none());
        assert!(decode(&vec![0.0; ANCHORS * 16], &s, 0, 192, 0.35, 0.3).is_none());
    }
    #[test]
    fn preparation_normalizes_rgb_and_zero_padding() {
        let mut out = vec![0.0; 192 * 192 * 3];
        prepare(&[255, 0, 128, 255, 255, 0, 128, 255], 1, 2, 4, &mut out).unwrap();
        assert_eq!(&out[..3], &[-1.0, -1.0, -1.0]);
        let p = (96 * 192 + 96) * 3;
        assert_eq!(out[p], 1.0);
        assert_eq!(out[p + 1], -1.0);
        assert!((out[p + 2] - (128.0 * 2.0 / 255.0 - 1.0)).abs() < 1e-6);
    }
}
