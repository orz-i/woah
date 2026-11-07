use frame_kernel::{
    face_geometry::{self, HeadMaskSpec},
    face_localization::Candidate,
    geometry::{Rect, Transform},
    temporal_region::Ellipse,
};
struct Reader {
    bytes: &'static [u8],
    at: usize,
}
impl Reader {
    fn u(&mut self) -> u32 {
        let v = u32::from_be_bytes(self.bytes[self.at..self.at + 4].try_into().unwrap());
        self.at += 4;
        v
    }
    fn f(&mut self) -> f32 {
        f32::from_bits(self.u())
    }
    fn flag(&mut self) -> bool {
        let v = self.bytes[self.at];
        self.at += 1;
        v != 0
    }
    fn rect(&mut self) -> Rect {
        Rect {
            left: self.f(),
            top: self.f(),
            right: self.f(),
            bottom: self.f(),
        }
    }
    fn region(&mut self, kind: u32) -> Option<Ellipse> {
        if !self.flag() {
            return None;
        }
        let out = Ellipse {
            center_x: self.f(),
            center_y: self.f(),
            radius_x: self.f(),
            radius_y: self.f(),
        };
        assert_eq!(self.u(), kind);
        Some(out)
    }
}
fn bits(e: Option<Ellipse>) -> Option<[u32; 4]> {
    e.map(|r| {
        [
            r.center_x.to_bits(),
            r.center_y.to_bits(),
            r.radius_x.to_bits(),
            r.radius_y.to_bits(),
        ]
    })
}
#[test]
fn all_native_face_regions_and_mask_guided_heads_match_frozen_float_bits() {
    let mut r = Reader {
        bytes: include_bytes!("../testdata/face-region-native-v1.bin"),
        at: 0,
    };
    assert_eq!(r.u(), 0x464B4731);
    assert_eq!(r.u(), 512);
    for i in 0..512 {
        let fw = r.u();
        let fh = r.u();
        let person = r.rect();
        let view = r.rect();
        let size = r.u();
        let bounds = r.rect();
        let confidence = r.f();
        let points = (0..r.u()).map(|_| [r.f(), r.f()]).collect();
        let face = Candidate {
            bounds,
            confidence,
            points,
        };
        assert_eq!(
            bits(face_geometry::detected(
                &face,
                Some((view, size as f32)),
                0.0
            )),
            bits(r.region(0)),
            "detected case{i}"
        );
        assert_eq!(
            bits(face_geometry::fallback(person)),
            bits(r.region(2)),
            "fallback case{i}"
        );
        let scale = r.f();
        let pad_left = r.f();
        let pad_top = r.f();
        let seed = Ellipse {
            center_x: r.f(),
            center_y: r.f(),
            radius_x: r.f(),
            radius_y: r.f(),
        };
        let spec = HeadMaskSpec {
            width: 160,
            height: 160,
            person,
            transform: Transform {
                scale,
                pad_left,
                pad_top,
                source_width: fw,
                source_height: fh,
                input_size: 640,
                proto_size: 160,
            },
        };
        let mut pixels = Vec::with_capacity(25600);
        for _ in 0..r.u() {
            let n = r.u() as usize;
            let value = r.bytes[r.at];
            r.at += 1;
            assert!(n > 0 && pixels.len() + n <= 25600);
            pixels.resize(pixels.len() + n, value)
        }
        assert_eq!(pixels.len(), 25600);
        let expected = if r.flag() {
            Some([r.f().to_bits(), r.f().to_bits()])
        } else {
            None
        };
        assert_eq!(
            face_geometry::estimate(&pixels, spec, seed).map(|p| p.map(f32::to_bits)),
            expected,
            "head case{i}"
        );
        assert_eq!(
            bits(face_geometry::trusted_fallback(
                &pixels, spec, person, seed, 1.10
            )),
            bits(r.region(2)),
            "trusted case{i}"
        );
    }
    assert_eq!(r.at, r.bytes.len());
}
