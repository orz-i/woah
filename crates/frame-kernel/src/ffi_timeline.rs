//! Checked, allocation-free timeline ABI shared with native adapters and Dart.
use crate::timeline::{AffineMap, Interval};

#[repr(C)]
#[derive(Default)]
pub struct TimeRange {
    pub start: i64,
    pub end: i64,
    pub valid: u32,
    pub reserved: u32,
}
#[repr(C)]
#[derive(Default)]
pub struct TimeMap {
    pub scale: f64,
    pub offset: i64,
    pub valid: u32,
    pub reserved: u32,
}

#[repr(C)]
#[derive(Default)]
pub struct FrameStep {
    pub value: i64,
    pub probe: u32,
    pub valid: u32,
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_frame_step(
    fs: i64,
    fe: i64,
    rs: i64,
    re: i64,
    forward: u32,
    ps: i64,
    pe: i64,
) -> FrameStep {
    let previous = if ps < 0 {
        None
    } else {
        Some(Interval { start: ps, end: pe })
    };
    crate::timeline::frame_step(
        Interval { start: fs, end: fe },
        Interval { start: rs, end: re },
        forward != 0,
        previous,
    )
    .map_or_else(FrameStep::default, |(value, probe)| FrameStep {
        value,
        probe: u32::from(probe),
        valid: 1,
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_time_value(
    mode: u32,
    value: i64,
    ticks: u32,
    scale: f64,
    offset: i64,
) -> i64 {
    let map = AffineMap { scale, offset };
    match mode {
        0 => map.map_ticks(value, ticks),
        1 => map.reference(value),
        _ => None,
    }
    .unwrap_or(i64::MIN)
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_time_inverse(scale: f64, offset: i64) -> TimeMap {
    AffineMap { scale, offset }
        .inverse()
        .map_or_else(TimeMap::default, |m| TimeMap {
            scale: m.scale,
            offset: m.offset,
            valid: 1,
            reserved: 0,
        })
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_time_anchors(r1: i64, t1: i64, r2: i64, t2: i64) -> TimeMap {
    AffineMap::anchors(r1, t1, r2, t2).map_or_else(TimeMap::default, |m| TimeMap {
        scale: m.scale,
        offset: m.offset,
        valid: 1,
        reserved: 0,
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_time_overlap(
    rs: i64,
    re: i64,
    ts: i64,
    te: i64,
    scale: f64,
    offset: i64,
) -> TimeRange {
    AffineMap { scale, offset }
        .overlap(
            Interval { start: rs, end: re },
            Interval { start: ts, end: te },
        )
        .map_or_else(TimeRange::default, |r| TimeRange {
            start: r.start,
            end: r.end,
            valid: 1,
            reserved: 0,
        })
}

/// # Safety
/// `pts` points to `len` initialized i64 values, sorted and unique, and remains
/// readable throughout this call. Null is accepted only for an empty slice.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_frame_index(
    pts: *const i64,
    len: usize,
    time: i64,
    start: i64,
    end: i64,
    mode: u32,
) -> i64 {
    if len > 10_000_000 || (pts.is_null() && len != 0) {
        return -1;
    }
    let values = if len == 0 {
        &[]
    } else {
        // SAFETY: The caller guarantees a readable slice; null and length were checked.
        unsafe { std::slice::from_raw_parts(pts, len) }
    };
    crate::timeline::frame_index(values, time, Interval { start, end }, mode)
        .map_or(-1, |i| i as i64)
}
