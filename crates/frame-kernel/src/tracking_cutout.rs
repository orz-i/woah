//! Appearance-aware association profile on the common multi-person Tracker.
//! This profile never emits privacy prediction/coverage masks.
use crate::{
    appearance::{Appearance, AppearanceMemory, present, similarity},
    dance_cutout::{CutoutClock, MIN_CONF, STRONG_CONF},
    geometry::{Rect, overlap_iou},
    tracking::{Detection, Track, TrackState, Tracker, TrackingError},
    tracking_lifecycle::bound_prediction,
    tracking_mask::raw_mask_iou,
    tracking_update::{center, commit, max_dimension, pairs, ranking},
};
use std::collections::BTreeSet;
const MAX_GAP_US: i64 = 4_000_000;
const CONTINUITY_US: i64 = 160_000;
const MAX_TRACKS: usize = 256;
const MARGIN: f32 = 0.045;

#[derive(Clone)]
pub(crate) struct PendingMatch {
    pub(crate) at: i64,
    pub(crate) bounds: Rect,
    pub(crate) appearance: Appearance,
}
#[derive(Clone, Copy, Default)]
struct Evidence {
    score: f32,
    app: f32,
    geom: f32,
    contrast: f32,
    bank: f32,
    owner: bool,
}

fn distance(a: Rect, b: Rect) -> f32 {
    let [x, y] = center(a);
    let [u, v] = center(b);
    (x - u).hypot(y - v)
}
fn overlaps(track: &Track, d: &Detection) -> (f32, f32) {
    let b = overlap_iou(track.observed_bounds, d.bounds);
    let m = match (&track.observed_mask, &d.mask) {
        (Some(a), Some(b)) => raw_mask_iou(a, b),
        _ => 0.,
    };
    (b, m)
}
fn scale(track: &Track) -> f32 {
    let floor = track.observed_mask.as_ref().map_or(1., |m| {
        m.transform.source_width.max(m.transform.source_height) as f32 / 32.
    });
    max_dimension(track.observed_bounds).max(floor).max(1.)
}
fn candidate(
    track: &Track,
    d: &Detection,
    f: &Appearance,
    at: i64,
    chosen: bool,
    negatives: &[Appearance],
    anchor_bounds: Rect,
) -> Evidence {
    let Some(memory) = &track.appearance else {
        return Evidence::default();
    };
    let elapsed = at - memory.last_seen;
    if elapsed <= 0
        || elapsed > MAX_GAP_US
        || !present(f)
        || !present(&memory.anchor)
        || d.confidence < MIN_CONF
        || d.mask
            .as_ref()
            .is_none_or(|m| !m.pixels.iter().any(|&p| p >= 128))
    {
        return Evidence::default();
    }
    // Apply only logically necessary cheap guards before walking the full
    // mask grid. Do not approximate IoU, or change any score/threshold/tie.
    let s = scale(track);
    let gap = elapsed as f32 / 1_000_000.;
    let dist = distance(track.predicted_bounds, d.bounds) / s;
    if !chosen {
        if d.confidence < STRONG_CONF || elapsed > 500_000 || dist > 0.7 {
            return Evidence::default();
        }
    } else if (elapsed <= CONTINUITY_US && dist > 1.25)
        || (elapsed > CONTINUITY_US
            && (d.confidence < STRONG_CONF || dist > 1.8_f32.min(0.55 + gap * 0.35)))
        || (d.confidence < STRONG_CONF && (elapsed > 150_000 || !track.observed_this_frame))
    {
        return Evidence::default();
    }
    let bank = memory.best(f);
    let app = memory.score_with_best(f, bank);
    if (!chosen && app < 0.74)
        || (chosen && elapsed <= CONTINUITY_US && app < 0.66)
        || (chosen && elapsed > CONTINUITY_US && (bank < 0.82 || app < 0.74))
    {
        return Evidence::default();
    }
    let negative = negatives
        .iter()
        .map(|x| similarity(x, f))
        .fold(0., f32::max);
    let contrast = bank - negative;
    if chosen && contrast < -0.035 {
        return Evidence::default();
    }
    let (bi, mi) = overlaps(track, d);
    // A sharp collapse into a much smaller scene instance is negative
    // identity evidence, especially with background people of similar color.
    // Both the last observed extent and the immutable selected anchor must
    // contradict it; strong mask continuity still permits posture changes.
    // Using the longest dimension rather than height avoids penalizing
    // horizontal floorwork merely because the vertical axis contracts.
    if chosen {
        let extent = max_dimension(d.bounds);
        if extent < 0.55 * max_dimension(track.observed_bounds)
            && extent < 0.55 * max_dimension(anchor_bounds)
            && mi < 0.35
        {
            return Evidence::default();
        }
    }
    let geom = bi
        .max(mi)
        .max(overlap_iou(track.predicted_bounds, d.bounds));
    if !chosen {
        if bi < 0.25 && mi < 0.25 {
            return Evidence::default();
        }
        let owner = elapsed <= CONTINUITY_US && app >= 0.82 && (mi >= 0.42 || bi >= 0.60);
        return Evidence {
            score: 0.48 * app + 0.37 * geom + 0.15 * (1. - dist).max(0.),
            app,
            geom,
            contrast,
            bank,
            owner,
        };
    }
    if d.confidence < STRONG_CONF && (bi < 0.55 || mi < 0.50) {
        return Evidence::default();
    }
    let score = if elapsed <= CONTINUITY_US {
        if geom < 0.06 && dist > 0.50 {
            return Evidence::default();
        }
        0.52 * app + 0.29 * geom + 0.15 * (1. - dist).max(0.) + 0.04 * contrast
    } else {
        if contrast < -0.03 && (geom < 0.35 || gap > 0.4) {
            return Evidence::default();
        }
        0.65 * app + 0.17 * geom + 0.13 * (1. - dist).max(0.) + 0.05 * contrast
    };
    Evidence {
        score,
        app,
        geom,
        contrast,
        bank,
        owner: false,
    }
}

/// A stale competing owner may not steal the incumbent selected track
/// solely by bbox overlap. Release only when the current *raw* mask
/// uniquely supports the more recent observed selected identity.
fn incumbent_continuity(
    selected: &Track,
    competitor: &Track,
    observation: &Detection,
    at: i64,
    chosen_score: f32,
    chosen_margin: f32,
) -> bool {
    let (Some(a), Some(b)) = (&selected.appearance, &competitor.appearance) else {
        return false;
    };
    let age = at - a.last_seen;
    if !selected.observed_this_frame
        || age <= 0
        || age > CONTINUITY_US
        || b.last_seen >= a.last_seen
        || observation.confidence < STRONG_CONF
        || chosen_score < 0.40
        || chosen_margin < MARGIN
        || overlap_iou(selected.observed_bounds, competitor.observed_bounds) < 0.20
    {
        return false;
    }
    let (_, own) = overlaps(selected, observation);
    let (_, rival) = overlaps(competitor, observation);
    own >= 0.45 && own - rival >= 0.15
}

/// Output-only rescue after a short interruption. The regular association
/// already committed its missing state; this function never changes it.
/// Use only a CURRENT weak observation with strong geometric and immutable
/// identity support, never the old mask or a newly learned identity.
pub(crate) fn weak_gap_overlay(
    tracker: &Tracker,
    clock: &CutoutClock,
    at: i64,
    detections: &[Detection],
    features: &[Appearance],
) -> Option<usize> {
    let track = tracker.tracks.iter().find(|t| t.id == clock.chosen)?;
    let memory = track.appearance.as_ref()?;
    let age = at.checked_sub(memory.last_seen)?;
    // At least one intervening missing sample; at most two, and no
    // chaining through weak observations. Last canonical match was strong.
    if !(2..=3).contains(&track.frames_since_observation)
        || !(1..=120_000).contains(&age)
        || track.confidence < STRONG_CONF
    {
        return None;
    }
    let mut selected = None;
    for (j, (d, f)) in detections.iter().zip(features).enumerate() {
        if !(MIN_CONF..STRONG_CONF).contains(&d.confidence) || !present(f) {
            continue;
        }
        let (bi, mi) = overlaps(track, d);
        if bi < 0.60 || mi < 0.65 || distance(track.observed_bounds, d.bounds) > scale(track) * 0.18
        {
            continue;
        }
        let anchor = similarity(&memory.anchor, f);
        let last = similarity(&memory.last_observed, f);
        let negative = clock
            .initial_competitors
            .iter()
            .map(|n| similarity(n, f))
            .fold(0., f32::max);
        if anchor < 0.90 || last < 0.90 || anchor - negative < 0.12 {
            continue;
        }
        // Any competing current mask near the last selected silhouette is
        // sufficient reason not to rescue. Retain recent competing ownership,
        // even if the competitor itself is missing in this sample.
        if detections.iter().enumerate().any(|(k, other)| {
            if k == j || other.confidence < MIN_CONF {
                return false;
            }
            let (b, m) = overlaps(track, other);
            b >= 0.25 && m >= 0.20
        }) || tracker.tracks.iter().any(|other| {
            if other.id == clock.chosen {
                return false;
            }
            let Some(m) = other.appearance.as_ref() else {
                return false;
            };
            if at - m.last_seen > CONTINUITY_US {
                return false;
            }
            let (b, raw) = overlaps(other, d);
            raw >= 0.20 || b >= 0.60
        }) {
            continue;
        }
        if selected.replace(j).is_some() {
            return None;
        }
    }
    selected
}

impl Tracker {
    pub(crate) fn initialize_appearance(
        &mut self,
        d: &[Detection],
        f: &[Appearance],
        chosen: i64,
    ) -> Result<(), TrackingError> {
        self.initialize(d.to_vec(), &[])?;
        self.selected_ids.insert(chosen);
        for (track, appearance) in self.tracks.iter_mut().zip(f) {
            track.filter.init(track.observed_bounds, 1);
            track.appearance = Some(AppearanceMemory::new(*appearance, 1));
        }
        Ok(())
    }

    pub(crate) fn update_cutout(
        &mut self,
        d: &[Detection],
        f: &[Appearance],
        at: i64,
        clock: &CutoutClock,
        pending: &mut Option<PendingMatch>,
    ) -> Option<Option<usize>> {
        // Same identity table, assignment and commit used by the canonical
        // tracker. Only the association evidence/absence policy differ.
        self.clear_frame_evidence();
        let chosen = self.tracks.iter().position(|t| t.id == clock.chosen)?;
        let chosen_age = at - self.tracks[chosen].appearance.as_ref()?.last_seen;
        for t in &mut self.tracks {
            let previous = t.observed_bounds;
            let predicted = t.filter.predict(at);
            // XYAH can change shape dramatically in floor work; bound the
            // predicted box. The actual observed box/mask remains immutable.
            t.predicted_bounds = bound_prediction(previous, predicted, 0.70, 0.65, 1.55);
        }
        let mut evidence = self
            .tracks
            .iter()
            .enumerate()
            .map(|(i, t)| {
                d.iter()
                    .zip(f)
                    .map(|(d, f)| {
                        candidate(
                            t,
                            d,
                            f,
                            at,
                            i == chosen,
                            if i == chosen {
                                &clock.initial_competitors
                            } else {
                                &[]
                            },
                            clock.anchor_bounds,
                        )
                    })
                    .collect::<Vec<_>>()
            })
            .collect::<Vec<_>>();
        // Weak observations can only continue a unique directly observed
        // subject. Preserve the established raw-mask competition contract.
        for (j, obs) in d.iter().enumerate() {
            if obs.confidence < STRONG_CONF
                && d.iter().enumerate().any(|(k, other)| {
                    if j == k || other.confidence < MIN_CONF {
                        return false;
                    }
                    let (b, m) = overlaps(&self.tracks[chosen], other);
                    b >= 0.25 && m >= 0.20
                })
            {
                evidence[chosen][j] = Evidence::default();
            }
        }
        // Use canonical global one-to-one matching and reciprocal rankings
        // for all established competitors. Their reliable ownership blocks
        // selected-person claims to the same current detection.
        let competitors = (0..self.tracks.len())
            .filter(|i| *i != chosen)
            .collect::<Vec<_>>();
        let matrix = competitors
            .iter()
            .map(|&i| evidence[i].iter().map(|e| e.score).collect())
            .collect::<Vec<Vec<f32>>>();
        let mut owned = BTreeSet::new();
        let mut assigned = Vec::new();
        let incumbent = vec![evidence[chosen].iter().map(|e| e.score).collect::<Vec<_>>()];
        for (r, c) in pairs(&matrix, 0.55) {
            let row = competitors[r];
            let rank = ranking(&matrix, r, c);
            if rank.score >= rank.row_best - 1e-6
                && rank.score >= rank.col_best - 1e-6
                && rank.row_margin >= MARGIN
                && rank.col_margin >= MARGIN
            {
                if evidence[row][c].owner {
                    let rank = ranking(&incumbent, 0, c);
                    if !incumbent_continuity(
                        &self.tracks[chosen],
                        &self.tracks[row],
                        &d[c],
                        at,
                        rank.score,
                        rank.row_margin,
                    ) {
                        owned.insert(c);
                    }
                }
                assigned.push((row, c));
            }
        }
        for &c in &owned {
            evidence[chosen][c] = Evidence::default();
        }
        // Canonical assignment for the selected row; reserve the highest
        // supported candidate only when it beats alternatives.
        let selected_matrix = vec![evidence[chosen].iter().map(|e| e.score).collect::<Vec<_>>()];
        let choice = pairs(&selected_matrix, 0.40)
            .first()
            .copied()
            .and_then(|(r, c)| {
                let rank = ranking(&selected_matrix, r, c);
                (rank.row_margin >= MARGIN).then_some(c)
            });
        let selected = if let Some(c) = choice {
            if chosen_age > CONTINUITY_US {
                let confirmed = pending.as_ref().is_some_and(|p| {
                    at - p.at <= 150_000
                        && (overlap_iou(p.bounds, d[c].bounds) > 0.25
                            || distance(p.bounds, d[c].bounds) < scale(&self.tracks[chosen]) * 0.35)
                        && similarity(&p.appearance, &f[c]) > 0.8
                });
                if confirmed {
                    *pending = None;
                    Some(c)
                } else {
                    *pending = Some(PendingMatch {
                        at,
                        bounds: d[c].bounds,
                        appearance: f[c],
                    });
                    None
                }
            } else {
                *pending = None;
                Some(c)
            }
        } else {
            *pending = None;
            None
        };
        // Do not learn/create a competing identity from a selected candidate,
        // including a tentative recovery. Conversely a strong existing owner
        // cannot be overridden by the selected row.
        assigned.retain(|(_, c)| Some(*c) != choice);
        if let Some(c) = selected {
            assigned.push((chosen, c));
        }
        let matched_tracks = assigned.iter().map(|(r, _)| *r).collect::<BTreeSet<_>>();
        let matched_cols = assigned.iter().map(|(_, c)| *c).collect::<BTreeSet<_>>();
        for (i, t) in self.tracks.iter_mut().enumerate() {
            t.observed_previous_frame = t.observed_this_frame;
            t.observed_this_frame = false;
            t.fresh_motion = None;
            t.current_foot_y = None;
            t.age = t.age.saturating_add(1);
            t.frames_since_observation = t.frames_since_observation.saturating_add(1);
            if !matched_tracks.contains(&i) {
                t.render_mask = None;
                t.state = if i == chosen && pending.is_some() {
                    TrackState::Reacquiring
                } else {
                    TrackState::Lost
                };
                t.lost_frames = t.lost_frames.saturating_add(1);
            }
        }
        for (r, c) in assigned {
            let t = &mut self.tracks[r];
            let e = evidence[r][c];
            let memory = t.appearance.as_mut()?;
            let gap = at - memory.last_seen;
            if gap <= CONTINUITY_US
                && e.app > 0.75
                && e.geom > 0.23
                && d[c].confidence >= STRONG_CONF
                && (r != chosen || e.contrast > 0.01 && similarity(&memory.anchor, &f[c]) > 0.70)
                && e.bank > 0.70
            {
                memory.learn(f[c]);
            }
            memory.last_seen = at;
            memory.last_observed = f[c];
            commit(t, &d[c], at);
        }
        // New distinct, unclaimed people get ordinary stable IDs. Never
        // enroll a possible selected subject as a competing identity.
        let selected_track = &self.tracks[chosen];
        let selected_bounds = selected_track.observed_bounds;
        let selected_scale = scale(selected_track);
        let selected_memory = selected_track.appearance.as_ref()?;
        let new = d
            .iter()
            .enumerate()
            .filter(|(c, obs)| {
                if matched_cols.contains(c)
                    || Some(*c) == choice
                    || obs.confidence < STRONG_CONF
                    || !present(&f[*c])
                {
                    return false;
                }
                if self.tracks.iter().enumerate().any(|(i, t)| {
                    i != chosen
                        && (overlap_iou(t.observed_bounds, obs.bounds) >= 0.25
                            || overlap_iou(t.predicted_bounds, obs.bounds) >= 0.25)
                }) {
                    return false;
                }
                let b = overlap_iou(selected_bounds, obs.bounds);
                let (_, m) = overlaps(selected_track, obs);
                let distinct = selected_memory.best(&f[*c]) < 0.72;
                b < 0.08
                    && m < 0.08
                    && (distinct || distance(selected_bounds, obs.bounds) > selected_scale * 0.95)
            })
            .map(|(c, _)| c)
            .collect::<Vec<_>>();
        self.tracks.retain(|t| {
            t.id == clock.chosen
                || t.appearance
                    .as_ref()
                    .is_some_and(|a| at - a.last_seen <= MAX_GAP_US)
        });
        for c in new
            .into_iter()
            .take(MAX_TRACKS.saturating_sub(self.tracks.len()))
        {
            self.add_observation(d[c].clone()).ok()?;
            let t = self.tracks.last_mut()?;
            t.filter.init(t.observed_bounds, at);
            t.appearance = Some(AppearanceMemory::new(f[c], at));
        }
        Some(selected)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{appearance::APPEARANCE_BINS, geometry::Transform, tracking::Mask};
    use std::sync::Arc;

    fn observation(bounds: Rect, left: usize, right: usize) -> Detection {
        let t = Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 0.,
            source_width: 160,
            source_height: 160,
            input_size: 160,
            proto_size: 16,
        };
        let raw = (0..256)
            .map(|i| {
                if (left..right).contains(&(i % 16)) && (2..12).contains(&(i / 16)) {
                    255
                } else {
                    0
                }
            })
            .collect::<Vec<u8>>();
        Detection {
            bounds,
            confidence: 0.9,
            foot_y: bounds.bottom,
            mask: Some(Mask {
                width: 16,
                height: 16,
                pixels: Arc::from(raw),
                transform: t,
            }),
        }
    }

    #[test]
    fn stale_box_owner_yields_only_to_unique_fresher_raw_mask() {
        let selected = observation(
            Rect {
                left: 20.,
                top: 20.,
                right: 100.,
                bottom: 120.,
            },
            2,
            8,
        );
        let competitor = observation(
            Rect {
                left: 30.,
                top: 20.,
                right: 110.,
                bottom: 120.,
            },
            6,
            11,
        );
        let current = observation(
            Rect {
                left: 30.,
                top: 20.,
                right: 110.,
                bottom: 120.,
            },
            3,
            9,
        );
        let mut f = [0.; APPEARANCE_BINS];
        f[0] = 1.;
        let mut a = Track::observed(0, selected.clone());
        let mut b = Track::observed(1, competitor.clone());
        a.appearance = Some(AppearanceMemory::new(f, 40_001));
        b.appearance = Some(AppearanceMemory::new(f, 1));
        b.observed_this_frame = false;
        assert!(incumbent_continuity(&a, &b, &current, 80_001, 0.8, 0.2));
        assert!(!incumbent_continuity(&a, &b, &current, 80_001, 0.8, 0.01));
        assert!(!incumbent_continuity(&a, &b, &competitor, 80_001, 0.8, 0.2));
        let mut weak = current.clone();
        weak.confidence = 0.22;
        assert!(!incumbent_continuity(&a, &b, &weak, 80_001, 0.8, 0.2));
        b.appearance.as_mut().unwrap().last_seen = 40_001;
        assert!(!incumbent_continuity(&a, &b, &current, 80_001, 0.8, 0.2));
        b.appearance.as_mut().unwrap().last_seen = 1;
        assert!(!incumbent_continuity(&a, &b, &current, 240_001, 0.8, 0.2));
        a.observed_this_frame = false;
        assert!(!incumbent_continuity(&a, &b, &current, 80_001, 0.8, 0.2));
    }

    #[test]
    fn crossing_owner_release_preserves_incumbent_and_does_not_learn_competitor() {
        let a = observation(
            Rect {
                left: 20.,
                top: 20.,
                right: 100.,
                bottom: 120.,
            },
            2,
            8,
        );
        let b = observation(
            Rect {
                left: 30.,
                top: 20.,
                right: 110.,
                bottom: 120.,
            },
            6,
            11,
        );
        let current = observation(
            Rect {
                left: 30.,
                top: 20.,
                right: 110.,
                bottom: 120.,
            },
            3,
            9,
        );
        let mut f = [0.; APPEARANCE_BINS];
        f[0] = 1.;
        let mut tracker = Tracker::new(crate::tracking::TrackingConfig::default());
        tracker
            .initialize_appearance(&[a.clone(), b.clone()], &[f, f], 0)
            .unwrap();
        tracker.tracks[0].appearance.as_mut().unwrap().last_seen = 40_001;
        tracker.tracks[0].filter.init(a.bounds, 40_001);
        tracker.tracks[1].observed_this_frame = false;
        let clock = CutoutClock {
            chosen: 0,
            anchor: 0,
            last: 40_000,
            direction: 1,
            transform: a.mask.as_ref().unwrap().transform,
            anchor_bounds: a.bounds,
            initial_competitors: vec![f],
        };
        let mut pending = None;
        assert_eq!(
            tracker.update_cutout(
                std::slice::from_ref(&current),
                &[f],
                80_001,
                &clock,
                &mut pending
            ),
            Some(Some(0))
        );
        let selected = tracker.tracks.iter().find(|t| t.id == 0).unwrap();
        let competitor = tracker.tracks.iter().find(|t| t.id == 1).unwrap();
        assert_eq!(selected.observed_bounds, current.bounds);
        assert_eq!(selected.appearance.as_ref().unwrap().last_seen, 80_001);
        assert_eq!(competitor.observed_bounds, b.bounds);
        assert_eq!(competitor.appearance.as_ref().unwrap().last_seen, 1);
        assert_eq!(competitor.state, TrackState::Lost);
        assert!(!competitor.observed_this_frame);
    }
}
