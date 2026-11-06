use crate::{
    face_localization::{self, Candidate},
    geometry::Rect,
};

// Transport record: xyxy, confidence, point count, six xy points. No pointer retained.
fn decode(row: &[f32]) -> Option<Candidate> {
    if row.len() != 18
        || !row.iter().all(|x| x.is_finite())
        || row[5] < 0.0
        || row[5] > 6.0
        || row[5].fract() != 0.0
    {
        return None;
    }
    Some(Candidate {
        bounds: Rect {
            left: row[0],
            top: row[1],
            right: row[2],
            bottom: row[3],
        },
        confidence: row[4],
        points: row[6..6 + row[5] as usize * 2]
            .chunks_exact(2)
            .map(|p| [p[0], p[1]])
            .collect(),
    })
}

/// # Safety
/// Output is six disjoint writable floats: source xyxy, normalized anchor xy.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_head_plan(
    person: Rect,
    width: u32,
    height: u32,
    out: *mut f32,
) -> i32 {
    if out.is_null() {
        return -1;
    }
    let Some(p) = face_localization::plan(person, width, height) else {
        return -1;
    };
    let values = [
        p.rect.left,
        p.rect.top,
        p.rect.right,
        p.rect.bottom,
        p.anchor[0],
        p.anchor[1],
    ];
    // SAFETY: caller supplies six disjoint writable aligned floats.
    unsafe { std::ptr::copy_nonoverlapping(values.as_ptr(), out, 6) };
    0
}

/// # Safety
/// Row has 18 readable floats; output is two disjoint writable floats.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_center(row: *const f32, out: *mut f32) -> i32 {
    if row.is_null() || out.is_null() {
        return -1;
    }
    // SAFETY: caller supplies a readable 18-float record.
    let Some(f) = decode(unsafe { std::slice::from_raw_parts(row, 18) }) else {
        return -1;
    };
    // SAFETY: caller supplies two disjoint writable aligned floats.
    unsafe { std::ptr::copy_nonoverlapping(f.center().as_ptr(), out, 2) };
    0
}

/// # Safety
/// Rows has count*18 readable floats (count<=4096). Distance is a disjoint writable float.
/// Returns candidate index, -1 for no unambiguous owner, -2 for invalid transport.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_select(
    rows: *const f32,
    count: usize,
    view: Rect,
    ax: f32,
    ay: f32,
    maximum: f32,
    separation: f32,
    distance: *mut f32,
) -> i32 {
    if count > 4096 || (count > 0 && rows.is_null()) || distance.is_null() {
        return -2;
    }
    let raw = if count == 0 {
        &[]
    } else {
        // SAFETY: caller supplies count*18 readable aligned floats; count is bounded.
        unsafe { std::slice::from_raw_parts(rows, count * 18) }
    };
    let Some(faces) = raw.chunks_exact(18).map(decode).collect::<Option<Vec<_>>>() else {
        return -2;
    };
    if !view.valid()
        || ![ax, ay, maximum, separation].iter().all(|v| v.is_finite())
        || maximum < 0.0
        || separation < 0.0
    {
        return -2;
    }
    let Some((index, d)) = face_localization::select(&faces, view, [ax, ay], maximum, separation)
    else {
        return -1;
    };
    // SAFETY: caller supplies disjoint writable distance storage.
    unsafe { *distance = d };
    index as i32
}

/// # Safety
/// Row is 18 readable floats. No caller storage is retained.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_face_owned(
    row: *const f32,
    source: Rect,
    output_size: f32,
    person: Rect,
) -> i32 {
    if row.is_null() {
        return -1;
    }
    // SAFETY: caller supplies one readable aligned record.
    let Some(f) = decode(unsafe { std::slice::from_raw_parts(row, 18) }) else {
        return -1;
    };
    i32::from(face_localization::owned(&f, source, output_size, person))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_transport_never_writes_distance_and_empty_candidates_have_no_owner() {
        let view = Rect {
            left: 0.0,
            top: 0.0,
            right: 256.0,
            bottom: 256.0,
        };
        let mut distance = 123.0;
        // SAFETY: null input is permitted for zero count; output points to a live disjoint float.
        assert_eq!(
            // SAFETY: the test supplies the documented readable row and disjoint live output.
            unsafe {
                frame_kernel_face_select(
                    std::ptr::null(),
                    0,
                    view,
                    0.5,
                    0.5,
                    0.22,
                    0.04,
                    &mut distance,
                )
            },
            -1
        );
        let mut row = [0.0; 18];
        row[5] = 7.0;
        // SAFETY: one readable 18-float row and disjoint output are supplied.
        assert_eq!(
            // SAFETY: the test supplies the documented readable row and disjoint live output.
            unsafe {
                frame_kernel_face_select(row.as_ptr(), 1, view, 0.5, 0.5, 0.22, 0.04, &mut distance)
            },
            -2
        );
        assert_eq!(distance, 123.0);
    }
}
