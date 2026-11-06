//! Prediction cadence and observation-age policy, independent of decoder or UI.
use crate::{
    geometry::Rect,
    tracking::{Track, TrackState, Tracker, TrackingError},
    tracking_mask::warp_mask,
};

pub fn bound_prediction(
    anchor: Rect,
    predicted: Rect,
    travel_ratio: f32,
    min_scale: f32,
    max_scale: f32,
) -> Rect {
    let width = (anchor.right - anchor.left).max(1.0);
    let height = (anchor.bottom - anchor.top).max(1.0);
    let ax = anchor.left + (anchor.right - anchor.left).max(0.0) / 2.0;
    let ay = anchor.top + (anchor.bottom - anchor.top).max(0.0) / 2.0;
    let px = predicted.left + (predicted.right - predicted.left).max(0.0) / 2.0;
    let py = predicted.top + (predicted.bottom - predicted.top).max(0.0) / 2.0;
    let dx = px - ax;
    let dy = py - ay;
    let distance = (dx * dx + dy * dy).sqrt();
    let max_travel = width.max(height) * travel_ratio.max(0.0);
    let scale = if distance > max_travel && distance > 1e-4 {
        max_travel / distance
    } else {
        1.0
    };
    let x = ax + dx * scale;
    let y = ay + dy * scale;
    let min_scale = min_scale.max(0.1);
    let max_scale = max_scale.max(min_scale);
    let w = (predicted.right - predicted.left).clamp(width * min_scale, width * max_scale);
    let h = (predicted.bottom - predicted.top).clamp(height * min_scale, height * max_scale);
    Rect {
        left: x - w * 0.5,
        top: y - h * 0.5,
        right: x + w * 0.5,
        bottom: y + h * 0.5,
    }
}
fn bounded(track: &Track, predicted: Rect, protected: bool) -> Rect {
    if !protected {
        return predicted;
    }
    bound_prediction(
        track
            .occlusion_motion_bounds
            .unwrap_or(track.observed_bounds),
        predicted,
        0.30,
        0.82,
        1.18,
    )
}
fn blend(a: Rect, b: Rect) -> Rect {
    Rect {
        left: a.left * 0.5 + b.left * 0.5,
        top: a.top * 0.5 + b.top * 0.5,
        right: a.right * 0.5 + b.right * 0.5,
        bottom: a.bottom * 0.5 + b.bottom * 0.5,
    }
}
fn increment(n: u32) -> u32 {
    n.saturating_add(1).min(i32::MAX as u32)
}
fn likely_exit(track: &Track, enabled: bool, selected: bool) -> bool {
    if !enabled || !selected || track.offscreen_dormant {
        return false;
    }
    let Some(mask) = &track.observed_mask else {
        return false;
    };
    let width = (mask.transform.source_width as f32).max(2.0);
    let height = (mask.transform.source_height as f32).max(2.0);
    let r = track.observed_bounds;
    let [dx, dy] = track.reliable_motion;
    let sx = 4.0_f32.max((r.right - r.left).max(0.0) * 0.03);
    let sy = 4.0_f32.max((r.bottom - r.top).max(0.0) * 0.03);
    (r.left <= width * 0.06 && dx <= -sx)
        || (r.right >= width * (1.0 - 0.06) && dx >= sx)
        || (r.top <= height * 0.06 && dy <= -sy)
        || (r.bottom >= height * (1.0 - 0.06) && dy >= sy)
}
fn render_prediction(track: &mut Track, bounds: Rect, missed: u32) -> Result<(), TrackingError> {
    if let Some(mask) = &track.observed_mask {
        track.render_mask = Some(
            warp_mask(mask, track.observed_bounds, bounds, missed)
                .ok_or(TrackingError::InvalidObservation)?,
        );
    }
    Ok(())
}
impl Tracker {
    /// A decoder cadence skip is not a failed detector observation. Both advance
    /// observation age, but only a real miss advances LOST/OCCLUDED grace.
    /// Compute transactionally so an invalid mask cannot leave partial state.
    pub fn predict(
        &mut self,
        timestamp_us: i64,
        count_as_detection_miss: bool,
    ) -> Result<&[Track], TrackingError> {
        let mut pending = self.clone();
        pending.predict_inner(timestamp_us, count_as_detection_miss)?;
        *self = pending;
        Ok(&self.tracks)
    }
    fn predict_inner(&mut self, timestamp_us: i64, count_miss: bool) -> Result<(), TrackingError> {
        self.clear_frame_evidence();
        for track in &mut self.tracks {
            track.frames_since_observation = increment(track.frames_since_observation);
            track.observed_previous_frame = false;
            track.observed_this_frame = false;
            track.fresh_motion = None;
            track.current_foot_y = None;
        }
        for track in &mut self.tracks {
            let pred = track.filter.predict(timestamp_us);
            track.predicted_bounds = pred;
            let protected = self.protected_ids.contains(&track.id);
            let selected = self.selected_ids.contains(&track.id);
            if track.offscreen_dormant {
                track.state = TrackState::Lost;
                track.render_mask = None;
                track.lost_frames = track
                    .lost_frames
                    .max(self.config.max_missed_frames.saturating_add(1));
                continue;
            }
            if count_miss && likely_exit(track, self.offscreen_dormancy, selected) {
                track.offscreen_dormant = true;
                track.state = TrackState::Lost;
                track.lost_frames = self.config.max_missed_frames.saturating_add(1);
                track.reacquire_frames = 0;
                track.occluded_frames = 0;
                track.render_mask = None;
                track.occluder_ids.clear();
                track.occlusion_motion_bounds = None;
                for group in &mut self.groups {
                    group.members.retain(|id| *id != track.id);
                }
                self.groups.retain(|group| group.members.len() >= 2);
                continue;
            }
            if count_miss {
                let occluded = self.groups.iter().any(|g| g.members.contains(&track.id))
                    || track.state == TrackState::Occluded;
                if occluded {
                    track.occluded_frames = increment(track.occluded_frames);
                    track.state = TrackState::Occluded;
                    let anchor = track
                        .occlusion_motion_bounds
                        .unwrap_or(track.observed_bounds);
                    track.occlusion_motion_bounds = Some(anchor);
                    let b = blend(pred, anchor);
                    track.predicted_bounds = b;
                    track.filter.dampen(0.50);
                    render_prediction(track, b, 0)?;
                } else if track.state == TrackState::Reacquiring {
                    track.reacquire_frames = increment(track.reacquire_frames);
                    track.filter.dampen(0.70);
                    let b = bounded(track, pred, protected);
                    track.predicted_bounds = b;
                    if track.reacquire_frames <= self.config.post_occlusion_grace_frames {
                        render_prediction(track, b, 0)?;
                    } else {
                        track.state = TrackState::Lost;
                        track.lost_frames = 1;
                        track.occluder_ids.clear();
                        track.occlusion_motion_bounds = None;
                        render_prediction(track, b, track.lost_frames)?;
                    }
                } else {
                    track.state = TrackState::Lost;
                    track.lost_frames = increment(track.lost_frames);
                    track.occlusion_motion_bounds = None;
                    let b = bounded(track, pred, protected);
                    track.predicted_bounds = b;
                    render_prediction(track, b, track.lost_frames)?;
                    if track.lost_frames > self.config.max_missed_frames {
                        if protected {
                            track.render_mask = None;
                        } else {
                            track.state = TrackState::Removed;
                        }
                    }
                }
            } else if protected
                && track.state == TrackState::Lost
                && track.lost_frames > self.config.max_missed_frames
            {
                track.render_mask = None;
            } else if track.observed_mask.is_some() {
                let b = if protected
                    && matches!(track.state, TrackState::Reacquiring | TrackState::Lost)
                {
                    bounded(track, pred, true)
                } else {
                    pred
                };
                track.predicted_bounds = b;
                render_prediction(track, b, 0)?;
            }
            if count_miss
                && self.offscreen_dormancy
                && selected
                && !track.observed_this_frame
                && track.frames_since_observation >= 3
            {
                track.render_mask = None;
            }
        }
        self.tracks.retain(|t| t.state != TrackState::Removed);
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::{
        geometry::Transform,
        tracking::{Detection, Mask, TrackingConfig},
    };
    use std::sync::Arc;
    fn tracker() -> Tracker {
        let mut t = Tracker::new(TrackingConfig {
            max_missed_frames: 2,
            post_occlusion_grace_frames: 1,
            ..Default::default()
        });
        t.initialize(
            vec![Detection {
                bounds: Rect {
                    left: 10.0,
                    top: 20.0,
                    right: 100.0,
                    bottom: 220.0,
                },
                mask: Some(Mask {
                    width: 2,
                    height: 2,
                    pixels: Arc::from([255; 4]),
                    transform: Transform {
                        scale: 1.0,
                        pad_left: 0.0,
                        pad_top: 0.0,
                        source_width: 640,
                        source_height: 640,
                        input_size: 640,
                        proto_size: 2,
                    },
                }),
                confidence: 0.9,
                foot_y: 220.0,
            }],
            &[5],
        )
        .unwrap();
        t
    }
    #[test]
    fn skipped_inference_does_not_consume_miss_grace_and_expiry_retains_only_protected_slot() {
        let mut t = tracker();
        t.protected_ids.insert(5);
        for n in 0..20 {
            t.predict(n * 40_000, false).unwrap();
        }
        assert_eq!(t.tracks[0].lost_frames, 0);
        assert_eq!(t.tracks[0].frames_since_observation, 20);
        assert!(!t.tracks[0].observed_this_frame);
        assert!(t.tracks[0].current_foot_y.is_none());
        for n in 20..23 {
            t.predict(n * 40_000, true).unwrap();
        }
        assert_eq!(t.tracks[0].state, TrackState::Lost);
        assert!(t.tracks[0].render_mask.is_none());
        t.protected_ids.clear();
        t.predict(960_000, true).unwrap();
        assert!(t.tracks.is_empty());
    }
    #[test]
    fn reacquisition_uses_its_own_grace_then_canonical_mask_and_detection_age_suppresses_mixed_body()
     {
        let mut t = tracker();
        t.protected_ids.insert(5);
        t.selected_ids.insert(5);
        t.offscreen_dormancy = true;
        t.tracks[0].state = TrackState::Reacquiring;
        t.predict(0, true).unwrap();
        assert_eq!(t.tracks[0].state, TrackState::Reacquiring);
        t.predict(40_000, true).unwrap();
        assert_eq!(t.tracks[0].state, TrackState::Lost);
        assert_eq!(t.tracks[0].lost_frames, 1);
        assert!(t.tracks[0].render_mask.is_some());
        t.predict(80_000, true).unwrap();
        assert!(t.tracks[0].render_mask.is_none());
        assert_eq!(
            t.tracks[0].observed_mask.as_ref().unwrap().pixels.as_ref(),
            &[255; 4]
        );
    }
    #[test]
    fn face_only_identity_never_takes_body_offscreen_shortcut() {
        let mut t = tracker();
        t.protected_ids.insert(5);
        t.face_selected_ids.insert(5);
        t.offscreen_dormancy = true;
        t.tracks[0].observed_bounds.left = 0.0;
        t.tracks[0].reliable_motion = [-20.0, 0.0];
        t.predict(0, true).unwrap();
        assert!(!t.tracks[0].offscreen_dormant);
        t.selected_ids.insert(5);
        t.predict(40_000, true).unwrap();
        assert!(t.tracks[0].offscreen_dormant);
    }
}
