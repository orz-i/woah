//! Uniform region ABI:0 success,1 no evidence,-1 invalid. Failures never write.
use crate::{
    face_geometry::{self, HeadMaskSpec},
    geometry::Rect,
    temporal_region::Ellipse,
};
pub const REGION_OK: i32 = 0;
pub const REGION_NONE: i32 = 1;
pub const REGION_INVALID: i32 = -1;

/// # Safety
/// Row is18 readable aligned floats; nonnull view is one readable Rect; output
/// is one disjoint writable aligned ellipse. Pointers are borrowed for this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_detected_face_region(
    row: *const f32,
    view: *const Rect,
    size: f32,
    yaw: f32,
    output: *mut Ellipse,
) -> i32 {
    if row.is_null() || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies the documented readable18-float record.
    let Some(face) = crate::ffi_face::decode(unsafe { std::slice::from_raw_parts(row, 18) }) else {
        return REGION_INVALID;
    };
    let view = if view.is_null() {
        None
    } else {
        // SAFETY: a nonnull view points to one readable aligned Rect.
        Some((unsafe { *view }, size))
    };
    let Some(region) = face_geometry::detected(&face, view, yaw) else {
        return REGION_INVALID;
    };
    // SAFETY: caller supplies a disjoint writable aligned ellipse.
    unsafe { output.write(region) };
    REGION_OK
}
/// # Safety
/// Output points to one disjoint writable aligned ellipse.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_head_fallback(person: Rect, output: *mut Ellipse) -> i32 {
    if output.is_null() {
        return REGION_INVALID;
    }
    let Some(region) = face_geometry::fallback(person) else {
        return REGION_INVALID;
    };
    // SAFETY: caller supplies a disjoint writable aligned ellipse.
    unsafe { output.write(region) };
    REGION_OK
}
/// # Safety
/// Pixels are readable for len bytes. Output is two disjoint writable aligned
/// floats. Returns REGION_OK only after both output values have been written.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_mask_head(
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    seed: Ellipse,
    output: *mut f32,
) -> i32 {
    if len > 1024 * 1024 || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller guarantees the documented readable byte range.
    let Some(pixels) = (unsafe { crate::ffi_tracking::borrowed(pixels, len) }) else {
        return REGION_INVALID;
    };
    if !spec.valid(pixels) || !face_geometry::valid_seed(seed) {
        return REGION_INVALID;
    }
    let Some(center) = face_geometry::estimate(pixels, spec, seed) else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies two disjoint writable aligned floats.
    unsafe { std::ptr::copy_nonoverlapping(center.as_ptr(), output, 2) };
    REGION_OK
}
/// # Safety
/// Pixels are readable for len bytes; output is a disjoint writable ellipse.
/// Returns REGION_OK only after the complete ellipse has been written.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_trusted_mask_region(
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    previous: Rect,
    trusted: Ellipse,
    expansion: f32,
    output: *mut Ellipse,
) -> i32 {
    if len > 1024 * 1024 || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller guarantees the documented readable byte range.
    let Some(pixels) = (unsafe { crate::ffi_tracking::borrowed(pixels, len) }) else {
        return REGION_INVALID;
    };
    if !spec.valid(pixels)
        || !face_geometry::valid_seed(trusted)
        || !previous.valid()
        || !expansion.is_finite()
        || expansion <= 0.0
    {
        return REGION_INVALID;
    }
    let Some(region) = face_geometry::trusted_fallback(pixels, spec, previous, trusted, expansion)
    else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies one disjoint writable aligned ellipse.
    unsafe { output.write(region) };
    REGION_OK
}

/// # Safety
/// Output points to one disjoint writable aligned ellipse. Invalid input never writes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_project_face_region(
    current: Rect,
    previous: Rect,
    trusted: Ellipse,
    age_us: i64,
    output: *mut Ellipse,
) -> i32 {
    if output.is_null() {
        return REGION_INVALID;
    }
    let Some(region) = face_geometry::project_trusted(
        current,
        previous,
        trusted,
        age_us,
        face_geometry::ProjectionOptions::default(),
    ) else {
        return REGION_INVALID;
    };
    // SAFETY: caller supplies the documented disjoint aligned output.
    unsafe { output.write(region) };
    REGION_OK
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geometry::Transform;
    #[test]
    fn projection_abi_initializes_success_and_preserves_invalid_output() {
        let body = Rect {
            left: 10.0,
            top: 20.0,
            right: 110.0,
            bottom: 320.0,
        };
        let seed = Ellipse {
            center_x: 60.0,
            center_y: 65.0,
            radius_x: 20.0,
            radius_y: 30.0,
        };
        let mut output = Ellipse::default();
        // SAFETY: disjoint live aligned output for this call.
        let status =
            unsafe { frame_kernel_project_face_region(body, body, seed, 150_000, &mut output) };
        assert_eq!(status, REGION_OK);
        assert_eq!(output.radius_x, 22.0);
        let saved = output;
        // SAFETY: live output; invalid body must leave it unchanged.
        let status = unsafe {
            frame_kernel_project_face_region(Rect::default(), body, seed, 0, &mut output)
        };
        assert_eq!(status, REGION_INVALID);
        assert_eq!(output, saved);
    }
    #[test]
    fn success_zero_and_nonzero_failure_have_distinct_output_contracts() {
        let body = Rect {
            left: 200.0,
            top: 0.0,
            right: 440.0,
            bottom: 600.0,
        };
        let mut region = Ellipse::default();
        // SAFETY: output is live disjoint aligned storage.
        let ok = unsafe { frame_kernel_head_fallback(body, &mut region) };
        assert_eq!(ok, REGION_OK);
        assert!(region.radius_x > 0.0 && region.radius_y > 0.0);
        let saved = region;
        // SAFETY: output is live disjoint aligned storage, including the invalid-input path.
        let invalid = unsafe { frame_kernel_head_fallback(Rect::default(), &mut region) };
        assert_eq!(invalid, REGION_INVALID);
        assert_eq!(region, saved);
        let spec = HeadMaskSpec {
            width: 160,
            height: 160,
            person: body,
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
        let seed = Ellipse {
            center_x: 320.0,
            center_y: 100.0,
            radius_x: 30.0,
            radius_y: 40.0,
        };
        let mut pixels = vec![0; 25600];
        let mut center = [-7.0, -9.0];
        // SAFETY: readable byte storage and two disjoint writable aligned floats are supplied.
        let empty = unsafe {
            frame_kernel_mask_head(
                pixels.as_ptr(),
                pixels.len(),
                spec,
                seed,
                center.as_mut_ptr(),
            )
        };
        assert_eq!(empty, REGION_NONE);
        assert_eq!(center, [-7.0, -9.0]);
        for y in 20..32 {
            for x in 76..84 {
                pixels[y * 160 + x] = 255
            }
        }
        // SAFETY: the same live readable and writable buffers satisfy the ABI contract.
        let found = unsafe {
            frame_kernel_mask_head(
                pixels.as_ptr(),
                pixels.len(),
                spec,
                seed,
                center.as_mut_ptr(),
            )
        };
        assert_eq!(found, REGION_OK);
        assert!(center.iter().all(|v| v.is_finite() && *v > 0.0));
        // SAFETY: the live output is disjoint from the readable input pixels.
        let trusted = unsafe {
            frame_kernel_trusted_mask_region(
                pixels.as_ptr(),
                pixels.len(),
                spec,
                body,
                seed,
                1.1,
                &mut region,
            )
        };
        assert_eq!(trusted, REGION_OK);
        assert!(region.radius_x > seed.radius_x);
    }
}
