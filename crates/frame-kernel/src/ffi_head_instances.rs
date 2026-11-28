//! Host research C ABI for independent head geometry.
use crate::{
    ffi_tracking::borrowed,
    geometry::{Rect, Transform},
    head_instances as algo,
    instances::InstanceDetection as Head,
    temporal_region::Ellipse,
};

/// # Safety
/// Input tensor is readable; capacity head records are disjoint writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_decode_heads(
    tensor: *const f32,
    len: usize,
    anchors: usize,
    tr: Transform,
    flip: u32,
    out: *mut Head,
    capacity: usize,
) -> i32 {
    if anchors > 100_000
        || len != anchors.saturating_mul(5)
        || flip > 1
        || capacity > 128
        || out.is_null()
    {
        return -1;
    }
    // SAFETY: caller provides the checked readable tensor.
    let Some(t) = (unsafe { borrowed(tensor, len) }) else {
        return -1;
    };
    let Some(v) = algo::decode(t, anchors, tr, flip == 1) else {
        return -1;
    };
    if v.len() > capacity {
        return -1;
    }
    // SAFETY: caller provides disjoint writable storage with sufficient capacity.
    unsafe { std::ptr::copy_nonoverlapping(v.as_ptr(), out, v.len()) };
    v.len() as i32
}

/// # Safety
/// Inputs are readable; capacity output records are disjoint writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_fuse_heads(
    a: *const Head,
    na: usize,
    b: *const Head,
    nb: usize,
    out: *mut Head,
    capacity: usize,
) -> i32 {
    if na > 128 || nb > 128 || capacity > 128 || out.is_null() {
        return -1;
    }
    // SAFETY: caller provides readable bounded arrays.
    let (Some(a), Some(b)) = (unsafe { (borrowed(a, na), borrowed(b, nb)) }) else {
        return -1;
    };
    let Some(v) = algo::fuse(a, b) else {
        return -1;
    };
    if v.len() > capacity {
        return -1;
    }
    // SAFETY: caller provides disjoint writable output.
    unsafe { std::ptr::copy_nonoverlapping(v.as_ptr(), out, v.len()) };
    v.len() as i32
}

/// # Safety
/// Both head arrays are readable; na output indices are disjoint writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_head_links(
    a: *const Head,
    na: usize,
    b: *const Head,
    nb: usize,
    delta: i64,
    out: *mut i32,
) -> i32 {
    if na > 128 || nb > 128 || out.is_null() {
        return -1;
    }
    // SAFETY: caller provides readable bounded arrays.
    let (Some(a), Some(b)) = (unsafe { (borrowed(a, na), borrowed(b, nb)) }) else {
        return -1;
    };
    let Some(v) = algo::links(a, b, delta) else {
        return -1;
    };
    // SAFETY: caller supplies na disjoint writable indices.
    unsafe { std::ptr::copy_nonoverlapping(v.as_ptr(), out, v.len()) };
    0
}

/// # Safety
/// Head/body arrays and packed masks are readable; nh output indices writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_head_owners(
    heads: *const Head,
    nh: usize,
    bodies: *const Rect,
    nb: usize,
    masks: *const u8,
    len: usize,
    tr: Transform,
    out: *mut i32,
) -> i32 {
    if nh > 128 || nb > 32 || len > 32 * 1024 * 1024 || out.is_null() {
        return -1;
    }
    // SAFETY: caller provides readable bounded arrays.
    let (Some(h), Some(b), Some(m)) = (unsafe {
        (
            borrowed(heads, nh),
            borrowed(bodies, nb),
            borrowed(masks, len),
        )
    }) else {
        return -1;
    };
    let Some(v) = algo::owners(h, b, m, tr) else {
        return -1;
    };
    // SAFETY: caller supplies nh disjoint writable indices.
    unsafe { std::ptr::copy_nonoverlapping(v.as_ptr(), out, v.len()) };
    0
}

/// # Safety
/// Path times and owners are readable; n output states are disjoint writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_head_path(
    times: *const i64,
    owners: *const i32,
    n: usize,
    target: i32,
    out: *mut u32,
) -> i32 {
    if n > 100_000 || out.is_null() {
        return -1;
    }
    // SAFETY: caller provides readable bounded arrays.
    let (Some(t), Some(o)) = (unsafe { (borrowed(times, n), borrowed(owners, n)) }) else {
        return -1;
    };
    let Some(v) = algo::assign_path(t, o, target) else {
        return -1;
    };
    // SAFETY: caller provides n disjoint writable states.
    unsafe { std::ptr::copy_nonoverlapping(v.as_ptr(), out, v.len()) };
    0
}

/// # Safety
/// Path times and owners are readable; n output states disjoint writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_head_certified_path(
    times: *const i64,
    owners: *const i32,
    n: usize,
    target: i32,
    out: *mut u32,
) -> i32 {
    if n > 100_000 || out.is_null() {
        return -1;
    }
    // SAFETY: caller supplies bounded readable arrays.
    let (Some(t), Some(o)) = (unsafe { (borrowed(times, n), borrowed(owners, n)) }) else {
        return -1;
    };
    let Some(v) = algo::certified_path(t, o, target) else {
        return -1;
    };
    // SAFETY: caller supplies n disjoint writable states.
    unsafe { std::ptr::copy_nonoverlapping(v.as_ptr(), out, v.len()) };
    0
}

/// # Safety
/// Path timestamps/flags readable; n output states disjoint writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_head_reconnect_path(
    times: *const i64,
    flags: *const u32,
    n: usize,
    out: *mut u32,
) -> i32 {
    if n > 100_000 || out.is_null() {
        return -1;
    }
    // SAFETY: caller supplies bounded readable arrays.
    let (Some(t), Some(f)) = (unsafe { (borrowed(times, n), borrowed(flags, n)) }) else {
        return -1;
    };
    let Some(v) = algo::reconnect_path(t, f) else {
        return -1;
    };
    // SAFETY: caller supplies n disjoint writable states.
    unsafe { std::ptr::copy_nonoverlapping(v.as_ptr(), out, v.len()) };
    0
}

#[repr(C)]
pub struct HeadInstancePlacement {
    pub required: Rect,
    pub square: Ellipse,
    pub rival_overlap: f32,
}
/// # Safety
/// Heads are readable and out is one disjoint writable result.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_head_instance_placement(
    heads: *const Head,
    n: usize,
    index: usize,
    tr: Transform,
    out: *mut HeadInstancePlacement,
) -> i32 {
    if n > 128 || out.is_null() {
        return -1;
    }
    // SAFETY: caller provides a readable bounded array.
    let Some(h) = (unsafe { borrowed(heads, n) }) else {
        return -1;
    };
    let Some((required, square, rival_overlap)) = algo::placement(h, index, tr) else {
        return 1;
    };
    // SAFETY: caller supplies disjoint writable output.
    unsafe {
        out.write(HeadInstancePlacement {
            required,
            square,
            rival_overlap,
        })
    };
    0
}

/// # Safety
/// Heads are readable and out/mode are disjoint writable results.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_research_head_arbitrate(
    heads: *const Head,
    n: usize,
    index: usize,
    tr: Transform,
    previous: Ellipse,
    required_before: Rect,
    face: Ellipse,
    out: *mut HeadInstancePlacement,
    mode: *mut u32,
) -> i32 {
    if n > 128 || out.is_null() || mode.is_null() {
        return -1;
    }
    // SAFETY: caller supplies a bounded readable head array.
    let Some(h) = (unsafe { borrowed(heads, n) }) else {
        return -1;
    };
    let Some((required, square, rival_overlap, how)) =
        algo::arbitrate(h, index, tr, previous, required_before, face)
    else {
        return 1;
    };
    // SAFETY: caller supplies disjoint writable result storage.
    unsafe {
        out.write(HeadInstancePlacement {
            required,
            square,
            rival_overlap,
        });
        mode.write(how)
    };
    0
}
