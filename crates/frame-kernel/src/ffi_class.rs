//! Owned privacy-class sessions. Handles share the identity session ID allocator;
//! typed registries reject wrong-kind, stale and destroyed IDs.
use crate::{
    ffi_tracking::{TrackingObservation, borrowed, decode_observations, next_session_id},
    privacy_class::{ClassConfig, ClassTracker},
    tracking::SelectionClass,
};
use std::{
    collections::BTreeMap,
    sync::{Arc, Mutex, OnceLock},
};
#[repr(C)]
#[derive(Clone, Copy)]
pub struct ClassOptions {
    pub minimum: f32,
    pub single_minimum: f32,
    pub margin: f32,
    pub maximum_misses: u32,
    pub flags: u32,
}
#[repr(C)]
#[derive(Clone, Copy)]
pub struct ClassRoot {
    pub index: u32,
    pub selection: u32,
}
#[repr(C)]
#[derive(Clone, Copy, Default)]
pub struct ClassDecision {
    pub index: u32,
    pub selection: u32,
    pub unknown: u32,
}
type Session = Arc<Mutex<ClassTracker>>;
static SESSIONS: OnceLock<Mutex<BTreeMap<u64, Session>>> = OnceLock::new();
fn registry() -> &'static Mutex<BTreeMap<u64, Session>> {
    SESSIONS.get_or_init(|| Mutex::new(BTreeMap::new()))
}
fn owned(id: u64) -> Option<Session> {
    registry().lock().ok()?.get(&id).cloned()
}
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_class_create(options: ClassOptions) -> u64 {
    if options.flags > 7 {
        return 0;
    }
    let Some(tracker) = ClassTracker::new(ClassConfig {
        minimum: options.minimum,
        single_minimum: options.single_minimum,
        margin: options.margin,
        maximum_misses: options.maximum_misses,
        reuse_similarity: options.flags & 1 != 0,
        reuse_warp: options.flags & 2 != 0,
        count_evaluations: options.flags & 4 != 0,
    }) else {
        return 0;
    };
    let Ok(mut r) = registry().lock() else {
        return 0;
    };
    if r.len() >= 4096 {
        return 0;
    }
    let Some(id) = next_session_id() else {
        return 0;
    };
    r.insert(id, Arc::new(Mutex::new(tracker)));
    id
}
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_class_destroy(id: u64) -> i32 {
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
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_class_reset(id: u64) -> i32 {
    let Some(s) = owned(id) else { return -1 };
    let Ok(mut t) = s.lock() else { return -1 };
    t.reset();
    0
}
/// # Safety
/// All nonempty inputs are live aligned readable buffers. Output has capacity
/// disjoint writable decisions and evaluations points to a writable u32.
/// No pointer is retained. Output capacity must cover count before any mutation.
/// Returns decision count, or -1 for invalid input/capacity/handle.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_class_update(
    id: u64,
    observations: *const TrackingObservation,
    count: usize,
    pixels: *const u8,
    pixels_len: usize,
    roots: *const ClassRoot,
    roots_len: usize,
    output: *mut ClassDecision,
    capacity: usize,
    evaluations: *mut u32,
) -> i32 {
    if count > 4096
        || roots_len > 4096
        || pixels_len > 256 * 1024 * 1024
        || capacity < count
        || (count > 0 && output.is_null())
        || evaluations.is_null()
    {
        return -1;
    }
    let (Some(obs), Some(pool), Some(hard)) = (
        // SAFETY: caller guarantees the documented observation buffer.
        unsafe { borrowed(observations, count) },
        // SAFETY: caller guarantees the documented pixel buffer.
        unsafe { borrowed(pixels, pixels_len) },
        // SAFETY: caller guarantees the documented root buffer.
        unsafe { borrowed(roots, roots_len) },
    ) else {
        return -1;
    };
    if hard.iter().any(|r| r.selection > 1) {
        return -1;
    }
    let Some(detections) = decode_observations(obs, pool) else {
        return -1;
    };
    let hard: Vec<_> = hard
        .iter()
        .map(|r| {
            (
                r.index as usize,
                if r.selection == 0 {
                    SelectionClass::Selected
                } else {
                    SelectionClass::Unselected
                },
            )
        })
        .collect();
    let Some(s) = owned(id) else { return -1 };
    let Ok(mut t) = s.lock() else { return -1 };
    let Ok(result) = t.update(&detections, &hard) else {
        return -1;
    };
    for (i, e) in result.iter().enumerate() {
        let decision = ClassDecision {
            index: e.detection_index as u32,
            selection: if e.selection == SelectionClass::Selected {
                0
            } else {
                1
            },
            unknown: u32::from(e.conservative_unknown),
        };
        // SAFETY: caller output is disjoint writable capacity>=count; result.len()<=count.
        unsafe { output.add(i).write(decision) };
    }
    // SAFETY: caller supplies a disjoint live writable evaluations integer.
    unsafe { *evaluations = t.last_evaluations };
    result.len() as i32
}

/// # Safety
/// Output points to eight disjoint writable u32 counters: hard selected/unselected,
/// inferred selected/unselected, unknown, selected/unselected prototypes, evaluations.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_class_summary(id: u64, output: *mut u32) -> i32 {
    if output.is_null() {
        return -1;
    }
    let Some(s) = owned(id) else { return -1 };
    let Ok(t) = s.lock() else { return -1 };
    // SAFETY: caller guarantees eight disjoint live aligned writable integers.
    unsafe { std::ptr::copy_nonoverlapping(t.summary.as_ptr(), output, 8) };
    0
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ffi_tracking::{
        frame_kernel_tracking_create, frame_kernel_tracking_defaults, frame_kernel_tracking_destroy,
    };
    #[test]
    fn owned_typed_handles_cannot_alias_or_outlive_destruction() {
        let identity = frame_kernel_tracking_create(frame_kernel_tracking_defaults());
        let class = frame_kernel_class_create(ClassOptions {
            minimum: 0.42,
            single_minimum: 0.65,
            margin: 0.12,
            maximum_misses: 4,
            flags: 3,
        });
        assert!(identity != 0 && class != 0 && identity != class);
        assert_eq!(frame_kernel_class_reset(identity), -1);
        assert_eq!(frame_kernel_tracking_destroy(class), -1);
        assert_eq!(frame_kernel_class_destroy(class), 0);
        assert_eq!(frame_kernel_class_reset(class), -1);
        assert_eq!(frame_kernel_class_destroy(class), -1);
        assert_eq!(frame_kernel_tracking_destroy(identity), 0);
    }
}
