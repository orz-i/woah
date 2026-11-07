//! Frozen native tracker outputs, with full observation, miss, skip, overlap,
//! ambiguous class evidence, recovery, and scene-motion transitions.
use frame_kernel::{
    geometry::{Rect, Transform},
    tracking::{Detection, Mask, SelectionClass, Tracker, TrackingConfig},
};
use std::sync::Arc;
struct Reader {
    bytes: &'static [u8],
    position: usize,
}
impl Reader {
    fn take<const N: usize>(&mut self) -> [u8; N] {
        let v = self.bytes[self.position..self.position + N]
            .try_into()
            .unwrap();
        self.position += N;
        v
    }
    fn int(&mut self) -> i32 {
        i32::from_be_bytes(self.take())
    }
    fn long(&mut self) -> i64 {
        i64::from_be_bytes(self.take())
    }
    fn float(&mut self) -> f32 {
        f32::from_bits(u32::from_be_bytes(self.take()))
    }
    fn flag(&mut self) -> bool {
        self.take::<1>()[0] != 0
    }
    fn rect(&mut self) -> Rect {
        Rect {
            left: self.float(),
            top: self.float(),
            right: self.float(),
            bottom: self.float(),
        }
    }
    fn ids(&mut self) -> Vec<i64> {
        (0..self.int()).map(|_| self.int() as i64).collect()
    }
    fn mask(&mut self) -> Option<Vec<u8>> {
        let size = self.int();
        if size < 0 {
            return None;
        }
        let b = self.bytes[self.position..self.position + size as usize].to_vec();
        self.position += size as usize;
        Some(b)
    }
    fn input(&mut self) -> Vec<Detection> {
        (0..self.int())
            .map(|_| {
                let bounds = self.rect();
                let confidence = self.float();
                let foot_y = self.float();
                let seed = self.int();
                Detection {
                    bounds,
                    confidence,
                    foot_y,
                    mask: Some(mask(seed)),
                }
            })
            .collect()
    }
    fn classes(&mut self) -> Vec<(u32, usize, Vec<i64>, bool)> {
        (0..self.int())
            .map(|_| {
                (
                    self.int() as u32,
                    self.int() as usize,
                    self.ids(),
                    self.flag(),
                )
            })
            .collect()
    }
}
fn mask(seed: i32) -> Mask {
    Mask {
        width: 16,
        height: 16,
        pixels: Arc::from(
            (0..256)
                .map(|i| ((i * 17 + i / 16 * 29 + seed * 37) & 255) as u8)
                .collect::<Vec<_>>(),
        ),
        transform: Transform {
            scale: 640.0 / 1920.0,
            pad_left: 0.0,
            pad_top: 140.0,
            source_width: 1920,
            source_height: 1080,
            input_size: 640,
            proto_size: 16,
        },
    }
}
fn class_code(v: SelectionClass) -> u32 {
    if v == SelectionClass::Selected { 0 } else { 1 }
}
#[test]
fn complete_observation_update_matches_frozen_native_bytes() {
    let mut reader = Reader {
        bytes: include_bytes!("../testdata/tracking-update-reference-v1.bin"),
        position: 0,
    };
    assert_eq!(reader.int(), 0x464B5531);
    for _ in 0..reader.int() {
        let mode = reader.int();
        let mut t = Tracker::new(TrackingConfig {
            max_missed_frames: (3 + mode % 3) as u32,
            post_occlusion_grace_frames: 3,
            scene_motion_compensation: mode >= 6,
            ..Default::default()
        });
        t.protected_ids.extend(reader.ids());
        t.selected_ids.extend(reader.ids());
        t.face_selected_ids.extend(reader.ids());
        t.offscreen_dormancy = mode % 2 == 0;
        let initial = reader.input();
        let identities = reader.ids();
        t.initialize(initial, &identities).unwrap();
        for frame in 0..reader.int() {
            let command = reader.int();
            let at = reader.long();
            let detections = reader.input();
            match command {
                1 => {
                    t.predict(at, true).unwrap();
                }
                2 => {
                    t.predict(at, false).unwrap();
                }
                _ => {
                    t.update(detections, at).unwrap();
                }
            }
            let context = format!("mode {mode} frame {frame} command {command}");
            assert_eq!(
                t.tracks.len(),
                reader.int() as usize,
                "{context} track count"
            );
            for actual in &t.tracks {
                let id = reader.int() as i64;
                let state = reader.int() as u32;
                let missed = reader.int() as u32;
                let age = reader.int() as u32;
                let lifetime = reader.int() as u32;
                let observed = reader.flag();
                let bounds = reader.rect();
                let confidence = reader.float();
                let foot = reader.float();
                let occluders = reader.ids();
                let mask = reader.mask();
                assert_eq!(actual.id, id, "{context} identity");
                assert_eq!(actual.state as u32, state, "{context} id {id} state");
                assert_eq!(actual.missed_frames(), missed, "{context} id {id} missed");
                assert_eq!(
                    actual.frames_since_observation, age,
                    "{context} id {id} observation age"
                );
                assert_eq!(actual.age, lifetime, "{context} id {id} lifetime");
                assert_eq!(
                    actual.observed_this_frame, observed,
                    "{context} id {id} observation"
                );
                assert_eq!(
                    [
                        actual.predicted_bounds.left.to_bits(),
                        actual.predicted_bounds.top.to_bits(),
                        actual.predicted_bounds.right.to_bits(),
                        actual.predicted_bounds.bottom.to_bits()
                    ],
                    [
                        bounds.left.to_bits(),
                        bounds.top.to_bits(),
                        bounds.right.to_bits(),
                        bounds.bottom.to_bits()
                    ],
                    "{context} id {id} bounds actual {:?} expected {:?}",
                    actual.predicted_bounds,
                    bounds
                );
                assert_eq!(
                    actual.confidence.to_bits(),
                    confidence.to_bits(),
                    "{context}"
                );
                assert_eq!(
                    actual
                        .current_foot_y
                        .unwrap_or(actual.observed_foot_y)
                        .to_bits(),
                    foot.to_bits(),
                    "{context}"
                );
                let mut ids = actual.occluder_ids.clone();
                ids.sort();
                assert_eq!(ids, occluders, "{context} id {id} occluders");
                assert_eq!(
                    actual.render_mask.as_ref().map(|m| m.pixels.as_ref()),
                    mask.as_deref(),
                    "{context} id {id} render mask"
                );
            }
            for actual in [&t.class_evidence, &t.face_class_evidence] {
                let expected = reader.classes();
                let got = actual
                    .iter()
                    .map(|e| {
                        let mut ids = e.residual_ids.clone();
                        ids.sort();
                        (
                            class_code(e.selection),
                            e.detection_index,
                            ids,
                            e.conservative_unknown,
                        )
                    })
                    .collect::<Vec<_>>();
                assert_eq!(got, expected, "{context} class evidence");
            }
            assert_eq!(
                t.suppressed_selected_ids
                    .iter()
                    .copied()
                    .collect::<Vec<_>>(),
                reader.ids(),
                "{context} suppressed"
            );
            let hard = (0..reader.int())
                .map(|_| (reader.int() as usize, reader.int() as u32))
                .collect::<Vec<_>>();
            assert_eq!(
                t.hard_classes
                    .iter()
                    .map(|(&k, &v)| (k, class_code(v)))
                    .collect::<Vec<_>>(),
                hard,
                "{context} hard classes"
            );
            let mut expected_motion = (0..reader.int())
                .map(|_| {
                    (
                        reader.int() as i64,
                        reader.int() as usize,
                        reader.float().to_bits(),
                        reader.float().to_bits(),
                        reader.float().to_bits(),
                        reader.long(),
                    )
                })
                .collect::<Vec<_>>();
            expected_motion.sort();
            let got = t
                .motion_evidence
                .values()
                .map(|e| {
                    (
                        e.id,
                        e.detection_index,
                        e.score.to_bits(),
                        e.bbox_iou.to_bits(),
                        e.mask_iou.to_bits(),
                        e.timestamp_us,
                    )
                })
                .collect::<Vec<_>>();
            assert_eq!(got, expected_motion, "{context} motion evidence");
        }
    }
    assert_eq!(reader.position, reader.bytes.len());
}
