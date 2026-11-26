//! Checked C transport for the shared Tracker cutout association profile.
use crate::{
    appearance::{APPEARANCE_BINS, appearance_features},
    ffi_tracking::{borrowed, create_dance_session, destroy_dance_session, step_dance_session},
    geometry::Transform,
    instances::InstanceDetection,
};
unsafe fn write<'a, T>(ptr: *mut T, n: usize) -> Option<&'a mut [T]> {
    if n == 0 {
        return Some(&mut []);
    }
    if ptr.is_null()
        || n > isize::MAX as usize / std::mem::size_of::<T>()
        || !(ptr as usize).is_multiple_of(std::mem::align_of::<T>())
    {
        return None;
    }
    // SAFETY: caller owns the unique writable buffer of the declared length.
    Some(unsafe { std::slice::from_raw_parts_mut(ptr, n) })
}
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_dance_cutout_create() -> u64 {
    create_dance_session()
}
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_dance_cutout_destroy(id: u64) -> i32 {
    destroy_dance_session(id)
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
        unsafe { borrowed(rgba, rgba_len) },
        unsafe { borrowed(masks, mask_len) },
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
        unsafe { borrowed(rgb, rgb_len) },
        unsafe { borrowed(masks, mask_len) },
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
        unsafe { borrowed(detections, count) },
        unsafe { borrowed(masks, mask_len) },
        unsafe { borrowed(features, features_len) },
    ) else {
        return -2;
    };
    step_dance_session(id, at, seed, d, m, t, f)
}

/// # Safety
/// Arrays must be readable and the ROI output uniquely writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_dance_step_or_detail(
    id: u64,
    at: i64,
    d: *const InstanceDetection,
    count: usize,
    m: *const u8,
    mask_len: usize,
    t: Transform,
    f: *const f32,
    feature_len: usize,
    roi: *mut crate::composition::Viewport,
) -> i32 {
    if !valid_lengths(t, count, mask_len, feature_len) {
        return -2;
    }
    // SAFETY: checked lengths and the caller's exclusive output region.
    let (Some(d), Some(m), Some(f), Some(roi)) = (
        unsafe { borrowed(d, count) },
        unsafe { borrowed(m, mask_len) },
        unsafe { borrowed(f, feature_len) },
        unsafe { write(roi, 1) },
    ) else {
        return -2;
    };
    roi[0] = crate::composition::Viewport::default();
    crate::ffi_tracking::step_or_detail(
        id,
        at,
        crate::dance_cutout::CutoutObservation {
            detections: d,
            masks: m,
            transform: t,
            features: f,
        },
        &mut roi[0],
    )
}
/// # Safety
/// Arrays must describe the original full-frame rows followed by detail rows.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_dance_finish_detail(
    id: u64,
    at: i64,
    full_count: usize,
    d: *const InstanceDetection,
    count: usize,
    m: *const u8,
    mask_len: usize,
    t: Transform,
    f: *const f32,
    feature_len: usize,
) -> i32 {
    if full_count > count || !valid_lengths(t, count, mask_len, feature_len) {
        return -2;
    }
    // SAFETY: checked lengths and caller-owned readable regions.
    let (Some(d), Some(m), Some(f)) = (
        unsafe { borrowed(d, count) },
        unsafe { borrowed(m, mask_len) },
        unsafe { borrowed(f, feature_len) },
    ) else {
        return -2;
    };
    crate::ffi_tracking::finish_detail(
        id,
        at,
        full_count,
        crate::dance_cutout::CutoutObservation {
            detections: d,
            masks: m,
            transform: t,
            features: f,
        },
    )
}
/// # Safety
/// Buffers must be readable/writable at the given lengths and disjoint.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_dance_detail_remap(
    local: InstanceDetection,
    mask: *const u8,
    mask_len: usize,
    local_t: Transform,
    scene: Transform,
    roi: crate::composition::Viewport,
    full: *const InstanceDetection,
    count: usize,
    full_masks: *const u8,
    full_len: usize,
    out: *mut u8,
    out_len: usize,
    result: *mut InstanceDetection,
) -> i32 {
    if count > 256
        || !valid_lengths(scene, count, full_len, count * APPEARANCE_BINS)
        || !local_t.valid()
        || local_t.input_size > 1024
        || local_t.proto_size > 1024
        || mask_len != (local_t.proto_size as usize).pow(2)
        || out_len != (scene.proto_size as usize).pow(2)
    {
        return -1;
    }
    // SAFETY: checked lengths and caller-owned disjoint buffers.
    let (Some(m), Some(d), Some(f), Some(out), Some(result)) = (
        unsafe { borrowed(mask, mask_len) },
        unsafe { borrowed(full, count) },
        unsafe { borrowed(full_masks, full_len) },
        unsafe { write(out, out_len) },
        unsafe { write(result, 1) },
    ) else {
        return -1;
    };
    match crate::dance_detail::remap(local, m, local_t, scene, roi, d, f, out) {
        Some(value) => {
            result[0] = value;
            1
        }
        None => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn detail_retry_does_not_advance_pts_or_replace_a_valid_full_observation() {
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
            bounds: crate::geometry::Rect {
                left: 60.,
                top: 50.,
                right: 90.,
                bottom: 100.,
            },
            confidence: 0.9,
        };
        let masks = [255u8; 256];
        let mut feature = [0.; APPEARANCE_BINS];
        feature[0] = 1.;
        let id = frame_kernel_dance_cutout_create();
        let mut roi = crate::composition::Viewport::default();
        // SAFETY: all arrays are owned and match their lengths, null arrays have zero length.
        unsafe {
            assert_eq!(
                frame_kernel_dance_cutout_step(
                    id,
                    100_000,
                    0,
                    &d,
                    1,
                    masks.as_ptr(),
                    masks.len(),
                    t,
                    feature.as_ptr(),
                    feature.len()
                ),
                0
            );
            assert_eq!(
                frame_kernel_dance_step_or_detail(
                    id,
                    140_000,
                    std::ptr::null(),
                    0,
                    std::ptr::null(),
                    0,
                    t,
                    std::ptr::null(),
                    0,
                    &mut roi
                ),
                -3
            );
            assert!(roi.width > 0 && roi.height > 0);
            // Commit the same source PTS with the newly supplied current mask.
            assert_eq!(
                frame_kernel_dance_finish_detail(
                    id,
                    140_000,
                    0,
                    &d,
                    1,
                    masks.as_ptr(),
                    masks.len(),
                    t,
                    feature.as_ptr(),
                    feature.len()
                ),
                0
            );
            assert_eq!(
                frame_kernel_dance_step_or_detail(
                    id,
                    180_000,
                    &d,
                    1,
                    masks.as_ptr(),
                    masks.len(),
                    t,
                    feature.as_ptr(),
                    feature.len(),
                    &mut roi
                ),
                0
            );
            assert_eq!(roi.width, 0);
            assert_eq!(
                frame_kernel_dance_cutout_step(
                    id,
                    180_000,
                    -1,
                    &d,
                    1,
                    masks.as_ptr(),
                    masks.len(),
                    t,
                    feature.as_ptr(),
                    feature.len()
                ),
                -2
            );
        }
        assert_eq!(frame_kernel_dance_cutout_destroy(id), 0);
    }

    #[test]
    fn shared_registry_preserves_session_mode_boundaries() {
        let privacy = crate::frame_kernel_tracking_create(crate::frame_kernel_tracking_defaults());
        let cutout = frame_kernel_dance_cutout_create();
        assert_ne!(privacy, cutout);
        assert_eq!(frame_kernel_dance_cutout_destroy(privacy), -1);
        assert_eq!(crate::frame_kernel_tracking_destroy(cutout), -1);
        // SAFETY: zero-length buffer queries borrow no memory.
        unsafe {
            assert_eq!(
                crate::frame_kernel_tracking_snapshot(cutout, std::ptr::null_mut(), 0),
                -1
            );
        }
        assert_eq!(crate::frame_kernel_tracking_destroy(privacy), 0);
        assert_eq!(frame_kernel_dance_cutout_destroy(cutout), 0);
        assert_eq!(frame_kernel_dance_cutout_destroy(cutout), -1);
    }

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
