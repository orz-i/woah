//! Ordered group quarantine and residual privacy-class evidence.
use crate::tracking_update::*;
use crate::{
    geometry::overlap_iou,
    tracking::*,
    tracking_evidence::{MaskCompetition, face_mask_conflict, identity_evidence, motion_evidence},
    tracking_mask::raw_mask_iou,
};
use std::collections::BTreeSet;
/// A two-sided fresh, unwarped silhouette match can disambiguate an overlap
/// before predicted geometry moves an identity to its neighbor. All four
/// masks must be current/last canonical and mutually comparable. This
/// experimental path is restricted to a complete two-person face-only scene.
fn direct_pair(tracks: [&Track; 2], detections: [&Detection; 2], at: i64) -> Option<[usize; 2]> {
    let old = [
        tracks[0].observed_mask.as_ref()?,
        tracks[1].observed_mask.as_ref()?,
    ];
    let current = [detections[0].mask.as_ref()?, detections[1].mask.as_ref()?];
    if tracks.iter().any(|t| {
        !matches!(
            t.state,
            TrackState::Active | TrackState::Occluded | TrackState::Reacquiring
        ) || t.offscreen_dormant
            || !t
                .observed_mask_at_us
                .and_then(|v| at.checked_sub(v))
                .is_some_and(|v| (1..=150_000).contains(&v))
    }) || detections.iter().any(|d| d.confidence < 0.50)
        || old.iter().chain(&current).any(|m| {
            !m.valid()
                || m.transform != old[0].transform
                || m.width != old[0].width
                || m.height != old[0].height
        })
        || raw_mask_iou(old[0], old[1]) > 0.10
        || raw_mask_iou(current[0], current[1]) > 0.10
    {
        return None;
    }
    let raw: [[f32; 2]; 2] =
        std::array::from_fn(|r| std::array::from_fn(|c| raw_mask_iou(old[r], current[c])));
    let supports = |r: usize, c: usize| {
        raw[r][c] >= 0.65
            && raw[r][c] - raw[r][1 - c] >= 0.25
            && raw[r][c] - raw[1 - r][c] >= 0.25
            && overlap_iou(tracks[r].observed_bounds, detections[c].bounds) >= 0.60
    };
    let straight = supports(0, 0) && supports(1, 1);
    let crossed = supports(0, 1) && supports(1, 0);
    match (straight, crossed) {
        (true, false) => Some([0, 1]),
        (false, true) => Some([1, 0]),
        _ => None,
    }
}

impl Tracker {
    pub(super) fn associate_groups(
        &mut self,
        d: &[Detection],
        at: i64,
        f: &mut Frame,
    ) -> Result<()> {
        for group in self.groups.clone() {
            let rows = (0..self.tracks.len())
                .filter(|i| {
                    group.members.contains(&self.tracks[*i].id)
                        && !f.matched_tracks.contains(i)
                        && self.tracks[*i].state != TrackState::Removed
                })
                .collect::<Vec<_>>();
            if rows.is_empty() {
                continue;
            }
            let mut left = f32::MAX;
            let mut top = f32::MAX;
            let mut right = f32::MIN_POSITIVE;
            let mut bottom = f32::MIN_POSITIVE;
            let predicted = rows
                .iter()
                .map(|&i| self.tracks[i].predicted_bounds)
                .collect::<Vec<_>>();
            let geometry = rows
                .iter()
                .flat_map(|&i| {
                    [
                        self.tracks[i].predicted_bounds,
                        self.tracks[i].observed_bounds,
                    ]
                })
                .collect::<Vec<_>>();
            for b in &geometry {
                left = left.min(b.left);
                top = top.min(b.top);
                right = right.max(b.right);
                bottom = bottom.max(b.bottom)
            }
            let mx = ((right - left) * 0.50).max(100.0);
            let my = ((bottom - top) * 0.50).max(100.0);
            let envelope = crate::geometry::Rect {
                left: left - mx,
                top: top - my,
                right: right + mx,
                bottom: bottom + my,
            };
            let cols = (0..d.len())
                .filter(|i| {
                    !f.matched_detections.contains(i) && intersection(envelope, d[*i].bounds) > 0.0
                })
                .collect::<Vec<_>>();
            f.group_tracks.extend(rows.iter().copied());
            if cols.is_empty() {
                continue;
            }
            if self.privacy_pair_continuity
                && self.tracks.len() == 2
                && d.len() == 2
                && self.selected_ids.is_empty()
                && self.face_selected_ids.len() == 1
                && rows.len() == 2
                && cols.len() == 2
                && rows
                    .iter()
                    .all(|&i| self.protected_ids.contains(&self.tracks[i].id))
                && let Some(pair) = direct_pair(
                    [&self.tracks[rows[0]], &self.tracks[rows[1]]],
                    [&d[cols[0]], &d[cols[1]]],
                    at,
                )
            {
                for (r, c) in pair.into_iter().enumerate() {
                    let ti = rows[r];
                    let di = cols[c];
                    f.matched_tracks.insert(ti);
                    f.matched_detections.insert(di);
                    f.group_tracks.remove(&ti);
                    commit(&mut self.tracks[ti], &d[di], at);
                }
                continue;
            }
            let matrix = scores(self, d, &rows, &cols, at, &mut f.cache)?;
            let matches = pairs(&matrix, self.config.min_match_score);
            if rows
                .iter()
                .any(|&i| self.protected_ids.contains(&self.tracks[i].id))
            {
                for (r, &ti) in rows.iter().enumerate() {
                    if matches.iter().any(|&(row, _)| row == r) {
                        continue;
                    }
                    // Kotlin maxByOrNull retains the first maximum on equal scores.
                    let mut best = 0;
                    for c in 1..cols.len() {
                        if matrix[r][c] > matrix[r][best] {
                            best = c
                        }
                    }
                    let di = cols[best];
                    let track = &self.tracks[ti];
                    let bi = overlap_iou(track.predicted_bounds, d[di].bounds);
                    let mi = f.cache.mask_iou(track, d, di);
                    let winner = matches
                        .iter()
                        .find_map(|&(row, col)| (col == best).then_some(rows[row]));
                    let winner_mask = winner.map(|i| f.cache.mask_iou(&self.tracks[i], d, di));
                    let protected = self.protected_ids.contains(&track.id);
                    let conflict = face_mask_conflict(MaskCompetition {
                        group_count: rows.len(),
                        state: track.state,
                        protected,
                        selected: self.selected_ids.contains(&track.id),
                        score: matrix[r][best],
                        minimum_score: self.config.min_match_score,
                        identity_supported: protected && identity_evidence(track.state, bi, mi),
                        mask_iou: mi,
                        winner_protected: winner
                            .is_none_or(|i| self.protected_ids.contains(&self.tracks[i].id)),
                        winner_mask_iou: winner_mask,
                    });
                    if conflict && let Some(i) = winner {
                        self.uncertain_occluders
                            .entry(track.id)
                            .or_default()
                            .insert(self.tracks[i].id);
                    }
                }
            }
            let mut matched_cols = BTreeSet::new();
            let mut reserved_here = BTreeSet::new();
            for (r, c) in &matches {
                let (r, c) = (*r, *c);
                let ti = rows[r];
                let di = cols[c];
                let track = &self.tracks[ti];
                let rank = ranking(&matrix, r, c);
                let bi = overlap_iou(track.predicted_bounds, d[di].bounds);
                let mi = f.cache.mask_iou(track, d, di);
                let protected = self.protected_ids.contains(&track.id);
                let valid = rank.score >= self.config.min_match_score
                    && rank.score >= rank.row_best - 1e-6
                    && rank.score >= rank.col_best - 1e-6
                    && rank.row_margin >= self.config.association_ambiguity_margin
                    && rank.col_margin >= self.config.association_ambiguity_margin
                    && (!protected || identity_evidence(track.state, bi, mi));
                if !valid {
                    if protected
                        && !self.selected_ids.contains(&track.id)
                        && d[di].mask.is_some()
                        && rank.score >= self.config.min_match_score
                        && rank.score >= rank.col_best - 1e-6
                        && rank.score
                            >= rank.row_best - self.config.association_ambiguity_margin * 2.0
                        && motion_evidence(bi, mi)
                    {
                        remember_motion(self, ti, d, di, &rank, (bi, mi), at);
                    }
                    if matches!(
                        self.tracks[ti].state,
                        TrackState::Occluded | TrackState::Active
                    ) {
                        self.tracks[ti].state = TrackState::Reacquiring
                    }
                    f.group_tracks.insert(ti);
                    reserve(f, di, &group.members, true);
                    continue;
                }
                f.matched_tracks.insert(ti);
                f.matched_detections.insert(di);
                matched_cols.insert(c);
                f.group_tracks.remove(&ti);
                commit(&mut self.tracks[ti], &d[di], at);
            }
            for (c, &di) in cols.iter().enumerate() {
                if matched_cols.contains(&c) {
                    continue;
                }
                let bounds = d[di].bounds;
                let support = geometry.iter().any(|&b| meaningful_overlap(b, bounds));
                let linked = matches
                    .iter()
                    .any(|&(_, mc)| mc != c && meaningful_overlap(d[cols[mc]].bounds, bounds));
                if support || linked {
                    reserve(
                        f,
                        di,
                        &group.members,
                        predicted.iter().any(|&b| meaningful_overlap(b, bounds)),
                    );
                    reserved_here.insert(di);
                }
            }
            let residual_tracks = rows
                .iter()
                .filter(|i| !f.matched_tracks.contains(i))
                .map(|&i| self.tracks[i].id)
                .collect::<Vec<_>>();
            let residual_detections = cols
                .iter()
                .filter(|i| !f.matched_detections.contains(i))
                .copied()
                .collect::<Vec<_>>();
            let balanced = cols.len() == rows.len()
                && !residual_tracks.is_empty()
                && residual_tracks.len() == residual_detections.len()
                && residual_detections
                    .iter()
                    .all(|i| reserved_here.contains(i));
            if balanced && residual_detections.iter().all(|&i| d[i].mask.is_some()) {
                let selected = residual_tracks
                    .iter()
                    .all(|id| self.selected_ids.contains(id));
                let unselected = residual_tracks
                    .iter()
                    .all(|id| !self.selected_ids.contains(id));
                if selected || unselected {
                    for &di in &residual_detections {
                        self.class_evidence.push(ClassEvidence {
                            selection: if selected {
                                SelectionClass::Selected
                            } else {
                                SelectionClass::Unselected
                            },
                            detection_index: di,
                            detection: d[di].clone(),
                            residual_ids: residual_tracks.clone(),
                            conservative_unknown: false,
                        });
                    }
                    if selected {
                        self.suppressed_selected_ids
                            .extend(residual_tracks.iter().copied());
                    }
                }
                if !self.face_selected_ids.is_empty()
                    && residual_tracks
                        .iter()
                        .all(|id| self.face_selected_ids.contains(id))
                {
                    for &di in &residual_detections {
                        self.face_class_evidence.push(ClassEvidence {
                            selection: SelectionClass::Selected,
                            detection_index: di,
                            detection: d[di].clone(),
                            residual_ids: residual_tracks.clone(),
                            conservative_unknown: false,
                        });
                    }
                }
            }
        }
        Ok(())
    }
}
fn meaningful_overlap(a: crate::geometry::Rect, b: crate::geometry::Rect) -> bool {
    let width = a.right.min(b.right) - a.left.max(b.left);
    let height = a.bottom.min(b.bottom) - a.top.max(b.top);
    if width <= 0.0 || height <= 0.0 {
        return false;
    }
    let min_width = (a.right - a.left)
        .max(0.0)
        .min((b.right - b.left).max(0.0))
        .max(1.0);
    let min_height = (a.bottom - a.top)
        .max(0.0)
        .min((b.bottom - b.top).max(0.0))
        .max(1.0);
    width >= 1.0_f32.max(min_width * 0.01) && height >= 1.0_f32.max(min_height * 0.01)
}
fn reserve(f: &mut Frame, di: usize, owners: &[i64], direct: bool) {
    f.group_detections.insert(di);
    if direct {
        f.direct_group_detections.insert(di);
    }
    f.owner_tracks
        .entry(di)
        .or_default()
        .extend(owners.iter().copied());
    let group = owners.iter().copied().collect::<BTreeSet<_>>();
    let groups = f.owner_groups.entry(di).or_default();
    if !groups.contains(&group) {
        groups.push(group)
    }
}

#[cfg(test)]
mod pair_tests {
    use super::*;
    use crate::geometry::{Rect, Transform};
    fn observation(x: usize) -> Detection {
        let mut pixels = vec![0; 64 * 64];
        for y in 8..56 {
            pixels[y * 64 + x..y * 64 + x + 12].fill(255);
        }
        Detection {
            bounds: Rect {
                left: x as f32 - 4.0,
                top: 4.0,
                right: x as f32 + 24.0,
                bottom: 60.0,
            },
            confidence: 0.9,
            foot_y: 60.0,
            mask: Some(Mask {
                width: 64,
                height: 64,
                pixels: pixels.into(),
                transform: Transform {
                    scale: 1.0,
                    pad_left: 0.0,
                    pad_top: 0.0,
                    source_width: 64,
                    source_height: 64,
                    input_size: 64,
                    proto_size: 64,
                },
            }),
        }
    }
    fn tracked(id: i64, d: &Detection) -> Track {
        let mut t = Track::observed(id, d.clone());
        t.observed_mask_at_us = Some(40_000);
        t
    }
    #[test]
    fn reciprocal_current_masks_resolve_independently_of_predicted_boxes_and_order() {
        let a = observation(10);
        let b = observation(24);
        let mut ta = tracked(0, &a);
        let mut tb = tracked(1, &b);
        ta.predicted_bounds = b.bounds;
        tb.predicted_bounds = a.bounds;
        assert_eq!(direct_pair([&ta, &tb], [&a, &b], 80_000), Some([0, 1]));
        assert_eq!(direct_pair([&ta, &tb], [&b, &a], 80_000), Some([1, 0]));
        // Normal 150ms lease remains a hard bound for BOTH old masks.
        assert_eq!(direct_pair([&ta, &tb], [&a, &b], 190_000), Some([0, 1]));
        assert_eq!(direct_pair([&ta, &tb], [&a, &b], 190_001), None);
        assert_eq!(direct_pair([&ta, &tb], [&a, &b], 40_000), None);
    }
    #[test]
    fn ambiguous_merged_missing_weak_and_noncomparable_masks_abstain() {
        let a = observation(10);
        let b = observation(24);
        let ta = tracked(0, &a);
        let mut tb = tracked(1, &b);
        assert_eq!(direct_pair([&ta, &tb], [&a, &a], 80_000), None);
        let merged = observation(16);
        assert_eq!(direct_pair([&ta, &tb], [&a, &merged], 80_000), None);
        let weak = Detection {
            confidence: 0.49,
            ..b.clone()
        };
        assert_eq!(direct_pair([&ta, &tb], [&a, &weak], 80_000), None);
        let missing = Detection {
            mask: None,
            ..b.clone()
        };
        assert_eq!(direct_pair([&ta, &tb], [&a, &missing], 80_000), None);
        let mut other_grid = b.clone();
        other_grid.mask.as_mut().unwrap().transform.pad_left = 1.0;
        assert_eq!(direct_pair([&ta, &tb], [&a, &other_grid], 80_000), None);
        // A fresh bbox-only commit cannot refresh historical mask evidence.
        commit(&mut tb, &missing, 70_000);
        assert_eq!(tb.observed_mask_at_us, None);
        assert_eq!(direct_pair([&ta, &tb], [&a, &b], 80_000), None);
    }
}
