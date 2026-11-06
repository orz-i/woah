//! PTS-based camera composition from normalized visual top-left observations.
//! The caller owns subject identity. Missing observations keep the last trusted
//! aim point; the camera never assigns an identity or extrapolates a subject.
use crate::geometry::Rect;

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct FollowState {
    pub camera_x: f32,
    pub camera_y: f32,
    pub target_x: f32,
    pub target_y: f32,
    pub velocity_x: f32,
    pub velocity_y: f32,
    pub last_pts_us: i64,
    pub initialized: u32,
    pub has_time: u32,
}

impl Default for FollowState {
    fn default() -> Self {
        Self {
            camera_x: 0.5,
            camera_y: 0.5,
            target_x: 0.5,
            target_y: 0.5,
            velocity_x: 0.0,
            velocity_y: 0.0,
            last_pts_us: 0,
            initialized: 0,
            has_time: 0,
        }
    }
}

fn sign(value: f32) -> f32 {
    if value > 0.0 {
        1.0
    } else if value < 0.0 {
        -1.0
    } else {
        0.0
    }
}
fn move_toward(current: f32, target: f32, max_delta: f32) -> f32 {
    if (target - current).abs() <= max_delta {
        target
    } else {
        current + sign(target - current) * max_delta
    }
}

fn advance(current: f32, target: f32, velocity: f32, span: f32, alpha: f32, dt: f32) -> (f32, f32) {
    if span <= 0.0 || dt <= 0.0 {
        return (current, velocity);
    }
    let delta = target - current;
    let direction = sign(delta);
    let excess = (delta.abs() - span * 0.02).max(0.0);
    let desired = if excess <= 0.0 || direction == 0.0 {
        0.0
    } else {
        let lag = (excess / (span * 0.45)).clamp(0.0, 1.0);
        let eased = lag * lag * (3.0 - 2.0 * lag);
        let max_spans_per_second = 0.65 + (1.50 - 0.65) * eased;
        direction * (excess * alpha / dt).min(span * max_spans_per_second)
    };
    let braking =
        desired == 0.0 || (velocity != 0.0 && desired != 0.0 && sign(velocity) != sign(desired));
    let acceleration = span * if braking { 7.5 } else { 5.5 };
    let next_velocity = move_toward(velocity, desired, acceleration * dt);
    let next_position = current + next_velocity * dt;
    if delta != 0.0 && (target - current) * (target - next_position) <= 0.0 {
        (target, 0.0)
    } else {
        (next_position, next_velocity)
    }
}

impl FollowState {
    pub fn valid(self) -> bool {
        [
            self.camera_x,
            self.camera_y,
            self.target_x,
            self.target_y,
            self.velocity_x,
            self.velocity_y,
        ]
        .iter()
        .all(|v| v.is_finite())
            && self.initialized <= 1
            && self.has_time <= 1
    }

    pub fn step(
        &mut self,
        target: Option<Rect>,
        pts_us: i64,
        source_aspect: f32,
        output_aspect: f32,
        zoom: f32,
        smooth: f32,
    ) -> Option<Rect> {
        if !self.valid()
            || !source_aspect.is_finite()
            || source_aspect <= 0.0
            || !output_aspect.is_finite()
            || output_aspect <= 0.0
        {
            return None;
        }
        let zoom = if zoom.is_finite() {
            zoom.clamp(1.0, 3.0)
        } else {
            1.0
        };
        let width = (output_aspect / source_aspect).min(1.0) / zoom;
        let height = (source_aspect / output_aspect).min(1.0) / zoom;
        if width <= 0.0 || height <= 0.0 {
            return None;
        }
        if self.has_time == 1 && pts_us < self.last_pts_us {
            *self = Self::default();
        }
        let half_w = width / 2.0;
        let half_h = height / 2.0;
        if let Some(target) = target.filter(|t| t.valid()) {
            self.target_x =
                (target.left + (target.right - target.left) / 2.0).clamp(half_w, 1.0 - half_w);
            self.target_y =
                (target.top + (target.bottom - target.top) / 2.0).clamp(half_h, 1.0 - half_h);
            if self.initialized == 0 {
                self.camera_x = self.target_x;
                self.camera_y = self.target_y;
                self.velocity_x = 0.0;
                self.velocity_y = 0.0;
                self.initialized = 1;
            }
        }
        let dt = if self.has_time == 1 {
            (pts_us.saturating_sub(self.last_pts_us).max(0) as f32 / 1_000_000.0).min(0.1)
        } else {
            0.0
        };
        self.last_pts_us = pts_us;
        self.has_time = 1;
        if self.initialized == 1 && dt > 0.0 {
            let reference = if smooth.is_finite() {
                smooth.clamp(0.01, 1.0)
            } else {
                0.1
            };
            // Match the established Float32 base/exponent followed by a
            // Double pow and Float32 conversion; do not reassociate the inputs.
            let alpha = 1.0 - f64::from(1.0 - reference).powf(f64::from(dt * 30.0)) as f32;
            (self.camera_x, self.velocity_x) = advance(
                self.camera_x,
                self.target_x,
                self.velocity_x,
                width,
                alpha,
                dt,
            );
            (self.camera_y, self.velocity_y) = advance(
                self.camera_y,
                self.target_y,
                self.velocity_y,
                height,
                alpha,
                dt,
            );
        }
        let before_x = self.camera_x;
        let before_y = self.camera_y;
        let left = (self.camera_x - half_w).clamp(0.0, 1.0 - width);
        let top = (self.camera_y - half_h).clamp(0.0, 1.0 - height);
        self.camera_x = left + half_w;
        self.camera_y = top + half_h;
        if (self.camera_x - before_x).abs() > 1e-6 {
            self.velocity_x = 0.0;
        }
        if (self.camera_y - before_y).abs() > 1e-6 {
            self.velocity_y = 0.0;
        }
        Some(Rect {
            left,
            top,
            right: left + width,
            bottom: top + height,
        })
    }
}

/// Largest exact ratio with encoder-even dimensions. No project-specific ratio
/// is built into the core; consumers select their presentation format.
pub fn fit_even_ratio(
    width: u32,
    height: u32,
    max_height: u32,
    ratio_w: u32,
    ratio_h: u32,
) -> Option<(u32, u32)> {
    if [width, height, max_height, ratio_w, ratio_h].contains(&0) {
        return None;
    }
    let (mut a, mut b) = (ratio_w, ratio_h);
    while b != 0 {
        (a, b) = (b, a % b);
    }
    let base_w = (ratio_w / a).checked_mul(2)?;
    let base_h = (ratio_h / a).checked_mul(2)?;
    let units = (width / base_w).min(height.min(max_height) / base_h);
    if units == 0 {
        None
    } else {
        Some((base_w * units, base_h * units))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn target(x: f32) -> Rect {
        Rect {
            left: x - 0.04,
            top: 0.3,
            right: x + 0.04,
            bottom: 0.7,
        }
    }
    #[test]
    fn missing_observations_hold_identity_while_camera_brakes_and_seek_resets() {
        let mut state = FollowState::default();
        state
            .step(Some(target(0.2)), 0, 16.0 / 9.0, 9.0 / 16.0, 1.0, 0.1)
            .unwrap();
        state
            .step(Some(target(0.8)), 33_333, 16.0 / 9.0, 9.0 / 16.0, 1.0, 0.1)
            .unwrap();
        assert!(state.velocity_x > 0.0);
        let mut previous = state.camera_x;
        for i in 2..120 {
            state
                .step(None, i * 33_333, 16.0 / 9.0, 9.0 / 16.0, 1.0, 0.1)
                .unwrap();
            assert!(state.camera_x >= previous && state.camera_x <= 0.8);
            previous = state.camera_x;
        }
        state
            .step(Some(target(0.3)), 0, 16.0 / 9.0, 9.0 / 16.0, 1.0, 0.1)
            .unwrap();
        assert!((state.camera_x - 0.3).abs() < 1e-6);
        assert_eq!(state.velocity_x, 0.0);
    }
    #[test]
    fn exact_even_ratio_never_enlarges_or_returns_odd_encoder_sizes() {
        assert_eq!(fit_even_ratio(1920, 1080, 1280, 9, 16), Some((594, 1056)));
        assert_eq!(fit_even_ratio(3840, 2160, 1920, 9, 16), Some((1080, 1920)));
        assert_eq!(fit_even_ratio(1920, 1080, 1280, 16, 9), Some((1920, 1080)));
        assert_eq!(fit_even_ratio(17, 31, 1280, 9, 16), None);
        assert_eq!(fit_even_ratio(1920, 1080, 1280, 0, 9), None);
    }
}
