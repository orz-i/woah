//! Experimental, bounded confirmation of a missing interval from two
//! immutable incumbent endpoints. No tracking state, new seed, mask warp,
//! prediction, or identity-memory update. The only possible output is a
//! complete sequence of indices into supplied current observations.
use crate::{
    appearance::{APPEARANCE_BINS, Appearance, feature, similarity},
    dance_cutout::{CutoutObservation, MIN_CONF, STRONG_CONF},
    geometry::overlap_iou,
};

pub(crate) const MAX_SPAN_US: i64 = 1_200_000;
pub(crate) const MAX_FRAMES: usize = 73;
pub(crate) const MAX_MASK_BYTES: usize = 64 * 1024 * 1024;
const MAX_DELTA_US: i64 = 80_000;
const MIN_MASK_IOU: f32 = 0.55;
const MIN_BOX_IOU: f32 = 0.55;
const MIN_APPEARANCE: f32 = 0.88;
const COMPETING_BOX_IOU: f32 = 0.35;
const COMPETING_CONTAINMENT: f32 = 0.25;

#[derive(Clone, Copy)]
pub(crate) struct GapFrame<'a> {
    pub pts: i64,
    pub observed: CutoutObservation<'a>,
    // Only endpoints have a canonical selected index.
    pub selected: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub(crate) enum Reject {
    NoLink = -1,
    Ambiguous = -2,
    Invalid = -3,
    EndpointDisagreement = -4,
    Competition = -5,
    WeakEndpoint = -6,
}

struct ValidFrame<'a> {
    frame: GapFrame<'a>,
    features: Vec<Appearance>,
    mask_area: usize,
}

impl<'a> ValidFrame<'a> {
    fn new(frame: GapFrame<'a>) -> Option<Self> {
        let o = frame.observed;
        let t = o.transform;
        let mask_area = (t.proto_size as usize).checked_pow(2)?;
        if !t.valid()
            || t.input_size > 1024
            || o.detections.len() > 256
            || o.masks.len() != o.detections.len().checked_mul(mask_area)?
            || o.features.len() != o.detections.len().checked_mul(APPEARANCE_BINS)?
            || !o
                .detections
                .iter()
                .all(|d| d.bounds.valid() && (0.0..=1.0).contains(&d.confidence))
        {
            return None;
        }
        let features = o
            .features
            .chunks_exact(APPEARANCE_BINS)
            .map(feature)
            .collect::<Option<Vec<_>>>()?;
        Some(Self {
            frame,
            features,
            mask_area,
        })
    }
    fn mask(&self, j: usize) -> &[u8] {
        &self.frame.observed.masks[j * self.mask_area..(j + 1) * self.mask_area]
    }
    fn count(&self) -> usize {
        self.frame.observed.detections.len()
    }
    fn strong(&self, j: usize) -> bool {
        self.frame.observed.detections[j].confidence >= STRONG_CONF
    }
    fn clear(&self, j: usize) -> bool {
        let o = self.frame.observed;
        let mask = self.mask(j);
        let a = o.detections[j].bounds;
        for (k, other) in o.detections.iter().enumerate() {
            if k == j
                || other.confidence < MIN_CONF
                || overlap_iou(a, other.bounds) < COMPETING_BOX_IOU
            {
                continue;
            }
            let (common, left, right) = overlap_counts(mask, self.mask(k));
            if common as f32 / left.min(right).max(1) as f32 >= COMPETING_CONTAINMENT {
                return false;
            }
        }
        true
    }
}

/// Same raw mask threshold (>=128) as the existing cutout association.
/// The caller validates the common transform and grid.
fn overlap_counts(a: &[u8], b: &[u8]) -> (u32, u32, u32) {
    const HIGH: u64 = 0x8080_8080_8080_8080;
    let mut aa = a.chunks_exact(8);
    let mut bb = b.chunks_exact(8);
    let (mut common, mut left, mut right) = (0, 0, 0);
    for (x, y) in aa.by_ref().zip(bb.by_ref()) {
        let x = u64::from_ne_bytes(x.try_into().expect("eight bytes")) & HIGH;
        let y = u64::from_ne_bytes(y.try_into().expect("eight bytes")) & HIGH;
        common += (x & y).count_ones();
        left += x.count_ones();
        right += y.count_ones();
    }
    for (&x, &y) in aa.remainder().iter().zip(bb.remainder()) {
        common += u32::from(x >= 128 && y >= 128);
        left += u32::from(x >= 128);
        right += u32::from(y >= 128);
    }
    (common, left, right)
}

fn linked(a: &ValidFrame<'_>, aj: usize, b: &ValidFrame<'_>, bj: usize) -> bool {
    if !a.strong(aj)
        || !b.strong(bj)
        || overlap_iou(
            a.frame.observed.detections[aj].bounds,
            b.frame.observed.detections[bj].bounds,
        ) < MIN_BOX_IOU
        || similarity(&a.features[aj], &b.features[bj]) < MIN_APPEARANCE
    {
        return false;
    }
    let (common, left, right) = overlap_counts(a.mask(aj), b.mask(bj));
    common as f32 / (left + right - common).max(1) as f32 >= MIN_MASK_IOU
}

/// Require every edge to be uniquely supported in both directions. Reject the
/// *whole* gap on any missing link, conflicting current mask, or wrong endpoint.
/// Do not accept a prefix or suffix, and never feed recovered indices back.
pub(crate) fn confirm(frames: &[GapFrame<'_>]) -> Result<Vec<i32>, Reject> {
    if !(3..=MAX_FRAMES).contains(&frames.len()) {
        return Err(Reject::Invalid);
    }
    let first = frames[0];
    let last = frames[frames.len() - 1];
    if first.pts < 0
        || last
            .pts
            .checked_sub(first.pts)
            .is_none_or(|span| !(1..=MAX_SPAN_US).contains(&span))
        || first.selected < 0
        || last.selected < 0
        || frames[1..frames.len() - 1].iter().any(|f| f.selected != -1)
        || frames.windows(2).any(|p| {
            p[1].pts
                .checked_sub(p[0].pts)
                .is_none_or(|d| !(1..=MAX_DELTA_US).contains(&d))
        })
        || frames
            .iter()
            .any(|f| f.observed.transform != first.observed.transform)
    {
        return Err(Reject::Invalid);
    }
    let bytes = frames
        .iter()
        .try_fold(0usize, |n, f| n.checked_add(f.observed.masks.len()))
        .ok_or(Reject::Invalid)?;
    if bytes > MAX_MASK_BYTES {
        return Err(Reject::Invalid);
    }
    let frames = frames
        .iter()
        .copied()
        .map(ValidFrame::new)
        .collect::<Option<Vec<_>>>()
        .ok_or(Reject::Invalid)?;
    let start = first.selected as usize;
    let end = last.selected as usize;
    if start >= frames[0].count() || end >= frames[frames.len() - 1].count() {
        return Err(Reject::Invalid);
    }
    if !frames[0].strong(start) || !frames[frames.len() - 1].strong(end) {
        return Err(Reject::WeakEndpoint);
    }
    let mut path = vec![first.selected];
    let mut j = start;
    for pair in frames.windows(2) {
        let (a, b) = (&pair[0], &pair[1]);
        if !a.clear(j) {
            return Err(Reject::Competition);
        }
        let mut forward = (0..b.count()).filter(|&k| linked(a, j, b, k));
        let k = forward.next().ok_or(Reject::NoLink)?;
        if forward.next().is_some() {
            return Err(Reject::Ambiguous);
        }
        let mut reverse = (0..a.count()).filter(|&u| linked(a, u, b, k));
        if reverse.next() != Some(j) || reverse.next().is_some() {
            return Err(Reject::Ambiguous);
        }
        if !b.clear(k) {
            return Err(Reject::Competition);
        }
        path.push(k as i32);
        j = k;
    }
    if j != end {
        return Err(Reject::EndpointDisagreement);
    }
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        geometry::{Rect, Transform},
        instances::InstanceDetection,
    };

    fn obs<'a>(d: &'a [InstanceDetection], m: &'a [u8], f: &'a [f32]) -> CutoutObservation<'a> {
        CutoutObservation {
            detections: d,
            masks: m,
            features: f,
            transform: Transform {
                scale: 1.,
                pad_left: 0.,
                pad_top: 0.,
                source_width: 40,
                source_height: 40,
                input_size: 40,
                proto_size: 4,
            },
        }
    }
    fn row(confidence: f32, x: f32) -> InstanceDetection {
        InstanceDetection {
            bounds: Rect {
                left: x,
                top: 2.,
                right: x + 16.,
                bottom: 38.,
            },
            confidence,
        }
    }
    fn appearance(a: f32) -> Appearance {
        let mut f = [0.; APPEARANCE_BINS];
        f[0] = a;
        f[1] = 1. - a;
        f
    }
    fn frame<'a>(pts: i64, selected: i32, observed: CutoutObservation<'a>) -> GapFrame<'a> {
        GapFrame {
            pts,
            selected,
            observed,
        }
    }

    #[test]
    fn gradual_color_change_can_bridge_incompatible_endpoints() {
        let d = [row(0.8, 2.)];
        let m = [255; 16];
        let f = [
            appearance(1.),
            appearance(0.8),
            appearance(0.5),
            appearance(0.2),
            appearance(0.),
        ];
        assert!(similarity(&f[0], &f[4]) < MIN_APPEARANCE);
        let lane: Vec<_> = f
            .iter()
            .enumerate()
            .map(|(i, f)| {
                frame(
                    i as i64 * 40_000,
                    if i == 0 || i == 4 { 0 } else { -1 },
                    obs(&d, &m, f),
                )
            })
            .collect();
        assert_eq!(confirm(&lane), Ok(vec![0; 5]));
    }

    #[test]
    fn reject_missing_link_weak_endpoint_and_wrong_endpoint_without_partial_output() {
        let d = [row(0.8, 2.)];
        let weak = [row(0.24, 2.)];
        let m = [255; 16];
        let empty = [0; 16];
        let f = appearance(1.);
        let a = frame(0, 0, obs(&d, &m, &f));
        let b = frame(40_000, -1, obs(&d, &empty, &f));
        let c = frame(80_000, 0, obs(&d, &m, &f));
        assert_eq!(confirm(&[a, b, c]), Err(Reject::NoLink));
        assert_eq!(
            confirm(&[frame(0, 0, obs(&weak, &m, &f)), b, c]),
            Err(Reject::WeakEndpoint)
        );
        let two = [d[0], row(0.8, 22.)];
        let both = [255; 32];
        let features = [f, f].concat();
        let ok = frame(40_000, -1, obs(&d, &m, &f));
        assert_eq!(
            confirm(&[a, ok, frame(80_000, 1, obs(&two, &both, &features))]),
            Err(Reject::EndpointDisagreement)
        );
    }

    #[test]
    fn reject_ambiguous_and_weak_competing_masks() {
        let d = [row(0.8, 2.)];
        let pair = [d[0], d[0]];
        let weak = [d[0], row(0.20, 2.)];
        let m = [255; 16];
        let both = [255; 32];
        let f = appearance(1.);
        let ff = [f, f].concat();
        let a = frame(0, 0, obs(&d, &m, &f));
        let c = frame(80_000, 0, obs(&d, &m, &f));
        assert_eq!(
            confirm(&[a, frame(40_000, -1, obs(&pair, &both, &ff)), c]),
            Err(Reject::Ambiguous)
        );
        assert_eq!(
            confirm(&[a, frame(40_000, -1, obs(&weak, &both, &ff)), c]),
            Err(Reject::Competition)
        );
    }

    #[test]
    fn reject_nonconsecutive_or_already_selected_middle_samples() {
        let d = [row(0.8, 2.)];
        let m = [255; 16];
        let f = appearance(1.);
        let o = obs(&d, &m, &f);
        assert_eq!(
            confirm(&[frame(0, 0, o), frame(100_000, -1, o), frame(140_000, 0, o)]),
            Err(Reject::Invalid)
        );
        assert_eq!(
            confirm(&[frame(0, 0, o), frame(40_000, 0, o), frame(80_000, 0, o)]),
            Err(Reject::Invalid)
        );
        assert_eq!(
            confirm(&[frame(80_000, 0, o), frame(40_000, -1, o), frame(0, 0, o)]),
            Err(Reject::Invalid)
        );
    }
}
