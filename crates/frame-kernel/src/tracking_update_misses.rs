//! Unmatched observation lifecycle. Evidence can move a privacy anchor without
//! transferring identity ownership from an uncertain occluder.
use crate::tracking_update::*;
use crate::{
    geometry::overlap_iou,
    tracking::*,
    tracking_evidence::{dormant_mixed_body, ignore_uncertain_occluder, lost_anchor_support},
    tracking_lifecycle::{bounded, likely_exit, render_prediction},
};
use std::collections::BTreeSet;
fn median(mut values: Vec<f32>) -> f32 {
    if values.is_empty() {
        return 0.0;
    }
    values.sort_by(f32::total_cmp);
    let n = values.len() / 2;
    if values.len() % 2 == 1 {
        values[n]
    } else {
        (values[n - 1] + values[n]) / 2.0
    }
}
impl Tracker {
    pub(super) fn update_misses(
        &mut self,
        _d: &[Detection],
        _at: i64,
        f: &mut Frame,
    ) -> Result<()> {
        for i in 0..self.tracks.len() {
            if f.matched_tracks.contains(&i) {
                continue;
            }
            let track = &self.tracks[i];
            let predicted = track.predicted_bounds;
            if track.offscreen_dormant {
                self.tracks[i].render_mask = None;
                continue;
            }
            if dormant_mixed_body(self, track) {
                let t = &mut self.tracks[i];
                t.state = TrackState::Lost;
                t.render_mask = None;
                t.occluder_ids.clear();
                t.occlusion_motion_bounds = None;
                continue;
            }
            let protected = self.protected_ids.contains(&track.id);
            let selected = self.selected_ids.contains(&track.id);
            if likely_exit(track, self.offscreen_dormancy, selected) {
                let t = &mut self.tracks[i];
                t.offscreen_dormant = true;
                t.state = TrackState::Lost;
                t.lost_frames = self.config.max_missed_frames + 1;
                t.reacquire_frames = 0;
                t.occluded_frames = 0;
                t.render_mask = None;
                t.occluder_ids.clear();
                t.occlusion_motion_bounds = None;
                for group in &mut self.groups {
                    group.members.retain(|id| *id != t.id)
                }
                self.groups.retain(|g| g.members.len() >= 2);
                continue;
            }
            let globally_uncertain = self
                .uncertain_occluders
                .values()
                .flatten()
                .copied()
                .collect::<BTreeSet<_>>();
            let others = self
                .tracks
                .iter()
                .enumerate()
                .filter(|(j, other)| {
                    if other.id == track.id
                        || self
                            .uncertain_occluders
                            .get(&track.id)
                            .is_some_and(|ids| ids.contains(&other.id))
                        || !f.matched_tracks.contains(j)
                    {
                        return false;
                    }
                    let ignored = ignore_uncertain_occluder(
                        track.state,
                        protected,
                        globally_uncertain.contains(&other.id),
                    );
                    let current = overlap(predicted, other.predicted_bounds)
                        >= self.config.occlusion_overlap_ratio;
                    let prior = track.occluder_ids.contains(&other.id)
                        && overlap_iou(track.observed_bounds, other.predicted_bounds)
                            >= self.config.occlusion_overlap_ratio;
                    let anchor = lost_anchor_support(
                        track.state,
                        protected,
                        overlap_iou(predicted, track.observed_bounds),
                        overlap(track.observed_bounds, other.predicted_bounds),
                        self.config.occlusion_overlap_ratio,
                        !ignored,
                    );
                    !ignored && (current || prior || anchor)
                })
                .map(|(_, t)| (t.id, t.fresh_motion))
                .collect::<Vec<_>>();
            let active_group = self
                .groups
                .iter()
                .any(|g| g.members.contains(&track.id) && g.state == GroupState::ActiveOverlap);
            let reacquiring_group = self
                .groups
                .iter()
                .any(|g| g.members.contains(&track.id) && g.state == GroupState::Reacquiring);
            let t = &mut self.tracks[i];
            if !others.is_empty() {
                let was_occluded = t.state == TrackState::Occluded;
                t.occluded_frames += 1;
                t.state = TrackState::Occluded;
                t.occluder_ids = others.iter().map(|x| x.0).collect();
                let fresh = others.iter().filter_map(|(_, m)| *m).collect::<Vec<_>>();
                let dx = median(fresh.iter().map(|m| m[0]).collect());
                let dy = median(fresh.iter().map(|m| m[1]).collect());
                let group = offset(
                    t.occlusion_motion_bounds.unwrap_or(t.observed_bounds),
                    dx,
                    dy,
                );
                t.occlusion_motion_bounds = Some(group);
                let blended = crate::geometry::Rect {
                    left: predicted.left * 0.5 + group.left * 0.5,
                    top: predicted.top * 0.5 + group.top * 0.5,
                    right: predicted.right * 0.5 + group.right * 0.5,
                    bottom: predicted.bottom * 0.5 + group.bottom * 0.5,
                };
                t.predicted_bounds = blended;
                let own = center(predicted);
                let gp = center(group);
                let old = center(t.observed_bounds);
                let ox = own[0] - old[0];
                let oy = own[1] - old[1];
                let gx = gp[0] - old[0];
                let gy = gp[1] - old[1];
                let om = (ox * ox + oy * oy).sqrt();
                let gm = (gx * gx + gy * gy).sqrt();
                let agreement = if om > 1e-3 && gm > 1e-3 {
                    (ox * gx + oy * gy) / (om * gm)
                } else {
                    0.0
                };
                let static_group =
                    (dx * dx + dy * dy).sqrt() < max_dimension(t.observed_bounds) * 0.02;
                if !was_occluded || fresh.is_empty() || static_group || agreement < 0.25 {
                    t.filter.dampen(0.50)
                }
                render_prediction(t, blended, 0)?;
            } else if active_group
                || reacquiring_group
                || f.ambiguous_tracks.contains(&i)
                || matches!(t.state, TrackState::Occluded | TrackState::Reacquiring)
            {
                if t.state == TrackState::Occluded {
                    t.state = TrackState::Reacquiring;
                    t.lost_frames = 0;
                    t.reacquire_frames = 1;
                    t.occluder_ids.clear();
                    render_prediction(t, t.predicted_bounds, 0)?;
                } else if t.state != TrackState::Reacquiring {
                    t.state = TrackState::Reacquiring;
                    t.lost_frames = 0;
                    t.reacquire_frames = 1;
                    t.occluder_ids.clear();
                    let b = bounded(t, predicted, protected);
                    t.predicted_bounds = b;
                    render_prediction(t, b, 0)?;
                } else {
                    t.reacquire_frames += 1;
                    t.occluder_ids.clear();
                    let b = bounded(t, predicted, protected);
                    t.predicted_bounds = b;
                    if t.reacquire_frames <= self.config.post_occlusion_grace_frames {
                        render_prediction(t, b, 0)?;
                    } else {
                        t.state = TrackState::Lost;
                        t.lost_frames = 1;
                        t.occluder_ids.clear();
                        t.occlusion_motion_bounds = None;
                        render_prediction(t, b, 1)?;
                    }
                }
            } else {
                match t.state {
                    TrackState::Active | TrackState::New => {
                        t.state = TrackState::Lost;
                        t.lost_frames = 1;
                        t.occluder_ids.clear();
                        t.occlusion_motion_bounds = None;
                        let b = bounded(t, predicted, protected);
                        t.predicted_bounds = b;
                        render_prediction(t, b, 1)?;
                    }
                    TrackState::Lost => {
                        t.lost_frames += 1;
                        t.occluder_ids.clear();
                        let b = bounded(t, predicted, protected);
                        t.predicted_bounds = b;
                        if t.lost_frames > self.config.max_missed_frames {
                            if protected {
                                t.render_mask = None
                            } else {
                                t.state = TrackState::Removed
                            }
                        } else {
                            render_prediction(t, b, t.lost_frames)?;
                        }
                    }
                    _ => {}
                }
            }
            if self.offscreen_dormancy
                && selected
                && !t.observed_this_frame
                && t.frames_since_observation >= 3
            {
                t.render_mask = None;
            }
        }
        Ok(())
    }
}
