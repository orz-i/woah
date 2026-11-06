use crate::{
    ABI_VERSION,
    geometry::{self, Rect, Transform},
};

#[unsafe(no_mangle)]
pub extern "C" fn woah_core_abi_version() -> u32 {
    ABI_VERSION
}

/// # Safety
/// `output` must point to at least four writable, aligned Rect values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn woah_core_detail_views(width: u32, height: u32, output: *mut Rect) -> i32 {
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
pub extern "C" fn woah_core_detail_complete(
    box_: Rect,
    view: Rect,
    width: u32,
    height: u32,
) -> i32 {
    i32::from(geometry::detail_complete(box_, view, width, height))
}

#[unsafe(no_mangle)]
pub extern "C" fn woah_core_iou(a: Rect, b: Rect) -> f32 {
    geometry::iou(a, b)
}

/// # Safety
/// Input/output point to nonoverlapping initialized buffers of their specified
/// lengths. Output is writable. No pointer is retained after this call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn woah_core_reproject_mask(
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
