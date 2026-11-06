//! Checked C transport for owned tracking sessions. The algorithms are in the
//! safe tracking modules; IDs never expose a native pointer to mobile runtimes.
use crate::{
    association_score::AssociationWeights,
    geometry::{Rect, Transform},
    tracking::*,
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
}
type OwnedSession = Arc<Mutex<Session>>;
static SESSIONS: OnceLock<Mutex<BTreeMap<u64, OwnedSession>>> = OnceLock::new();
static NEXT_ID: AtomicU64 = AtomicU64::new(1);
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
    let Ok(mut sessions) = registry().lock() else {
        return 0;
    };
    if sessions.len() >= 4096 {
        return 0;
    }
    let Ok(id) = NEXT_ID.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |n| n.checked_add(1))
    else {
        return 0;
    };
    sessions.insert(
        id,
        Arc::new(Mutex::new(Session {
            tracker: Tracker::new(config),
            snapshot: Vec::new(),
        })),
    );
    id
}
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_tracking_destroy(id: u64) -> i32 {
    if registry()
        .lock()
        .ok()
        .and_then(|mut r| r.remove(&id))
        .is_some()
    {
        0
    } else {
        -1
    }
}
unsafe fn borrowed<'a, T>(p: *const T, n: usize) -> Option<&'a [T]> {
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
    s.tracker.protected_ids = protected.iter().copied().collect();
    s.tracker.selected_ids = body.iter().copied().collect();
    s.tracker.face_selected_ids = face.iter().copied().collect();
    s.tracker.offscreen_dormancy = dormancy != 0;
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
    let mut detections = Vec::with_capacity(count);
    for d in observations {
        let mask = if d.mask_offset == u64::MAX {
            None
        } else {
            let Some(n) = (d.mask_width as usize).checked_mul(d.mask_height as usize) else {
                return -1;
            };
            let Ok(start) = usize::try_from(d.mask_offset) else {
                return -1;
            };
            let Some(end) = start.checked_add(n) else {
                return -1;
            };
            let Some(bytes) = pixels.get(start..end) else {
                return -1;
            };
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
            return -1;
        }
        detections.push(detection);
    }
    let Some(owner) = session(id) else { return -1 };
    let Ok(mut s) = owner.lock() else { return -1 };
    let result = match action {
        0 => s.tracker.initialize(detections, assigned),
        1 => s.tracker.update(detections, at).map(|_| ()),
        2 => s.tracker.predict(at, true).map(|_| ()),
        _ => s.tracker.predict(at, false).map(|_| ()),
    };
    if result.is_err() {
        return -1;
    }
    s.snapshot = encode(&s.tracker);
    0
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
