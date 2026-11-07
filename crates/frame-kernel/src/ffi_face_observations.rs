//! Observation geometry ABI. Zero succeeds; nonzero leaves outputs unchanged.
use crate::{
    face_geometry::{HeadMaskSpec, valid_seed},
    face_observations::{self, ViewObservation},
    ffi_face_geometry::{REGION_INVALID, REGION_NONE, REGION_OK},
    ffi_tracking::borrowed,
    geometry::Rect,
    temporal_region::Ellipse,
};
/// # Safety
/// Pixels are readable for len bytes; output2 is two disjoint writable u32 values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_mask_support(
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    region: Ellipse,
    output2: *mut u32,
) -> i32 {
    if len > 1024 * 1024 || output2.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies the documented readable byte range.
    let Some(pixels) = (unsafe { borrowed(pixels, len) }) else {
        return REGION_INVALID;
    };
    let Some(support) = face_observations::mask_support(pixels, spec, region) else {
        return REGION_INVALID;
    };
    // SAFETY: caller supplies two disjoint writable aligned values.
    unsafe { std::ptr::copy_nonoverlapping(support.as_ptr(), output2, 2) };
    REGION_OK
}
/// # Safety
/// Pixels is readable for len bytes, row18 for18 floats, and output is one
/// disjoint writable aligned u32. Only REGION_OK writes the 0/1 result.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_mask_owned(
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    row18: *const f32,
    yaw: f32,
    output: *mut u32,
) -> i32 {
    if len > 1024 * 1024 || row18.is_null() || output.is_null() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies the documented borrowed input ranges.
    let (Some(pixels), Some(row)) = (unsafe { borrowed(pixels, len) }, unsafe {
        borrowed(row18, 18)
    }) else {
        return REGION_INVALID;
    };
    if !spec.valid(pixels) {
        return REGION_INVALID;
    }
    let Some(face) = crate::ffi_face::decode(row) else {
        return REGION_INVALID;
    };
    let owned = face_observations::face_mask_owned(pixels, spec, &face, yaw);
    // SAFETY: caller supplies the documented disjoint writable aligned result.
    unsafe { output.write(u32::from(owned)) };
    REGION_OK
}
/// # Safety
/// Row18 is 18 readable floats; output is one disjoint writable float.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_observation_score(
    row18: *const f32,
    person: Rect,
    output: *mut f32,
) -> i32 {
    if row18.is_null() || output.is_null() || !person.valid() {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies the documented readable record.
    let Some(face) = crate::ffi_face::decode(unsafe { std::slice::from_raw_parts(row18, 18) })
    else {
        return REGION_INVALID;
    };
    let Some(score) = face_observations::association_score(&face, person) else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies one disjoint writable aligned float.
    unsafe { output.write(score) };
    REGION_OK
}
/// # Safety
/// Output4 points to four disjoint writable aligned xywh floats.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_upper_body_view(person: Rect, output4: *mut f32) -> i32 {
    if output4.is_null() {
        return REGION_INVALID;
    }
    let Some(view) = face_observations::upper_body_view(person) else {
        return REGION_INVALID;
    };
    // SAFETY: caller supplies four disjoint writable aligned floats.
    unsafe { std::ptr::copy_nonoverlapping(view.as_ptr(), output4, 4) };
    REGION_OK
}
/// # Safety
/// Input has count initialized records. Output has capacity writable u32 values;
/// output_count is writable usize storage. All buffers are disjoint and borrowed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_merge_views(
    input: *const ViewObservation,
    count: usize,
    primary: usize,
    require_owner: u32,
    output: *mut u32,
    capacity: usize,
    output_count: *mut usize,
) -> i32 {
    if count > 4096
        || primary > count
        || require_owner > 1
        || output_count.is_null()
        || (count > 0 && output.is_null())
    {
        return REGION_INVALID;
    }
    if capacity < count {
        return -2;
    }
    // SAFETY: caller supplies the documented readable aligned records.
    let Some(input) = (unsafe { borrowed(input, count) }) else {
        return REGION_INVALID;
    };
    let Some(indices) = face_observations::merge_views(input, primary, require_owner != 0) else {
        return REGION_INVALID;
    };
    let values: Vec<u32> = indices.iter().map(|i| *i as u32).collect();
    if !values.is_empty() {
        // SAFETY: capacity covers count and every output index; buffers are disjoint.
        unsafe { std::ptr::copy_nonoverlapping(values.as_ptr(), output, values.len()) };
    }
    // SAFETY: caller supplies disjoint writable aligned count storage.
    unsafe { output_count.write(values.len()) };
    REGION_OK
}
/// # Safety
/// Scores and preferred have the declared initialized element counts; output
/// has capacity writable i32 elements. All buffers are disjoint and borrowed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_resolve_owners(
    scores: *const f32,
    len: usize,
    people: usize,
    faces: usize,
    preferred: *const i32,
    hero: i64,
    margin: f32,
    output: *mut i32,
    capacity: usize,
) -> i32 {
    if people > 4096
        || faces > 4096
        || len > 4096 * 4096
        || hero < -1
        || (people > 0 && output.is_null())
    {
        return REGION_INVALID;
    }
    if capacity < people {
        return -2;
    }
    // SAFETY: caller supplies the documented readable scores and choices.
    let (Some(scores), Some(preferred)) = (unsafe { borrowed(scores, len) }, unsafe {
        borrowed(preferred, people)
    }) else {
        return REGION_INVALID;
    };
    let Some(indices) = face_observations::resolve_owners(
        scores,
        people,
        faces,
        preferred,
        (hero >= 0).then_some(hero as usize),
        margin,
    ) else {
        return REGION_INVALID;
    };
    if !indices.is_empty() {
        // SAFETY: validated capacity covers all outputs; buffers are disjoint.
        unsafe { std::ptr::copy_nonoverlapping(indices.as_ptr(), output, indices.len()) };
    }
    REGION_OK
}
/// # Safety
/// Scores contains count readable floats; output is one disjoint writable u32.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_unique_owner(
    scores: *const f32,
    count: usize,
    margin: f32,
    output: *mut u32,
) -> i32 {
    if count > 4096 || output.is_null() || !margin.is_finite() || margin < 0.0 {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies the documented readable score range.
    let Some(scores) = (unsafe { borrowed(scores, count) }) else {
        return REGION_INVALID;
    };
    if scores
        .iter()
        .any(|v| !v.is_finite() || !(-1.0..=1.0).contains(v))
    {
        return REGION_INVALID;
    }
    let Some(index) = face_observations::unique_best(scores, margin) else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies one disjoint writable aligned index.
    unsafe { output.write(index as u32) };
    REGION_OK
}
/// # Safety
/// Pixels contains len readable bytes; output is one disjoint writable ellipse.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_occlusion_padding(
    pixels: *const u8,
    len: usize,
    spec: HeadMaskSpec,
    seed: Ellipse,
    age: i64,
    lease: i64,
    output: *mut Ellipse,
) -> i32 {
    if len > 1024 * 1024 || output.is_null() || lease <= 0 {
        return REGION_INVALID;
    }
    // SAFETY: caller supplies the documented readable byte range.
    let Some(pixels) = (unsafe { borrowed(pixels, len) }) else {
        return REGION_INVALID;
    };
    if !spec.valid(pixels) || !valid_seed(seed) {
        return REGION_INVALID;
    }
    let Some(region) = face_observations::occlusion_padding(pixels, spec, seed, age, lease) else {
        return REGION_NONE;
    };
    // SAFETY: caller supplies one disjoint writable aligned ellipse.
    unsafe { output.write(region) };
    REGION_OK
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn owned_mask_abi_writes_boolean_only_after_zero_status() {
        let spec = HeadMaskSpec {
            width: 160,
            height: 160,
            person: Rect {
                left: 150.0,
                top: 50.0,
                right: 450.0,
                bottom: 600.0,
            },
            transform: crate::geometry::Transform {
                scale: 1.0,
                pad_left: 0.0,
                pad_top: 0.0,
                source_width: 640,
                source_height: 640,
                input_size: 640,
                proto_size: 160,
            },
        };
        let row = [
            200.0, 200.0, 300.0, 300.0, 0.9, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0.0,
        ];
        let pixels = vec![255; 25600];
        let mut output = 77;
        // SAFETY: all input/output buffers are live, aligned and disjoint.
        let status = unsafe {
            frame_kernel_face_mask_owned(
                pixels.as_ptr(),
                pixels.len(),
                spec,
                row.as_ptr(),
                0.0,
                &mut output,
            )
        };
        assert_eq!(status, REGION_OK);
        assert_eq!(output, 1);
        let empty = vec![0; 25600];
        // SAFETY: all input/output buffers are live, aligned and disjoint.
        let status = unsafe {
            frame_kernel_face_mask_owned(
                empty.as_ptr(),
                empty.len(),
                spec,
                row.as_ptr(),
                0.0,
                &mut output,
            )
        };
        assert_eq!(status, REGION_OK);
        assert_eq!(output, 0);
        output = 77;
        // SAFETY: null pixels is an explicitly rejected invalid range.
        let status = unsafe {
            frame_kernel_face_mask_owned(
                std::ptr::null(),
                pixels.len(),
                spec,
                row.as_ptr(),
                0.0,
                &mut output,
            )
        };
        assert_eq!(status, REGION_INVALID);
        assert_eq!(output, 77);
    }
    #[test]
    fn failed_view_merge_and_ambiguous_choice_preserve_all_outputs() {
        let view = ViewObservation {
            bounds: Rect {
                left: 0.0,
                top: 0.0,
                right: 10.0,
                bottom: 20.0,
            },
            view: 0,
            known_owner: 1,
            owner: 7,
        };
        let mut output = [42_u32; 2];
        let mut count = 99_usize;
        // SAFETY: all input/output buffers are live, aligned and disjoint.
        let status = unsafe {
            frame_kernel_face_merge_views(&view, 1, 1, 1, output.as_mut_ptr(), 0, &mut count)
        };
        assert_eq!(status, -2);
        assert_eq!(output, [42, 42]);
        assert_eq!(count, 99);
        let mut index = 42;
        let scores = [0.9, 0.89];
        // SAFETY: all input/output buffers are live, aligned and disjoint.
        let status =
            unsafe { frame_kernel_face_unique_owner(scores.as_ptr(), 2, 0.08, &mut index) };
        assert_eq!(status, REGION_NONE);
        assert_eq!(index, 42);
    }
}
