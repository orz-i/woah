//! Ordered spatial overlap components and finite reacquisition windows.
use crate::{
    geometry::Rect,
    tracking::{Group, GroupState, TrackState, Tracker},
};
use std::collections::{BTreeSet, VecDeque};
fn intersection(a: Rect, b: Rect) -> f32 {
    (a.right.min(b.right) - a.left.max(b.left)).max(0.0)
        * (a.bottom.min(b.bottom) - a.top.max(b.top)).max(0.0)
}
fn area(r: Rect) -> f32 {
    (r.right - r.left).max(0.0) * (r.bottom - r.top).max(0.0)
}
impl Tracker {
    pub fn update_groups(&mut self, timestamp_us: i64) {
        let valid = self
            .tracks
            .iter()
            .filter(|t| {
                t.state != TrackState::Removed
                    && !t.offscreen_dormant
                    && !(self.offscreen_dormancy
                        && self.selected_ids.contains(&t.id)
                        && !t.observed_this_frame
                        && t.frames_since_observation > self.config.max_missed_frames)
            })
            .map(|t| (t.id, t.predicted_bounds))
            .collect::<Vec<_>>();
        // Adjacency and traversal preserve input track order, as does the frozen
        // reference's insertion-ordered map/set. Numeric ID sorting changes ties.
        let mut adjacent = vec![Vec::new(); valid.len()];
        for i in 0..valid.len() {
            for j in i + 1..valid.len() {
                let minimum = area(valid[i].1).min(area(valid[j].1));
                let ratio = if minimum > 0.0 {
                    intersection(valid[i].1, valid[j].1) / minimum
                } else {
                    0.0
                };
                if ratio >= self.config.occlusion_overlap_ratio {
                    adjacent[i].push(j);
                    adjacent[j].push(i);
                }
            }
        }
        let mut visited = vec![false; valid.len()];
        let mut components = Vec::new();
        for i in 0..valid.len() {
            if visited[i] {
                continue;
            }
            let mut component = Vec::new();
            let mut queue = VecDeque::from([i]);
            visited[i] = true;
            while let Some(index) = queue.pop_front() {
                component.push(valid[index].0);
                for &next in &adjacent[index] {
                    if !visited[next] {
                        visited[next] = true;
                        queue.push_back(next);
                    }
                }
            }
            if component.len() >= 2 {
                components.push(component);
            }
        }
        let mut matched = BTreeSet::new();
        for members in components {
            if let Some(index) = self.groups.iter().enumerate().find_map(|(i, g)| {
                (!matched.contains(&i) && g.members.iter().any(|id| members.contains(id)))
                    .then_some(i)
            }) {
                let g = &mut self.groups[index];
                g.members = members;
                g.last_overlap_us = timestamp_us;
                g.state = GroupState::ActiveOverlap;
                g.reacquire_frames = 0;
                matched.insert(index);
            } else {
                let index = self.groups.len();
                self.groups.push(Group {
                    members,
                    started_at_us: timestamp_us,
                    last_overlap_us: timestamp_us,
                    state: GroupState::ActiveOverlap,
                    reacquire_frames: 0,
                });
                matched.insert(index);
            }
        }
        for (i, g) in self.groups.iter_mut().enumerate() {
            if !matched.contains(&i) {
                if g.state == GroupState::ActiveOverlap {
                    g.state = GroupState::Reacquiring;
                    g.reacquire_frames = 1;
                    for t in &mut self.tracks {
                        if g.members.contains(&t.id) {
                            t.occluder_ids.clear();
                        }
                    }
                } else {
                    g.reacquire_frames = g.reacquire_frames.saturating_add(1);
                }
            }
        }
        self.groups.retain(|g| {
            valid
                .iter()
                .filter(|(id, _)| g.members.contains(id))
                .count()
                >= 2
                && !(g.state == GroupState::Reacquiring
                    && g.reacquire_frames > self.config.post_occlusion_grace_frames)
        });
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::tracking::{Detection, TrackingConfig};
    fn bounds(x: f32) -> Rect {
        Rect {
            left: x,
            top: 0.0,
            right: x + 100.0,
            bottom: 200.0,
        }
    }
    fn tracker() -> Tracker {
        let mut t = Tracker::new(TrackingConfig {
            post_occlusion_grace_frames: 2,
            ..Default::default()
        });
        t.initialize(
            [0.0, 60.0, 120.0, 500.0]
                .into_iter()
                .map(|x| Detection {
                    bounds: bounds(x),
                    mask: None,
                    confidence: 1.0,
                    foot_y: 200.0,
                })
                .collect(),
            &[9, 2, 7, 1],
        )
        .unwrap();
        t
    }
    #[test]
    fn transitive_components_preserve_input_order_and_reacquire_expires() {
        let mut t = tracker();
        t.update_groups(0);
        assert_eq!(t.groups.len(), 1);
        assert_eq!(t.groups[0].members, [9, 2, 7]);
        t.tracks[1].predicted_bounds = bounds(300.0);
        t.update_groups(40_000);
        assert_eq!(t.groups[0].state, GroupState::Reacquiring);
        assert_eq!(t.groups[0].reacquire_frames, 1);
        t.update_groups(80_000);
        assert_eq!(t.groups.len(), 1);
        t.update_groups(120_000);
        assert!(t.groups.is_empty());
    }
    #[test]
    fn expired_mixed_body_slots_do_not_hold_unrelated_group_members() {
        let mut t = tracker();
        t.selected_ids.insert(2);
        t.offscreen_dormancy = true;
        t.tracks[1].observed_this_frame = false;
        t.tracks[1].frames_since_observation = 16;
        t.update_groups(0);
        assert!(t.groups.is_empty());
    }
}
