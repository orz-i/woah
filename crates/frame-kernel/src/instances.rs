//! Single-class instance segmentation from a configurable detection head and
//! prototype tensor. Model names, class selection and tensor I/O stay outside
//! the crate. Arithmetic is Float32 with ordered, unfused channel accumulation.
use crate::{
    geometry::{Rect, Transform},
    mask_transitions::TRANSITION_BITS,
};
use std::time::Instant;

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct InstanceSpec {
    pub anchors: u32,
    pub attributes: u32,
    pub score_index: u32,
    pub coefficient_index: u32,
    pub channels: u32,
    /// 0: attribute-major, 1: anchor-major.
    pub head_transposed: u32,
    /// 0: planar CHW, 1: interleaved HWC.
    pub proto_nhwc: u32,
    /// 0: model pixels, 1: normalized, 2: legacy per-anchor auto detection.
    pub coordinate_units: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct InstanceThresholds {
    pub confidence: f32,
    pub bbox_iou: f32,
    pub mask_iou: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct InstanceDetection {
    pub bounds: Rect,
    pub confidence: f32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct InstanceMetrics {
    pub candidate_scan_ns: u64,
    pub suppression_ns: u64,
    pub mask_decode_ns: u64,
    pub mask_iou_ns: u64,
    pub materialize_ns: u64,
}

#[derive(Debug, PartialEq)]
pub struct Instance {
    pub detection: InstanceDetection,
    pub mask: Vec<u8>,
}

#[derive(Debug, PartialEq)]
pub enum DecodeError {
    TensorContract,
    NonFiniteTensor,
}

impl InstanceSpec {
    pub fn lengths(self, transform: Transform) -> Option<(usize, usize)> {
        if !transform.valid()
            || !(1..=100_000).contains(&self.anchors)
            || !(5..=4096).contains(&self.attributes)
            || !(1..=1024).contains(&self.channels)
            || self.score_index >= self.attributes
            || self.coefficient_index.checked_add(self.channels)? > self.attributes
            || self.head_transposed > 1
            || self.proto_nhwc > 1
            || self.coordinate_units > 2
        {
            return None;
        }
        let head = (self.anchors as usize).checked_mul(self.attributes as usize)?;
        let proto = (transform.proto_size as usize)
            .checked_pow(2)?
            .checked_mul(self.channels as usize)?;
        if head > 64_000_000 || proto > 64_000_000 {
            return None;
        }
        Some((head, proto))
    }
}

/// Stable byte quantization without a platform-specific libm call. NaN maps to
/// zero, negative infinity to zero and positive infinity to 255.
pub fn mask_byte(logit: f32) -> u8 {
    if logit.is_nan() {
        return 0;
    }
    TRANSITION_BITS.partition_point(|&bits| logit >= f32::from_bits(bits)) as u8
}

#[derive(Debug)]
struct Candidate {
    bounds: Rect,
    confidence: f32,
    coefficients: Vec<f32>,
    support: Support,
}
#[derive(Clone, Copy, Debug)]
struct Support {
    x1: usize,
    y1: usize,
    x2: usize,
    y2: usize,
}

fn support(bounds: Rect, transform: Transform) -> Support {
    let size = transform.proto_size as f32;
    let input = transform.input_size as f32;
    // Division before multiplication preserves the established Float32 support
    // at values adjacent to a prototype pixel boundary.
    let lower = |x: f32| ((x / input * size).floor() - 1.0).clamp(0.0, size) as usize;
    let upper = |x: f32| ((x / input * size).ceil() + 1.0).clamp(0.0, size) as usize;
    Support {
        x1: lower(bounds.left),
        y1: lower(bounds.top),
        x2: upper(bounds.right),
        y2: upper(bounds.bottom),
    }
}

fn bbox_iou(a: Rect, b: Rect) -> f32 {
    let area = (a.right.min(b.right) - a.left.max(b.left)).max(0.0)
        * (a.bottom.min(b.bottom) - a.top.max(b.top)).max(0.0);
    let union =
        (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - area;
    if union > 0.0 { area / union } else { 0.0 }
}

fn decode_mask(
    candidate: &Candidate,
    proto: &[f32],
    spec: InstanceSpec,
    transform: Transform,
    scratch: &mut [f32],
) -> Vec<u8> {
    let side = transform.proto_size as usize;
    let pixels = side * side;
    let Support { x1, y1, x2, y2 } = candidate.support;
    let mut output = vec![0; pixels];
    for y in y1..y2 {
        scratch[y * side + x1..y * side + x2].fill(0.0);
    }
    // Each pixel accumulates c=0..channels, even when planar memory is visited
    // channel-first. Do not replace these operations with mul_add or reassociate.
    for (channel, &coefficient) in candidate.coefficients.iter().enumerate() {
        for y in y1..y2 {
            for x in x1..x2 {
                let pixel = y * side + x;
                let index = if spec.proto_nhwc == 1 {
                    pixel * spec.channels as usize + channel
                } else {
                    channel * pixels + pixel
                };
                scratch[pixel] += coefficient * proto[index];
            }
        }
    }
    for y in y1..y2 {
        for x in x1..x2 {
            output[y * side + x] = mask_byte(scratch[y * side + x]);
        }
    }
    output
}

fn mask_iou(a: &[u8], b: &[u8], a_support: Support, b_support: Support, side: usize) -> f32 {
    let mut intersection = 0_u32;
    let mut union = 0_u32;
    for y in a_support.y1.min(b_support.y1)..a_support.y2.max(b_support.y2) {
        for x in a_support.x1.min(b_support.x1)..a_support.x2.max(b_support.x2) {
            let index = y * side + x;
            let present_a = a[index] != 0;
            let present_b = b[index] != 0;
            intersection += u32::from(present_a && present_b);
            union += u32::from(present_a || present_b);
        }
    }
    if union > 0 {
        intersection as f32 / union as f32
    } else {
        0.0
    }
}

fn elapsed(start: Instant) -> u64 {
    start.elapsed().as_nanos().min(u128::from(u64::MAX)) as u64
}

pub fn decode_instances(
    head: &[f32],
    proto: &[f32],
    spec: InstanceSpec,
    transform: Transform,
    thresholds: InstanceThresholds,
) -> Result<(Vec<Instance>, InstanceMetrics), DecodeError> {
    if spec.lengths(transform) != Some((head.len(), proto.len()))
        || ![
            thresholds.confidence,
            thresholds.bbox_iou,
            thresholds.mask_iou,
        ]
        .iter()
        .all(|v| v.is_finite() && (0.0..=1.0).contains(v))
    {
        return Err(DecodeError::TensorContract);
    }
    let start = Instant::now();
    if proto.iter().any(|value| !value.is_finite()) {
        return Err(DecodeError::NonFiniteTensor);
    }
    let mut metrics = InstanceMetrics::default();
    let mut candidates = Vec::new();
    for anchor in 0..spec.anchors as usize {
        let attribute = |index: usize| {
            head[if spec.head_transposed == 1 {
                anchor * spec.attributes as usize + index
            } else {
                index * spec.anchors as usize + anchor
            }]
        };
        let confidence = attribute(spec.score_index as usize);
        if !confidence.is_finite() {
            return Err(DecodeError::NonFiniteTensor);
        }
        if confidence < thresholds.confidence {
            continue;
        }
        let (mut cx, mut cy, mut w, mut h) =
            (attribute(0), attribute(1), attribute(2), attribute(3));
        let coefficients = (0..spec.channels as usize)
            .map(|i| attribute(spec.coefficient_index as usize + i))
            .collect::<Vec<_>>();
        if ![cx, cy, w, h]
            .iter()
            .chain(coefficients.iter())
            .all(|v| v.is_finite())
        {
            return Err(DecodeError::NonFiniteTensor);
        }
        if spec.coordinate_units == 1 || (spec.coordinate_units == 2 && cx <= 2.0 && w <= 2.0) {
            let input = transform.input_size as f32;
            cx *= input;
            cy *= input;
            w *= input;
            h *= input;
        }
        let bounds = Rect {
            left: cx - w / 2.0,
            top: cy - h / 2.0,
            right: cx + w / 2.0,
            bottom: cy + h / 2.0,
        };
        if !bounds.valid() {
            continue;
        }
        candidates.push(Candidate {
            bounds,
            confidence,
            coefficients,
            support: support(bounds, transform),
        });
    }
    candidates.sort_by(|a, b| b.confidence.total_cmp(&a.confidence));
    metrics.candidate_scan_ns = elapsed(start);
    let start = Instant::now();
    let mut suppressed = vec![false; candidates.len()];
    let mut masks: Vec<Option<Vec<u8>>> = (0..candidates.len()).map(|_| None).collect();
    let mut kept = Vec::new();
    let mut scratch = vec![0.0; (transform.proto_size as usize).pow(2)];
    for i in 0..candidates.len() {
        if suppressed[i] {
            continue;
        }
        kept.push(i);
        for j in i + 1..candidates.len() {
            if suppressed[j]
                || bbox_iou(candidates[i].bounds, candidates[j].bounds) <= thresholds.bbox_iou
            {
                continue;
            }
            for k in [i, j] {
                if masks[k].is_none() {
                    let mark = Instant::now();
                    masks[k] = Some(decode_mask(
                        &candidates[k],
                        proto,
                        spec,
                        transform,
                        &mut scratch,
                    ));
                    metrics.mask_decode_ns += elapsed(mark);
                }
            }
            let mark = Instant::now();
            let overlap = mask_iou(
                masks[i].as_ref().expect("decoded"),
                masks[j].as_ref().expect("decoded"),
                candidates[i].support,
                candidates[j].support,
                transform.proto_size as usize,
            );
            metrics.mask_iou_ns += elapsed(mark);
            if overlap >= thresholds.mask_iou {
                suppressed[j] = true;
            }
        }
    }
    metrics.suppression_ns = elapsed(start);
    let start = Instant::now();
    let mut instances = Vec::with_capacity(kept.len());
    for i in kept {
        let candidate = &candidates[i];
        let source_x = |v: f32| {
            ((v - transform.pad_left) / transform.scale).clamp(0.0, transform.source_width as f32)
        };
        let source_y = |v: f32| {
            ((v - transform.pad_top) / transform.scale).clamp(0.0, transform.source_height as f32)
        };
        let bounds = Rect {
            left: source_x(candidate.bounds.left),
            top: source_y(candidate.bounds.top),
            right: source_x(candidate.bounds.right),
            bottom: source_y(candidate.bounds.bottom),
        };
        if !bounds.valid() {
            continue;
        }
        let mask = masks[i].take().unwrap_or_else(|| {
            let mark = Instant::now();
            let mask = decode_mask(candidate, proto, spec, transform, &mut scratch);
            metrics.mask_decode_ns += elapsed(mark);
            mask
        });
        instances.push(Instance {
            detection: InstanceDetection {
                bounds,
                confidence: candidate.confidence,
            },
            mask,
        });
    }
    instances.sort_by(|a, b| {
        ((a.detection.bounds.left + a.detection.bounds.right) * 0.5)
            .total_cmp(&((b.detection.bounds.left + b.detection.bounds.right) * 0.5))
    });
    metrics.materialize_ns = elapsed(start);
    Ok((instances, metrics))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn float_byte_boundaries_and_special_values_are_stable() {
        for (i, &bits) in TRANSITION_BITS.iter().enumerate() {
            let value = f32::from_bits(bits);
            assert_eq!(mask_byte(value), i as u8 + 1);
            assert_eq!(mask_byte(value.next_down()), i as u8);
        }
        assert_eq!(mask_byte(f32::NAN), 0);
        assert_eq!(mask_byte(f32::NEG_INFINITY), 0);
        assert_eq!(mask_byte(f32::INFINITY), 255);
        assert_eq!(mask_byte(0.0), 127);
    }
    #[test]
    fn generic_head_preserves_separate_overlapping_instances_and_suppresses_duplicates() {
        let spec = InstanceSpec {
            anchors: 3,
            attributes: 7,
            score_index: 4,
            coefficient_index: 5,
            channels: 2,
            head_transposed: 1,
            proto_nhwc: 0,
            coordinate_units: 0,
        };
        let transform = Transform {
            scale: 1.0,
            pad_left: 0.0,
            pad_top: 0.0,
            source_width: 8,
            source_height: 8,
            input_size: 8,
            proto_size: 4,
        };
        let head = [
            4., 4., 6., 6., 0.9, 1., 0., 4., 4., 6., 6., 0.8, 0., 1., 4., 4., 6., 6., 0.7, 1., 0.,
        ];
        let proto = (0..32)
            .map(|i| if (i < 16) == (i % 4 < 2) { 10.0 } else { -10.0 })
            .collect::<Vec<_>>();
        let thresholds = InstanceThresholds {
            confidence: 0.25,
            bbox_iou: 0.5,
            mask_iou: 0.5,
        };
        let (instances, _) =
            decode_instances(&head, &proto, spec, transform, thresholds).expect("valid tensors");
        assert_eq!(instances.len(), 2);
        assert_eq!(instances[0].detection.confidence, 0.9);
        assert_eq!(instances[1].detection.confidence, 0.8);
        assert_eq!(instances[0].mask[0], 254);
        assert_eq!(instances[1].mask[0], 0);
        let mut spec2 = spec;
        spec2.head_transposed = 0;
        spec2.proto_nhwc = 1;
        let transposed = (0..7)
            .flat_map(|a| (0..3).map(move |i| head[i * 7 + a]))
            .collect::<Vec<_>>();
        let nhwc = (0..16)
            .flat_map(|i| [proto[i], proto[16 + i]])
            .collect::<Vec<_>>();
        assert_eq!(
            decode_instances(&transposed, &nhwc, spec2, transform, thresholds)
                .expect("equivalent layouts")
                .0,
            instances
        );
    }
}
