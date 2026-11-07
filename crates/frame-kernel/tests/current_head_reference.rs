use frame_kernel::{
    face_geometry::{self, HeadMaskSpec},
    geometry::{Rect, Transform},
    temporal_region::Ellipse,
};

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}
impl Reader<'_> {
    fn u(&mut self) -> u32 {
        let v = u32::from_be_bytes(self.bytes[self.at..self.at + 4].try_into().unwrap());
        self.at += 4;
        v
    }
    fn q(&mut self) -> i64 {
        let v = i64::from_be_bytes(self.bytes[self.at..self.at + 8].try_into().unwrap());
        self.at += 8;
        v
    }
    fn f(&mut self) -> f32 {
        f32::from_bits(self.u())
    }
    fn rect(&mut self) -> Rect {
        Rect {
            left: self.f(),
            top: self.f(),
            right: self.f(),
            bottom: self.f(),
        }
    }
}
fn actual_input() -> (HeadMaskSpec, Vec<u8>, Rect) {
    let bytes = include_bytes!("../testdata/face-edge-ios-185-v1.bin");
    assert_eq!(bytes.len(), 12113);
    let mut r = Reader { bytes, at: 0 };
    assert_eq!(r.u(), 0x464b4531);
    let width = r.u();
    let height = r.u();
    let input_size = r.u();
    let proto = r.u();
    let transform = Transform {
        scale: r.f(),
        pad_left: r.f(),
        pad_top: r.f(),
        source_width: width,
        source_height: height,
        input_size,
        proto_size: proto,
    };
    assert_eq!((width, height, input_size, proto), (3840, 2160, 640, 160));
    assert_eq!(r.q(), 7_400_000);
    assert_eq!(r.q(), -2_185_002);
    let person = r.rect();
    let count = r.u() as usize;
    assert_eq!(count, 25600);
    let runs = r.u();
    let mut pixels = Vec::with_capacity(count);
    for _ in 0..runs {
        let n = r.u() as usize;
        let value = r.bytes[r.at];
        r.at += 1;
        assert!(pixels.len() + n <= count);
        pixels.extend(std::iter::repeat_n(value, n));
    }
    assert_eq!(pixels.len(), count);
    let independently_detected_partial_face = r.rect();
    assert_eq!(r.at, bytes.len());
    (
        HeadMaskSpec {
            width: proto,
            height: proto,
            transform,
            person,
        },
        pixels,
        independently_detected_partial_face,
    )
}
fn covered_core(region: Ellipse, face: Rect) -> f32 {
    let mut covered = 0;
    for y in 0..31 {
        for x in 0..31 {
            let sx = face.left + (face.right - face.left) * (0.15 + 0.70 * x as f32 / 30.0);
            let sy = face.top + (face.bottom - face.top) * (0.15 + 0.70 * y as f32 / 30.0);
            let dx = (sx - region.center_x) / region.radius_x;
            let dy = (sy - region.center_y) / region.radius_y;
            covered += u32::from(dx * dx + dy * dy <= 1.0);
        }
    }
    covered as f32 / (31 * 31) as f32
}
#[test]
fn actual_frame185_bbox_only_misses_visible_nose_and_mouth() {
    let (spec, pixels, face) = actual_input();
    let bbox_only = face_geometry::fallback(spec.person).unwrap();
    assert_eq!(covered_core(bbox_only, face), 0.0);
    let completed = face_geometry::current_head_region(Some(&pixels), spec, None).unwrap();
    assert_eq!(completed.current_mask_guided, 1);
    let mut through_abi = frame_kernel::face_geometry::CurrentHeadRegion {
        region: Ellipse::default(),
        current_mask_guided: 0,
    };
    // SAFETY: actual frozen mask storage and a disjoint live aligned output.
    let status = unsafe {
        frame_kernel::frame_kernel_current_head_region(
            pixels.as_ptr(),
            pixels.len(),
            spec,
            std::ptr::null(),
            &mut through_abi,
        )
    };
    assert_eq!(status, frame_kernel::REGION_OK);
    assert_eq!(through_abi, completed);
    assert!(covered_core(completed.region, face) >= 0.90);
    let center = face_geometry::estimate(&pixels, spec, bbox_only).unwrap();
    assert_eq!(
        [completed.region.center_x, completed.region.center_y],
        center
    );
    assert_eq!(
        (completed.region.radius_x, completed.region.radius_y),
        (bbox_only.radius_x, bbox_only.radius_y)
    );
}
#[test]
fn missing_empty_or_malformed_current_mask_never_invents_head_support() {
    let (spec, pixels, _) = actual_input();
    let bbox_only = face_geometry::fallback(spec.person).unwrap();
    for mask in [None, Some(vec![0; pixels.len()])] {
        let result = face_geometry::current_head_region(mask.as_deref(), spec, None).unwrap();
        assert_eq!(result.current_mask_guided, 0);
        assert_eq!(result.region, bbox_only);
    }
    assert!(
        face_geometry::current_head_region(Some(&pixels[..pixels.len() - 1]), spec, None).is_none()
    );
    let bad = Ellipse {
        center_x: f32::NAN,
        ..bbox_only
    };
    assert!(face_geometry::current_head_region(Some(&pixels), spec, Some(bad)).is_none());
}
