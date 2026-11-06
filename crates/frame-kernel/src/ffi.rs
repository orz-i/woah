use crate::association::MatchPair;
use crate::camera::FollowState;
use crate::instances::{InstanceDetection, InstanceMetrics, InstanceSpec, InstanceThresholds};
use crate::pixels::{Letterbox, PixelSpec};
use crate::yuv::YuvSpec;
use crate::{
    ABI_VERSION,
    geometry::{self, Rect, Transform},
};

unsafe fn write_matches(pairs: &[MatchPair], output: *mut MatchPair, capacity: usize) -> i32 {
    if capacity < pairs.len() {
        return -(pairs.len() as i32) - 1;
    }
    if pairs.is_empty() {
        return 0;
    }
    if output.is_null() {
        return -1;
    }
    // SAFETY: caller supplies aligned writable output, disjoint from the owned
    // local pairs vector. Capacity and nonnull pointer were checked above.
    unsafe {
        std::ptr::copy_nonoverlapping(pairs.as_ptr(), output, pairs.len());
    }
    pairs.len() as i32
}

/// # Safety
/// output points to one writable aligned Letterbox.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_letterbox(
    width: u32,
    height: u32,
    ow: u32,
    oh: u32,
    output: *mut Letterbox,
) -> i32 {
    let Some(plan) = crate::pixels::letterbox(width, height, ow, oh) else {
        return -1;
    };
    if output.is_null() {
        return -1;
    }
    // SAFETY: caller supplies a live aligned output; no pointers retained.
    unsafe {
        output.write(plan);
    }
    0
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct PlaneView {
    pub data: *const u8,
    pub len: usize,
    pub row_stride: u32,
    pub pixel_stride: u32,
}

/// # Safety
/// Input planes are readable for declared lengths (they may overlap each other).
/// Output is writable for capacity bytes and disjoint from every input plane.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_yuv420_rgba(
    y: PlaneView,
    u: PlaneView,
    v: PlaneView,
    spec: YuvSpec,
    output: *mut u8,
    capacity: usize,
) -> i32 {
    if [y, u, v].iter().any(|p| p.data.is_null() || p.len == 0) || output.is_null() || capacity == 0
    {
        return -1;
    }
    let view = |p: PlaneView| {
        // SAFETY: caller provides a readable plane of len bytes, no retention.
        crate::yuv::Plane {
            // SAFETY: caller supplies a readable plane of the declared length.
            bytes: unsafe { std::slice::from_raw_parts(p.data, p.len) },
            row_stride: p.row_stride,
            pixel_stride: p.pixel_stride,
        }
    };
    let (y, u, v) = (view(y), view(u), view(v));
    // SAFETY: caller provides disjoint writable output of capacity bytes.
    let out = unsafe { std::slice::from_raw_parts_mut(output, capacity) };
    if crate::yuv::convert([&y, &u, &v], spec, out).is_some() {
        0
    } else {
        -1
    }
}

/// # Safety
/// Nonempty buffers must be aligned, valid and disjoint as declared.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_normalize_pixels(
    input: *const u8,
    len: usize,
    spec: PixelSpec,
    output: *mut f32,
    capacity: usize,
) -> i32 {
    if input.is_null() || output.is_null() || len == 0 || capacity == 0 {
        return -1;
    }
    // SAFETY: caller supplies readable/writable disjoint buffers of these lengths.
    let (input, output) = unsafe {
        (
            std::slice::from_raw_parts(input, len),
            std::slice::from_raw_parts_mut(output, capacity),
        )
    };
    if crate::pixels::normalize(input, spec, output).is_some() {
        0
    } else {
        -1
    }
}

/// # Safety
/// Nonempty buffers must be valid and disjoint as declared. Optional plan points
/// to one aligned writable Letterbox, disjoint from both buffers.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_resize_rgba(
    input: *const u8,
    len: usize,
    width: u32,
    height: u32,
    stride: u32,
    output: *mut u8,
    capacity: usize,
    ow: u32,
    oh: u32,
    fill: u8,
    plan: *mut Letterbox,
) -> i32 {
    if input.is_null() || output.is_null() || len == 0 || capacity == 0 {
        return -1;
    }
    // SAFETY: caller supplies readable/writable disjoint buffers of these lengths.
    let (input, output) = unsafe {
        (
            std::slice::from_raw_parts(input, len),
            std::slice::from_raw_parts_mut(output, capacity),
        )
    };
    let Some(value) =
        crate::pixels::resize_rgba(input, width, height, stride, output, ow, oh, fill)
    else {
        return -1;
    };
    if !plan.is_null() {
        // SAFETY: caller supplies an aligned writable optional plan.
        unsafe {
            plan.write(value);
        }
    }
    0
}

/// # Safety
/// Nonempty buffers are aligned, readable/writable as declared and disjoint.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_match_costs(
    costs: *const f32,
    len: usize,
    rows: u32,
    columns: u32,
    max_cost: f32,
    output: *mut MatchPair,
    capacity: usize,
) -> i32 {
    if crate::association::dimensions(rows, columns) != Some(len) || (len > 0 && costs.is_null()) {
        return -1;
    }
    let costs = if len == 0 {
        &[]
    } else {
        // SAFETY: caller supplies a readable aligned input of validated length.
        unsafe { std::slice::from_raw_parts(costs, len) }
    };
    let Some(pairs) = crate::association::match_costs(costs, rows, columns, max_cost) else {
        return -1;
    };
    // SAFETY: output contract is supplied by caller; helper checks size/null.
    unsafe { write_matches(&pairs, output, capacity) }
}

/// # Safety
/// Nonempty buffers are aligned, readable/writable as declared and disjoint.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_match_rects(
    rows: *const Rect,
    nr: u32,
    columns: *const Rect,
    nc: u32,
    max_cost: f32,
    output: *mut MatchPair,
    capacity: usize,
) -> i32 {
    if crate::association::dimensions(nr, nc).is_none()
        || (nr > 0 && rows.is_null())
        || (nc > 0 && columns.is_null())
    {
        return -1;
    }
    let rows = if nr == 0 {
        &[]
    } else {
        // SAFETY: caller supplies a readable aligned array of nr Rect values.
        unsafe { std::slice::from_raw_parts(rows, nr as usize) }
    };
    let columns = if nc == 0 {
        &[]
    } else {
        // SAFETY: caller supplies a readable aligned array of nc Rect values.
        unsafe { std::slice::from_raw_parts(columns, nc as usize) }
    };
    let Some(pairs) = crate::association::match_rects(rows, columns, max_cost) else {
        return -1;
    };
    // SAFETY: output contract is supplied by caller; helper checks size/null.
    unsafe { write_matches(&pairs, output, capacity) }
}

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
