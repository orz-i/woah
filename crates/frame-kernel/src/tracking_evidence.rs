//! Evidence gates and reusable mask sampling for stateful association.
use crate::{
    association_score::{self, AssociationEvidence},
    geometry::Rect,
    tracking::{Detection, Mask, Track, TrackState, Tracker},
    tracking_mask::{WarpedSamples, prepare_warped_samples, prepared_iou},
};
use std::{collections::BTreeMap, sync::Arc};

pub fn identity_evidence(state: TrackState, bounds_iou: f32, mask_iou: f32) -> bool {
    match state {
        TrackState::Lost => bounds_iou >= 0.50 || mask_iou >= 0.45,
        TrackState::Occluded | TrackState::Reacquiring => bounds_iou >= 0.45 || mask_iou >= 0.25,
        _ => bounds_iou >= 0.35 || mask_iou >= 0.20,
    }
}
pub fn motion_evidence(bounds_iou: f32, mask_iou: f32) -> bool {
    bounds_iou >= 0.20 || mask_iou >= 0.08
}
pub fn lost_anchor_support(
    state: TrackState,
    protected: bool,
    anchor_iou: f32,
    fresh_overlap: f32,
    threshold: f32,
    reliable_occluder: bool,
) -> bool {
    protected
        && state == TrackState::Lost
        && reliable_occluder
        && anchor_iou >= threshold
        && fresh_overlap >= threshold
}
pub fn ignore_uncertain_occluder(
    state: TrackState,
    protected: bool,
    globally_uncertain: bool,
) -> bool {
    protected && state == TrackState::Lost && globally_uncertain
}
pub struct MaskCompetition {
    pub group_count: usize,
    pub state: TrackState,
    pub protected: bool,
    pub selected: bool,
    pub score: f32,
    pub minimum_score: f32,
    pub identity_supported: bool,
    pub mask_iou: f32,
    pub winner_protected: bool,
    pub winner_mask_iou: Option<f32>,
}
pub fn face_mask_conflict(e: MaskCompetition) -> bool {
    e.group_count == 2
        && matches!(e.state, TrackState::Occluded | TrackState::Reacquiring)
        && e.protected
        && !e.selected
        && e.score >= e.minimum_score
        && e.identity_supported
        && e.mask_iou > 0.0
        && !e.winner_protected
        && e.winner_mask_iou.is_some_and(|other| e.mask_iou >= other)
}
pub fn dormant_mixed_body(tracker: &Tracker, track: &Track) -> bool {
    tracker.offscreen_dormancy
        && tracker.selected_ids.contains(&track.id)
        && !track.observed_this_frame
        && track.frames_since_observation > tracker.config.max_missed_frames
}
pub fn record_motion(track: &mut Track, detection: &Detection, timestamp_us: i64) {
    let old = track.observed_bounds;
    let new = detection.bounds;
    let ow = (old.right - old.left).max(0.0);
    let oh = (old.bottom - old.top).max(0.0);
    let dx = (new.left + (new.right - new.left).max(0.0) / 2.0) - (old.left + ow / 2.0);
    let dy = (new.top + (new.bottom - new.top).max(0.0) / 2.0) - (old.top + oh / 2.0);
    if track.observed_previous_frame {
        track.fresh_motion = Some([dx, dy]);
    }
    if (dx * dx + dy * dy).sqrt() >= 5.0_f32.max(ow.max(oh) * 0.03) {
        track.reliable_motion = [dx, dy];
        track.reliable_motion_at_us = timestamp_us;
    }
}
struct Entry {
    source: Mask,
    observed: Rect,
    predicted: Rect,
    samples: WarpedSamples,
    ious: Vec<f32>,
}
/// Per-update cache. Detection vectors remain immutable for this lifetime.
#[derive(Default)]
pub struct AssociationCache {
    entries: BTreeMap<i64, Entry>,
}
impl AssociationCache {
    pub fn mask_iou(&mut self, track: &Track, detections: &[Detection], index: usize) -> f32 {
        let (Some(source), Some(candidate)) = (
            track.observed_mask.as_ref(),
            detections[index].mask.as_ref(),
        ) else {
            return 0.0;
        };
        if source.width != candidate.width || source.height != candidate.height {
            return 0.0;
        }
        let reuse = self.entries.get(&track.id).is_some_and(|e| {
            Arc::ptr_eq(&e.source.pixels, &source.pixels)
                && e.source.width == source.width
                && e.source.height == source.height
                && e.source.transform == source.transform
                && e.observed == track.observed_bounds
                && e.predicted == track.predicted_bounds
                && e.ious.len() == detections.len()
        });
        if !reuse {
            let Some(samples) =
                prepare_warped_samples(source, track.observed_bounds, track.predicted_bounds, 4)
            else {
                return 0.0;
            };
            self.entries.insert(
                track.id,
                Entry {
                    source: source.clone(),
                    observed: track.observed_bounds,
                    predicted: track.predicted_bounds,
                    samples,
                    ious: vec![f32::NAN; detections.len()],
                },
            );
        }
        let entry = self.entries.get_mut(&track.id).unwrap();
        if entry.ious[index].is_nan() {
            entry.ious[index] = prepared_iou(&entry.samples, Some(candidate));
        }
        entry.ious[index]
    }
    pub fn score(
        &mut self,
        tracker: &Tracker,
        track_index: usize,
        detections: &[Detection],
        index: usize,
        timestamp_us: i64,
    ) -> Option<f32> {
        let track = &tracker.tracks[track_index];
        let detection = &detections[index];
        let phase = match track.state {
            TrackState::Active => 0,
            TrackState::Occluded => 1,
            TrackState::Reacquiring => 2,
            _ => 3,
        };
        let flags = phase
            | if tracker.groups.iter().any(|g| g.members.contains(&track.id)) {
                4
            } else {
                0
            }
            | if track.observed_mask.is_some() { 8 } else { 0 }
            | if detection.mask.is_some() { 16 } else { 0 };
        association_score::score(
            AssociationEvidence {
                predicted: track.predicted_bounds,
                observed: track.observed_bounds,
                candidate: detection.bounds,
                mask_iou: self.mask_iou(track, detections, index),
                gating_distance: track.filter.gating_distance(detection.bounds),
                velocity_x: track.filter.mean[4],
                velocity_y: track.filter.mean[5],
                history_x: track.reliable_motion[0],
                history_y: track.reliable_motion[1],
                history_age_us: if track.reliable_motion_at_us == i64::MIN {
                    i64::MAX
                } else {
                    timestamp_us
                        .saturating_sub(track.reliable_motion_at_us)
                        .max(0)
                },
                flags,
            },
            tracker.config.weights,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn timed_out_identity_does_not_use_the_active_recovery_threshold() {
        // Real mixed-privacy failure: a LOST body identity committed at .458/.20.
        assert!(!identity_evidence(TrackState::Lost, 0.458, 0.20));
        assert!(identity_evidence(TrackState::Active, 0.458, 0.20));
        assert!(identity_evidence(TrackState::Lost, 0.50, 0.0));
        assert!(!lost_anchor_support(
            TrackState::Lost,
            true,
            0.9,
            0.9,
            0.30,
            false
        ));
    }
    #[test]
    fn neighbour_mask_ownership_never_grants_a_body_selection_an_identity_rescue() {
        let base = || MaskCompetition {
            group_count: 2,
            state: TrackState::Occluded,
            protected: true,
            selected: false,
            score: 0.7,
            minimum_score: 0.2,
            identity_supported: true,
            mask_iou: 0.4,
            winner_protected: false,
            winner_mask_iou: Some(0.3),
        };
        assert!(face_mask_conflict(base()));
        assert!(!face_mask_conflict(MaskCompetition {
            selected: true,
            ..base()
        }));
        assert!(!face_mask_conflict(MaskCompetition {
            winner_mask_iou: None,
            ..base()
        }));
    }
}
