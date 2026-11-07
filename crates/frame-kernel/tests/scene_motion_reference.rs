use frame_kernel::{
    geometry::Rect,
    tracking::{Detection, TrackingConfig},
    tracking_motion::estimate,
};
struct Reader<'a> {
    bytes: &'a [u8],
    index: usize,
}
impl Reader<'_> {
    fn u32(&mut self) -> u32 {
        let n = u32::from_be_bytes(self.bytes[self.index..self.index + 4].try_into().unwrap());
        self.index += 4;
        n
    }
    fn f32(&mut self) -> f32 {
        f32::from_bits(self.u32())
    }
    fn rect(&mut self) -> Rect {
        Rect {
            left: self.f32(),
            top: self.f32(),
            right: self.f32(),
            bottom: self.f32(),
        }
    }
}
#[test]
fn scene_motion_matches_frozen_android_float_bits_and_input_order() {
    let mut r = Reader {
        bytes: include_bytes!("../testdata/scene-motion-reference-v1.bin"),
        index: 0,
    };
    assert_eq!(r.u32(), 0x464b4d31);
    let count = r.u32();
    assert_eq!(count, 512);
    for case in 0..count {
        let config = TrackingConfig {
            min_scene_motion_inliers: r.u32() as usize,
            max_global_shift: r.f32(),
            scene_motion_tolerance: r.f32(),
            ..Default::default()
        };
        let tracks: Vec<_> = (0..r.u32())
            .map(|_| {
                let id = r.u32() as i32 as i64;
                (id, r.rect())
            })
            .collect();
        let detections: Vec<_> = (0..r.u32())
            .map(|_| {
                let bounds = r.rect();
                Detection {
                    bounds,
                    mask: None,
                    confidence: 0.9,
                    foot_y: bounds.bottom,
                }
            })
            .collect();
        let expected_dx = r.u32();
        let expected_dy = r.u32();
        let expected_inliers = r.u32();
        let expected_confidence = r.u32();
        let actual = estimate(&tracks, &detections, &config).unwrap();
        assert_eq!(
            (
                actual.dx.to_bits(),
                actual.dy.to_bits(),
                actual.inliers,
                actual.confidence.to_bits()
            ),
            (
                expected_dx,
                expected_dy,
                expected_inliers as usize,
                expected_confidence
            ),
            "case {case}: {actual:?}"
        );
    }
    assert_eq!(r.index, r.bytes.len());
}
