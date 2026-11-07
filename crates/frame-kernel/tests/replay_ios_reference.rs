//! Actual simulator observations bind the overmask diagnosis to real model inputs.
use frame_kernel::{
    geometry::{Rect, Transform, overlap_iou},
    tracking::{Detection, Mask, Tracker, TrackingConfig},
    tracking_evidence::AssociationCache,
};
use std::sync::Arc;
struct Reader {
    bytes: &'static [u8],
    at: usize,
}
impl Reader {
    fn u(&mut self) -> u32 {
        let v = u32::from_le_bytes(self.bytes[self.at..self.at + 4].try_into().unwrap());
        self.at += 4;
        v
    }
    fn id(&mut self) -> i64 {
        let v = i64::from_le_bytes(self.bytes[self.at..self.at + 8].try_into().unwrap());
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
    fn ids(&mut self) -> Vec<i64> {
        (0..self.u()).map(|_| self.id()).collect()
    }
    fn detection(&mut self) -> Detection {
        let bounds = self.rect();
        let confidence = self.f();
        let foot_y = self.f();
        let transform = Transform {
            scale: self.f(),
            pad_left: self.f(),
            pad_top: self.f(),
            source_width: self.u(),
            source_height: self.u(),
            input_size: self.u(),
            proto_size: self.u(),
        };
        let width = self.u() as usize;
        let height = self.u() as usize;
        let runs = self.u();
        let mut pixels = Vec::with_capacity(width * height);
        for _ in 0..runs {
            let count = self.u() as usize;
            let value = self.bytes[self.at];
            self.at += 1;
            assert!(count > 0 && pixels.len() + count <= width * height);
            pixels.resize(pixels.len() + count, value)
        }
        assert_eq!(pixels.len(), width * height);
        Detection {
            bounds,
            confidence,
            foot_y,
            mask: if width == 0 {
                None
            } else {
                Some(Mask {
                    width,
                    height,
                    pixels: Arc::from(pixels),
                    transform,
                })
            },
        }
    }
}
fn inspect(t: &Tracker, d: &[Detection], at: i64) {
    let mut p = t.clone();
    for track in &mut p.tracks {
        track.frames_since_observation += 1;
        track.observed_previous_frame = track.observed_this_frame;
        track.observed_this_frame = false;
        track.fresh_motion = None;
        track.current_foot_y = None;
        track.predicted_bounds = track.filter.predict(at);
        track.age += 1;
    }
    p.update_groups(at);
    println!(
        "INSPECT at={at} groups={:?}",
        p.groups.iter().map(|g| &g.members).collect::<Vec<_>>()
    );
    let mut cache = AssociationCache::default();
    for (ti, track) in p
        .tracks
        .iter()
        .enumerate()
        .filter(|(_, t)| t.id >= 4 && t.id <= 6)
    {
        println!(
            "TRACK id={} state={:?} predicted={:?} anchor={:?}",
            track.id, track.state, track.predicted_bounds, track.observed_bounds
        );
        for (di, det) in d.iter().enumerate() {
            let bi = overlap_iou(track.predicted_bounds, det.bounds);
            let mi = cache.mask_iou(track, d, di);
            let score = cache.score(&p, ti, d, di, at).unwrap();
            if bi > 0.05 || mi > 0.05 {
                println!(
                    "PAIR id={} candidate={di} bbox={:?} bi={bi:.7} mi={mi:.7} score={score:.7}",
                    track.id, det.bounds
                )
            }
        }
    }
}
#[test]
fn actual_simulator_observations_reproduce_the_recorded_native_state() {
    let bytes = include_bytes!("../testdata/replay-ios-hero-v1.bin");
    assert_eq!(&bytes[..4], b"FKP1");
    let mut r = Reader { bytes, at: 4 };
    assert_eq!(r.u(), 1);
    let mut t = Tracker::new(TrackingConfig::default());
    t.protected_ids = r.ids().into_iter().collect();
    t.selected_ids = r.ids().into_iter().collect();
    t.face_selected_ids = r.ids().into_iter().collect();
    let floor = r.id();
    let steps = r.u();
    assert_eq!(steps, 166);
    for _ in 0..steps {
        let at = r.id();
        let action = r.u();
        let assigned = r.ids();
        let d = (0..r.u()).map(|_| r.detection()).collect::<Vec<_>>();
        if std::env::var_os("FRAME_KERNEL_INSPECT_REAL_REPLAY").is_some()
            && [6080000, 6120000, 6600000].contains(&at)
        {
            inspect(&t, &d, at)
        }
        if action == 0 {
            t.initialize(d, &assigned).unwrap()
        } else {
            t.update(d, at).unwrap();
        }
        let count = r.u() as usize;
        assert_eq!(t.tracks.len(), count, "track count at {at}");
        for track in &t.tracks {
            assert_eq!(track.id, r.id(), "id at {at}");
            assert_eq!(track.state as u32, r.u(), "state id={} at {at}", track.id);
            assert_eq!(
                u32::from(track.observed_this_frame),
                r.u(),
                "observed id={} at {at}",
                track.id
            );
            assert_eq!(track.missed_frames(), r.u());
            let bounds = r.rect();
            let anchor = r.rect();
            assert_eq!(
                track.predicted_bounds, bounds,
                "bounds id={} at {at}",
                track.id
            );
            assert_eq!(
                track.observed_bounds, anchor,
                "anchor id={} at {at}",
                track.id
            );
        }
        if action == 0 {
            t.next_id = t.next_id.max(floor)
        }
    }
    assert_eq!(r.at, bytes.len());
}
