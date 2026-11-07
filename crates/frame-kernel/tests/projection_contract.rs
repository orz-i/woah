use frame_kernel::{
    face_geometry::{ProjectionOptions, project_trusted},
    geometry::Rect,
    temporal_region::Ellipse,
};
#[test]
fn projection_uses_bounded_scale_age_and_edge_translation() {
    let previous = Rect {
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
    let moved = Rect {
        left: 30.0,
        top: 30.0,
        right: 130.0,
        bottom: 330.0,
    };
    let p = project_trusted(moved, previous, seed, 150_000, ProjectionOptions::default()).unwrap();
    assert_eq!(
        [p.center_x, p.center_y, p.radius_x, p.radius_y],
        [80.0, 75.0, 22.0, 33.0]
    );
    assert_eq!(
        project_trusted(
            moved,
            previous,
            seed,
            i64::MAX,
            ProjectionOptions::default()
        ),
        Some(p)
    );
    let huge = Rect {
        left: 10.0,
        top: 20.0,
        right: 1010.0,
        bottom: 3020.0,
    };
    let q = project_trusted(huge, previous, seed, 0, ProjectionOptions::default()).unwrap();
    assert_eq!(q.radius_x, seed.radius_x * 1.12);
    assert_eq!([q.center_x, q.center_y], [seed.center_x, seed.center_y]);
    assert!(
        project_trusted(
            Rect::default(),
            previous,
            seed,
            0,
            ProjectionOptions::default()
        )
        .is_none()
    );
    assert!(
        project_trusted(
            moved,
            previous,
            seed,
            0,
            ProjectionOptions {
                lease_us: 0,
                ..ProjectionOptions::default()
            }
        )
        .is_none()
    );
}
