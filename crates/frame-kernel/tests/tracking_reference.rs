//! Frozen Android outputs, generated before replacing native tracking consumers.
use frame_kernel::{
    geometry::{Rect, Transform},
    tracking::{Detection, Mask, Tracker, TrackingConfig},
    tracking_mask::{conservative_mask, warp_mask, warped_iou},
};
use std::sync::Arc;
struct Reader {
    bytes: &'static [u8],
    index: usize,
}
impl Reader {
    fn take<const N: usize>(&mut self) -> [u8; N] {
        let value = self.bytes[self.index..self.index + N].try_into().unwrap();
        self.index += N;
        value
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
    fn boolean(&mut self) -> bool {
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
    fn mask(&mut self) -> Option<Vec<u8>> {
        let n = self.int();
        if n < 0 {
            return None;
        }
        let b = self.bytes[self.index..self.index + n as usize].to_vec();
        self.index += n as usize;
        Some(b)
    }
}
fn mask(seed: i32) -> Mask {
    Mask {
        width: 16,
        height: 16,
        pixels: Arc::from(
            (0..256)
                .map(|i| ((i * 17 + (i / 16) * 29 + seed * 37) & 255) as u8)
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
fn assert_mask(actual: Option<&Mask>, expected: Option<Vec<u8>>, context: &str) {
    assert_eq!(
        actual.map(|m| m.pixels.as_ref()),
        expected.as_deref(),
        "{context}"
    );
}
#[test]
fn masks_and_prediction_lifecycle_match_frozen_android_bytes() {
    let mut r = Reader {
        bytes: include_bytes!("../testdata/tracking-state-reference-v1.bin"),
        index: 0,
    };
    assert_eq!(r.int(), 0x464B5431);
    for _ in 0..r.int() {
        let seed = r.int();
        let previous = r.rect();
        let predicted = r.rect();
        let missed = r.int() as u32;
        let stride = r.int() as usize;
        assert_mask(
            warp_mask(&mask(seed), previous, predicted, missed).as_ref(),
            r.mask(),
            &format!("warp {seed}"),
        );
        let expected = r.float();
        let actual = warped_iou(
            Some(&mask(seed)),
            previous,
            predicted,
            Some(&mask(seed + 3)),
            stride,
        );
        assert_eq!(
            actual.to_bits(),
            expected.to_bits(),
            "fractional association {seed}"
        );
        assert_mask(
            conservative_mask(&mask(seed), predicted, missed).as_ref(),
            r.mask(),
            &format!("fallback {seed}"),
        );
    }
    for _ in 0..r.int() {
        let mode = r.int();
        let mut t = Tracker::new(TrackingConfig {
            max_missed_frames: 3,
            post_occlusion_grace_frames: 2,
            ..Default::default()
        });
        if mode == 1 {
            t.protected_ids.insert(5);
        }
        if mode == 2 {
            t.protected_ids.insert(5);
            t.selected_ids.insert(5);
            t.offscreen_dormancy = true;
        }
        t.initialize(
            vec![
                Detection {
                    bounds: Rect {
                        left: 420.0,
                        top: 150.0,
                        right: 980.0,
                        bottom: 940.0,
                    },
                    mask: Some(mask(0)),
                    confidence: 0.9,
                    foot_y: 940.0,
                },
                Detection {
                    bounds: Rect {
                        left: 1080.0,
                        top: 120.0,
                        right: 1690.0,
                        bottom: 1010.0,
                    },
                    mask: Some(mask(1)),
                    confidence: 0.8,
                    foot_y: 1010.0,
                },
            ],
            &[5, 2],
        )
        .unwrap();
        for step in 0..r.int() {
            let miss = r.boolean();
            let time = r.long();
            let actual = t.predict(time, miss).unwrap();
            let count = r.int() as usize;
            assert_eq!(actual.len(), count, "mode {mode} step {step}");
            for track in actual {
                assert_eq!(track.id, r.int() as i64);
                assert_eq!(track.state as i32, r.int());
                assert_eq!(track.missed_frames(), r.int() as u32);
                assert_eq!(track.frames_since_observation, r.int() as u32);
                assert_eq!(track.age, r.int() as u32);
                assert_eq!(track.observed_this_frame, r.boolean());
                assert_eq!(
                    track.predicted_bounds,
                    r.rect(),
                    "bounds mode {mode} step {step}"
                );
                assert_eq!(track.confidence.to_bits(), r.float().to_bits());
                assert_eq!(
                    track
                        .current_foot_y
                        .unwrap_or(track.observed_foot_y)
                        .to_bits(),
                    r.float().to_bits()
                );
                assert_mask(track.render_mask.as_ref(), r.mask(), "prediction mask");
            }
        }
    }
    assert_eq!(r.index, r.bytes.len());
}
