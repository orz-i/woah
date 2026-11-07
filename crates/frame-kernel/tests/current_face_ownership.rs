//! Deliberately synthetic rectangles; no original face contours or observations.
use frame_kernel::{
    face_geometry::HeadMaskSpec,
    face_localization::Candidate,
    face_observations::current_face_owned,
    geometry::{Rect, Transform},
};
fn rect(left: f32, top: f32, right: f32, bottom: f32) -> Rect {
    Rect {
        left,
        top,
        right,
        bottom,
    }
}
fn spec(person: Rect) -> HeadMaskSpec {
    HeadMaskSpec {
        width: 160,
        height: 160,
        transform: Transform {
            scale: 1.0,
            pad_left: 0.0,
            pad_top: 0.0,
            source_width: 640,
            source_height: 640,
            input_size: 640,
            proto_size: 160,
        },
        person,
    }
}
fn paint(left: usize, right: usize) -> Vec<u8> {
    let mut p = vec![0; 25600];
    for y in 4..80 {
        p[y * 160 + left..y * 160 + right].fill(255)
    }
    p
}
#[test]
fn overlapping_body_boxes_do_not_own_the_same_neighbor_face() {
    let face = Candidate {
        bounds: rect(320.0, 120.0, 420.0, 220.0),
        confidence: 0.9,
        points: vec![],
    };
    let own = spec(rect(0.0, 0.0, 640.0, 640.0));
    let neighbor = spec(rect(240.0, 0.0, 600.0, 640.0));
    let own_mask = paint(4, 70);
    let neighbor_mask = paint(76, 110);
    assert_eq!(current_face_owned(None, own, &face, None, 0.0), Some(true));
    assert_eq!(
        current_face_owned(Some(&own_mask), own, &face, None, 0.0),
        Some(false)
    );
    assert_eq!(
        current_face_owned(Some(&neighbor_mask), neighbor, &face, None, 0.0),
        Some(true)
    );
    let local = Candidate {
        bounds: rect(160.0, 120.0, 260.0, 220.0),
        ..face
    };
    let view = Some((rect(160.0, 0.0, 480.0, 320.0), 320.0));
    assert_eq!(
        current_face_owned(Some(&own_mask), own, &local, view, 0.0),
        Some(false)
    );
    assert_eq!(
        current_face_owned(Some(&neighbor_mask), neighbor, &local, view, 0.0),
        Some(true)
    );
}
#[test]
fn invalid_transport_preserves_the_abi_output() {
    let s = spec(rect(0.0, 0.0, 640.0, 640.0));
    let mut row = [0.0; 18];
    row[..5].copy_from_slice(&[320.0, 120.0, 420.0, 220.0, 0.9]);
    let pixels = [255u8; 3];
    let mut output = 77;
    // SAFETY: readable arrays and disjoint aligned initialized output.
    let status = unsafe {
        frame_kernel::frame_kernel_current_face_owned(
            pixels.as_ptr(),
            pixels.len(),
            s,
            row.as_ptr(),
            std::ptr::null(),
            1.0,
            0.0,
            &mut output,
        )
    };
    assert_eq!(status, frame_kernel::REGION_INVALID);
    assert_eq!(output, 77);
}
