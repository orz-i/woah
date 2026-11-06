use crate::association::MatchPair;
use crate::association_score::{AssociationEvidence, AssociationWeights};
use crate::box_filter::BoxFilter;
use crate::camera::FollowState;
use crate::instances::{InstanceDetection, InstanceMetrics, InstanceSpec, InstanceThresholds};
use crate::pixels::{Letterbox, PixelSpec};
use crate::yuv::YuvSpec;
use crate::{
    ABI_VERSION,
    geometry::{self, Rect, Transform},
};

/// Source context probe timestamp, or -1 for a zero/invalid request.
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_region_context_probe_us(timestamp_us: i64) -> i64 {
    crate::region_continuation::context_probe_us(timestamp_us).unwrap_or(-1)
}

/// # Safety
/// Inputs are readable aligned values. Output is disjoint writable storage.
/// Return total records (history then current), -1 invalid, <=-2 capacity query.
/// No caller pointers are retained and failures never partially write output.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_continue_regions(
    history: *const crate::temporal_region::RegionMeasurement,
    history_len: usize,
    current: *const crate::temporal_region::RegionMeasurement,
    current_len: usize,
    now: i64,
    enabled: u32,
    output: *mut crate::temporal_region::RegionMeasurement,
    capacity: usize,
) -> i32 {
    if history_len > 1024
        || current_len > 1024
        || enabled > 1
        || (history_len > 0 && history.is_null())
        || (current_len > 0 && current.is_null())
    {
        return -1;
    }
    // SAFETY: bounded readable/aligned input storage is the caller's contract.
    let old = if history_len == 0 {
        &[]
    } else {
        // SAFETY: caller provides readable aligned history_len entries.
        unsafe { std::slice::from_raw_parts(history, history_len) }
    };
    // SAFETY: bounded readable/aligned input storage is the caller's contract.
    let fresh = if current_len == 0 {
        &[]
    } else {
        // SAFETY: caller provides readable aligned current_len entries.
        unsafe { std::slice::from_raw_parts(current, current_len) }
    };
    let Some(result) = crate::region_continuation::step(old, fresh, now, enabled == 1) else {
        return -1;
    };
    if capacity < result.len() {
        return -(result.len() as i32) - 1;
    }
    if !result.is_empty() {
        if output.is_null() {
            return -1;
        }
        // SAFETY: caller supplies disjoint output for capacity measurement entries.
        unsafe {
            std::ptr::copy_nonoverlapping(result.as_ptr(), output, result.len());
        }
    }
    result.len() as i32
}

/// # Safety
/// All ID slices are readable/aligned and output has capacity disjoint entries.
/// Returns count; -1 invalid; <=-2 insufficient capacity, need -(result+1).
/// No partial writes on failure and no caller pointers are retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_scene_targets(
    roots: *const i64,
    roots_len: usize,
    body: *const i64,
    body_len: usize,
    face: *const i64,
    face_len: usize,
    current: *const i64,
    current_len: usize,
    has_hero: u32,
    hero: i64,
    output: *mut crate::protection::SceneTarget,
    capacity: usize,
) -> i32 {
    // SAFETY: slice construction is bounded by the shared checked ID helper.
    let (Some(roots), Some(body), Some(face), Some(current)) = (unsafe {
        (
            id_slice(roots, roots_len),
            id_slice(body, body_len),
            id_slice(face, face_len),
            id_slice(current, current_len),
        )
    }) else {
        return -1;
    };
    if has_hero > 1 {
        return -1;
    }
    let targets = crate::protection::scene_targets(
        roots,
        body,
        face,
        current,
        (has_hero == 1).then_some(hero),
    );
    if capacity < targets.len() {
        return -(targets.len() as i32) - 1;
    }
    if !targets.is_empty() {
        if output.is_null() {
            return -1;
        }
        // SAFETY: caller supplies disjoint storage for capacity target entries.
        unsafe {
            std::ptr::copy_nonoverlapping(targets.as_ptr(), output, targets.len());
        }
    }
    targets.len() as i32
}

/// # Safety
/// State and output are writable, aligned, nonoverlapping caller-owned values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_region_step(
    state: *mut crate::temporal_region::RegionState,
    measurement: crate::temporal_region::RegionMeasurement,
    output: *mut crate::temporal_region::Ellipse,
) -> i32 {
    if state.is_null() || output.is_null() {
        return -1;
    }
    // SAFETY: both pointers are checked; alignment/lifetime are caller contracts.
    let mut next = unsafe { state.read() };
    let Some(region) = next.step(measurement) else {
        return -1;
    };
    // SAFETY: output buffers are disjoint and no references are retained.
    unsafe {
        state.write(next);
        output.write(region);
    }
    0
}

/// # Safety
/// Output points to two writable aligned floats; no data is retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_anchor_translation(
    previous: Rect,
    current: Rect,
    output: *mut f32,
) -> i32 {
    if output.is_null() {
        return -1;
    }
    let Some(value) = crate::temporal_region::anchor_translation(previous, current) else {
        return -1;
    };
    // SAFETY: caller supplies space for two aligned floats.
    unsafe {
        output.write(value[0]);
        output.add(1).write(value[1]);
    }
    0
}

unsafe fn id_slice<'a>(pointer: *const i64, len: usize) -> Option<&'a [i64]> {
    if len > 100_000 || (len > 0 && pointer.is_null()) {
        return None;
    }
    if len == 0 {
        Some(&[])
    } else {
        // SAFETY: the FFI caller supplies readable aligned input for len IDs.
        Some(unsafe { std::slice::from_raw_parts(pointer, len) })
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_association_defaults() -> AssociationWeights {
    AssociationWeights::default()
}

/// Returns 1 for release, 0 for quarantine, -1 for invalid evidence.
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_reservation_release(
    flags: u32,
    owner_groups: u32,
    bbox_iou: f32,
    mask_iou: f32,
) -> i32 {
    crate::association_score::reservation_release(flags, owner_groups, bbox_iou, mask_iou)
        .map_or(-1, i32::from)
}

/// # Safety
/// output points to one writable aligned float and does not alias input data.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_association_score(
    evidence: AssociationEvidence,
    weights: AssociationWeights,
    output: *mut f32,
) -> i32 {
    if output.is_null() {
        return -1;
    }
    let Some(score) = crate::association_score::score(evidence, weights) else {
        return -1;
    };
    // SAFETY: caller supplies one aligned writable float; all inputs validated.
    unsafe {
        output.write(score);
    }
    0
}

/// # Safety
/// Nonempty ID buffers are readable/aligned for the declared lengths.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_scene_scope(
    roots: *const i64,
    roots_len: usize,
    body: *const i64,
    body_len: usize,
    face: *const i64,
    face_len: usize,
    has_hero: u32,
    hero: i64,
) -> i32 {
    if has_hero > 1 {
        return -1;
    }
    // SAFETY: input lifetimes and lengths are supplied by the caller.
    let (Some(roots), Some(body), Some(face)) = (unsafe {
        (
            id_slice(roots, roots_len),
            id_slice(body, body_len),
            id_slice(face, face_len),
        )
    }) else {
        return -1;
    };
    crate::protection::scene_scope(
        roots,
        body,
        face,
        if has_hero == 1 { Some(hero) } else { None },
    ) as i32
}

/// # Safety
/// Nonempty ID buffers are readable/aligned. Output is writable/aligned for
/// capacity IDs and disjoint from input. No pointers are retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_compositor_targets(
    selected: *const i64,
    selected_len: usize,
    covered: *const i64,
    covered_len: usize,
    explicit: *const i64,
    explicit_len: usize,
    output: *mut i64,
    capacity: usize,
) -> i32 {
    // SAFETY: input lifetimes and lengths are supplied by the caller.
    let (Some(selected), Some(covered), Some(explicit)) = (unsafe {
        (
            id_slice(selected, selected_len),
            id_slice(covered, covered_len),
            id_slice(explicit, explicit_len),
        )
    }) else {
        return -1;
    };
    let ids = crate::protection::compositor_targets(selected, covered, explicit);
    if capacity < ids.len() {
        return -(ids.len() as i32) - 1;
    }
    if !ids.is_empty() {
        if output.is_null() {
            return -1;
        }
        // SAFETY: caller provides a disjoint writable output; size is checked.
        unsafe {
            std::ptr::copy_nonoverlapping(ids.as_ptr(), output, ids.len());
        }
    }
    ids.len() as i32
}

/// # Safety
/// Nonempty detections are aligned/readable. -1 means no supported exemption;
/// -2 means invalid input. A nonnegative result is the only exempted index.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_current_hero_index(
    detections: *const Rect,
    len: usize,
    has_observed: u32,
    observed: Rect,
) -> i32 {
    if len > 100_000 || (len > 0 && detections.is_null()) || has_observed > 1 {
        return -2;
    }
    let finite = |r: &Rect| {
        [r.left, r.top, r.right, r.bottom]
            .iter()
            .all(|x| x.is_finite())
    };
    let detections = if len == 0 {
        &[]
    } else {
        // SAFETY: caller supplies a readable aligned array of len rectangles.
        unsafe { std::slice::from_raw_parts(detections, len) }
    };
    if detections.iter().any(|r| !finite(r)) || (has_observed == 1 && !finite(&observed)) {
        return -2;
    }
    crate::protection::current_hero_index(
        detections,
        if has_observed == 1 {
            Some(observed)
        } else {
            None
        },
    )
    .map_or(-1, |index| index as i32)
}

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
/// state, bounds_out and distance_out are aligned writable, pairwise disjoint
/// objects. State is fully initialized. No pointer is retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_box_filter_step(
    state: *mut BoxFilter,
    operation: u32,
    bounds: Rect,
    pts_us: i64,
    factor: f32,
    bounds_out: *mut Rect,
    distance_out: *mut f32,
) -> i32 {
    if state.is_null() || bounds_out.is_null() || distance_out.is_null() || operation > 5 {
        return -1;
    }
    // SAFETY: caller supplies one initialized aligned state; mutate locally until valid.
    let mut next = unsafe { state.read() };
    let finite = |r: Rect| {
        [r.left, r.top, r.right, r.bottom]
            .iter()
            .all(|x| x.is_finite())
    };
    if (operation != 0 && !next.valid())
        || ([0, 2, 4].contains(&operation) && !finite(bounds))
        || (operation == 3 && !factor.is_finite())
    {
        return -1;
    }
    let mut distance = 0.0;
    match operation {
        0 => next.init(bounds, pts_us),
        1 => {
            next.predict(pts_us);
        }
        2 => next.update(bounds, pts_us),
        3 => next.dampen(factor),
        4 => distance = next.gating_distance(bounds),
        _ => {}
    }
    let output = next.bounds();
    if !next.valid() || !finite(output) || !distance.is_finite() {
        return -1;
    }
    // SAFETY: all caller-owned outputs are validated and disjoint.
    unsafe {
        state.write(next);
        bounds_out.write(output);
        distance_out.write(distance);
    }
    0
}

/// # Safety
/// input/output point to 16 aligned readable/writable floats and do not overlap.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_invert_matrix4(input: *const f32, output: *mut f32) -> i32 {
    if input.is_null() || output.is_null() {
        return -1;
    }
    // SAFETY: caller supplies 16 readable aligned floats.
    let input = unsafe { std::slice::from_raw_parts(input, 16) };
    if input.iter().any(|x| !x.is_finite()) {
        return -1;
    }
    let matrix = std::array::from_fn(|r| std::array::from_fn(|c| input[r * 4 + c]));
    let Some(inv) = crate::box_filter::invert4(matrix) else {
        return 1;
    };
    if inv.iter().flatten().any(|x| !x.is_finite()) {
        return -1;
    }
    // SAFETY: caller supplies 16 writable aligned floats, disjoint from input.
    unsafe {
        std::ptr::copy_nonoverlapping(inv.as_ptr().cast::<f32>(), output, 16);
    }
    0
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

/// Native worker completion barrier; -1 rejects an invalid state or event.
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_work_transition(state: u32, event: u32) -> i32 {
    crate::work_lifecycle::transition(state, event).map_or(-1, |s| s as i32)
}

/// -1 invalid; otherwise 1 permits a serialized replacement and 0 rejects it.
#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_work_can_replace(state: u32) -> i32 {
    crate::work_lifecycle::can_replace(state).map_or(-1, i32::from)
}
