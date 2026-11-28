//! Synthetic shapes only: current render support, no recorded personal observations.
use frame_kernel::{
    face_geometry::{HeadMaskSpec, current_face_region},
    geometry::{Rect, Transform},
    temporal_region::Ellipse,
};

fn spec() -> HeadMaskSpec {
    HeadMaskSpec {
        width: 32,
        height: 32,
        transform: Transform {
            scale: 1.0,
            pad_left: 0.0,
            pad_top: 0.0,
            source_width: 128,
            source_height: 128,
            input_size: 128,
            proto_size: 32,
        },
        person: Rect {
            left: 16.0,
            top: 12.0,
            right: 112.0,
            bottom: 124.0,
        },
    }
}
fn seed() -> Ellipse {
    Ellipse {
        center_x: 56.0,
        center_y: 40.0,
        radius_x: 12.0,
        radius_y: 14.0,
    }
}
fn contains(e: Ellipse, x: f32, y: f32) -> bool {
    ((x - e.center_x) / e.radius_x).powi(2) + ((y - e.center_y) / e.radius_y).powi(2) <= 1.00001
}
fn silhouette(mirror: bool) -> Vec<u8> {
    let mut pixels = vec![0; 1024];
    for y in 7..13 {
        for x in 10..20 {
            pixels[y * 32 + if mirror { 31 - x } else { x }] = 255;
        }
    }
    pixels
}
#[test]
fn current_profile_support_is_covered_on_both_sides_and_seed_is_retained() {
    for mirror in [false, true] {
        let input = if mirror {
            Ellipse {
                center_x: 128.0 - seed().center_x,
                ..seed()
            }
        } else {
            seed()
        };
        let output = current_face_region(Some(&silhouette(mirror)), spec(), input).unwrap();
        for y in [28.0, 52.0] {
            for x in [40.0, 80.0] {
                assert!(contains(output, if mirror { 128.0 - x } else { x }, y));
            }
        }
        for n in 0..360 {
            let angle = n as f32 * std::f32::consts::TAU / 360.0;
            assert!(contains(
                output,
                input.center_x + input.radius_x * angle.cos(),
                input.center_y + input.radius_y * angle.sin()
            ));
        }
        assert!(!contains(input, if mirror { 48.0 } else { 80.0 }, 40.0));
    }
}
#[test]
fn isolated_mask_row_cannot_enlarge_beyond_the_sampling_margin() {
    let mut pixels = vec![0; 1024];
    pixels[10 * 32 + 10..10 * 32 + 20].fill(255);
    assert_eq!(
        current_face_region(Some(&pixels), spec(), seed()),
        current_face_region(Some(&[0; 1024]), spec(), seed())
    );
    assert_eq!(current_face_region(None, spec(), seed()), Some(seed()));
    assert_eq!(
        current_face_region(Some(&[0; 1024]), spec(), seed()),
        Some(Ellipse {
            radius_x: 16.0,
            radius_y: 18.0,
            ..seed()
        })
    );
    assert_eq!(
        current_face_region(Some(&pixels[..1023]), spec(), seed()),
        None
    );
}
#[test]
fn broad_body_or_distant_support_cannot_become_a_head_envelope() {
    assert_eq!(
        current_face_region(Some(&[255; 1024]), spec(), seed()),
        current_face_region(Some(&[0; 1024]), spec(), seed())
    );
    let mut pixels = vec![0; 1024];
    for y in 7..13 {
        pixels[y * 32 + 24..y * 32 + 28].fill(255);
    }
    assert_eq!(
        current_face_region(Some(&pixels), spec(), seed()),
        current_face_region(Some(&[0; 1024]), spec(), seed())
    );
}
#[test]
fn sampling_margin_protects_chin_without_inventing_a_mask_head() {
    let output = current_face_region(Some(&[0; 1024]), spec(), seed()).unwrap();
    assert_eq!(output.center_x, seed().center_x);
    assert_eq!(output.center_y, seed().center_y);
    assert!(!contains(seed(), 56.0, 57.0));
    assert!(contains(output, 56.0, 57.0));
    for n in 0..360 {
        let angle = n as f32 * std::f32::consts::TAU / 360.0;
        assert!(contains(
            output,
            seed().center_x + seed().radius_x * angle.cos(),
            seed().center_y + seed().radius_y * angle.sin()
        ));
    }
    let mut invalid = spec();
    invalid.transform.scale = f32::MIN_POSITIVE;
    invalid.transform.input_size = 65535;
    assert!(current_face_region(Some(&[0; 1024]), invalid, seed()).is_none());
}
#[test]
fn abi_rejects_invalid_mask_without_writing_output() {
    use frame_kernel::frame_kernel_current_face_region;
    let pixels = vec![0; 1023];
    let mut output = seed();
    // SAFETY: readable pixels, valid value inputs, disjoint writable output.
    let status = unsafe {
        frame_kernel_current_face_region(pixels.as_ptr(), pixels.len(), spec(), seed(), &mut output)
    };
    assert_eq!(status, -1);
    assert_eq!(output, seed());
    let pixels = silhouette(false);
    // SAFETY: same value/pointer contract with a complete current mask.
    let status = unsafe {
        frame_kernel_current_face_region(pixels.as_ptr(), pixels.len(), spec(), seed(), &mut output)
    };
    assert_eq!(status, 0);
    assert!(contains(output, 80.0, 40.0));
}

#[test]
fn standalone_face_keeps_detected_coverage_and_pads_without_an_owner() {
    use frame_kernel::frame_kernel_standalone_face_region;
    let mut output = seed();
    // SAFETY: output points to one writable ellipse.
    let status =
        unsafe { frame_kernel_standalone_face_region(seed(), spec().transform, &mut output) };
    assert_eq!(status, 0);
    assert!(!contains(seed(), 56.0, 57.0));
    assert!(contains(output, 56.0, 57.0));
    assert!(contains(output, 56.0, 26.0));
    assert_eq!(
        Some(output),
        current_face_region(Some(&[0; 1024]), spec(), seed())
    );

    let invalid = Transform {
        scale: 0.0,
        ..spec().transform
    };
    // SAFETY: output is writable; invalid metadata must leave it untouched.
    let status = unsafe { frame_kernel_standalone_face_region(seed(), invalid, &mut output) };
    assert_eq!(status, -1);
    assert!(contains(output, 56.0, 57.0));
}

// Actual row-wise head envelope, tested separately from sticker rectangle.
#[test]
fn topology_ellipse_covers_every_head_cell_and_direct_seed_without_shoulders() {
    use frame_kernel::face_head_topology::ellipse_region;
    let mut s = spec();
    s.person = Rect {
        left: 12.0,
        top: 16.0,
        right: 116.0,
        bottom: 124.0,
    };
    let face = Ellipse {
        center_x: 64.0,
        center_y: 38.0,
        radius_x: 12.0,
        radius_y: 16.0,
    };
    let mut pixels = vec![0; 32 * 32];
    for (y, l, r) in [
        (6, 14, 18),
        (7, 13, 19),
        (8, 12, 20),
        (9, 12, 20),
        (10, 12, 20),
        (11, 13, 19),
    ] {
        pixels[y * 32 + l..y * 32 + r].fill(255);
    }
    for y in 12..29 {
        pixels[y * 32 + 5..27 + y * 32].fill(255);
    }
    let out = ellipse_region(&pixels, s, face, face, 0).unwrap();
    for y in 6..12 {
        for x in 0..32 {
            if pixels[y * 32 + x] == 0 {
                continue;
            }
            for dx in [0.0, 4.0] {
                for dy in [0.0, 4.0] {
                    assert!(contains(out, x as f32 * 4.0 + dx, y as f32 * 4.0 + dy));
                }
            }
        }
    }
    for n in 0..360 {
        let a = n as f32 * std::f32::consts::TAU / 360.0;
        assert!(contains(
            out,
            face.center_x + face.radius_x * a.cos(),
            face.center_y + face.radius_y * a.sin()
        ));
    }
    assert!(!contains(out, 20.0, 52.0));
    assert!(!contains(out, 108.0, 52.0));
    let mut mirrored = pixels.clone();
    for row in mirrored.chunks_exact_mut(32) {
        row.reverse();
    }
    let other = ellipse_region(&mirrored, s, face, face, 0).unwrap();
    assert!((other.radius_x - out.radius_x).abs() < 0.001);
    assert!((other.radius_y - out.radius_y).abs() < 0.001);
}

#[test]
fn topology_ellipse_preserves_weak_fallback_when_no_current_support() {
    use frame_kernel::face_head_topology::ellipse_region;
    let face = seed();
    let stable = Ellipse {
        center_y: face.center_y + 8.0,
        ..face
    };
    for kind in [1, 2] {
        assert_eq!(
            ellipse_region(&[0; 1024], spec(), face, stable, kind),
            Some(stable)
        );
    }
    assert!(ellipse_region(&[0; 1023], spec(), face, stable, 0).is_none());
}

#[test]
fn split_crown_is_only_accepted_when_next_row_reconnects() {
    use frame_kernel::face_head_topology::{ellipse_region, prefer_local_face};
    let mut s = spec();
    s.person = Rect {
        left: 8.0,
        top: 12.0,
        right: 120.0,
        bottom: 124.0,
    };
    let local = Ellipse {
        center_x: 64.0,
        center_y: 34.0,
        radius_x: 16.0,
        radius_y: 16.0,
    };
    let torso = Ellipse {
        center_y: 69.0,
        ..local
    };
    let mut mask = vec![0; 1024];
    // Two parts of a crown separated by one prototype cell, joined below.
    mask[5 * 32 + 12..5 * 32 + 15].fill(255);
    mask[5 * 32 + 16..5 * 32 + 20].fill(255);
    for y in 6..12 {
        mask[y * 32 + 12..y * 32 + 20].fill(255);
    }
    for y in 12..29 {
        mask[y * 32 + 7..y * 32 + 25].fill(255);
    }
    assert_eq!(prefer_local_face(&mask, s, torso, local), Some(true));
    let out = ellipse_region(&mask, s, local, local, 0).unwrap();
    assert!(contains(out, 48.0, 20.0) && contains(out, 80.0, 24.0));
    assert!(!contains(out, 28.0, 56.0));
    assert_eq!(prefer_local_face(&mask, s, local, local), Some(false));
    // The gap continues: no qualification as one crown and no override.
    mask[6 * 32 + 15] = 0;
    mask[7 * 32 + 15] = 0;
    assert_eq!(prefer_local_face(&mask, s, torso, local), Some(false));
}

#[test]
fn qualified_head_cap_can_override_torso_but_not_a_distant_or_unowned_hypothesis() {
    use frame_kernel::face_head_topology::prefer_local_face;
    let local = Ellipse {
        center_x: 64.0,
        center_y: 34.0,
        radius_x: 16.0,
        radius_y: 16.0,
    };
    let mut s = spec();
    s.person = Rect {
        left: 8.0,
        top: 12.0,
        right: 120.0,
        bottom: 124.0,
    };
    let mut mask = vec![0; 1024];
    mask[5 * 32 + 15..5 * 32 + 17].fill(255);
    for y in 6..12 {
        mask[y * 32 + 12..y * 32 + 20].fill(255);
    }
    for y in 12..29 {
        mask[y * 32 + 7..y * 32 + 25].fill(255);
    }
    let torso = Ellipse {
        center_y: 69.0,
        ..local
    };
    assert_eq!(prefer_local_face(&mask, s, torso, local), Some(true));
    assert_eq!(
        prefer_local_face(
            &mask,
            s,
            Ellipse {
                center_x: 100.0,
                ..torso
            },
            local
        ),
        Some(false)
    );
    assert_eq!(
        prefer_local_face(&[255; 1024], s, torso, local),
        Some(false)
    );
    assert_eq!(prefer_local_face(&mask[..1023], s, torso, local), None);
}

#[test]
fn connected_head_does_not_include_confirmed_early_shoulder_junction() {
    use frame_kernel::face_head_topology::ellipse_region;
    let mut s = spec();
    s.person = Rect {
        left: 8.0,
        top: 12.0,
        right: 120.0,
        bottom: 124.0,
    };
    let face = Ellipse {
        center_x: 60.0,
        center_y: 36.0,
        radius_x: 12.0,
        radius_y: 13.0,
    };
    let mut p = vec![0; 1024];
    for (y, l, r) in [
        (4, 14, 18),
        (5, 13, 19),
        (6, 12, 20),
        (7, 12, 20),
        (8, 12, 20),
        (9, 12, 20),
        (10, 12, 20),
        (11, 11, 21),
    ] {
        p[y * 32 + l..y * 32 + r].fill(255);
    }
    for y in 12..29 {
        p[y * 32 + 6..y * 32 + 26].fill(255);
    }
    // The head itself (including all cell corners) remains covered.
    let out = ellipse_region(&p, s, face, face, 0).unwrap();
    for y in 4..11 {
        for x in 0..32 {
            if p[y * 32 + x] == 0 {
                continue;
            }
            for dx in [0.0, 4.0] {
                for dy in [0.0, 4.0] {
                    assert!(contains(out, x as f32 * 4.0 + dx, y as f32 * 4.0 + dy));
                }
            }
        }
    }
    for k in 0..360 {
        let a = k as f32 * std::f32::consts::TAU / 360.0;
        assert!(contains(
            out,
            face.center_x + face.radius_x * a.cos(),
            face.center_y + face.radius_y * a.sin()
        ));
    }
    assert!(!contains(out, 24.0, 56.0));
}
