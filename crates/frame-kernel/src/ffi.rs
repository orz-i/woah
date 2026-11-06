use crate::camera::FollowState;
use crate::instances::{InstanceDetection, InstanceMetrics, InstanceSpec, InstanceThresholds};
use crate::{
    ABI_VERSION,
    geometry::{self, Rect, Transform},
};

/// # Safety
/// state points to one writable, aligned FollowState.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_follow_reset(state: *mut FollowState) -> i32 {
    if state.is_null() {
        return -1;
    }
    // SAFETY: caller supplies one writable, aligned state; no pointers retained.
    unsafe {
        state.write(FollowState::default());
    }
    0
}

/// # Safety
/// state and output point to valid aligned, writable, nonoverlapping objects.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_follow_step(
    state: *mut FollowState,
    target: Rect,
    has_target: i32,
    pts_us: i64,
    source_aspect: f32,
    output_aspect: f32,
    zoom: f32,
    smooth: f32,
    output: *mut Rect,
) -> i32 {
    if state.is_null() || output.is_null() || !(0..=1).contains(&has_target) {
        return -1;
    }
    // SAFETY: caller supplies one live aligned state; work locally until valid.
    let mut next = unsafe { state.read() };
    let Some(crop) = next.step(
        if has_target == 1 { Some(target) } else { None },
        pts_us,
        source_aspect,
        output_aspect,
        zoom,
        smooth,
    ) else {
        return -1;
    };
    // SAFETY: outputs are writable, aligned, disjoint and have the stated sizes.
    unsafe {
        state.write(next);
        output.write(crop);
    }
    0
}

/// # Safety
/// output points to two writable aligned u32 values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_fit_even_ratio(
    width: u32,
    height: u32,
    max_height: u32,
    ratio_w: u32,
    ratio_h: u32,
    output: *mut u32,
) -> i32 {
    let Some((w, h)) = crate::camera::fit_even_ratio(width, height, max_height, ratio_w, ratio_h)
    else {
        return -1;
    };
    if output.is_null() {
        return -1;
    }
    // SAFETY: caller supplies two writable aligned u32 values; null checked.
    unsafe {
        output.write(w);
        output.add(1).write(h);
    }
    0
}

/// # Safety
/// Nonempty buffers must be valid, aligned, disjoint and have the stated lengths.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_mask_bytes(
    input: *const f32,
    len: usize,
    output: *mut u8,
    output_len: usize,
) -> i32 {
    if len > 64_000_000 || output_len < len || (len > 0 && (input.is_null() || output.is_null())) {
        return -1;
    }
    if len == 0 {
        return 0;
    }
    // SAFETY: caller provides disjoint buffers of checked nonzero lengths.
    let (input, output) = unsafe {
        (
            std::slice::from_raw_parts(input, len),
            std::slice::from_raw_parts_mut(output, len),
        )
    };
    for (value, byte) in input.iter().zip(output.iter_mut()) {
        *byte = crate::instances::mask_byte(*value);
    }
    0
}

/// Returns count on success, -1 for invalid tensors/buffers, or -(required+1)
/// when output capacity is insufficient. No partial detection/mask writes.
/// # Safety
/// Nonempty input/output buffers must be valid, aligned, disjoint and sized as
/// declared. metrics may be null or point to one writable InstanceMetrics.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_decode_instances(
    head: *const f32,
    head_len: usize,
    proto: *const f32,
    proto_len: usize,
    spec: InstanceSpec,
    transform: Transform,
    thresholds: InstanceThresholds,
    detections: *mut InstanceDetection,
    detection_capacity: usize,
    masks: *mut u8,
    mask_capacity: usize,
    metrics: *mut InstanceMetrics,
) -> i32 {
    if spec.lengths(transform) != Some((head_len, proto_len)) || head.is_null() || proto.is_null() {
        return -1;
    }
    // SAFETY: caller supplies readable, aligned buffers of validated dimensions.
    let (head, proto) = unsafe {
        (
            std::slice::from_raw_parts(head, head_len),
            std::slice::from_raw_parts(proto, proto_len),
        )
    };
    let Ok((instances, measurements)) =
        crate::instances::decode_instances(head, proto, spec, transform, thresholds)
    else {
        return -1;
    };
    if !metrics.is_null() {
        // SAFETY: caller supplies one writable aligned metrics struct, if nonnull.
        unsafe {
            metrics.write(measurements);
        }
    }
    let count = instances.len();
    let pixels = (transform.proto_size as usize).pow(2);
    if detection_capacity < count || mask_capacity < count * pixels {
        return -(count as i32) - 1;
    }
    if count > 0 && (detections.is_null() || masks.is_null()) {
        return -1;
    }
    for (i, instance) in instances.iter().enumerate() {
        // SAFETY: output capacities were checked; buffers are writable, disjoint
        // from inputs and from each other, and each mask has exactly pixels bytes.
        unsafe {
            detections.add(i).write(instance.detection);
            std::ptr::copy_nonoverlapping(instance.mask.as_ptr(), masks.add(i * pixels), pixels);
        }
    }
    count as i32
}

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
