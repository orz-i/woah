//! Uniform region ABI:0 success,1 no evidence,-1 invalid. Failures never write.
use crate::{
    face_geometry::{self, HeadMaskSpec},
    geometry::{Rect, Transform},
    temporal_region::Ellipse,
};
pub const REGION_OK: i32 = 0;
pub const REGION_NONE: i32 = 1;
pub const REGION_INVALID: i32 = -1;

/// Research-only current complete cap. No historical face/pose is accepted.
/// # Safety
/// Packed masks, six shoulder floats, and count ellipses are readable.
/// Output is disjoint and writable. Nonzero leaves output unchanged.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_tracklet_head(
    masks: *const u8,
    len: usize,
    count: usize,
    index: u32,
    spec: HeadMaskSpec,
    shoulders: *const f32,
    faces: *const Ellipse,
    out: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    if !(2..=32).contains(&count) || len > 32 * 1024 * 1024 || out.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies bounded readable arrays.
    let (Some(m), Some(p), Some(f)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(masks, len),
            crate::ffi_tracking::borrowed(shoulders, 6),
            crate::ffi_tracking::borrowed(faces, count),
        )
    }) else {
        return REGION_INVALID;
    };
    let Some(v) = crate::face_head_topology::tracklet_current_head(
        m,
        index as usize,
        count,
        spec,
        p.try_into().unwrap(),
        f,
    ) else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies disjoint writable output.
    unsafe { out.write(v) };
    REGION_OK
}

/// # Safety
/// Readable packed current masks, twelve ear/shoulder floats and count
/// face measurements; one disjoint writable sticker. No write on failure.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_tracklet_ear_cap(
    masks: *const u8,
    len: usize,
    count: usize,
    index: u32,
    spec: HeadMaskSpec,
    pose: *const f32,
    faces: *const Ellipse,
    out: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    if !(2..=32).contains(&count) || len > 32 * 1024 * 1024 || out.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller provides bounded readable inputs.
    let (Some(m), Some(p), Some(f)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(masks, len),
            crate::ffi_tracking::borrowed(pose, 12),
            crate::ffi_tracking::borrowed(faces, count),
        )
    }) else {
        return REGION_INVALID;
    };
    let Some(v) = crate::face_head_topology::tracklet_ear_cap(
        m,
        index as usize,
        count,
        spec,
        p.try_into().unwrap(),
        f,
    ) else {
        return REGION_NONE;
    };
    // SAFETY: caller provides one disjoint writable output.
    unsafe { out.write(v) };
    REGION_OK
}

/// # Safety
/// Readable packed current masks, 24 floats for current original and mapped
/// flipped ear/shoulder poses, count faces; disjoint writable sticker.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_tracklet_flip_ear_cap(
    masks: *const u8,
    len: usize,
    count: usize,
    index: u32,
    spec: HeadMaskSpec,
    poses: *const f32,
    faces: *const Ellipse,
    out: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    if !(2..=32).contains(&count) || len > 32 * 1024 * 1024 || out.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller provides bounded readable inputs.
    let (Some(m), Some(p), Some(f)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(masks, len),
            crate::ffi_tracking::borrowed(poses, 24),
            crate::ffi_tracking::borrowed(faces, count),
        )
    }) else {
        return REGION_INVALID;
    };
    let Some(v) = crate::face_head_topology::tracklet_flip_ear_cap(
        m,
        index as usize,
        count,
        spec,
        [p[..12].try_into().unwrap(), p[12..].try_into().unwrap()],
        f,
    ) else {
        return REGION_NONE;
    };
    // SAFETY: caller provides one disjoint writable output.
    unsafe { out.write(v) };
    REGION_OK
}

/// # Safety
/// Packed current masks, two mapped current pose7 arrays (42 floats), count
/// faces, and one disjoint writable sticker.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_profile_head_cap(
    masks: *const u8,
    len: usize,
    count: usize,
    index: u32,
    spec: HeadMaskSpec,
    poses: *const f32,
    faces: *const Ellipse,
    previous: Ellipse,
    repair: u32,
    out: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    if !(3..=32).contains(&count) || len > 32 * 1024 * 1024 || repair > 1 || out.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies bounded readable inputs.
    let (Some(m), Some(p), Some(f)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(masks, len),
            crate::ffi_tracking::borrowed(poses, 42),
            crate::ffi_tracking::borrowed(faces, count),
        )
    }) else {
        return REGION_INVALID;
    };
    let poses = [p[..21].try_into().unwrap(), p[21..].try_into().unwrap()];
    let result = if repair == 1 {
        crate::face_head_topology::profile_head_repair(
            m,
            index as usize,
            count,
            spec,
            poses,
            f,
            previous,
        )
    } else {
        crate::face_head_topology::profile_head_cap(m, index as usize, count, spec, poses, f)
    };
    let Some(v) = result else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies one disjoint writable output.
    unsafe { out.write(v) };
    REGION_OK
}

/// # Safety
/// Two writable disjoint Rect outputs; no writes on failure.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_tracklet_detail_views(
    spec: HeadMaskSpec,
    out: *mut Rect,
) -> i32 {
    if out.is_null() {
        return REGION_INVALID;
    }
    let Some(v) = crate::face_head_topology::tracklet_detail_views(spec) else {
        return REGION_NONE;
    };
    // SAFETY: caller provides two writable Rects.
    unsafe { std::ptr::copy_nonoverlapping(v.as_ptr(), out, 2) };
    REGION_OK
}
/// # Safety
/// Readable projected/current local masks and confidences; output index writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_tracklet_detail_owner(
    projected: *const u8,
    projected_len: usize,
    locals: *const u8,
    local_len: usize,
    scene_count: usize,
    local_count: usize,
    index: u32,
    plane: usize,
    confidence: *const f32,
    out: *mut u32,
) -> i32 {
    if scene_count > 32
        || local_count > 32
        || plane > 1024 * 1024
        || projected_len > 32 * 1024 * 1024
        || local_len > 32 * 1024 * 1024
        || out.is_null()
    {
        return REGION_INVALID;
    }
    // SAFETY: caller provides bounded readable input pools.
    let (Some(p), Some(l), Some(c)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(projected, projected_len),
            crate::ffi_tracking::borrowed(locals, local_len),
            crate::ffi_tracking::borrowed(confidence, local_count),
        )
    }) else {
        return REGION_INVALID;
    };
    let Some(j) = crate::face_head_topology::tracklet_detail_owner(
        p,
        l,
        scene_count,
        local_count,
        index as usize,
        plane,
        c,
    ) else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies writable index.
    unsafe { out.write(j as u32) };
    REGION_OK
}
/// # Safety
/// Readable current scene masks, two source-space caps and count face measurements.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_tracklet_detail_finish(
    masks: *const u8,
    len: usize,
    count: usize,
    index: u32,
    spec: HeadMaskSpec,
    caps: *const crate::face_head_topology::HeadSticker,
    faces: *const Ellipse,
    out: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    if !(2..=32).contains(&count) || len > 32 * 1024 * 1024 || out.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies bounded readable arrays.
    let (Some(m), Some(b), Some(f)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(masks, len),
            crate::ffi_tracking::borrowed(caps, 2),
            crate::ffi_tracking::borrowed(faces, count),
        )
    }) else {
        return REGION_INVALID;
    };
    let Some(v) = crate::face_head_topology::tracklet_detail_finish(
        m,
        index as usize,
        count,
        spec,
        [b[0], b[1]],
        f,
    ) else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies disjoint writable output.
    unsafe { out.write(v) };
    REGION_OK
}

/// # Safety
/// Readable packed raw masks and twelve writable disjoint floats. 0 means
/// two current retry plans, 1 means no retry, -1 invalid transport.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_crowded_face_views(
    masks: *const u8,
    len: usize,
    count: usize,
    index: u32,
    spec: HeadMaskSpec,
    face: Ellipse,
    out: *mut f32,
) -> i32 {
    if len > 32 * 1024 * 1024 || out.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: bounded readable mask pool is supplied by the caller.
    let Some(masks) = (unsafe { crate::ffi_tracking::borrowed(masks, len) }) else {
        return REGION_INVALID;
    };
    match crate::face_observations::crowded_face_views(masks, count, index as usize, spec, face) {
        Some(Some(views)) => {
            // SAFETY: caller provides twelve disjoint writable floats.
            unsafe { std::ptr::copy_nonoverlapping(views.as_ptr().cast::<f32>(), out, 12) };
            REGION_OK
        }
        Some(None) => REGION_NONE,
        None => REGION_INVALID,
    }
}
/// # Safety
/// Readable packed masks and two local detector ellipses (zero means miss).
/// 0 accepted, 1 disputed/unconfirmed, -1 invalid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_crowded_face_accept(
    masks: *const u8,
    len: usize,
    count: usize,
    index: u32,
    spec: HeadMaskSpec,
    face: Ellipse,
    local: *const Ellipse,
) -> i32 {
    if len > 32 * 1024 * 1024 || local.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies bounded readable mask pool and two ellipses.
    let (Some(masks), Some(local)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(masks, len),
            crate::ffi_tracking::borrowed(local, 2),
        )
    }) else {
        return REGION_INVALID;
    };
    match crate::face_observations::crowded_face_accept(
        masks,
        count,
        index as usize,
        spec,
        face,
        [local[0], local[1]],
    ) {
        Some(true) => REGION_OK,
        Some(false) => REGION_NONE,
        None => REGION_INVALID,
    }
}
/// # Safety
/// Readable packed masks. 0 means weak estimate has conflicting current
/// evidence, 1 no conflict, -1 invalid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_crowded_weak_conflict(
    masks: *const u8,
    len: usize,
    count: usize,
    index: u32,
    spec: HeadMaskSpec,
    estimate: Ellipse,
    age: i64,
) -> i32 {
    if len > 32 * 1024 * 1024 {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies bounded readable mask pool.
    let Some(masks) = (unsafe { crate::ffi_tracking::borrowed(masks, len) }) else {
        return REGION_INVALID;
    };
    match crate::face_observations::crowded_weak_conflict(
        masks,
        count,
        index as usize,
        spec,
        estimate,
        age,
    ) {
        Some(true) => REGION_OK,
        Some(false) => REGION_NONE,
        None => REGION_INVALID,
    }
}

/// # Safety
/// Readable raw masks and proposal rows. Return 0 for possible geometry,
/// 1 for none, -1 invalid; never writes or requires face measurements.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_duplicate_probe(
    masks: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    rows: *const crate::face_reappearance::DuplicateProposal,
    count: usize,
) -> i32 {
    use crate::face_reappearance::{Error, duplicate_geometry};
    if count > 32 || len > 32 * 1024 * 1024 {
        return REGION_INVALID;
    }
    // SAFETY: bounded readable arrays supplied by caller.
    let (Some(m), Some(p)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(masks, len),
            crate::ffi_tracking::borrowed(rows, count),
        )
    }) else {
        return REGION_INVALID;
    };
    match duplicate_geometry(m, spec, p) {
        Ok(v) if !v.is_empty() => REGION_OK,
        Ok(_) | Err(Error::NoEvidence) => REGION_NONE,
        Err(Error::Invalid) => REGION_INVALID,
    }
}

/// # Safety
/// Raw masks and proposal rows are readable; pair2 and union_mask writable
/// and disjoint. Nonzero result leaves caller output unchanged.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_duplicate_pair(
    masks: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    rows: *const crate::face_reappearance::DuplicateProposal,
    count: usize,
    pair2: *mut u32,
    union_mask: *mut u8,
    union_len: usize,
) -> i32 {
    use crate::face_reappearance::{Error, duplicate_pair};
    if count > 32
        || len > 32 * 1024 * 1024
        || union_len > 1024 * 1024
        || pair2.is_null()
        || union_mask.is_null()
    {
        return REGION_INVALID;
    }
    // SAFETY: checked bounded readable arrays supplied by caller.
    let (Some(m), Some(p)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(masks, len),
            crate::ffi_tracking::borrowed(rows, count),
        )
    }) else {
        return REGION_INVALID;
    };
    let mut u = vec![0; union_len];
    match duplicate_pair(m, spec, p, &mut u) {
        Ok(pair) => {
            // SAFETY: caller supplies two writable indices and union storage.
            unsafe {
                pair2.write(pair[0] as u32);
                pair2.add(1).write(pair[1] as u32);
                std::ptr::copy_nonoverlapping(u.as_ptr(), union_mask, union_len);
            };
            REGION_OK
        }
        Err(Error::NoEvidence) => REGION_NONE,
        Err(Error::Invalid) => REGION_INVALID,
    }
}
/// # Safety
/// Six writable disjoint floats. No write on failure.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_duplicate_plan(
    face: Ellipse,
    t: Transform,
    out: *mut f32,
) -> i32 {
    if out.is_null() {
        return REGION_INVALID;
    }
    let Some(plan) = crate::face_reappearance::duplicate_face_plan(face, t) else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies six writable floats.
    unsafe { std::ptr::copy_nonoverlapping(plan.as_ptr(), out, 6) };
    REGION_OK
}
/// # Safety
/// Raw masks and proposal rows readable, output disjoint and writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_duplicate_sticker(
    masks: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    rows: *const crate::face_reappearance::DuplicateProposal,
    count: usize,
    primary: u32,
    secondary: u32,
    local: Ellipse,
    out: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    use crate::face_reappearance::{Error, duplicate_sticker};
    if count > 32 || len > 32 * 1024 * 1024 || out.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: checked bounded readable arrays supplied by caller.
    let (Some(m), Some(p)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(masks, len),
            crate::ffi_tracking::borrowed(rows, count),
        )
    }) else {
        return REGION_INVALID;
    };
    match duplicate_sticker(m, spec, p, [primary as usize, secondary as usize], local) {
        Ok(v) => {
            // SAFETY: caller supplies a disjoint writable output.
            unsafe { out.write(v) };
            REGION_OK
        }
        Err(Error::NoEvidence) => REGION_NONE,
        Err(Error::Invalid) => REGION_INVALID,
    }
}

/// # Safety
/// Output has three writable disjoint Rects. On failure output is unchanged.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_merged_edge_views(
    spec: HeadMaskSpec,
    anchor: crate::face_head_topology::MergedEdgeAnchor,
    output: *mut Rect,
) -> i32 {
    if output.is_null() {
        return REGION_INVALID;
    }
    let Some(views) = crate::face_head_topology::merged_edge_views(spec, anchor) else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies three writable disjoint Rects.
    unsafe { std::ptr::copy_nonoverlapping(views.as_ptr(), output, 3) };
    REGION_OK
}

/// # Safety
/// Four readable packed current scene-grid masks; confidence3 is readable;
/// output is disjoint and writable. Nonzero leaves output unchanged.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_merged_edge_finish(
    masks: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    anchor: crate::face_head_topology::MergedEdgeAnchor,
    confidence3: *const f32,
    rival: Ellipse,
    output: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    if len > 4 * 1024 * 1024
        || output.is_null()
        || confidence3.is_null()
        || (spec.width as usize)
            .checked_mul(spec.height as usize)
            .and_then(|n| n.checked_mul(4))
            != Some(len)
        || len == 0
    {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies live readable masks and three confidences.
    let (Some(masks), Some(conf)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(masks, len),
            crate::ffi_tracking::borrowed(confidence3, 3),
        )
    }) else {
        return REGION_INVALID;
    };
    let Some(result) = crate::face_head_topology::merged_edge_finish(
        masks,
        spec,
        anchor,
        [conf[0], conf[1], conf[2]],
        rival,
    ) else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies one disjoint writable output.
    unsafe { output.write(result) };
    REGION_OK
}

/// # Safety
/// Readable packed current pair masks; disjoint writable output. Nonzero
/// leaves output unchanged. Current geometry only, no observation lease.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_pair_edge_sticker(
    masks: *const u8,
    len: usize,
    index: u32,
    spec: HeadMaskSpec,
    output: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    if len > 2 * 1024 * 1024 || output.is_null() || index >= 2 {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies live readable masks.
    let Some(masks) = (unsafe { crate::ffi_tracking::borrowed(masks, len) }) else {
        return REGION_INVALID;
    };
    if (spec.width as usize)
        .checked_mul(spec.height as usize)
        .and_then(|v| v.checked_mul(2))
        != Some(len)
        || len == 0
        || !(2..=1024).contains(&spec.width)
        || !(2..=1024).contains(&spec.height)
        || !spec.transform.valid()
        || !spec.person.valid()
        || spec.width != spec.transform.proto_size
        || spec.height != spec.transform.proto_size
    {
        return REGION_INVALID;
    }
    let Some(result) = crate::face_head_topology::pair_edge_cap(masks, index as usize, spec) else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies disjoint writable output.
    unsafe { output.write(result) };
    REGION_OK
}

/// # Safety
/// Both current masks and one 18-float detector record are readable. Returns
/// 0 accepted, 1 rejected, -1 invalid. No memory is retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_pair_owned(
    masks: *const u8,
    len: usize,
    index: u32,
    spec: HeadMaskSpec,
    row: *const f32,
    view: Rect,
    size: f32,
) -> i32 {
    if len > 2 * 1024 * 1024 || row.is_null() || index >= 2 {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies live readable masks and one detector record.
    let (Some(masks), Some(row)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(masks, len),
            crate::ffi_tracking::borrowed(row, 18),
        )
    }) else {
        return REGION_INVALID;
    };
    let Some(face) = crate::ffi_face::decode(row) else {
        return REGION_INVALID;
    };
    match crate::face_observations::pair_face_owned(masks, index as usize, spec, &face, view, size)
    {
        Some(true) => REGION_OK,
        Some(false) => REGION_NONE,
        None => REGION_INVALID,
    }
}

/// # Safety
/// Current mask readable; output is disjoint writable aligned HeadSticker.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_reappearance_sticker(
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    face: Ellipse,
    output: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    use crate::face_reappearance::{Error, cover_reappearance};
    if len > 1024 * 1024 || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: checked length and caller-owned readable input.
    let Some(pixels) = (unsafe { crate::ffi_tracking::borrowed(pixels, len) }) else {
        return REGION_INVALID;
    };
    match cover_reappearance(pixels, spec, face) {
        Ok(result) => {
            // SAFETY: caller-owned disjoint writable output.
            unsafe { output.write(result) };
            REGION_OK
        }
        Err(Error::NoEvidence) => REGION_NONE,
        Err(Error::Invalid) => REGION_INVALID,
    }
}

/// # Safety
/// All input ranges readable; output is disjoint writable aligned Garment.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_garment(
    rgb: *const u8,
    rgb_len: usize,
    masks: *const u8,
    mask_len: usize,
    count: usize,
    index: usize,
    spec: HeadMaskSpec,
    face: Ellipse,
    output: *mut crate::face_reappearance::Garment,
) -> i32 {
    use crate::face_reappearance::{Error, describe};
    if rgb_len > 256 * 1024 * 1024 || mask_len > 256 * 1024 * 1024 || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: checked lengths, caller owns readable ranges.
    let (Some(rgb), Some(masks)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(rgb, rgb_len),
            crate::ffi_tracking::borrowed(masks, mask_len),
        )
    }) else {
        return REGION_INVALID;
    };
    match describe(rgb, masks, count, index, spec, face) {
        Ok(result) => {
            // SAFETY: disjoint aligned writable output.
            unsafe { output.write(result) };
            REGION_OK
        }
        Err(Error::NoEvidence) => REGION_NONE,
        Err(Error::Invalid) => REGION_INVALID,
    }
}

/// # Safety
/// Readable arrays of fixed-size descriptors, disjoint writable output.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_reappearance_choose(
    root_body: Rect,
    root_face: Ellipse,
    age_us: i64,
    roots: *const crate::face_reappearance::Garment,
    root_count: usize,
    rivals: *const crate::face_reappearance::Garment,
    rival_count: usize,
    current: *const crate::face_reappearance::Candidate,
    count: usize,
    output: *mut crate::face_reappearance::Decision,
) -> i32 {
    use crate::face_reappearance::{Error, choose};
    if root_count > 8 || rival_count > 256 || count > 256 || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: checked lengths, caller owns readable arrays.
    let (Some(roots), Some(rivals), Some(current)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(roots, root_count),
            crate::ffi_tracking::borrowed(rivals, rival_count),
            crate::ffi_tracking::borrowed(current, count),
        )
    }) else {
        return REGION_INVALID;
    };
    match choose(root_body, root_face, age_us, roots, rivals, current) {
        Ok(result) => {
            // SAFETY: disjoint writable output.
            unsafe { output.write(result) };
            REGION_OK
        }
        Err(Error::NoEvidence) => REGION_NONE,
        Err(Error::Invalid) => REGION_INVALID,
    }
}

/// # Safety
/// Inputs are readable arrays; output is a disjoint writable aligned index.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_recovery_probe(
    anchor: crate::face_recovery::RecoveryAnchor,
    bodies: *const Rect,
    count: usize,
    foreign: *const Rect,
    foreign_count: usize,
    output: *mut u32,
) -> i32 {
    use crate::face_recovery::{RecoveryError, probe};
    if count > 4096 || foreign_count > 4096 || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies live arrays of the checked lengths.
    let (Some(bodies), Some(foreign)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(bodies, count),
            crate::ffi_tracking::borrowed(foreign, foreign_count),
        )
    }) else {
        return REGION_INVALID;
    };
    match probe(anchor, bodies, foreign) {
        Ok(index) => {
            // SAFETY: caller supplies one disjoint writable index.
            unsafe { output.write(index as u32) };
            REGION_OK
        }
        Err(RecoveryError::NoEvidence) => REGION_NONE,
        Err(RecoveryError::Invalid) => REGION_INVALID,
    }
}

/// # Safety
/// Input arrays and current mask are readable for their lengths. Output is
/// one disjoint writable HeadSticker. Nonzero status never writes output.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_recovery_finish_exclusive(
    anchor: crate::face_recovery::RecoveryAnchor,
    bodies: *const Rect,
    count: usize,
    foreign: *const Rect,
    foreign_count: usize,
    index: u32,
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    measured: Ellipse,
    output: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    use crate::face_recovery::{RecoveryError, finish};
    if count > 256 || foreign_count > 4096 || len > 64 * 1024 * 1024 || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: readable input ranges supplied by the caller.
    let (Some(bodies), Some(foreign), Some(pixels)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(bodies, count),
            crate::ffi_tracking::borrowed(foreign, foreign_count),
            crate::ffi_tracking::borrowed(pixels, len),
        )
    }) else {
        return REGION_INVALID;
    };
    match finish(
        anchor,
        bodies,
        foreign,
        index as usize,
        pixels,
        spec,
        measured,
    ) {
        Ok(result) => {
            // SAFETY: output is disjoint and writable by contract.
            unsafe { output.write(result) };
            REGION_OK
        }
        Err(RecoveryError::NoEvidence) => REGION_NONE,
        Err(RecoveryError::Invalid) => REGION_INVALID,
    }
}

/// # Safety
/// Inputs are readable arrays; output is a disjoint writable aligned index.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_recovery_continuation_probe(
    anchor: crate::face_recovery::RecoveryAnchor,
    previous: crate::face_recovery::RecoveryPrevious,
    bodies: *const Rect,
    count: usize,
    foreign: *const Rect,
    foreign_count: usize,
    output: *mut u32,
) -> i32 {
    use crate::face_recovery::{RecoveryError, probe_continuation};
    if count > 4096 || foreign_count > 4096 || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: live readable arrays, checked lengths.
    let (Some(bodies), Some(foreign)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(bodies, count),
            crate::ffi_tracking::borrowed(foreign, foreign_count),
        )
    }) else {
        return REGION_INVALID;
    };
    match probe_continuation(anchor, previous, bodies, foreign) {
        Ok(index) => {
            // SAFETY: disjoint writable index.
            unsafe { output.write(index as u32) };
            REGION_OK
        }
        Err(RecoveryError::NoEvidence) => REGION_NONE,
        Err(RecoveryError::Invalid) => REGION_INVALID,
    }
}

/// # Safety
/// All input ranges are readable and output is one disjoint writable
/// HeadSticker. No pointer or face image is retained by FrameKernel.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_recovery_continuation_finish_exclusive(
    anchor: crate::face_recovery::RecoveryAnchor,
    previous: crate::face_recovery::RecoveryPrevious,
    previous_gray: *const u8,
    previous_len: usize,
    previous_view: crate::face_pixel_motion::GrayView,
    current_gray: *const u8,
    current_len: usize,
    current_view: crate::face_pixel_motion::GrayView,
    bodies: *const Rect,
    count: usize,
    foreign: *const Rect,
    foreign_count: usize,
    index: u32,
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    measured: Ellipse,
    output: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    use crate::face_recovery::{CurrentPixels, RecoveryError, finish_continuation};
    if previous_len > 256 * 1024 * 1024
        || current_len > 256 * 1024 * 1024
        || len > 64 * 1024 * 1024
        || count > 256
        || foreign_count > 4096
        || output.is_null()
    {
        return REGION_INVALID;
    }
    // SAFETY: readable input ranges supplied by caller.
    let (Some(previous_gray), Some(current_gray), Some(bodies), Some(foreign), Some(pixels)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(previous_gray, previous_len),
            crate::ffi_tracking::borrowed(current_gray, current_len),
            crate::ffi_tracking::borrowed(bodies, count),
            crate::ffi_tracking::borrowed(foreign, foreign_count),
            crate::ffi_tracking::borrowed(pixels, len),
        )
    }) else {
        return REGION_INVALID;
    };
    let current = CurrentPixels {
        previous,
        previous_gray,
        previous_view,
        current_gray,
        current_view,
    };
    match finish_continuation(
        anchor,
        current,
        bodies,
        foreign,
        index as usize,
        pixels,
        spec,
        measured,
    ) {
        Ok(result) => {
            // SAFETY: disjoint writable output by contract.
            unsafe { output.write(result) };
            REGION_OK
        }
        Err(RecoveryError::NoEvidence) => REGION_NONE,
        Err(RecoveryError::Invalid) => REGION_INVALID,
    }
}

/// # Safety
/// Readable current mask, disjoint writable decision. No write on invalid.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_head_prefer_local(
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    primary: Ellipse,
    local: Ellipse,
    output: *mut u32,
) -> i32 {
    if len > 1024 * 1024 || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies a live readable range.
    let Some(pixels) = (unsafe { crate::ffi_tracking::borrowed(pixels, len) }) else {
        return REGION_INVALID;
    };
    let Some(result) = crate::face_head_topology::prefer_local_face(pixels, spec, primary, local)
    else {
        return REGION_INVALID;
    };
    // SAFETY: disjoint writable output by contract.
    unsafe { output.write(u32::from(result)) };
    REGION_OK
}

/// # Safety
/// Readable current mask, output disjoint and writable. Invalid leaves it unchanged.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_head_ellipse(
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    measured: Ellipse,
    stabilized: Ellipse,
    kind: u32,
    output: *mut Ellipse,
) -> i32 {
    if len > 1024 * 1024 || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies a live readable input range.
    let Some(pixels) = (unsafe { crate::ffi_tracking::borrowed(pixels, len) }) else {
        return REGION_INVALID;
    };
    let Some(result) =
        crate::face_head_topology::ellipse_region(pixels, spec, measured, stabilized, kind)
    else {
        return REGION_INVALID;
    };
    // SAFETY: caller supplies a disjoint aligned writable output.
    unsafe { output.write(result) };
    REGION_OK
}

/// # Safety
/// Pixels are readable for len bytes, output is disjoint, writable and aligned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_head_sticker(
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    measured: Ellipse,
    stabilized: Ellipse,
    kind: u32,
    output: *mut crate::face_head_topology::HeadSticker,
) -> i32 {
    if len > 1024 * 1024 || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies a live readable range for this call.
    let Some(pixels) = (unsafe { crate::ffi_tracking::borrowed(pixels, len) }) else {
        return REGION_INVALID;
    };
    let Some(result) = crate::face_head_topology::sticker(pixels, spec, measured, stabilized, kind)
    else {
        return REGION_INVALID;
    };
    // SAFETY: output is disjoint and writable by contract.
    unsafe { output.write(result) };
    REGION_OK
}

/// # Safety
/// The pixel and foreign-rectangle buffers must be readable for their lengths.
/// Output is a disjoint, writable, aligned PixelBridge. No pointer is retained.
/// A nonzero result never writes output.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_pixel_bridge(
    previous: *const u8,
    previous_len: usize,
    previous_view: crate::face_pixel_motion::GrayView,
    current: *const u8,
    current_len: usize,
    current_view: crate::face_pixel_motion::GrayView,
    seed: Ellipse,
    covered: Ellipse,
    age_us: i64,
    foreign_boxes: *const Rect,
    foreign_len: usize,
    output: *mut crate::face_pixel_motion::PixelBridge,
) -> i32 {
    use crate::face_pixel_motion::{MotionError, estimate, project};
    if output.is_null()
        || !face_geometry::valid_seed(covered)
        || previous_len > 256 * 1024 * 1024
        || current_len > 256 * 1024 * 1024
        || foreign_len > 4096
    {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies readable arrays of the checked lengths.
    let (Some(previous), Some(current), Some(foreign)) = (unsafe {
        (
            crate::ffi_tracking::borrowed(previous, previous_len),
            crate::ffi_tracking::borrowed(current, current_len),
            crate::ffi_tracking::borrowed(foreign_boxes, foreign_len),
        )
    }) else {
        return REGION_INVALID;
    };
    match estimate(
        previous,
        previous_view,
        current,
        current_view,
        seed,
        age_us,
        foreign,
    ) {
        Ok(motion) => {
            let Some(bridge) = project(motion, seed, covered) else {
                return REGION_INVALID;
            };
            // SAFETY: output is exclusive and aligned by the caller contract.
            unsafe { output.write(bridge) };
            REGION_OK
        }
        Err(MotionError::NoEvidence) => REGION_NONE,
        Err(MotionError::Invalid) => REGION_INVALID,
    }
}

/// # Safety
/// Caller owns disjoint writable aligned state and output. No write on failure.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_render_axes_step(
    state: *mut crate::face_render_size::FaceRenderAxesState,
    required: Ellipse,
    timestamp_us: i64,
    kind: u32,
    output: *mut Ellipse,
) -> i32 {
    if state.is_null() || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller owns a live writable aligned state.
    let mut next = unsafe { state.read() };
    let Some(result) = next.step(required, timestamp_us, kind) else {
        return REGION_INVALID;
    };
    // SAFETY: caller owns disjoint writable aligned outputs.
    unsafe {
        state.write(next);
        output.write(result);
    }
    REGION_OK
}

/// # Safety
/// State and output must point to disjoint writable aligned values. Caller
/// owns the per-identity lifetime; no face is created from size history alone.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_render_size_step(
    state: *mut crate::face_render_size::FaceRenderSizeState,
    required: Ellipse,
    timestamp_us: i64,
    kind: u32,
    output: *mut Ellipse,
) -> i32 {
    if state.is_null() || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: nonnull aligned buffers are caller contracts.
    let mut next = unsafe { state.read() };
    let Some(region) = next.step(required, timestamp_us, kind) else {
        return REGION_INVALID;
    };
    // SAFETY: writable disjoint values; invalid inputs never reach this write.
    unsafe {
        state.write(next);
        output.write(region);
    }
    REGION_OK
}

/// # Safety
/// A positive len requires readable pixels. Output is one disjoint writable ellipse.
/// Current mask evidence adds coverage only, never identity or ownership.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_current_face_region(
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    region: Ellipse,
    output: *mut Ellipse,
) -> i32 {
    if len > 1024 * 1024 || output.is_null() {
        return REGION_INVALID;
    }
    let pixels = if len == 0 {
        None
    } else {
        // SAFETY: caller supplies the documented readable byte range.
        let Some(p) = (unsafe { crate::ffi_tracking::borrowed(pixels, len) }) else {
            return REGION_INVALID;
        };
        Some(p)
    };
    let Some(region) = face_geometry::current_face_region(pixels, spec, region) else {
        return REGION_INVALID;
    };
    // SAFETY: caller supplies a disjoint writable aligned ellipse.
    unsafe { output.write(region) };
    REGION_OK
}

/// # Safety
/// Output is one disjoint writable aligned ellipse. No owner is assigned.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_standalone_face_region(
    region: Ellipse,
    transform: Transform,
    output: *mut Ellipse,
) -> i32 {
    if output.is_null() {
        return REGION_INVALID;
    }
    let Some(region) = face_geometry::standalone_face_region(region, transform) else {
        return REGION_INVALID;
    };
    // SAFETY: caller supplies a disjoint writable aligned ellipse.
    unsafe { output.write(region) };
    REGION_OK
}

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
/// A positive len requires that many readable pixels. Zero len means no current
/// mask and does not dereference pixels. A nonnull seed is one readable ellipse.
/// Output is one disjoint writable CurrentHeadRegion. Invalid input never writes.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_current_head_region(
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    seed: *const Ellipse,
    output: *mut face_geometry::CurrentHeadRegion,
) -> i32 {
    if len > 1024 * 1024 || output.is_null() {
        return REGION_INVALID;
    }
    let pixels = if len == 0 {
        None
    } else {
        // SAFETY: caller supplies the documented readable byte range.
        let Some(pixels) = (unsafe { crate::ffi_tracking::borrowed(pixels, len) }) else {
            return REGION_INVALID;
        };
        Some(pixels)
    };
    let seed = if seed.is_null() {
        None
    } else {
        // SAFETY: a nonnull seed points to one readable aligned ellipse.
        Some(unsafe { *seed })
    };
    let Some(region) = face_geometry::current_head_region(pixels, spec, seed) else {
        return REGION_INVALID;
    };
    // SAFETY: caller supplies the documented disjoint writable output.
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
    fn complete_current_head_abi_preserves_output_on_invalid_evidence() {
        let spec = HeadMaskSpec {
            width: 160,
            height: 160,
            person: Rect {
                left: 200.0,
                top: 0.0,
                right: 440.0,
                bottom: 600.0,
            },
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
        let seed = face_geometry::fallback(spec.person).unwrap();
        let mut out = face_geometry::CurrentHeadRegion {
            region: Ellipse::default(),
            current_mask_guided: 99,
        };
        // SAFETY: absent pixels/seed are documented; the output is live and disjoint.
        let status = unsafe {
            frame_kernel_current_head_region(std::ptr::null(), 0, spec, std::ptr::null(), &mut out)
        };
        assert_eq!(status, REGION_OK);
        assert_eq!(out.region, seed);
        assert_eq!(out.current_mask_guided, 0);
        let saved = out;
        let pixels = vec![0; 25600];
        // SAFETY: live buffers; invalid length must not write output.
        let status = unsafe {
            frame_kernel_current_head_region(
                pixels.as_ptr(),
                pixels.len() - 1,
                spec,
                &seed,
                &mut out,
            )
        };
        assert_eq!(status, REGION_INVALID);
        assert_eq!(out, saved);
        // SAFETY: null with positive length is invalid and is never dereferenced.
        let status = unsafe {
            frame_kernel_current_head_region(std::ptr::null(), pixels.len(), spec, &seed, &mut out)
        };
        assert_eq!(status, REGION_INVALID);
        assert_eq!(out, saved);
        // SAFETY: disjoint live input and output; zero-valued valid pixels provide no head support.
        let status = unsafe {
            frame_kernel_current_head_region(pixels.as_ptr(), pixels.len(), spec, &seed, &mut out)
        };
        assert_eq!(status, REGION_OK);
        assert_eq!(out.current_mask_guided, 0);
        assert_eq!(out.region, seed);
    }
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
