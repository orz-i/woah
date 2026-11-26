//! Cutout-specific session adapter. The common Tracker owns person identities,
//! association, motion and observation state. This adapter owns source PTS
//! direction and selection policy, not another tracking engine.
use crate::{
    appearance::{APPEARANCE_BINS, Appearance, feature, present},
    geometry::Transform,
    instances::InstanceDetection,
    tracking::{Detection, Mask, Tracker},
};
use std::sync::Arc;

pub(crate) const MIN_CONF: f32 = 0.20;
pub(crate) const STRONG_CONF: f32 = 0.25;

#[derive(Clone)]
pub(crate) struct CutoutClock {
    pub chosen: i64,
    pub anchor: i64,
    pub last: i64,
    pub direction: i64,
    pub transform: Transform,
    pub initial_competitors: Vec<Appearance>,
}
#[derive(Default, Clone)]
pub(crate) struct CutoutPolicy {
    pub clock: Option<CutoutClock>,
    // Unconfirmed observations cannot enter the selected identity memory.
    pub pending: Option<crate::tracking_cutout::PendingMatch>,
}
#[cfg(test)]
struct DanceCutout {
    tracker: Tracker,
    policy: CutoutPolicy,
}
#[cfg(test)]
impl Default for DanceCutout {
    fn default() -> Self {
        Self {
            tracker: Tracker::new(crate::tracking::TrackingConfig::default()),
            policy: CutoutPolicy::default(),
        }
    }
}
#[cfg(test)]
impl DanceCutout {
    pub fn step(
        &mut self,
        at: i64,
        seed: i32,
        detections: &[InstanceDetection],
        masks: &[u8],
        transform: Transform,
        features: &[f32],
    ) -> Option<i32> {
        step(
            &mut self.tracker,
            &mut self.policy,
            at,
            seed,
            CutoutObservation {
                detections,
                masks,
                transform,
                features,
            },
        )
    }
}
pub(crate) struct CutoutObservation<'a> {
    pub detections: &'a [InstanceDetection],
    pub masks: &'a [u8],
    pub transform: Transform,
    pub features: &'a [f32],
}
pub(crate) fn step(
    tracker: &mut Tracker,
    policy: &mut CutoutPolicy,
    at: i64,
    seed: i32,
    observation: CutoutObservation<'_>,
) -> Option<i32> {
    let CutoutObservation {
        detections,
        masks,
        transform,
        features,
    } = observation;
    if at < 0
        || seed < -1
        || !transform.valid()
        || detections.len() > 256
        || detections
            .iter()
            .any(|d| !d.bounds.valid() || !(0. ..=1.).contains(&d.confidence))
    {
        return None;
    }
    let area = (transform.proto_size as usize).checked_mul(transform.proto_size as usize)?;
    if masks.len() != detections.len().checked_mul(area)?
        || features.len() != detections.len().checked_mul(APPEARANCE_BINS)?
    {
        return None;
    }
    let values = features
        .chunks_exact(APPEARANCE_BINS)
        .map(feature)
        .collect::<Option<Vec<_>>>()?;
    let observations = detections
        .iter()
        .zip(masks.chunks_exact(area))
        .map(|(d, m)| Detection {
            bounds: d.bounds,
            confidence: d.confidence,
            foot_y: d.bounds.bottom,
            mask: Some(Mask {
                width: transform.proto_size as usize,
                height: transform.proto_size as usize,
                pixels: Arc::from(m),
                transform,
            }),
        })
        .collect::<Vec<_>>();
    if seed >= 0 {
        let j = seed as usize;
        let d = observations.get(j)?;
        if policy.clock.is_some()
            || d.confidence < STRONG_CONF
            || !d.mask.as_ref()?.pixels.iter().any(|x| *x >= 128)
        {
            return None;
        }
        let chosen = i64::from(seed);
        tracker
            .initialize_appearance(&observations, &values, chosen)
            .ok()?;
        policy.clock = Some(CutoutClock {
            chosen,
            anchor: at,
            last: at,
            direction: 0,
            transform,
            initial_competitors: values
                .iter()
                .enumerate()
                .filter_map(|(i, a)| (i != j && present(a)).then_some(*a))
                .collect(),
        });
        return Some(seed);
    }
    let mut clock = policy.clock.clone()?;
    let delta = at.checked_sub(clock.last)?;
    if delta == 0
        || (clock.direction != 0 && delta.signum() != clock.direction)
        || transform != clock.transform
    {
        return None;
    }
    let elapsed = at
        .checked_sub(clock.anchor)?
        .checked_abs()?
        .checked_add(1)?;
    let selected =
        tracker.update_cutout(&observations, &values, elapsed, &clock, &mut policy.pending)?;
    clock.last = at;
    clock.direction = delta.signum();
    policy.clock = Some(clock);
    Some(selected.map_or(-1, |i| i as i32))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geometry::Rect;
    fn t() -> Transform {
        Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 0.,
            source_width: 100,
            source_height: 100,
            input_size: 100,
            proto_size: 4,
        }
    }
    fn d(left: f32, confidence: f32) -> InstanceDetection {
        InstanceDetection {
            bounds: Rect {
                left,
                top: 0.,
                right: left + 30.,
                bottom: 80.,
            },
            confidence,
        }
    }
    fn f(bin: usize) -> Appearance {
        let mut a = [0.; APPEARANCE_BINS];
        a[bin] = 1.;
        a
    }
    fn step(
        lane: &mut DanceCutout,
        at: i64,
        seed: i32,
        ds: &[InstanceDetection],
        fs: &[Appearance],
    ) -> Option<i32> {
        let features = fs.iter().flatten().copied().collect::<Vec<_>>();
        lane.step(at, seed, ds, &vec![255; ds.len() * 16], t(), &features)
    }
    #[test]
    fn distractor_cannot_take_identity_at_the_old_location() {
        let mut lane = DanceCutout::default();
        assert_eq!(
            step(&mut lane, 0, 0, &[d(0., 0.9), d(45., 0.9)], &[f(0), f(4)]),
            Some(0)
        );
        assert_eq!(
            step(&mut lane, 40_000, -1, &[d(0., 0.9)], &[f(4)]),
            Some(-1)
        );
        assert_eq!(step(&mut lane, 80_000, -1, &[d(1., 0.9)], &[f(0)]), Some(0));
    }
    #[test]
    fn reacquisition_requires_two_current_observations_and_is_bounded() {
        let mut lane = DanceCutout::default();
        assert_eq!(step(&mut lane, 0, 0, &[d(0., 0.9)], &[f(0)]), Some(0));
        assert_eq!(
            step(&mut lane, 300_000, -1, &[d(3., 0.9)], &[f(0)]),
            Some(-1)
        );
        assert_eq!(
            step(&mut lane, 340_000, -1, &[d(4., 0.9)], &[f(0)]),
            Some(0)
        );
        assert_eq!(step(&mut lane, 340_000, -1, &[d(5., 0.9)], &[f(0)]), None);
        assert_eq!(
            step(&mut lane, 5_000_000, -1, &[d(5., 0.9)], &[f(0)]),
            Some(-1)
        );
        assert_eq!(
            step(&mut lane, 5_040_000, -1, &[d(5., 0.9)], &[f(0)]),
            Some(-1)
        );
    }
    #[test]
    fn reverse_weak_requires_unique_current_match() {
        let mut lane = DanceCutout::default();
        assert_eq!(step(&mut lane, 400_000, 0, &[d(0., 0.9)], &[f(0)]), Some(0));
        assert_eq!(
            step(&mut lane, 360_000, -1, &[d(0., 0.22)], &[f(0)]),
            Some(0)
        );
        assert_eq!(
            step(
                &mut lane,
                320_000,
                -1,
                &[d(0., 0.22), d(1., 0.22)],
                &[f(0), f(0)]
            ),
            Some(-1)
        );
        assert_eq!(step(&mut lane, 340_000, -1, &[], &[]), None);
    }
    #[test]
    fn established_competitor_keeps_ownership_even_with_same_appearance() {
        // Two visually identical people. The competitor walks toward the
        // selected person's last location. The selected person disappears.
        // A position-only selected lane could seize the competitor.
        let transform = Transform {
            scale: 1.,
            pad_left: 0.,
            pad_top: 0.,
            source_width: 160,
            source_height: 160,
            input_size: 160,
            proto_size: 16,
        };
        let mut lane = DanceCutout::default();
        let feature = f(0);
        let detection = |left: f32| InstanceDetection {
            bounds: Rect {
                left,
                top: 20.,
                right: left + 30.,
                bottom: 100.,
            },
            confidence: 0.9,
        };
        let mut feed = |at: i64, seed: i32, positions: &[f32]| {
            let ds = positions.iter().map(|&x| detection(x)).collect::<Vec<_>>();
            let masks = positions
                .iter()
                .flat_map(|&x| {
                    (0..256).map(move |i| {
                        let px = (i % 16) as f32 * 10. + 5.;
                        let py = (i / 16) as f32 * 10. + 5.;
                        if px >= x && px < x + 30. && (20. ..100.).contains(&py) {
                            255
                        } else {
                            0
                        }
                    })
                })
                .collect::<Vec<u8>>();
            let features = positions.iter().flat_map(|_| feature).collect::<Vec<_>>();
            lane.step(at, seed, &ds, &masks, transform, &features)
        };
        assert_eq!(feed(0, 0, &[10., 70.]), Some(0));
        for (i, x) in [64., 58., 52., 46.].iter().enumerate() {
            // Detection order changes; stable ownership cannot depend on index.
            assert_eq!(feed((i as i64 + 1) * 40_000, -1, &[*x, 10.]), Some(1));
        }
        for (i, x) in [40., 34., 28., 22.].iter().enumerate() {
            assert_eq!(feed((i as i64 + 5) * 40_000, -1, &[*x]), Some(-1));
        }
        assert_eq!(lane.tracker.tracks.len(), 2);
        assert_eq!(
            lane.tracker
                .tracks
                .iter()
                .find(|t| t.id == 0)
                .unwrap()
                .observed_bounds
                .left,
            10.
        );
        assert_eq!(
            lane.tracker
                .tracks
                .iter()
                .find(|t| t.id == 1)
                .unwrap()
                .observed_bounds
                .left,
            22.
        );
    }

    #[test]
    fn features_are_mask_scoped_and_zero_for_too_few_pixels() {
        let mut output = vec![0.; 2 * APPEARANCE_BINS];
        let mut mask = vec![255; 32];
        mask[16..].fill(0);
        mask[16] = 255;
        crate::appearance::appearance_features(t(), &mask, 2, |_, _| [10, 10, 10], &mut output)
            .unwrap();
        assert_eq!(output[0], 1.);
        assert!(output[APPEARANCE_BINS..].iter().all(|v| *v == 0.));
    }
}
