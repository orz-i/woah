//! Dedicated handle registry and checked C transport for selected dance identity.
use crate::{
    dance_cutout::{APPEARANCE_BINS, DanceCutout, appearance_features},
    ffi_tracking::next_session_id,
    geometry::Transform,
    instances::InstanceDetection,
};
use std::{
    collections::BTreeMap,
    sync::{Arc, Mutex, OnceLock},
};
type Owner = Arc<Mutex<DanceCutout>>;
static LANES: OnceLock<Mutex<BTreeMap<u64, Owner>>> = OnceLock::new();
fn registry() -> &'static Mutex<BTreeMap<u64, Owner>> {
    LANES.get_or_init(|| Mutex::new(BTreeMap::new()))
}
unsafe fn read<'a, T>(ptr: *const T, n: usize) -> Option<&'a [T]> {
    if n == 0 {
        return Some(&[]);
    }
    if ptr.is_null() {
        return None;
    }
    // SAFETY: all callers guarantee a readable region of the checked length.
    Some(unsafe { std::slice::from_raw_parts(ptr, n) })
}
unsafe fn write<'a, T>(ptr: *mut T, n: usize) -> Option<&'a mut [T]> {
    if n == 0 {
        return Some(&mut []);
    }
    if ptr.is_null() {
        return None;
    }
    // SAFETY: caller guarantees unique, writable storage of the checked length.
    Some(unsafe { std::slice::from_raw_parts_mut(ptr, n) })
}
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_dance_cutout_create() -> u64 {
    let Some(id) = next_session_id() else {
        return 0;
    };
    let Ok(mut entries) = registry().lock() else {
        return 0;
    };
    entries.insert(id, Arc::new(Mutex::new(DanceCutout::default())));
    id
}
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_dance_cutout_destroy(id: u64) -> i32 {
    registry()
        .lock()
        .ok()
        .and_then(|mut r| r.remove(&id))
        .map_or(-1, |_| 0)
}
fn valid_lengths(t: Transform, count: usize, mask_len: usize, features_len: usize) -> bool {
    t.valid()
        && t.input_size <= 1024
        && count <= 256
        && mask_len == count * (t.proto_size as usize).pow(2)
        && features_len == count * APPEARANCE_BINS
}
/// # Safety
/// Input arrays must be readable for their stated lengths and not overlap the output.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_dance_appearance_rgba(
    rgba: *const u8,
    rgba_len: usize,
    bottom_up: u32,
    masks: *const u8,
    mask_len: usize,
    count: usize,
    t: Transform,
    out: *mut f32,
    out_len: usize,
) -> i32 {
    let n = t.input_size as usize;
    if !valid_lengths(t, count, mask_len, out_len) || rgba_len != n * n * 4 || bottom_up > 1 {
        return -1;
    }
    // SAFETY: lengths are checked above and owned by the caller.
    let (Some(rgb), Some(masks), Some(out)) = (
        unsafe { read(rgba, rgba_len) },
        unsafe { read(masks, mask_len) },
        unsafe { write(out, out_len) },
    ) else {
        return -1;
    };
    appearance_features(
        t,
        masks,
        count,
        |x, y| {
            let y = if bottom_up == 1 { n - 1 - y } else { y };
            let i = (y * n + x) * 4;
            [rgb[i], rgb[i + 1], rgb[i + 2]]
        },
        out,
    )
    .map_or(-1, |_| 0)
}
/// # Safety
/// Input is a readable top-down normalized planar RGB tensor; arrays do not overlap output.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_dance_appearance_planar(
    rgb: *const f32,
    rgb_len: usize,
    masks: *const u8,
    mask_len: usize,
    count: usize,
    t: Transform,
    out: *mut f32,
    out_len: usize,
) -> i32 {
    if !valid_lengths(t, count, mask_len, out_len) {
        return -1;
    }
    let n = t.input_size as usize;
    let area = n * n;
    if rgb_len != area * 3 {
        return -1;
    }
    // SAFETY: lengths are checked above and owned by the caller.
    let (Some(rgb), Some(masks), Some(out)) = (
        unsafe { read(rgb, rgb_len) },
        unsafe { read(masks, mask_len) },
        unsafe { write(out, out_len) },
    ) else {
        return -1;
    };
    if rgb.iter().any(|v| !v.is_finite()) {
        return -1;
    }
    appearance_features(
        t,
        masks,
        count,
        |x, y| {
            let i = y * n + x;
            [0, 1, 2].map(|c| (rgb[c * area + i].clamp(0., 1.) * 255.).round() as u8)
        },
        out,
    )
    .map_or(-1, |_| 0)
}
/// # Safety
/// Detection, mask and descriptor buffers must be readable for their stated lengths.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_dance_cutout_step(
    id: u64,
    at: i64,
    seed: i32,
    detections: *const InstanceDetection,
    count: usize,
    masks: *const u8,
    mask_len: usize,
    t: Transform,
    features: *const f32,
    features_len: usize,
) -> i32 {
    if !valid_lengths(t, count, mask_len, features_len) {
        return -2;
    }
    // SAFETY: lengths are checked above and owned by the caller.
    let (Some(d), Some(m), Some(f)) = (
        unsafe { read(detections, count) },
        unsafe { read(masks, mask_len) },
        unsafe { read(features, features_len) },
    ) else {
        return -2;
    };
    let Some(owner) = registry().lock().ok().and_then(|r| r.get(&id).cloned()) else {
        return -2;
    };
    let Ok(mut lane) = owner.lock() else {
        return -2;
    };
    lane.step(at, seed, d, m, t, f).unwrap_or(-2)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn top_down_planar_and_bottom_up_rgba_are_identical() {
        let t = Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 2.,
            source_width: 8,
            source_height: 4,
            input_size: 8,
            proto_size: 8,
        };
        let mut top = vec![255u8; 8 * 8 * 4];
        for y in 0..8 {
            for x in 0..8 {
                let color = if y < 4 { [10, 10, 10] } else { [240, 30, 30] };
                top[(y * 8 + x) * 4..(y * 8 + x) * 4 + 3].copy_from_slice(&color);
            }
        }
        let bottom = top
            .chunks_exact(8 * 4)
            .rev()
            .flatten()
            .copied()
            .collect::<Vec<_>>();
        let planar = (0..3)
            .flat_map(|c| top.chunks_exact(4).map(move |p| p[c] as f32 / 255.))
            .collect::<Vec<_>>();
        let masks = [255; 64];
        let mut a = [0.; APPEARANCE_BINS];
        let mut b = a;
        let mut c = a;
        // SAFETY: the three disjoint owned buffers match their declared lengths.
        unsafe {
            assert_eq!(
                frame_kernel_dance_appearance_rgba(
                    top.as_ptr(),
                    top.len(),
                    0,
                    masks.as_ptr(),
                    masks.len(),
                    1,
                    t,
                    a.as_mut_ptr(),
                    a.len()
                ),
                0
            );
            assert_eq!(
                frame_kernel_dance_appearance_rgba(
                    bottom.as_ptr(),
                    bottom.len(),
                    1,
                    masks.as_ptr(),
                    masks.len(),
                    1,
                    t,
                    b.as_mut_ptr(),
                    b.len()
                ),
                0
            );
            assert_eq!(
                frame_kernel_dance_appearance_planar(
                    planar.as_ptr(),
                    planar.len(),
                    masks.as_ptr(),
                    masks.len(),
                    1,
                    t,
                    c.as_mut_ptr(),
                    c.len()
                ),
                0
            );
        }
        assert_eq!(a, b);
        assert_eq!(a, c);
        assert_eq!(a[0], 0.5);
    }
}
