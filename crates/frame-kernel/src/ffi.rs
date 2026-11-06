use crate::{
    ABI_VERSION,
    geometry::{self, Rect, Transform},
};

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_abi_version() -> u32 {
    ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_protection_layers(body: i32, face: i32, sticker: i32) -> u32 {
    crate::protection::layers(body != 0, face != 0, sticker != 0)
}

/// # Safety
/// Nonempty inputs and output are valid, aligned and nonoverlapping. Output
/// capacity is at least body_len + face_len. Zero-length inputs may be null.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_targets(
    body: *const i64,
    body_len: usize,
    face: *const i64,
    face_len: usize,
    output: *mut i64,
    capacity: usize,
) -> i32 {
    let Some(total) = body_len.checked_add(face_len) else {
        return -1;
    };
    if total > 65536
        || capacity < total
        || (total > 0 && output.is_null())
        || (body_len > 0 && body.is_null())
        || (face_len > 0 && face.is_null())
    {
        return -1;
    }
    let bodies = if body_len == 0 {
        &[]
    } else {
        // SAFETY: caller supplies a readable aligned input of checked length.
        unsafe { std::slice::from_raw_parts(body, body_len) }
    };
    let faces = if face_len == 0 {
        &[]
    } else {
        // SAFETY: caller supplies a readable aligned input of checked length.
        unsafe { std::slice::from_raw_parts(face, face_len) }
    };
    let targets = crate::protection::face_targets(bodies, faces);
    if !targets.is_empty() {
        // SAFETY: output is writable, disjoint and large enough (validated).
        unsafe {
            std::ptr::copy_nonoverlapping(targets.as_ptr(), output, targets.len());
        }
    }
    targets.len() as i32
}

/// # Safety
/// `output` must point to at least four writable, aligned Rect values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_detail_views(
    width: u32,
    height: u32,
    output: *mut Rect,
) -> i32 {
    let Some(views) = geometry::detail_views(width, height) else {
        return -1;
    };
    if output.is_null() {
        return -1;
    }
    // SAFETY: caller supplies a valid four-element output; null checked above.
    unsafe {
        std::ptr::copy_nonoverlapping(views.as_ptr(), output, 4);
    }
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_detail_complete(
    box_: Rect,
    view: Rect,
    width: u32,
    height: u32,
) -> i32 {
    i32::from(geometry::detail_complete(box_, view, width, height))
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_iou(a: Rect, b: Rect) -> f32 {
    geometry::iou(a, b)
}

/// # Safety
/// Input/output point to nonoverlapping initialized buffers of their specified
/// lengths. Output is writable. No pointer is retained after this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_reproject_mask(
    mask: *const u8,
    mask_len: usize,
    local: Transform,
    scene: Transform,
    view: Rect,
    output: *mut u8,
    output_len: usize,
) -> i32 {
    if !local.valid()
        || !scene.valid()
        || mask.is_null()
        || output.is_null()
        || mask_len != (local.proto_size * local.proto_size) as usize
        || output_len != (scene.proto_size * scene.proto_size) as usize
    {
        return -1;
    }
    // SAFETY: caller guarantees valid, nonoverlapping buffers; lengths checked.
    let input = unsafe { std::slice::from_raw_parts(mask, mask_len) };
    // SAFETY: caller guarantees exclusive writable output of the checked length.
    let result = unsafe { std::slice::from_raw_parts_mut(output, output_len) };
    if geometry::reproject_mask(input, local, scene, view, result) {
        0
    } else {
        -1
    }
}

/// # Safety
/// Input and exclusive output are valid nonoverlapping buffers for the given
/// lengths. No pointer is retained after this call.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn frame_kernel_project_mask(
    mask: *const u8,
    mask_len: usize,
    transform: Transform,
    source_width: u32,
    source_height: u32,
    width: u32,
    height: u32,
    crop: Rect,
    output: *mut u8,
    output_len: usize,
) -> i32 {
    if !transform.valid()
        || mask.is_null()
        || output.is_null()
        || mask_len != (transform.proto_size * transform.proto_size) as usize
        || width == 0
        || height == 0
        || width > 16384
        || height > 16384
        || u64::from(width) * u64::from(height) > 64 * 1024 * 1024
        || output_len != width as usize * height as usize
    {
        return -1;
    }
    // SAFETY: caller supplies initialized input with the checked length.
    let input = unsafe { std::slice::from_raw_parts(mask, mask_len) };
    // SAFETY: caller supplies exclusive, nonoverlapping output of checked length.
    let result = unsafe { std::slice::from_raw_parts_mut(output, output_len) };
    if geometry::project_mask(
        input,
        transform,
        source_width,
        source_height,
        width,
        height,
        crop,
        result,
    ) {
        0
    } else {
        -1
    }
}
