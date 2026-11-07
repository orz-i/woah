//! Ordered group quarantine and residual privacy-class evidence.
use crate::tracking_update::*;
use crate::{
    geometry::overlap_iou,
    tracking::*,
    tracking_evidence::{MaskCompetition, face_mask_conflict, identity_evidence, motion_evidence},
};
use std::collections::BTreeSet;
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
