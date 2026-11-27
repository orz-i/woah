//! Per-owner final face render size. Geometry only; callers must independently
//! establish a current required region and clear history on identity changes.
//! Never projects a position, invents a region, or grants an exemption.
use crate::{face_geometry::valid_seed, temporal_region::Ellipse};

// Same contraction time as the observed-region stabilizer. The direct face
// projection lease bounds reuse across discontinuities, not continuous motion.
const SHRINK_TAU_US: f64 = 240_000.0;
const MAX_GAP_US: i64 = 150_000;

/// Caller-owned state; all-zero initialization. Store the final major radius,
/// not the detector radius or the previous face position.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct FaceRenderSizeState {
    pub timestamp_us: i64,
    pub observed_us: i64,
    pub major_radius: f32,
    /// Zero means no direct observation in this continuous history.
    pub observed_major: f32,
    pub initialized: u32,
}

impl FaceRenderSizeState {
    /// Evidence kind follows RegionMeasurement: 0 direct, 1 projected,
    /// 2 estimated. Weak evidence is not allowed to renew the observed lease.
    pub fn step(&mut self, required: Ellipse, timestamp_us: i64, kind: u32) -> Option<Ellipse> {
        if !valid_seed(required)
            || timestamp_us < 0
            || kind > 2
            || self.initialized > 1
            || (self.initialized == 1
                && (!self.major_radius.is_finite()
                    || self.major_radius <= 0.0
                    || self.timestamp_us < 0
                    || !self.observed_major.is_finite()
                    || self.observed_major < 0.0
                    || (self.observed_major > 0.0
                        && !(0..=self.timestamp_us).contains(&self.observed_us))))
        {
            return None;
        }
        let current = f64::from(required.radius_x.max(required.radius_y));
        let dt = timestamp_us.saturating_sub(self.timestamp_us);
        let continuous = self.initialized == 1 && (0..=MAX_GAP_US).contains(&dt);
        let (mut observed_us, mut observed_major) = if continuous {
            (self.observed_us, self.observed_major)
        } else {
            (0, 0.0)
        };
        let prior = f64::from(self.major_radius);
        let mut major = if continuous && prior > current {
            current + (prior - current) * (-(dt as f64) / SHRINK_TAU_US).exp()
        } else {
            // Real growth is immediate; no stale size on a seek or evidence gap.
            current
        };
        // A projected or estimated size is not a new measurement of a smaller
        // head. Retain the last observed render size within the existing
        // 150ms observation lease. The current center and any larger current
        // extent still win. This history cannot make an unowned face renderable.
        if kind != 0
            && observed_major > 0.0
            && timestamp_us.saturating_sub(observed_us) <= MAX_GAP_US
        {
            major = major.max(f64::from(observed_major));
        }
        let scale = |axis: f32| {
            let value = f64::from(axis) / current * major;
            let rounded = value as f32;
            let outward = if f64::from(rounded) < value {
                rounded.next_up()
            } else {
                rounded
            };
            axis.max(outward)
        };
        let output = Ellipse {
            radius_x: scale(required.radius_x),
            radius_y: scale(required.radius_y),
            ..required
        };
        if !valid_seed(output) {
            return None;
        }
        let output_major = output.radius_x.max(output.radius_y);
        if kind == 0 {
            observed_us = timestamp_us;
            observed_major = output_major;
        }
        *self = Self {
            timestamp_us,
            observed_us,
            major_radius: output_major,
            observed_major,
            initialized: 1,
        };
        Some(output)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn region(x: f32, radius: f32) -> Ellipse {
        Ellipse {
            center_x: x,
            center_y: 100.0,
            radius_x: radius * 0.8,
            radius_y: radius,
        }
    }

    #[test]
    fn alternating_required_extent_cannot_snap_to_a_small_projected_region() {
        let mut state = FaceRenderSizeState::default();
        // Independent synthetic detector/support lane transition: no device
        // observations are reverse-engineered from the Issue's output widths.
        let radii = [110.0, 106.0, 108.0, 112.0, 61.0, 60.0, 110.0];
        let mut rendered: Vec<f32> = Vec::new();
        let kinds = [0, 0, 0, 0, 1, 2, 0];
        for (i, radius) in radii.into_iter().enumerate() {
            let required = region(100.0 + i as f32 * 12.0, radius);
            let out = state.step(required, i as i64 * 100_000, kinds[i]).unwrap();
            assert_eq!(out.center_x, required.center_x);
            assert_eq!(out.center_y, required.center_y);
            assert!(out.radius_x >= required.radius_x && out.radius_y >= required.radius_y);
            rendered.push(out.radius_y);
        }
        let min = rendered.iter().copied().fold(f32::INFINITY, f32::min);
        let max = rendered.iter().copied().fold(0.0, f32::max);
        assert!(max / min <= 1.35, "{rendered:?}");
    }

    #[test]
    fn weak_size_lease_expires_and_is_never_renewed_by_weak_frames() {
        let mut state = FaceRenderSizeState::default();
        let large = region(100.0, 100.0);
        let small = region(130.0, 50.0);
        state.step(large, 0, 0).unwrap();
        for time in [30_000, 60_000, 90_000, 120_000, 150_000] {
            let out = state.step(small, time, 1).unwrap();
            assert_eq!(out.radius_y, large.radius_y);
            assert_eq!(out.center_x, small.center_x);
            assert_eq!(state.observed_us, 0);
        }
        let out = state.step(small, 180_000, 2).unwrap();
        assert!(out.radius_y < large.radius_y && out.radius_y > small.radius_y);
        assert_eq!(state.observed_us, 0);
        // Identity transport must clear this state; time discontinuity also
        // clears it even when the next observation is weak.
        assert_eq!(state.step(small, 500_000, 1), Some(small));
        assert_eq!(state.observed_major, 0.0);
    }

    #[test]
    fn growth_is_immediate_and_sustained_real_shrink_converges() {
        let mut state = FaceRenderSizeState::default();
        let large = region(200.0, 100.0);
        let small = region(205.0, 50.0);
        assert_eq!(state.step(small, 0, 0), Some(small));
        assert_eq!(state.step(large, 33_333, 0), Some(large));
        let mut previous = large.radius_y;
        for i in 2..=60 {
            let out = state.step(small, i * 33_333, 0).unwrap();
            assert!(out.radius_y <= previous && out.radius_y >= small.radius_y);
            assert_eq!(out.center_x, small.center_x);
            previous = out.radius_y;
        }
        assert!(previous < 50.1);
    }

    #[test]
    fn discontinuity_repeat_and_invalid_input_are_explicit() {
        let mut state = FaceRenderSizeState::default();
        let large = region(200.0, 100.0);
        let small = region(205.0, 50.0);
        state.step(large, 1_000_000, 0).unwrap();
        let held = state.step(small, 1_000_000, 0).unwrap();
        assert_eq!(held.radius_y, large.radius_y);
        assert_eq!(held.center_x, small.center_x);
        assert_eq!(state.step(small, 1_150_001, 0), Some(small));
        state.step(large, 1_200_000, 0).unwrap();
        assert_eq!(state.step(small, 10_000, 0), Some(small));
        let prior = state;
        let mut bad = large;
        bad.radius_x = f32::NAN;
        assert_eq!(state.step(bad, 20_000, 0), None);
        assert_eq!(state, prior);
        assert_eq!(state.step(large, -1, 0), None);
        assert_eq!(state, prior);
    }

    #[test]
    fn ffi_does_not_write_on_invalid_input() {
        let mut state = FaceRenderSizeState::default();
        let mut out = region(99.0, 30.0);
        let sentinel = out;
        let mut bad = out;
        bad.center_y = f32::NAN;
        // SAFETY: valid, aligned, disjoint caller-owned buffers.
        let result =
            unsafe { crate::frame_kernel_face_render_size_step(&mut state, bad, 0, 0, &mut out) };
        assert_eq!(result, -1);
        assert_eq!(state, FaceRenderSizeState::default());
        assert_eq!(out, sentinel);
    }
}
