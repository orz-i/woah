use frame_kernel::{
    face_geometry::{self, HeadMaskSpec},
    face_localization::{self, Candidate},
    face_observations::{self, ViewObservation},
    geometry::{Rect, Transform},
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
#[test]
fn actual_hero295_duplicate_does_not_become_unowned_when_render_center_moves() {
    let bytes = include_bytes!("../testdata/hero-face-ownership-295-v1.bin");
    assert_eq!(bytes.len(), 9565);
    let mut r = Reader { bytes, at: 0 };
    assert_eq!(r.u(), 0x464b4f31);
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
    assert_eq!(r.q(), 11_800_000);
    let hero = r.q();
    assert_eq!(hero, 6);
    let people: Vec<_> = (0..r.u()).map(|_| (r.q(), r.rect())).collect();
    assert_eq!(people.len(), 8);
    let count = r.u() as usize;
    let runs = r.u();
    let mut pixels = Vec::with_capacity(count);
    for _ in 0..runs {
        let n = r.u() as usize;
        let value = r.bytes[r.at];
        r.at += 1;
        pixels.extend(std::iter::repeat_n(value, n));
    }
    assert_eq!(pixels.len(), 25600);
    assert_eq!(pixels.len(), count);
    let primary = r.u() as usize;
    let detail = r.u() as usize;
    assert_eq!((primary, detail), (6, 10));
    let mut faces = Vec::new();
    for _ in 0..primary + detail {
        let bounds = r.rect();
        let confidence = r.f();
        let n = r.f() as usize;
        assert!(n <= 6);
        let points: Vec<_> = (0..6).map(|_| [r.f(), r.f()]).collect();
        let yaw = r.f();
        let view = r.u();
        faces.push((
            Candidate {
                bounds,
                confidence,
                points: points[..n].to_vec(),
            },
            yaw,
            view,
        ));
    }
    assert_eq!(r.at, bytes.len());
    let body = people.iter().find(|p| p.0 == hero).unwrap().1;
    let spec = HeadMaskSpec {
        width: proto,
        height: proto,
        transform,
        person: body,
    };
    let owner = |face: &Candidate, yaw: f32, render_window: bool| {
        let scores: Vec<_> = people
            .iter()
            .map(|(id, person)| {
                if *id == hero {
                    let supported = if render_window {
                        face_geometry::detected(face, None, yaw)
                            .and_then(|region| {
                                face_observations::mask_support(&pixels, spec, region)
                            })
                            .is_some_and(|s| s[0] >= 4 && s[1] >= 12)
                    } else {
                        face_observations::face_mask_owned(&pixels, spec, face, yaw)
                    };
                    if !supported {
                        return -1.0;
                    }
                } else {
                    let Some(plan) = face_localization::plan(*person, width, height) else {
                        return -1.0;
                    };
                    if face_localization::select(
                        std::slice::from_ref(face),
                        plan.rect,
                        plan.anchor,
                        0.22,
                        0.04,
                    )
                    .is_none()
                    {
                        return -1.0;
                    }
                }
                face_observations::association_score(face, *person).unwrap_or(-1.0)
            })
            .collect();
        face_observations::unique_best(&scores, 0.08).map(|i| people[i].0)
    };
    let before: Vec<_> = faces.iter().map(|(f, y, _)| owner(f, *y, true)).collect();
    let after: Vec<_> = faces.iter().map(|(f, y, _)| owner(f, *y, false)).collect();
    let changed: Vec<_> = before
        .iter()
        .zip(&after)
        .enumerate()
        .filter(|(_, p)| p.0 != p.1)
        .map(|(i, _)| i)
        .collect();
    assert_eq!(changed, [14]);
    assert_eq!(before[14], None);
    assert_eq!(after[14], Some(hero));
    let old_region = face_geometry::detected(&faces[14].0, None, faces[14].1).unwrap();
    assert_eq!(
        face_observations::mask_support(&pixels, spec, old_region),
        Some([6, 8])
    );
    assert!(!face_observations::face_mask_owned(
        &vec![0; 25600],
        spec,
        &faces[14].0,
        faces[14].1
    ));
    let merge = |owners: &[Option<i64>]| {
        let views: Vec<_> = faces
            .iter()
            .zip(owners)
            .map(|((f, _, view), owner)| ViewObservation {
                bounds: f.bounds,
                view: *view,
                known_owner: u32::from(owner.is_some()),
                owner: owner.unwrap_or(0),
            })
            .collect();
        face_observations::merge_views(&views, primary, true).unwrap()
    };
    assert_eq!(merge(&before), [0, 1, 2, 3, 4, 5, 8, 9, 10, 14]);
    assert_eq!(merge(&after), [0, 1, 2, 3, 4, 5, 8, 9, 10]);
}
