//! Checked C transport for owned tracking sessions. The algorithms are in the
//! safe tracking modules; IDs never expose a native pointer to mobile runtimes.
use crate::{
    association_score::AssociationWeights,
    dance_cutout::CutoutPolicy,
    geometry::{Rect, Transform, overlap_iou},
    instances::InstanceDetection,
    tracking::*,
    tracking_mask::raw_mask_iou,
};
use std::{
    collections::BTreeMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
};
#[repr(C)]
#[derive(Clone, Copy)]
pub struct TrackingOptions {
    pub max_missed: u32,
    pub grace: u32,
    pub occlusion_max: u32,
    pub scene_inliers: u32,
    pub minimum_score: f32,
    pub weights: AssociationWeights,
    pub overlap: f32,
    pub ambiguity: f32,
    pub scene_tolerance: f32,
    pub maximum_shift: f32,
    pub scene_compensation: u32,
    pub foot_margin: f32,
    pub erosion_radius: u32,
}
impl Default for TrackingOptions {
    fn default() -> Self {
        let c = TrackingConfig::default();
        Self {
            max_missed: c.max_missed_frames,
            grace: c.post_occlusion_grace_frames,
            occlusion_max: c.occlusion_max_duration_frames,
            scene_inliers: c.min_scene_motion_inliers as u32,
            minimum_score: c.min_match_score,
            weights: c.weights,
            overlap: c.occlusion_overlap_ratio,
            ambiguity: c.association_ambiguity_margin,
            scene_tolerance: c.scene_motion_tolerance,
            maximum_shift: c.max_global_shift,
            scene_compensation: u32::from(c.scene_motion_compensation),
            foot_margin: c.foreground_foot_margin_ratio,
            erosion_radius: c.privacy_occluder_erosion_radius,
        }
    }
}
impl TrackingOptions {
    fn checked(self) -> Option<TrackingConfig> {
        if self.max_missed > 1_000_000
            || self.grace > 1_000_000
            || self.occlusion_max > 1_000_000
            || self.scene_inliers == 0
            || self.scene_inliers > 100_000
            || self.scene_compensation > 1
            || self.erosion_radius > 1024
            || ![
                self.minimum_score,
                self.weights.bounds,
                self.weights.mask,
                self.weights.motion,
                self.weights.direction,
                self.weights.gating_threshold,
                self.overlap,
                self.ambiguity,
                self.scene_tolerance,
                self.maximum_shift,
                self.foot_margin,
            ]
            .into_iter()
            .all(|v| v.is_finite() && v >= 0.0)
        {
            return None;
        }
        Some(TrackingConfig {
            max_missed_frames: self.max_missed,
            post_occlusion_grace_frames: self.grace,
            occlusion_max_duration_frames: self.occlusion_max,
            min_scene_motion_inliers: self.scene_inliers as usize,
            min_match_score: self.minimum_score,
            weights: self.weights,
            occlusion_overlap_ratio: self.overlap,
            association_ambiguity_margin: self.ambiguity,
            scene_motion_tolerance: self.scene_tolerance,
            max_global_shift: self.maximum_shift,
            scene_motion_compensation: self.scene_compensation != 0,
            foreground_foot_margin_ratio: self.foot_margin,
            privacy_occluder_erosion_radius: self.erosion_radius,
        })
    }
}
#[repr(C)]
#[derive(Clone, Copy)]
pub struct TrackingObservation {
    pub bounds: Rect,
    pub confidence: f32,
    pub foot_y: f32,
    pub mask_offset: u64,
    pub mask_width: u32,
    pub mask_height: u32,
    pub transform: Transform,
}
struct Session {
    tracker: Tracker,
    snapshot: Vec<u8>,
    cutout: Option<CutoutClock>,
    dance: Option<CutoutPolicy>,
    detail_trial: Option<DetailTrial>,
    detail_pending: Option<crate::tracking_cutout::PendingMatch>,
    detail_backoff: crate::dance_detail::DetailBackoff,
}
// The tentative detail evaluation may never replace the canonical full
// observation result. Keep the full-only commit while the caller decodes ROI.
struct DetailTrial {
    at: i64,
    count: usize,
    tracker: Tracker,
    policy: CutoutPolicy,
    scheduled: bool,
}
#[derive(Clone, Copy)]
struct CutoutClock {
    chosen: i64,
    anchor: i64,
    last: i64,
    direction: i64,
}
type OwnedSession = Arc<Mutex<Session>>;
static SESSIONS: OnceLock<Mutex<BTreeMap<u64, OwnedSession>>> = OnceLock::new();
static NEXT_ID: AtomicU64 = AtomicU64::new(1);
pub(crate) fn next_session_id() -> Option<u64> {
    NEXT_ID
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |n| n.checked_add(1))
        .ok()
}

fn registry() -> &'static Mutex<BTreeMap<u64, OwnedSession>> {
    SESSIONS.get_or_init(|| Mutex::new(BTreeMap::new()))
}
fn session(id: u64) -> Option<OwnedSession> {
    registry().lock().ok()?.get(&id).cloned()
}
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_tracking_defaults() -> TrackingOptions {
    TrackingOptions::default()
}
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_tracking_create(options: TrackingOptions) -> u64 {
    let Some(config) = options.checked() else {
        return 0;
    };
    create_session(config, None)
}
fn create_session(config: TrackingConfig, dance: Option<CutoutPolicy>) -> u64 {
    let Ok(mut sessions) = registry().lock() else {
        return 0;
    };
    if sessions.len() >= 4096 {
        return 0;
    }
    let Some(id) = next_session_id() else {
        return 0;
    };
    sessions.insert(
        id,
        Arc::new(Mutex::new(Session {
            tracker: Tracker::new(config),
            snapshot: Vec::new(),
            cutout: None,
            dance,
            detail_trial: None,
            detail_pending: None,
            detail_backoff: Default::default(),
        })),
    );
    id
}
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_tracking_destroy(id: u64) -> i32 {
    destroy_session(id, false)
}
pub(crate) fn create_dance_session() -> u64 {
    create_session(TrackingConfig::default(), Some(CutoutPolicy::default()))
}
pub(crate) fn destroy_dance_session(id: u64) -> i32 {
    destroy_session(id, true)
}
fn destroy_session(id: u64, dance: bool) -> i32 {
    let Ok(mut r) = registry().lock() else {
        return -1;
    };
    let Some(owner) = r.get(&id) else { return -1 };
    let mode = owner.lock().ok().map(|s| s.dance.is_some());
    if mode != Some(dance) {
        return -1;
    }
    r.remove(&id);
    0
}
pub(crate) fn step_dance_session(
    id: u64,
    at: i64,
    seed: i32,
    d: &[InstanceDetection],
    m: &[u8],
    t: Transform,
    f: &[f32],
) -> i32 {
    let Some(owner) = session(id) else { return -2 };
    let Ok(mut s) = owner.lock() else { return -2 };
    if s.detail_trial.is_some() {
        return -2;
    }
    let Session {
        tracker,
        dance,
        detail_pending,
        detail_backoff,
        ..
    } = &mut *s;
    let Some(policy) = dance else { return -2 };
    let result = crate::dance_cutout::step(
        tracker,
        policy,
        at,
        seed,
        crate::dance_cutout::CutoutObservation {
            detections: d,
            masks: m,
            transform: t,
            features: f,
        },
    )
    .unwrap_or(-2);
    if result >= -1 {
        *detail_pending = None;
        detail_backoff.reset();
    }
    if result == -1 {
        policy.weak_output.take().map_or(-1, |i| i as i32)
    } else {
        result
    }
}
/// Transactional preview: commit the full-frame observation immediately if
/// valid, otherwise keep its next state for a bounded current-frame retry.
pub(crate) fn step_or_detail(
    id: u64,
    at: i64,
    o: crate::dance_cutout::CutoutObservation<'_>,
    roi: &mut crate::composition::Viewport,
) -> i32 {
    step_or_detail_impl(id, at, o, roi, false)
}
pub(crate) fn step_or_detail_scheduled(
    id: u64,
    at: i64,
    o: crate::dance_cutout::CutoutObservation<'_>,
    roi: &mut crate::composition::Viewport,
) -> i32 {
    step_or_detail_impl(id, at, o, roi, true)
}
fn step_or_detail_impl(
    id: u64,
    at: i64,
    o: crate::dance_cutout::CutoutObservation<'_>,
    roi: &mut crate::composition::Viewport,
    scheduled: bool,
) -> i32 {
    let Some(owner) = session(id) else { return -2 };
    let Ok(mut s) = owner.lock() else { return -2 };
    let Session {
        tracker,
        dance,
        detail_trial,
        detail_pending,
        detail_backoff,
        ..
    } = &mut *s;
    let Some(policy) = dance else { return -2 };
    if detail_trial.is_some() {
        return -2;
    }
    let Some(view) = crate::dance_detail::view(tracker, policy, at) else {
        let result = crate::dance_cutout::step(tracker, policy, at, -1, o).unwrap_or(-2);
        if result >= -1 {
            *detail_pending = None;
            detail_backoff.reset();
        }
        return if result == -1 {
            policy.weak_output.take().map_or(-1, |i| i as i32)
        } else {
            result
        };
    };
    let count = o.detections.len();
    let mut trial = tracker.clone();
    let mut trial_policy = policy.clone();
    let result = crate::dance_cutout::step(&mut trial, &mut trial_policy, at, -1, o).unwrap_or(-2);
    if result == -1 {
        // Only suppress optional detail inference; commit exactly the same
        // canonical full-only next state. A current provisional candidate
        // is never throttled, so two-observation confirmation remains intact.
        if scheduled
            && detail_pending.is_none()
            && trial_policy.pending.is_none()
            && detail_backoff.skip(at)
        {
            let weak = trial_policy.weak_output.take();
            *tracker = trial;
            *policy = trial_policy;
            return weak.map_or(-4, |i| i as i32);
        }
        *roi = view;
        *detail_trial = Some(DetailTrial {
            at,
            count,
            tracker: trial,
            policy: trial_policy,
            scheduled,
        });
        -3
    } else {
        if result >= 0 {
            *tracker = trial;
            *policy = trial_policy;
            *detail_pending = None;
            detail_backoff.reset();
        }
        result
    }
}

/// Confirm current-frame local candidates against the SAME prior identity
/// table. Commit the already-computed full-only state, never the detail
/// trial. A compact current-match confirmation is the only local evidence
/// carried forward; it cannot change track IDs, Kalman or appearance memory.
pub(crate) fn finish_detail(
    id: u64,
    at: i64,
    full_count: usize,
    o: crate::dance_cutout::CutoutObservation<'_>,
) -> i32 {
    use crate::{
        appearance::{APPEARANCE_BINS, feature},
        tracking_cutout::PendingMatch,
    };
    let Some(owner) = session(id) else { return -2 };
    let Ok(mut s) = owner.lock() else { return -2 };
    let Session {
        tracker,
        dance,
        detail_trial,
        detail_pending,
        detail_backoff,
        ..
    } = &mut *s;
    let Some(policy) = dance else { return -2 };
    let Some(base) = detail_trial.as_ref() else {
        return -2;
    };
    if at != base.at
        || full_count != base.count
        || full_count > o.detections.len()
        || policy
            .clock
            .as_ref()
            .is_none_or(|c| c.transform != o.transform)
    {
        return -2;
    }
    let full_rows = o.detections;
    let mut result = -1;
    let mut next_pending = None;
    if o.detections.len() > full_count {
        let mut trial = tracker.clone();
        let mut trial_policy = policy.clone();
        if detail_pending.is_some() {
            trial_policy.pending = detail_pending.clone();
        }
        let at_elapsed = at
            .checked_sub(policy.clock.as_ref().unwrap().anchor)
            .and_then(i64::checked_abs)
            .and_then(|v| v.checked_add(1));
        let Some(elapsed) = at_elapsed else { return -2 };
        let rows = o.detections;
        let features = o.features;
        let selected =
            crate::dance_cutout::step(&mut trial, &mut trial_policy, at, -1, o).unwrap_or(-2);
        if selected < -1 {
            return -2;
        }
        if selected >= full_count as i32 {
            let index = selected as usize;
            let Some(d) = rows.get(index) else { return -2 };
            let Some(f) =
                feature(&features[index * APPEARANCE_BINS..(index + 1) * APPEARANCE_BINS])
            else {
                return -2;
            };
            result = selected;
            next_pending = Some(PendingMatch {
                at: elapsed,
                bounds: d.bounds,
                appearance: f,
            });
        } else if selected == -1 {
            // A pending local observation can confirm the next *current*
            // detail candidate; it never creates/relearns a competitor.
            next_pending = trial_policy.pending.filter(|p| {
                p.at == elapsed
                    && rows.iter().enumerate().skip(full_count).any(|(i, d)| {
                        d.bounds == p.bounds
                            && feature(&features[i * APPEARANCE_BINS..(i + 1) * APPEARANCE_BINS])
                                == Some(p.appearance)
                    })
            });
        }
    }
    let mut base = detail_trial.take().unwrap();
    let weak = base.policy.weak_output.take().filter(|&i| {
        next_pending
            .as_ref()
            .is_none_or(|p| crate::geometry::overlap_iou(p.bounds, full_rows[i].bounds) >= 0.60)
    });
    if base.scheduled {
        detail_backoff.observe(at, result >= 0 || next_pending.is_some());
    } else {
        detail_backoff.reset();
    }
    *tracker = base.tracker;
    *policy = base.policy;
    *detail_pending = next_pending;
    if result == -1 {
        weak.map_or(-1, |i| i as i32)
    } else {
        result
    }
}

pub(crate) unsafe fn borrowed<'a, T>(p: *const T, n: usize) -> Option<&'a [T]> {
    if n == 0 {
        return Some(&[]);
    }
    if p.is_null()
        || n > isize::MAX as usize / std::mem::size_of::<T>()
        || !(p as usize).is_multiple_of(std::mem::align_of::<T>())
    {
        return None;
    }
    // SAFETY: Caller guarantees readable initialized storage; null, alignment
    // and byte-length bounds have been checked above.
    Some(unsafe { std::slice::from_raw_parts(p, n) })
}
/// # Safety
/// Each nonempty ID buffer must be readable and aligned for its declared length.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_tracking_selection(
    id: u64,
    protected: *const i64,
    protected_len: usize,
    body: *const i64,
    body_len: usize,
    face: *const i64,
    face_len: usize,
    dormancy: u32,
) -> i32 {
    if [protected_len, body_len, face_len]
        .into_iter()
        .any(|n| n > 100_000)
        || dormancy > 1
    {
        return -1;
    }
    let (Some(protected), Some(body), Some(face)) = (
        // SAFETY: The C entrypoint contract guarantees this live input buffer.
        unsafe { borrowed(protected, protected_len) },
        // SAFETY: The C entrypoint contract guarantees this live input buffer.
        unsafe { borrowed(body, body_len) },
        // SAFETY: The C entrypoint contract guarantees this live input buffer.
        unsafe { borrowed(face, face_len) },
    ) else {
        return -1;
    };
    let Some(owner) = session(id) else { return -1 };
    let Ok(mut s) = owner.lock() else { return -1 };
    if s.cutout.is_some() || s.dance.is_some() {
        return -1;
    }
    s.tracker.protected_ids = protected.iter().copied().collect();
    s.tracker.selected_ids = body.iter().copied().collect();
    s.tracker.face_selected_ids = face.iter().copied().collect();
    s.tracker.offscreen_dormancy = dormancy != 0;
    0
}
/// Host research opt-in, only before a privacy tracking session initializes.
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_tracking_pair_continuity(id: u64, enabled: u32) -> i32 {
    let Some(owner) = session(id) else { return -1 };
    let Ok(mut s) = owner.lock() else { return -1 };
    if enabled > 1 || s.tracker.initialized || s.cutout.is_some() || s.dance.is_some() {
        return -1;
    }
    s.tracker.privacy_pair_continuity = enabled == 1;
    0
}
/// # Safety
/// Observation, pixel pool and assigned ID buffers must be readable for the
/// declared lengths. No pointer is retained after the call. Mask offsets are
/// checked against the single borrowed pixel pool before making owned copies.
/// Action 0 initializes, 1 observes, 2 is a detector miss, 3 skips inference.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_tracking_step(
    id: u64,
    action: u32,
    at: i64,
    observations: *const TrackingObservation,
    count: usize,
    pixels: *const u8,
    pixels_len: usize,
    assigned: *const i64,
    assigned_len: usize,
) -> i32 {
    if action > 3 || count > 100_000 || assigned_len > 100_000 || pixels_len > 256 * 1024 * 1024 {
        return -1;
    }
    let (Some(observations), Some(pixels), Some(assigned)) = (
        // SAFETY: The C entrypoint contract guarantees this live input buffer.
        unsafe { borrowed(observations, count) },
        // SAFETY: The C entrypoint contract guarantees this live input buffer.
        unsafe { borrowed(pixels, pixels_len) },
        // SAFETY: The C entrypoint contract guarantees this live input buffer.
        unsafe { borrowed(assigned, assigned_len) },
    ) else {
        return -1;
    };
    let Some(detections) = decode_observations(observations, pixels) else {
        return -1;
    };
    let Some(owner) = session(id) else { return -1 };
    let Ok(mut s) = owner.lock() else { return -1 };
    if s.cutout.is_some() || s.dance.is_some() {
        return -1;
    }
    // A clothing-enabled observation must use its explicit RGB transport.
    // Prediction/miss actions remain valid without current pixel evidence.
    if s.tracker.crowd_clothing.is_some() && action <= 1 {
        return -1;
    }
    let result = match action {
        0 => s.tracker.initialize(detections, assigned),
        1 => s.tracker.update(detections, at).map(|_| ()),
        2 => s.tracker.predict(at, true).map(|_| ()),
        _ => s.tracker.predict(at, false).map(|_| ()),
    };
    if result.is_err() {
        return -1;
    }
    if action == 0 && s.tracker.privacy_pair_continuity {
        for track in &mut s.tracker.tracks {
            track.observed_mask_at_us = track.observed_mask.as_ref().map(|_| at);
        }
    }
    if action >= 2
        && let Some(c) = s.tracker.crowd_clothing.as_mut()
    {
        c.invalidate_motion();
    }
    s.snapshot = encode(&s.tracker);
    0
}
/// Host-only clothing-augmented privacy session. RGB is current source
/// pixels, tightly packed; the algorithm stores only bounded histograms.
/// # Safety
/// All nonempty input buffers are readable for their specified lengths.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_tracking_clothing_step(
    id: u64,
    action: u32,
    at: i64,
    observations: *const TrackingObservation,
    count: usize,
    pixels: *const u8,
    pixels_len: usize,
    rgb: *const u8,
    rgb_len: usize,
    transform: Transform,
) -> i32 {
    if !matches!(action, 0 | 1 | 4 | 5 | 6 | 7)
        || count == 0
        || count > 64
        || pixels_len > 64 * 1024 * 1024
        || rgb_len > 128 * 1024 * 1024
    {
        return -1;
    }
    // SAFETY: caller provides live readable arrays of checked lengths.
    let (Some(o), Some(p), Some(rgb)) = (unsafe {
        (
            borrowed(observations, count),
            borrowed(pixels, pixels_len),
            borrowed(rgb, rgb_len),
        )
    }) else {
        return -1;
    };
    let Some(d) = decode_observations(o, p) else {
        return -1;
    };
    let Some(owner) = session(id) else {
        return -1;
    };
    let Ok(mut s) = owner.lock() else {
        return -1;
    };
    if s.cutout.is_some()
        || s.dance.is_some()
        || !s.tracker.selected_ids.is_empty()
        || s.tracker.face_selected_ids.len() != 1
        || s.tracker.protected_ids.len() < 3
    {
        return -1;
    }
    let mut t = s.tracker.clone();
    let adaptive = action >= 4;
    let motion = action >= 6;
    if matches!(action, 0 | 4 | 6) {
        if t.initialized || t.initialize(d.clone(), &[]).is_err() {
            return -1;
        }
        let ids = t.tracks.iter().map(|q| q.id).collect::<Vec<_>>();
        let Some(mut clothing) = crate::appearance::CrowdClothing::seed(&d, &ids, rgb, transform)
        else {
            return -1;
        };
        if adaptive {
            clothing.enable_adaptive(at, &t.protected_ids);
        }
        if motion {
            clothing.enable_motion();
            if clothing
                .prepare_motion(&t, &d, rgb, transform, at)
                .is_none()
            {
                return -1;
            }
        }
        t.crowd_clothing = Some(clothing);
        for q in &mut t.tracks {
            q.observed_mask_at_us = q.observed_mask.as_ref().map(|_| at);
        }
    } else {
        let prior = t.clone();
        let Some(clothing) = t.crowd_clothing.as_mut() else {
            return -1;
        };
        if clothing.is_adaptive() != adaptive
            || clothing.is_motion() != motion
            || clothing.observe(&d, rgb, transform).is_none()
            || clothing
                .prepare_motion(&prior, &d, rgb, transform, at)
                .is_none()
        {
            return -1;
        }
        if adaptive {
            let before = t.clone();
            if t.update(d.clone(), at).is_err() {
                return -1;
            }
            let mut clothing = t.crowd_clothing.take().unwrap();
            clothing.learn_current(&before, &t, &d, at);
            clothing.clear();
            t.crowd_clothing = Some(clothing);
        } else {
            if t.update(d, at).is_err() {
                return -1;
            }
            t.crowd_clothing.as_mut().unwrap().clear();
        }
    }
    s.tracker = t;
    s.snapshot = encode(&s.tracker);
    0
}

/// # Safety
/// Diagnostic flattened (raw index, previous identity, pixel votes) triples.
/// Caller supplies capacity i64 elements; no retained pointer.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_tracking_motion_merges(
    id: u64,
    out: *mut i64,
    capacity: usize,
) -> i64 {
    let Some(owner) = session(id) else {
        return -1;
    };
    let Ok(s) = owner.lock() else {
        return -1;
    };
    let Some(c) = s.tracker.crowd_clothing.as_ref() else {
        return -1;
    };
    let values = c
        .motion_merges()
        .iter()
        .flat_map(|m| {
            m.ids
                .iter()
                .zip(&m.votes)
                .flat_map(|(&id, &v)| [m.index as i64, id, v as i64])
        })
        .collect::<Vec<_>>();
    if capacity == 0 {
        return values.len() as i64;
    }
    if out.is_null() || capacity < values.len() {
        return -1;
    }
    // SAFETY: caller supplies at least the required number of writable i64s.
    unsafe { std::ptr::copy_nonoverlapping(values.as_ptr(), out, values.len()) };
    values.len() as i64
}

/// # Safety
/// A nonzero output capacity must point to writable bytes. Passing null/zero
/// queries the needed size. Insufficient capacity does not mutate the session.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_tracking_snapshot(
    id: u64,
    output: *mut u8,
    capacity: usize,
) -> i64 {
    let Some(owner) = session(id) else { return -1 };
    let Ok(s) = owner.lock() else { return -1 };
    if s.dance.is_some() {
        return -1;
    }
    let Ok(n) = i64::try_from(s.snapshot.len()) else {
        return -1;
    };
    if capacity == 0 {
        return n;
    }
    if output.is_null() {
        return -1;
    }
    if capacity < s.snapshot.len() {
        return -2;
    }
    // SAFETY: Caller owns writable output, whose capacity was checked. The
    // source is private session memory kept live and immutable by this lock.
    unsafe { std::ptr::copy_nonoverlapping(s.snapshot.as_ptr(), output, s.snapshot.len()) };
    n
}
// Cutout-specific continuation profile. Privacy detection and association
// calls do not use this entry point or its lower inference threshold.
const CUTOUT_SEED_CONF: f32 = 0.25;
const CUTOUT_CONTINUE_CONF: f32 = 0.20;

fn weak_continuation(track: &Track, candidates: &[Detection], elapsed: i64) -> Option<usize> {
    if elapsed <= 0
        || elapsed > 150_000
        || !track.observed_this_frame
        || track.state != TrackState::Active
        || !track.occluder_ids.is_empty()
    {
        return None;
    }
    let previous = track.observed_mask.as_ref()?;
    let supported: Vec<_> = candidates
        .iter()
        .enumerate()
        .filter(|(_, candidate)| {
            candidate.confidence >= CUTOUT_CONTINUE_CONF
                && overlap_iou(track.observed_bounds, candidate.bounds) >= 0.55
                && candidate
                    .mask
                    .as_ref()
                    .is_some_and(|m| raw_mask_iou(previous, m) >= 0.50)
        })
        .collect();
    let [(index, candidate)] = supported.as_slice() else {
        return None;
    };
    if candidate.confidence >= CUTOUT_SEED_CONF {
        return None;
    }
    // A second partially overlapping current mask can be a competing person,
    // even if it does not meet the more stringent continuity thresholds.
    if candidates.iter().enumerate().any(|(i, d)| {
        i != *index
            && d.confidence >= CUTOUT_CONTINUE_CONF
            && overlap_iou(track.observed_bounds, d.bounds) >= 0.25
            && d.mask
                .as_ref()
                .is_some_and(|m| raw_mask_iou(previous, m) >= 0.20)
    }) {
        return None;
    }
    Some(*index)
}

fn cutout_step(s: &mut Session, at: i64, seed: i32, detections: Vec<Detection>) -> Option<i32> {
    if at < 0
        || seed < -1
        || detections
            .iter()
            .any(|d| !d.valid() || !(0.0..=1.0).contains(&d.confidence) || d.mask.is_none())
    {
        return None;
    }
    let mut tracker = s.tracker.clone();
    let (clock, weak) = if seed >= 0 {
        if s.cutout.is_some() || detections.get(seed as usize)?.confidence < CUTOUT_SEED_CONF {
            return None;
        }
        let chosen = i64::from(seed);
        tracker.protected_ids.insert(chosen);
        tracker.selected_ids.insert(chosen);
        (
            CutoutClock {
                chosen,
                anchor: at,
                last: at,
                direction: 0,
            },
            None,
        )
    } else {
        let mut clock = s.cutout?;
        let delta = at.checked_sub(clock.last)?;
        if delta == 0 || (clock.direction != 0 && delta.signum() != clock.direction) {
            return None;
        }
        clock.direction = delta.signum();
        let elapsed = delta.checked_abs()?;
        clock.last = at;
        let weak = tracker
            .tracks
            .iter()
            .find(|t| t.id == clock.chosen)
            .and_then(|t| weak_continuation(t, &detections, elapsed));
        (clock, weak)
    };
    let admitted: Vec<_> = detections
        .iter()
        .enumerate()
        .filter(|(i, d)| d.confidence >= CUTOUT_SEED_CONF || weak == Some(*i))
        .collect();
    let values = admitted.iter().map(|(_, d)| (*d).clone()).collect();
    if seed >= 0 {
        let ids = admitted.iter().map(|(i, _)| *i as i64).collect::<Vec<_>>();
        tracker.initialize(values, &ids).ok()?;
    } else {
        tracker
            .update(values, at.checked_sub(clock.anchor)?.checked_abs()?)
            .ok()?;
    }
    // Only an exact raw current detector mask may be rendered. Prediction,
    // retained/dilated masks and ambiguous same-pixel candidates are not accepted.
    let selected = tracker
        .tracks
        .iter()
        .find(|t| {
            t.id == clock.chosen
                && t.state == TrackState::Active
                && t.observed_this_frame
                && t.frames_since_observation == 0
                && t.occluder_ids.is_empty()
        })
        .and_then(|t| t.render_mask.as_ref())
        .and_then(|mask| {
            let mut matching = admitted.iter().filter(|(_, d)| {
                d.mask
                    .as_ref()
                    .is_some_and(|m| m.transform == mask.transform && m.pixels == mask.pixels)
            });
            let first = matching.next()?;
            matching.next().is_none().then_some(first.0 as i32)
        })
        .unwrap_or(-1);
    s.tracker = tracker;
    s.cutout = Some(clock);
    s.snapshot = encode(&s.tracker);
    Some(selected)
}

/// Cutout session step: seed is an original detection index (>=0), or -1
/// for strictly monotonic continuation in one direction per handle. Returns raw current index, -1 if
/// identity unavailable, -2 for invalid input. Input masks are contiguous.
/// # Safety
/// Nonempty detection and mask buffers must be readable for their stated sizes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_cutout_track_step(
    id: u64,
    at: i64,
    seed: i32,
    detections: *const InstanceDetection,
    count: usize,
    masks: *const u8,
    len: usize,
    transform: Transform,
) -> i32 {
    if count > 256 || !transform.valid() {
        return -2;
    }
    let area = (transform.proto_size as usize).pow(2);
    if len != count * area {
        return -2;
    }
    // SAFETY: caller guarantees readable buffers; sizes bounded above.
    let Some(raw) = (unsafe { borrowed(detections, count) }) else {
        return -2;
    };
    // SAFETY: caller guarantees readable bytes; size bounded above.
    let Some(pixels) = (unsafe { borrowed(masks, len) }) else {
        return -2;
    };
    let values = raw
        .iter()
        .zip(pixels.chunks_exact(area))
        .map(|(d, m)| Detection {
            bounds: d.bounds,
            confidence: d.confidence,
            foot_y: d.bounds.bottom,
            mask: Some(Mask {
                width: transform.proto_size as usize,
                height: transform.proto_size as usize,
                pixels: Arc::from(m),
                transform,
            }),
        })
        .collect();
    let Some(owner) = session(id) else { return -2 };
    let Ok(mut s) = owner.lock() else { return -2 };
    if s.dance.is_some() {
        return -2;
    }
    cutout_step(&mut s, at, seed, values).unwrap_or(-2)
}

#[cfg(test)]
mod cutout_tracking_tests {
    use super::*;
    fn d(confidence: f32, left: f32, mask: &[u8]) -> Detection {
        Detection {
            bounds: Rect {
                left,
                top: 0.,
                right: left + 40.,
                bottom: 80.,
            },
            confidence,
            foot_y: 80.,
            mask: Some(Mask {
                width: 4,
                height: 4,
                pixels: Arc::from(mask),
                transform: Transform {
                    scale: 1.,
                    pad_left: 0.,
                    pad_top: 0.,
                    source_width: 100,
                    source_height: 100,
                    input_size: 100,
                    proto_size: 4,
                },
            }),
        }
    }
    fn session() -> Session {
        Session {
            tracker: Tracker::new(TrackingConfig::default()),
            snapshot: Vec::new(),
            cutout: None,
            dance: None,
            detail_trial: None,
            detail_pending: None,
            detail_backoff: Default::default(),
        }
    }
    #[test]
    fn weak_gap_fallback_is_current_output_only_and_prefers_roi() {
        use crate::{
            appearance::APPEARANCE_BINS, composition::Viewport, dance_cutout::CutoutObservation,
        };
        let t = Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 0.,
            source_width: 160,
            source_height: 160,
            input_size: 160,
            proto_size: 16,
        };
        let d = InstanceDetection {
            bounds: Rect {
                left: 60.,
                top: 50.,
                right: 90.,
                bottom: 100.,
            },
            confidence: 0.9,
        };
        let weak = InstanceDetection {
            confidence: 0.23,
            ..d
        };
        let masks = [255u8; 256];
        let mut f = [0.; APPEARANCE_BINS];
        f[0] = 1.;
        let weak_rows = [weak];
        let current = || CutoutObservation {
            detections: &weak_rows,
            masks: &masks,
            transform: t,
            features: &f,
        };
        let strong_rows = [d];
        let preferred = create_dance_session();
        assert_eq!(
            step_dance_session(preferred, 0, 0, &strong_rows, &masks, t, &f),
            0
        );
        assert_eq!(
            step_dance_session(preferred, 40_000, -1, &[], &[], t, &[]),
            -1
        );
        let mut roi = Viewport::default();
        assert_eq!(
            step_or_detail_scheduled(preferred, 80_000, current(), &mut roi),
            -3
        );
        let appended = [weak, d];
        let both = [255u8; 512];
        let ff = [f, f].concat();
        assert_eq!(
            finish_detail(
                preferred,
                80_000,
                1,
                CutoutObservation {
                    detections: &appended,
                    masks: &both,
                    transform: t,
                    features: &ff
                }
            ),
            1
        );
        assert_eq!(destroy_dance_session(preferred), 0);
        for direction in [1, -1] {
            let anchor = 2_000_000;
            let base = create_dance_session();
            let lane = create_dance_session();
            for id in [base, lane] {
                assert_eq!(
                    step_dance_session(id, anchor, 0, &strong_rows, &masks, t, &f),
                    0
                );
                assert_eq!(
                    step_dance_session(id, anchor + direction * 40_000, -1, &[], &[], t, &[]),
                    -1
                );
            }
            let at = anchor + direction * 80_000;
            assert_eq!(step_dance_session(base, at, -1, &[], &[], t, &[]), -1);
            let mut roi = Viewport::default();
            assert_eq!(step_or_detail_scheduled(lane, at, current(), &mut roi), -3);
            // Full-only rescue is deferred until the ROI transaction finishes.
            assert_eq!(finish_detail(lane, at, 1, current()), 0);
            {
                let aa = super::session(base).unwrap();
                let bb = super::session(lane).unwrap();
                let a = aa.lock().unwrap();
                let b = bb.lock().unwrap();
                let x = &a.tracker.tracks[0];
                let y = &b.tracker.tracks[0];
                assert_eq!(x.observed_bounds, y.observed_bounds);
                assert_eq!(x.frames_since_observation, y.frames_since_observation);
                assert_eq!(x.state, y.state);
                assert_eq!(x.confidence, y.confidence);
                assert_eq!(
                    x.appearance.as_ref().unwrap().last_seen,
                    y.appearance.as_ref().unwrap().last_seen
                );
                assert_eq!(
                    x.appearance.as_ref().unwrap().bank,
                    y.appearance.as_ref().unwrap().bank
                );
                assert!(y.render_mask.is_none());
            }
            // The weak output cannot extend the identity's reliable time.
            assert_eq!(
                step_dance_session(
                    lane,
                    anchor + direction * 120_000,
                    -1,
                    &weak_rows,
                    &masks,
                    t,
                    &f
                ),
                0
            );
            assert_eq!(
                step_dance_session(
                    lane,
                    anchor + direction * 160_000,
                    -1,
                    &weak_rows,
                    &masks,
                    t,
                    &f
                ),
                -1
            );
            for id in [base, lane] {
                assert_eq!(destroy_dance_session(id), 0);
            }
        }
    }

    #[test]
    fn weak_gap_rejects_competing_current_masks_and_weak_seed_history() {
        use crate::appearance::APPEARANCE_BINS;
        let t = Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 0.,
            source_width: 160,
            source_height: 160,
            input_size: 160,
            proto_size: 16,
        };
        let d = InstanceDetection {
            bounds: Rect {
                left: 60.,
                top: 50.,
                right: 90.,
                bottom: 100.,
            },
            confidence: 0.9,
        };
        let weak = InstanceDetection {
            confidence: 0.23,
            ..d
        };
        let masks = [255u8; 256];
        let mut f = [0.; APPEARANCE_BINS];
        f[0] = 1.;
        let lane = create_dance_session();
        assert_eq!(step_dance_session(lane, 0, 0, &[d], &masks, t, &f), 0);
        assert_eq!(step_dance_session(lane, 40_000, -1, &[], &[], t, &[]), -1);
        let pair = [weak, weak];
        let both = [255u8; 512];
        let ff = [f, f].concat();
        assert_eq!(
            step_dance_session(lane, 80_000, -1, &pair, &both, t, &ff),
            -1
        );
        assert_eq!(destroy_dance_session(lane), 0);
        let lane = create_dance_session();
        assert_eq!(step_dance_session(lane, 0, 0, &[d], &masks, t, &f), 0);
        assert_eq!(
            step_dance_session(lane, 40_000, -1, &[weak], &masks, t, &f),
            0
        );
        assert_eq!(step_dance_session(lane, 80_000, -1, &[], &[], t, &[]), -1);
        assert_eq!(
            step_dance_session(lane, 120_000, -1, &[weak], &masks, t, &f),
            -1
        );
        assert_eq!(destroy_dance_session(lane), 0);
    }

    #[test]
    fn detail_backoff_commits_only_full_state_and_protects_pending_confirmation() {
        use crate::{
            appearance::APPEARANCE_BINS, composition::Viewport, dance_cutout::CutoutObservation,
        };
        let t = Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 0.,
            source_width: 160,
            source_height: 160,
            input_size: 160,
            proto_size: 16,
        };
        let d = InstanceDetection {
            bounds: Rect {
                left: 60.,
                top: 50.,
                right: 90.,
                bottom: 100.,
            },
            confidence: 0.9,
        };
        let masks = [255u8; 256];
        let mut f = [0.; APPEARANCE_BINS];
        f[0] = 1.;
        let empty = || CutoutObservation {
            detections: &[],
            masks: &[],
            transform: t,
            features: &[],
        };
        let rows = [d];
        let observed = || CutoutObservation {
            detections: &rows,
            masks: &masks,
            transform: t,
            features: &f,
        };
        for direction in [1, -1] {
            let anchor = 2_000_000;
            let a = create_dance_session();
            let b = create_dance_session();
            for id in [a, b] {
                assert_eq!(step_dance_session(id, anchor, 0, &rows, &masks, t, &f), 0);
            }
            let mut roi = Viewport::default();
            for i in 1..=8 {
                let at = anchor + direction * i * 40_000;
                assert_eq!(step_dance_session(a, at, -1, &[], &[], t, &[]), -1);
                let result = step_or_detail_scheduled(b, at, empty(), &mut roi);
                if i <= 6 {
                    assert_eq!(result, -3);
                    assert_eq!(finish_detail(b, at, 0, empty()), -1);
                } else {
                    assert_eq!(result, -4);
                    assert_eq!(finish_detail(b, at, 0, empty()), -2);
                }
                let x = super::session(a).unwrap();
                let y = super::session(b).unwrap();
                let x = x.lock().unwrap();
                let y = y.lock().unwrap();
                assert_eq!(
                    x.dance.as_ref().unwrap().clock.as_ref().unwrap().last,
                    y.dance.as_ref().unwrap().clock.as_ref().unwrap().last
                );
                assert_eq!(
                    x.tracker.tracks[0].observed_bounds,
                    y.tracker.tracks[0].observed_bounds
                );
                assert_eq!(x.tracker.tracks[0].state, y.tracker.tracks[0].state);
                assert_eq!(
                    x.tracker.tracks[0].appearance.as_ref().unwrap().last_seen,
                    y.tracker.tracks[0].appearance.as_ref().unwrap().last_seen
                );
            }
            let at = anchor + direction * 9 * 40_000;
            assert_eq!(step_or_detail_scheduled(b, at, empty(), &mut roi), -3);
            // Recovery candidate is not yet renderable, but must be
            // followed by a normal inference on the next sample.
            assert_eq!(finish_detail(b, at, 0, observed()), -1);
            assert_eq!(
                step_or_detail_scheduled(b, at + direction * 40_000, empty(), &mut roi),
                -3
            );
            assert_eq!(finish_detail(b, at + direction * 40_000, 0, observed()), 0);
            // A subsequent full observation takes its normal path.
            for i in 9..=10 {
                assert_eq!(
                    step_dance_session(a, anchor + direction * i * 40_000, -1, &[], &[], t, &[]),
                    -1
                );
            }
            for i in 11..=12 {
                let at = anchor + direction * i * 40_000;
                let base = step_dance_session(a, at, -1, &rows, &masks, t, &f);
                let selected = step_or_detail_scheduled(b, at, observed(), &mut roi);
                if selected == -3 {
                    assert_eq!(base, -1);
                    assert_eq!(finish_detail(b, at, 1, observed()), -1);
                } else {
                    assert_eq!(selected, base);
                }
            }
            assert_eq!(destroy_dance_session(a), 0);
            assert_eq!(destroy_dance_session(b), 0);
        }
    }

    #[test]
    fn detail_overlay_cannot_mutate_full_identity_or_learn_competitors() {
        use crate::{
            appearance::APPEARANCE_BINS, composition::Viewport, dance_cutout::CutoutObservation,
        };
        let t = Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 0.,
            source_width: 160,
            source_height: 160,
            input_size: 160,
            proto_size: 16,
        };
        let d = InstanceDetection {
            bounds: Rect {
                left: 60.,
                top: 50.,
                right: 90.,
                bottom: 100.,
            },
            confidence: 0.9,
        };
        let local = InstanceDetection {
            bounds: Rect {
                left: 62.,
                right: 92.,
                ..d.bounds
            },
            ..d
        };
        let mask = [255u8; 256];
        let mut feature = [0.; APPEARANCE_BINS];
        feature[0] = 1.;
        let baseline = create_dance_session();
        let detail = create_dance_session();
        for id in [baseline, detail] {
            assert_eq!(
                step_dance_session(id, 100_000, 0, &[d], &mask, t, &feature),
                0
            );
        }
        let mut roi = Viewport::default();
        for at in [140_000, 180_000, 220_000, 260_000, 300_000] {
            assert_eq!(step_dance_session(baseline, at, -1, &[], &[], t, &[]), -1);
            assert_eq!(
                step_or_detail(
                    detail,
                    at,
                    CutoutObservation {
                        detections: &[],
                        masks: &[],
                        transform: t,
                        features: &[]
                    },
                    &mut roi
                ),
                -3
            );
            // Invalid time must not consume the transaction.
            assert_eq!(
                finish_detail(
                    detail,
                    at + 1,
                    0,
                    CutoutObservation {
                        detections: &[local],
                        masks: &mask,
                        transform: t,
                        features: &feature
                    }
                ),
                -2
            );
            assert_eq!(
                finish_detail(
                    detail,
                    at,
                    0,
                    CutoutObservation {
                        detections: &[local],
                        masks: &mask,
                        transform: t,
                        features: &feature
                    }
                ),
                0
            );
            let aa = super::session(baseline).unwrap();
            let bb = super::session(detail).unwrap();
            let a = aa.lock().unwrap();
            let b = bb.lock().unwrap();
            assert_eq!(a.tracker.tracks.len(), b.tracker.tracks.len());
            let x = &a.tracker.tracks[0];
            let y = &b.tracker.tracks[0];
            assert_eq!(x.id, y.id);
            assert_eq!(x.observed_bounds, y.observed_bounds);
            assert_eq!(x.state, y.state);
            assert_eq!(
                x.appearance.as_ref().unwrap().last_seen,
                y.appearance.as_ref().unwrap().last_seen
            );
            assert_eq!(
                x.appearance.as_ref().unwrap().bank,
                y.appearance.as_ref().unwrap().bank
            );
        }
        // Long full gap remains a full gap, even though detail had output.
        // Re-enter the exact same full observation on two consecutive samples.
        for at in [340_000, 380_000] {
            let x = step_dance_session(baseline, at, -1, &[d], &mask, t, &feature);
            let y = step_dance_session(detail, at, -1, &[d], &mask, t, &feature);
            assert_eq!(x, y);
        }
        assert_eq!(destroy_dance_session(baseline), 0);
        assert_eq!(destroy_dance_session(detail), 0);
    }

    #[test]
    fn weak_observation_only_continues_a_recent_unique_seed() {
        let left = [
            255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0,
        ];
        let right = [
            0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255,
        ];
        let mut s = session();
        assert_eq!(cutout_step(&mut s, 0, 0, vec![d(0.22, 0., &left)]), None);
        assert_eq!(
            cutout_step(&mut s, 0, 0, vec![d(0.8, 0., &left), d(0.8, 60., &right)]),
            Some(0)
        );
        assert_eq!(
            cutout_step(
                &mut s,
                33_333,
                -1,
                vec![d(0.8, 60., &right), d(0.22, 0., &left)]
            ),
            Some(1)
        );
        assert_eq!(
            cutout_step(
                &mut s,
                66_667,
                -1,
                vec![d(0.22, 0., &left), d(0.23, 1., &left)]
            ),
            Some(-1)
        );
        assert_eq!(
            cutout_step(&mut s, 100_000, -1, vec![d(0.22, 0., &left)]),
            Some(-1)
        );
        assert_eq!(cutout_step(&mut s, 99_999, -1, vec![]), None);
    }
    #[test]
    fn reverse_tracking_uses_real_pts_and_rejects_direction_changes() {
        let a = [255; 16];
        let mut s = session();
        assert_eq!(
            cutout_step(&mut s, 200_000, 0, vec![d(0.8, 0., &a)]),
            Some(0)
        );
        assert_eq!(
            cutout_step(&mut s, 166_667, -1, vec![d(0.22, 0., &a)]),
            Some(0)
        );
        assert_eq!(
            cutout_step(&mut s, 133_333, -1, vec![d(0.22, 0., &a)]),
            Some(0)
        );
        assert_eq!(cutout_step(&mut s, 150_000, -1, vec![d(0.8, 0., &a)]), None);
    }
    #[test]
    fn weak_candidate_is_not_allowed_after_a_gap_or_wrong_mask() {
        let a = [255; 16];
        let b = [0; 16];
        let mut s = session();
        assert_eq!(cutout_step(&mut s, 0, 0, vec![d(0.8, 0., &a)]), Some(0));
        assert_eq!(
            cutout_step(&mut s, 200_000, -1, vec![d(0.24, 0., &a)]),
            Some(-1)
        );
        let mut s = session();
        assert_eq!(cutout_step(&mut s, 0, 0, vec![d(0.8, 0., &a)]), Some(0));
        assert_eq!(
            cutout_step(&mut s, 33_333, -1, vec![d(0.24, 0., &b)]),
            Some(-1)
        );
    }
}

struct Writer(Vec<u8>);
impl Writer {
    fn u(&mut self, v: u32) {
        self.0.extend(v.to_le_bytes())
    }
    fn id(&mut self, v: i64) {
        self.0.extend(v.to_le_bytes())
    }
    fn f(&mut self, v: f32) {
        self.0.extend(v.to_le_bytes())
    }
    fn rect(&mut self, r: Rect) {
        for v in [r.left, r.top, r.right, r.bottom] {
            self.f(v)
        }
    }
    fn ids(&mut self, values: impl IntoIterator<Item = i64>) {
        let ids = values.into_iter().collect::<Vec<_>>();
        self.u(ids.len() as u32);
        for id in ids {
            self.id(id)
        }
    }
    fn mask(&mut self, m: Option<&Mask>) {
        let Some(m) = m else {
            self.u(0);
            return;
        };
        self.u(1);
        self.u(m.width as u32);
        self.u(m.height as u32);
        for v in [m.transform.scale, m.transform.pad_left, m.transform.pad_top] {
            self.f(v)
        }
        for n in [
            m.transform.source_width,
            m.transform.source_height,
            m.transform.input_size,
            m.transform.proto_size,
        ] {
            self.u(n)
        }
        self.u(m.pixels.len() as u32);
        self.0.extend(m.pixels.iter());
    }
    fn classes(&mut self, values: &[ClassEvidence]) {
        self.u(values.len() as u32);
        for e in values {
            self.u(if e.selection == SelectionClass::Selected {
                0
            } else {
                1
            });
            self.u(e.detection_index as u32);
            self.ids(e.residual_ids.iter().copied());
            self.u(u32::from(e.conservative_unknown))
        }
    }
}
fn encode(t: &Tracker) -> Vec<u8> {
    let mut w = Writer(Vec::new());
    w.u(1);
    w.u(t.tracks.len() as u32);
    for track in &t.tracks {
        w.id(track.id);
        for n in [
            track.state as u32,
            track.missed_frames(),
            track.frames_since_observation,
            track.age,
            u32::from(track.observed_this_frame),
        ] {
            w.u(n)
        }
        w.rect(track.predicted_bounds);
        w.f(track.confidence);
        w.f(track.current_foot_y.unwrap_or(track.observed_foot_y));
        w.ids(track.occluder_ids.iter().copied());
        w.mask(track.render_mask.as_ref());
        w.rect(track.observed_bounds);
        w.u(u32::from(track.offscreen_dormant));
    }
    w.classes(&t.class_evidence);
    w.classes(&t.face_class_evidence);
    w.ids(t.suppressed_selected_ids.iter().copied());
    w.u(t.hard_classes.len() as u32);
    for (&index, &class) in &t.hard_classes {
        w.u(index as u32);
        w.u(if class == SelectionClass::Selected {
            0
        } else {
            1
        })
    }
    w.u(t.motion_evidence.len() as u32);
    for e in t.motion_evidence.values() {
        w.id(e.id);
        w.u(e.detection_index as u32);
        w.f(e.score);
        w.f(e.bbox_iou);
        w.f(e.mask_iou);
        w.id(e.timestamp_us)
    }
    w.0
}
/// Reserve the analysis identity namespace, including roots absent from the
/// initial detector result. A future entrant must never inherit their IDs.
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_tracking_reserve_id_floor(id: u64, minimum_next_id: i64) -> i32 {
    if minimum_next_id < 0 {
        return -1;
    }
    let Some(owner) = session(id) else { return -1 };
    let Ok(mut s) = owner.lock() else { return -1 };
    s.tracker.next_id = s.tracker.next_id.max(minimum_next_id);
    0
}

/// # Safety
/// Output must be non-null, aligned and writable for one ReplayPlan. The caller
/// retains ownership; no source frame or decoder is owned by this function.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_replay_plan(
    scope: u32,
    flags: u32,
    anchor: i64,
    last: i64,
    target: i64,
    step: i64,
    output: *mut crate::tracking_replay::ReplayPlan,
) -> i32 {
    if output.is_null()
        || !(output as usize)
            .is_multiple_of(std::mem::align_of::<crate::tracking_replay::ReplayPlan>())
    {
        return -1;
    }
    let Some(plan) = crate::tracking_replay::plan(scope, flags, anchor, last, target, step) else {
        return -1;
    };
    // SAFETY: The caller supplies a live writable aligned output object.
    unsafe { output.write(plan) };
    0
}

/// Stateless research pose parts. Points are 17 source x,y,confidence triples
/// per raw observation. Outputs are 2x1024 floats, 2x64x64 RGB bytes and two
/// support counts per observation. Zero support denotes unavailable evidence.
/// # Safety
/// Readable bounded input arrays; all outputs writable, disjoint and sized
/// as specified. No data retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_pose_parts(
    obs: *const TrackingObservation,
    count: usize,
    pixels: *const u8,
    len: usize,
    rgb: *const u8,
    rgb_len: usize,
    tr: Transform,
    points: *const f32,
    point_len: usize,
    vectors: *mut f32,
    vector_len: usize,
    patches: *mut u8,
    patch_len: usize,
    support: *mut u32,
    support_len: usize,
) -> i32 {
    if count > 64
        || len > 64 * 1024 * 1024
        || rgb_len > 128 * 1024 * 1024
        || point_len != count * 51
        || vector_len != count * 2 * crate::appearance::POSE_PART_DIM
        || patch_len != count * 2 * crate::appearance::POSE_PART_BYTES
        || support_len != count * 2
        || vectors.is_null()
        || patches.is_null()
        || support.is_null()
    {
        return -1;
    }
    // SAFETY: caller supplies readable bounded input arrays.
    let (Some(obs), Some(pix), Some(rgb), Some(points)) = (unsafe {
        (
            borrowed(obs, count),
            borrowed(pixels, len),
            borrowed(rgb, rgb_len),
            borrowed(points, point_len),
        )
    }) else {
        return -1;
    };
    let Some(d) = decode_observations(obs, pix) else {
        return -1;
    };
    let Some(v) = crate::appearance::research_pose_parts(&d, rgb, tr, points) else {
        return -1;
    };
    // SAFETY: caller supplies disjoint outputs with documented capacities.
    unsafe {
        std::ptr::copy_nonoverlapping(v.vectors.as_ptr(), vectors, v.vectors.len());
        std::ptr::copy_nonoverlapping(v.patches.as_ptr(), patches, v.patches.len());
        std::ptr::copy_nonoverlapping(v.support.as_ptr(), support, v.support.len());
    }
    0
}

/// Stateless research clothing descriptor extraction using the same sampler
/// as M4. Outputs are square-root histograms, not persisted by FrameKernel.
/// # Safety
/// Inputs are readable for their lengths, output contains count*2048 writable
/// floats, disjoint from inputs.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_clothing_vectors(
    obs: *const TrackingObservation,
    count: usize,
    pixels: *const u8,
    len: usize,
    rgb: *const u8,
    rgb_len: usize,
    tr: Transform,
    out: *mut f32,
    out_len: usize,
) -> i32 {
    if count > 64
        || len > 64 * 1024 * 1024
        || rgb_len > 128 * 1024 * 1024
        || out.is_null()
        || count.checked_mul(crate::appearance::RESEARCH_CLOTHING_DIM) != Some(out_len)
    {
        return -1;
    }
    // SAFETY: caller owns checked readable arrays.
    let (Some(obs), Some(pix), Some(rgb)) = (unsafe {
        (
            borrowed(obs, count),
            borrowed(pixels, len),
            borrowed(rgb, rgb_len),
        )
    }) else {
        return -1;
    };
    let Some(d) = decode_observations(obs, pix) else {
        return -1;
    };
    let Some(v) = crate::appearance::research_clothing_vectors(&d, rgb, tr) else {
        return -1;
    };
    // SAFETY: caller owns disjoint writable storage with out_len floats.
    unsafe { std::ptr::copy_nonoverlapping(v.as_ptr(), out, out_len) };
    0
}
/// # Safety
/// Inputs are readable for their lengths, output has rows*cols writable
/// disjoint floats. Nothing is retained by the kernel.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_similarity(
    a: *const f32,
    a_len: usize,
    b: *const f32,
    b_len: usize,
    rows: usize,
    cols: usize,
    dim: usize,
    blocks: usize,
    out: *mut f32,
) -> i32 {
    if a_len > 64 * 2048 || b_len > 64 * 2048 || out.is_null() {
        return -1;
    }
    // SAFETY: caller owns checked readable arrays.
    let (Some(a), Some(b)) = (unsafe { (borrowed(a, a_len), borrowed(b, b_len)) }) else {
        return -1;
    };
    let Some(v) = crate::appearance::research_similarity_matrix(a, b, rows, cols, dim, blocks)
    else {
        return -1;
    };
    // SAFETY: caller owns rows*cols disjoint writable floats.
    unsafe { std::ptr::copy_nonoverlapping(v.as_ptr(), out, v.len()) };
    0
}
/// # Safety
/// Scores are readable for len elements; output has rows writable disjoint
/// rank records. Invalid input never writes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_rank(
    scores: *const f32,
    len: usize,
    rows: usize,
    cols: usize,
    minimum: f32,
    margin: f32,
    out: *mut crate::appearance::ResearchRank,
) -> i32 {
    if len > 64 * 64 || out.is_null() {
        return -1;
    }
    // SAFETY: bounded readable array from caller.
    let Some(v) = (unsafe { borrowed(scores, len) }) else {
        return -1;
    };
    let Some(r) = crate::appearance::research_reciprocal_rank(v, rows, cols, minimum, margin)
    else {
        return -1;
    };
    // SAFETY: caller owns rows disjoint writable records.
    unsafe { std::ptr::copy_nonoverlapping(r.as_ptr(), out, r.len()) };
    0
}

/// Anonymous three-sample raw paths for research pooling, no track state.
/// # Safety
/// Inputs readable; output contains 2*middle_count disjoint u32 elements.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_triplet_paths(
    obs: *const TrackingObservation,
    count: usize,
    pixels: *const u8,
    len: usize,
    counts: *const u32,
    times: *const i64,
    out: *mut u32,
    out_len: usize,
) -> i32 {
    if count > 192 || len > 128 * 1024 * 1024 || out.is_null() {
        return -1;
    }
    // SAFETY: caller supplies bounded readable observation and frame arrays.
    let (Some(obs), Some(pix), Some(counts), Some(times)) = (unsafe {
        (
            borrowed(obs, count),
            borrowed(pixels, len),
            borrowed(counts, 3),
            borrowed(times, 3),
        )
    }) else {
        return -1;
    };
    if counts.iter().any(|&v| v > 64)
        || counts.iter().map(|&v| v as usize).sum::<usize>() != count
        || 2 * counts[1] as usize != out_len
    {
        return -1;
    }
    let Some(d) = decode_observations(obs, pix) else {
        return -1;
    };
    let a = counts[0] as usize;
    let b = a + counts[1] as usize;
    let Some(paths) =
        crate::tracking_motion::research_triplet_paths(&[&d[..a], &d[a..b], &d[b..]], times)
    else {
        return -1;
    };
    let output = paths
        .into_iter()
        .flat_map(|p| p.map_or([u32::MAX; 2], |v| v.map(|n| n as u32)))
        .collect::<Vec<_>>();
    // SAFETY: caller supplies out_len disjoint writable indices.
    unsafe { std::ptr::copy_nonoverlapping(output.as_ptr(), out, out_len) };
    0
}

/// Offline-only two-anchor proof over bounded consecutive raw observations.
/// Output is frame_count raw indices; no writes on invalid/no evidence.
/// # Safety
/// All nonempty inputs are readable; output has frame_count disjoint u32 slots.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_offline_tracklet_window(
    observations: *const TrackingObservation,
    count: usize,
    pixels: *const u8,
    len: usize,
    counts: *const u32,
    times: *const i64,
    frame_count: usize,
    left: u32,
    right: u32,
    out: *mut u32,
) -> i32 {
    if !(3..=8).contains(&frame_count) || count > 512 || len > 128 * 1024 * 1024 || out.is_null() {
        return -1;
    }
    // SAFETY: bounded live readable arrays supplied by caller.
    let (Some(obs), Some(pix), Some(counts), Some(times)) = (unsafe {
        (
            borrowed(observations, count),
            borrowed(pixels, len),
            borrowed(counts, frame_count),
            borrowed(times, frame_count),
        )
    }) else {
        return -1;
    };
    if counts.iter().any(|&v| v > 64) || counts.iter().map(|&v| v as usize).sum::<usize>() != count
    {
        return -1;
    }
    let Some(d) = decode_observations(obs, pix) else {
        return -1;
    };
    let mut frames = Vec::with_capacity(frame_count);
    let mut at = 0;
    for &n in counts {
        let end = at + n as usize;
        frames.push(&d[at..end]);
        at = end;
    }
    match crate::tracking_motion::offline_window(&frames, times, left as usize, right as usize) {
        None => -1,
        Some(None) => 1,
        Some(Some(path)) => {
            // SAFETY: caller provides frame_count disjoint output elements.
            for (i, &q) in path.iter().enumerate() {
                // SAFETY: each index is within the caller-provided output.
                unsafe { out.add(i).write(q as u32) };
            }
            0
        }
    }
}

/// Checked value conversion shared by the identity and privacy-class transports.
pub(crate) fn decode_observations(
    observations: &[TrackingObservation],
    pixels: &[u8],
) -> Option<Vec<Detection>> {
    let mut detections = Vec::with_capacity(observations.len());
    for d in observations {
        let mask = if d.mask_offset == u64::MAX {
            None
        } else {
            let n = (d.mask_width as usize).checked_mul(d.mask_height as usize)?;
            let Ok(start) = usize::try_from(d.mask_offset) else {
                return None;
            };
            let end = start.checked_add(n)?;
            let bytes = pixels.get(start..end)?;
            Some(Mask {
                width: d.mask_width as usize,
                height: d.mask_height as usize,
                pixels: Arc::from(bytes),
                transform: d.transform,
            })
        };
        let detection = Detection {
            bounds: d.bounds,
            confidence: d.confidence,
            foot_y: d.foot_y,
            mask,
        };
        if !detection.valid() {
            return None;
        }
        detections.push(detection);
    }
    Some(detections)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn closed_and_foreign_handles_are_rejected_and_snapshot_probe_does_not_advance() {
        // SAFETY: All pointers below refer to live local values or null/zero buffers.
        unsafe {
            let id = frame_kernel_tracking_create(TrackingOptions::default());
            assert_ne!(id, 0);
            let d = TrackingObservation {
                bounds: Rect {
                    left: 0.0,
                    top: 0.0,
                    right: 100.0,
                    bottom: 200.0,
                },
                confidence: 0.9,
                foot_y: 200.0,
                mask_offset: u64::MAX,
                mask_width: 0,
                mask_height: 0,
                transform: Transform {
                    scale: 1.0,
                    pad_left: 0.0,
                    pad_top: 0.0,
                    source_width: 640,
                    source_height: 640,
                    input_size: 640,
                    proto_size: 160,
                },
            };
            assert_eq!(
                frame_kernel_tracking_step(
                    id,
                    0,
                    0,
                    &d,
                    1,
                    std::ptr::null(),
                    0,
                    std::ptr::null(),
                    0
                ),
                0
            );
            let n = frame_kernel_tracking_snapshot(id, std::ptr::null_mut(), 0);
            assert!(n > 0);
            let mut bytes = vec![0; n as usize];
            assert_eq!(
                frame_kernel_tracking_snapshot(id, bytes.as_mut_ptr(), bytes.len()),
                n
            );
            let original = bytes.clone();
            assert_eq!(
                frame_kernel_tracking_snapshot(id, bytes.as_mut_ptr(), bytes.len()),
                n
            );
            assert_eq!(bytes, original);
            assert_eq!(frame_kernel_tracking_destroy(id), 0);
            assert_eq!(frame_kernel_tracking_destroy(id), -1);
            assert_eq!(
                frame_kernel_tracking_snapshot(id, std::ptr::null_mut(), 0),
                -1
            );
        }
    }
    #[test]
    fn invalid_observation_never_replaces_the_previous_snapshot() {
        // SAFETY: All pointers below refer to live local values or null/zero buffers.
        unsafe {
            let id = frame_kernel_tracking_create(TrackingOptions::default());
            assert_eq!(
                frame_kernel_tracking_step(
                    id,
                    0,
                    0,
                    std::ptr::null(),
                    0,
                    std::ptr::null(),
                    0,
                    std::ptr::null(),
                    0
                ),
                0
            );
            let n = frame_kernel_tracking_snapshot(id, std::ptr::null_mut(), 0);
            let d = TrackingObservation {
                bounds: Rect {
                    left: 0.0,
                    top: 0.0,
                    right: 100.0,
                    bottom: 200.0,
                },
                confidence: 0.9,
                foot_y: 200.0,
                mask_offset: 0,
                mask_width: 160,
                mask_height: 160,
                transform: Transform {
                    scale: 1.0,
                    pad_left: 0.0,
                    pad_top: 0.0,
                    source_width: 640,
                    source_height: 640,
                    input_size: 640,
                    proto_size: 160,
                },
            };
            assert_eq!(
                frame_kernel_tracking_step(
                    id,
                    1,
                    1,
                    &d,
                    1,
                    std::ptr::null(),
                    0,
                    std::ptr::null(),
                    0
                ),
                -1
            );
            assert_eq!(
                frame_kernel_tracking_snapshot(id, std::ptr::null_mut(), 0),
                n
            );
            assert_eq!(frame_kernel_tracking_destroy(id), 0);
        }
    }
}
