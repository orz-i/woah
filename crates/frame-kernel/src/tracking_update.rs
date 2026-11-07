//! Complete canonical observation update. Native rendering and inference own no
//! identity transition policy once this session is adopted by both adapters.
use crate::{
    association::match_costs,
    association_score::reservation_release_with_owner_evidence,
    geometry::{Rect, overlap_iou},
    tracking::*,
    tracking_evidence::{
        AssociationCache, CurrentOwnerEvidence, current_owner_refutes_candidate,
        dormant_mixed_body, identity_evidence, motion_evidence, record_motion,
    },
    tracking_motion,
};
use std::collections::{BTreeMap, BTreeSet};
pub(super) type Result<T> = std::result::Result<T, TrackingError>;
pub(super) fn area(r: Rect) -> f32 {
    (r.right - r.left).max(0.0) * (r.bottom - r.top).max(0.0)
}
pub(super) fn intersection(a: Rect, b: Rect) -> f32 {
    (a.right.min(b.right) - a.left.max(b.left)).max(0.0)
        * (a.bottom.min(b.bottom) - a.top.max(b.top)).max(0.0)
}
pub(super) fn overlap(a: Rect, b: Rect) -> f32 {
    let min = area(a).min(area(b));
    if min > 0.0 {
        intersection(a, b) / min
    } else {
        0.0
    }
}
pub(super) fn offset(a: Rect, x: f32, y: f32) -> Rect {
    Rect {
        left: a.left + x,
        top: a.top + y,
        right: a.right + x,
        bottom: a.bottom + y,
    }
}
pub(super) fn center(a: Rect) -> [f32; 2] {
    [
        a.left + (a.right - a.left).max(0.0) / 2.0,
        a.top + (a.bottom - a.top).max(0.0) / 2.0,
    ]
}
pub(super) fn max_dimension(a: Rect) -> f32 {
    (a.right - a.left).max(0.0).max((a.bottom - a.top).max(0.0))
}
pub(super) fn commit(t: &mut Track, d: &Detection, at: i64) {
    record_motion(t, d, at);
    t.observed_bounds = d.bounds;
    if d.mask.is_some() {
        t.observed_mask = d.mask.clone();
    }
    t.predicted_bounds = d.bounds;
    t.render_mask = d.mask.clone().or_else(|| t.observed_mask.clone());
    t.confidence = d.confidence;
    t.lost_frames = 0;
    t.occluded_frames = 0;
    t.reacquire_frames = 0;
    t.occluder_ids.clear();
    t.occlusion_motion_bounds = None;
    t.state = TrackState::Active;
    t.offscreen_dormant = false;
    t.observed_this_frame = true;
    t.frames_since_observation = 0;
    t.current_foot_y = Some(d.foot_y);
    t.observed_foot_y = d.foot_y;
    t.filter.update(d.bounds, at);
}
#[derive(Default)]
pub(super) struct Frame {
    pub matched_tracks: BTreeSet<usize>,
    pub matched_detections: BTreeSet<usize>,
    pub group_tracks: BTreeSet<usize>,
    pub group_detections: BTreeSet<usize>,
    pub direct_group_detections: BTreeSet<usize>,
    pub owner_tracks: BTreeMap<usize, BTreeSet<i64>>,
    pub owner_groups: BTreeMap<usize, Vec<BTreeSet<i64>>>,
    pub global_detections: BTreeSet<usize>,
    pub ambiguous_tracks: BTreeSet<usize>,
    pub cache: AssociationCache,
}
pub(super) fn scores(
    t: &Tracker,
    d: &[Detection],
    rows: &[usize],
    cols: &[usize],
    at: i64,
    cache: &mut AssociationCache,
) -> Result<Vec<Vec<f32>>> {
    rows.iter()
        .map(|&r| {
            cols.iter()
                .map(|&c| {
                    cache
                        .score(t, r, d, c, at)
                        .ok_or(TrackingError::InvalidObservation)
                })
                .collect()
        })
        .collect()
}
pub(super) fn pairs(matrix: &[Vec<f32>], minimum: f32) -> Vec<(usize, usize)> {
    let rows = matrix.len();
    let columns = matrix.first().map_or(0, Vec::len);
    let costs = matrix
        .iter()
        .flat_map(|row| row.iter().map(|s| (1.0 - s).clamp(0.0, 1.0)))
        .collect::<Vec<_>>();
    match_costs(
        &costs,
        rows as u32,
        columns as u32,
        (1.0 - minimum).clamp(0.1, 0.90),
    )
    .unwrap_or_default()
    .iter()
    .map(|p| (p.row as usize, p.column as usize))
    .collect()
}
pub(super) struct Ranking {
    pub score: f32,
    pub row_best: f32,
    pub col_best: f32,
    pub row_margin: f32,
    pub col_margin: f32,
}
pub(super) fn ranking(m: &[Vec<f32>], r: usize, c: usize) -> Ranking {
    let score = m[r][c];
    let maximum = |values: Vec<f32>| values.into_iter().reduce(f32::max);
    Ranking {
        score,
        row_best: maximum(m[r].clone()).unwrap_or(0.0),
        col_best: maximum(m.iter().map(|v| v[c]).collect()).unwrap_or(0.0),
        row_margin: maximum(
            m[r].iter()
                .enumerate()
                .filter_map(|(i, &x)| (i != c).then_some(x))
                .collect(),
        )
        .map_or(f32::INFINITY, |x| score - x),
        col_margin: maximum(
            m.iter()
                .enumerate()
                .filter_map(|(i, v)| (i != r).then_some(v[c]))
                .collect(),
        )
        .map_or(f32::INFINITY, |x| score - x),
    }
}
pub(super) fn geometry_ambiguous(
    t: &Tracker,
    d: &[Detection],
    rows: &[usize],
    cols: &[usize],
    m: &[Vec<f32>],
    position: (usize, usize),
    k: &Ranking,
) -> bool {
    let (r, c) = position;
    let margin = t.config.association_ambiguity_margin;
    let row_alt = cols.iter().enumerate().any(|(a, &i)| {
        a != c
            && k.row_best - m[r][a] <= margin
            && confusable_overlap(d[cols[c]].bounds, d[i].bounds) >= 0.10
    });
    let col_alt = rows.iter().enumerate().any(|(a, &i)| {
        a != r
            && k.col_best - m[a][c] <= margin
            && confusable_overlap(
                t.tracks[rows[r]].predicted_bounds,
                t.tracks[i].predicted_bounds,
            ) >= 0.10
    });
    (k.row_margin < margin && row_alt) || (k.col_margin < margin && col_alt)
}
pub(super) fn remember_motion(
    t: &mut Tracker,
    index: usize,
    d: &[Detection],
    di: usize,
    rank: &Ranking,
    overlap_evidence: (f32, f32),
    at: i64,
) {
    let id = t.tracks[index].id;
    t.motion_evidence.insert(
        id,
        MotionEvidence {
            id,
            detection_index: di,
            detection: d[di].clone(),
            score: rank.score,
            bbox_iou: overlap_evidence.0,
            mask_iou: overlap_evidence.1,
            timestamp_us: at,
        },
    );
}
impl Tracker {
    pub fn update(&mut self, detections: Vec<Detection>, timestamp_us: i64) -> Result<&[Track]> {
        if detections.iter().any(|d| !d.valid()) {
            return Err(TrackingError::InvalidObservation);
        }
        let mut pending = self.clone();
        pending.update_inner(&detections, timestamp_us)?;
        *self = pending;
        Ok(&self.tracks)
    }
    fn update_inner(&mut self, d: &[Detection], at: i64) -> Result<()> {
        if !self.initialized {
            return self.initialize(d.to_vec(), &[]);
        }
        self.clear_frame_evidence();
        if d.is_empty() {
            self.predict(at, true)?;
            return Ok(());
        }
        for t in &mut self.tracks {
            t.frames_since_observation = t
                .frames_since_observation
                .saturating_add(1)
                .min(i32::MAX as u32);
            t.observed_previous_frame = t.observed_this_frame;
            t.observed_this_frame = false;
            t.fresh_motion = None;
            t.current_foot_y = None;
        }
        if self.tracks.is_empty() {
            for detection in d {
                self.add_observation(detection.clone())?;
            }
            return Ok(());
        }
        let raw = self
            .tracks
            .iter_mut()
            .map(|t| {
                let p = t.filter.predict(at);
                t.age = t.age.saturating_add(1);
                t.predicted_bounds = p;
                p
            })
            .collect::<Vec<_>>();
        let scene = tracking_motion::estimate(
            &self
                .tracks
                .iter()
                .filter(|t| !t.offscreen_dormant && !dormant_mixed_body(self, t))
                .map(|t| (t.id, t.predicted_bounds))
                .collect::<Vec<_>>(),
            d,
            &self.config,
        )
        .ok_or(TrackingError::InvalidObservation)?;
        if self.config.scene_motion_compensation
            && scene.confidence > 0.0
            && (scene.dx.abs() > 0.5 || scene.dy.abs() > 0.5)
        {
            for (t, p) in self.tracks.iter_mut().zip(raw) {
                t.predicted_bounds = offset(p, scene.dx, scene.dy)
            }
        }
        self.update_groups(at);
        let mut frame = Frame::default();
        self.associate_groups(d, at, &mut frame)?;
        self.associate_global(d, at, &mut frame)?;
        self.update_misses(d, at, &mut frame)?;
        self.recover_or_insert(d, at, &mut frame)?;
        self.tracks.retain(|t| t.state != TrackState::Removed);
        Ok(())
    }
    fn add_observation(&mut self, d: Detection) -> Result<()> {
        let id = self.next_id;
        self.next_id = id.checked_add(1).ok_or(TrackingError::IdentityOverflow)?;
        self.tracks.push(Track::observed(id, d));
        Ok(())
    }
    fn associate_global(&mut self, d: &[Detection], at: i64, f: &mut Frame) -> Result<()> {
        let rows = (0..self.tracks.len())
            .filter(|i| {
                !f.matched_tracks.contains(i)
                    && !f.group_tracks.contains(i)
                    && !dormant_mixed_body(self, &self.tracks[*i])
                    && matches!(self.tracks[*i].state, TrackState::Active | TrackState::New)
            })
            .collect::<Vec<_>>();
        let shadow = (0..d.len())
            .filter(|i| !f.matched_detections.contains(i))
            .collect::<Vec<_>>();
        let mut rescue = BTreeSet::new();
        if !rows.is_empty() && !f.group_detections.is_empty() && !shadow.is_empty() {
            let matrix = scores(self, d, &rows, &shadow, at, &mut f.cache)?;
            let selected = pairs(&matrix, self.config.min_match_score);
            for (r, &ti) in rows.iter().enumerate() {
                for (c, &di) in shadow.iter().enumerate() {
                    if !f.group_detections.contains(&di)
                        || f.owner_tracks
                            .get(&di)
                            .is_some_and(|s| s.contains(&self.tracks[ti].id))
                    {
                        continue;
                    }
                    let track = &self.tracks[ti];
                    let rank = ranking(&matrix, r, c);
                    let ambiguous =
                        geometry_ambiguous(self, d, &rows, &shadow, &matrix, (r, c), &rank);
                    let bi = overlap_iou(track.predicted_bounds, d[di].bounds);
                    let mi = f.cache.mask_iou(track, d, di);
                    let evidence = !self.protected_ids.contains(&track.id)
                        || identity_evidence(track.state, bi, mi);
                    let strict = selected.contains(&(r, c))
                        && rank.score >= self.config.min_match_score
                        && rank.score >= rank.row_best - 1e-6
                        && rank.score >= rank.col_best - 1e-6
                        && !ambiguous
                        && evidence;
                    let face = self.protected_ids.contains(&track.id)
                        && !self.selected_ids.contains(&track.id);
                    let flags = u32::from(strict)
                        | if face { 2 } else { 0 }
                        | if f.direct_group_detections.contains(&di) {
                            4
                        } else {
                            0
                        };
                    let all_owners_refuted = flags & 7 == 7
                        && f.owner_tracks.get(&di).is_some_and(|owners| {
                            !owners.is_empty()
                                && owners.iter().all(|owner_id| {
                                    self.tracks
                                        .iter()
                                        .enumerate()
                                        .find(|(_, owner)| owner.id == *owner_id)
                                        .is_some_and(|(owner_index, owner)| {
                                            let owner_bounds =
                                                overlap_iou(owner.predicted_bounds, d[di].bounds);
                                            let owner_mask = f.cache.mask_iou(owner, d, di);
                                            let owner_score =
                                                f.cache.score(self, owner_index, d, di, at);
                                            current_owner_refutes_candidate(
                                                CurrentOwnerEvidence {
                                                    observed: owner.observed_this_frame,
                                                    state: owner.state,
                                                    score: owner_score,
                                                    bounds_iou: owner_bounds,
                                                    mask_iou: owner_mask,
                                                },
                                                self.config.min_match_score,
                                            )
                                        })
                                })
                        });
                    if reservation_release_with_owner_evidence(
                        flags,
                        f.owner_groups.get(&di).map_or(0, Vec::len) as u32,
                        bi,
                        mi,
                        all_owners_refuted,
                    ) == Some(true)
                    {
                        rescue.insert(di);
                    }
                }
            }
        }
        let cols = (0..d.len())
            .filter(|i| {
                !f.matched_detections.contains(i)
                    && (!f.group_detections.contains(i) || rescue.contains(i))
            })
            .collect::<Vec<_>>();
        if rows.is_empty() || cols.is_empty() {
            return Ok(());
        }
        let matrix = scores(self, d, &rows, &cols, at, &mut f.cache)?;
        for (r, c) in pairs(&matrix, self.config.min_match_score) {
            let ti = rows[r];
            let di = cols[c];
            let track = &self.tracks[ti];
            let rank = ranking(&matrix, r, c);
            let ambiguous = geometry_ambiguous(self, d, &rows, &cols, &matrix, (r, c), &rank);
            let bi = overlap_iou(track.predicted_bounds, d[di].bounds);
            let mi = f.cache.mask_iou(track, d, di);
            let protected = self.protected_ids.contains(&track.id);
            let evidence = !protected || identity_evidence(track.state, bi, mi);
            let valid = rank.score >= self.config.min_match_score
                && rank.score >= rank.row_best - 1e-6
                && rank.score >= rank.col_best - 1e-6
                && !ambiguous
                && evidence;
            if valid {
                f.matched_tracks.insert(ti);
                f.matched_detections.insert(di);
                commit(&mut self.tracks[ti], &d[di], at);
                continue;
            }
            if protected
                && !self.selected_ids.contains(&track.id)
                && d[di].mask.is_some()
                && rank.score >= self.config.min_match_score
                && rank.score >= rank.col_best - 1e-6
                && rank.score >= rank.row_best - self.config.association_ambiguity_margin * 2.0
                && motion_evidence(bi, mi)
                && ambiguous
            {
                remember_motion(self, ti, d, di, &rank, (bi, mi), at);
            }
            f.ambiguous_tracks.insert(ti);
            f.global_detections.insert(di);
            let mut confusable = vec![di];
            for (a, &other) in cols.iter().enumerate() {
                if a != c
                    && rank.row_best - matrix[r][a] <= self.config.association_ambiguity_margin
                    && confusable_overlap(d[di].bounds, d[other].bounds) >= 0.10
                {
                    f.global_detections.insert(other);
                    confusable.push(other);
                }
            }
            let mut identities = Vec::new();
            for (a, &index) in rows.iter().enumerate() {
                if confusable.iter().any(|ci| {
                    cols.iter()
                        .position(|x| x == ci)
                        .is_some_and(|b| matrix[a][b] >= self.config.min_match_score)
                }) {
                    identities.push(self.tracks[index].id)
                }
            }
            if identities.len() >= 2 {
                if let Some(g) = self
                    .groups
                    .iter_mut()
                    .find(|g| g.members.iter().any(|id| identities.contains(id)))
                {
                    for id in identities {
                        if !g.members.contains(&id) {
                            g.members.push(id)
                        }
                    }
                    g.state = GroupState::Reacquiring;
                    g.reacquire_frames = g.reacquire_frames.max(1);
                } else {
                    self.groups.push(Group {
                        members: identities,
                        started_at_us: at,
                        last_overlap_us: at,
                        state: GroupState::Reacquiring,
                        reacquire_frames: 1,
                    })
                }
            }
        }
        Ok(())
    }
    fn recover_or_insert(&mut self, d: &[Detection], at: i64, f: &mut Frame) -> Result<()> {
        let mut unassigned = Vec::new();
        for di in 0..d.len() {
            if f.matched_detections.contains(&di)
                || f.group_detections.contains(&di)
                || f.global_detections.contains(&di)
            {
                continue;
            }
            let mut plausible = false;
            for (ti, track) in self.tracks.iter().enumerate() {
                let unresolved = track.state == TrackState::Reacquiring
                    || f.ambiguous_tracks.contains(&ti)
                    || self.groups.iter().any(|g| {
                        g.state == GroupState::Reacquiring && g.members.contains(&track.id)
                    });
                if unresolved
                    && f.cache
                        .score(self, ti, d, di, at)
                        .ok_or(TrackingError::InvalidObservation)?
                        >= self.config.min_match_score
                {
                    plausible = true;
                    break;
                }
            }
            if plausible {
                f.global_detections.insert(di);
            } else {
                unassigned.push(di)
            }
        }
        let recoverable = (0..self.tracks.len())
            .filter(|i| self.tracks[*i].state == TrackState::Lost && !f.matched_tracks.contains(i))
            .collect::<Vec<_>>();
        let mut reclaimed = BTreeSet::new();
        for di in unassigned {
            let mut best = None;
            let mut best_distance = f32::MAX;
            let candidate = &d[di];
            for &ti in &recoverable {
                let t = &self.tracks[ti];
                if reclaimed.contains(&t.id) {
                    continue;
                }
                let p = center(t.predicted_bounds);
                let c = center(candidate.bounds);
                let o = center(t.observed_bounds);
                let distance = ((p[0] - c[0]).powi(2) + (p[1] - c[1]).powi(2)).sqrt();
                let bi = overlap_iou(t.predicted_bounds, candidate.bounds);
                let reference =
                    max_dimension(t.predicted_bounds).max(max_dimension(candidate.bounds));
                let dx = p[0] - o[0];
                let dy = p[1] - o[1];
                let travel = (dx * dx + dy * dy).sqrt();
                let progress = if travel > 1e-4 {
                    ((c[0] - o[0]) * dx + (c[1] - o[1]) * dy) / (travel * travel)
                } else {
                    1.0
                };
                let consistent = travel < reference * 0.10 || bi > 0.05 || progress >= 0.25;
                let nearby = bi > 0.05 || distance < reference * 0.8;
                let mi = f.cache.mask_iou(t, d, di);
                let evidence = !self.protected_ids.contains(&t.id) || bi >= 0.50 || mi >= 0.45;
                if nearby && consistent && evidence && distance < best_distance {
                    best_distance = distance;
                    best = Some(ti)
                }
            }
            if let Some(ti) = best {
                commit(&mut self.tracks[ti], candidate, at);
                reclaimed.insert(self.tracks[ti].id);
            } else {
                self.add_observation(candidate.clone())?;
            }
        }
        Ok(())
    }
}

fn confusable_overlap(a: Rect, b: Rect) -> f32 {
    intersection(a, b) / area(a).min(area(b)).max(1e-4)
}
