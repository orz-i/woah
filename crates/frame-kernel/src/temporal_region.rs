//! Time-stable region geometry. Anchor motion is separate from local evidence.
use crate::geometry::Rect;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Ellipse {
    pub center_x: f32,
    pub center_y: f32,
    pub radius_x: f32,
    pub radius_y: f32,
}
impl Ellipse {
    fn valid(self) -> bool {
        [self.center_x, self.center_y, self.radius_x, self.radius_y]
            .into_iter()
            .all(f32::is_finite)
            && self.radius_x > 0.0
            && self.radius_y > 0.0
    }
}

/// Flags: current observed anchor, trusted local center, half-pixel lattice,
/// translation-only weak measurement, fully cover current measured extent.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct RegionMeasurement {
    pub region: Ellipse,
    pub anchor: Rect,
    pub timestamp_us: i64,
    /// 0 observed, 1 projected, 2 estimated. These are geometry evidence kinds,
    /// not identities or detector framework names.
    pub kind: u32,
    pub flags: u32,
}

/// Caller-owned per-key history; no identity, allocation or platform pointer.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct RegionState {
    pub output: Ellipse,
    pub reference_radius_x: f32,
    pub reference_radius_y: f32,
    pub reference_width: f32,
    pub reference_height: f32,
    pub anchor: Rect,
    pub timestamp_us: i64,
    /// Initialized, has observed-size reference, previous anchor observed.
    pub flags: u32,
}

fn axis_translation(first: f32, second: f32, dimension: f32) -> f32 {
    let tolerance = 8.0_f32.max(dimension * 0.07);
    if (first - second).abs() <= tolerance
        || (first * second < 0.0 && (first.abs() - second.abs()).abs() <= tolerance)
    {
        (first + second) * 0.5
    } else if first.abs() <= second.abs() {
        first
    } else {
        second
    }
}
pub fn anchor_translation(previous: Rect, current: Rect) -> Option<[f32; 2]> {
    if !previous.valid() || !current.valid() {
        return None;
    }
    let width =
        (((previous.right - previous.left) + (current.right - current.left)) * 0.5).max(1.0);
    let height =
        (((previous.bottom - previous.top) + (current.bottom - current.top)) * 0.5).max(1.0);
    let out = [
        axis_translation(
            current.left - previous.left,
            current.right - previous.right,
            width,
        ),
        axis_translation(
            current.top - previous.top,
            current.bottom - previous.bottom,
            height,
        ),
    ];
    out.iter().all(|v| v.is_finite()).then_some(out)
}
fn lerp(a: f32, b: f32, alpha: f32) -> f32 {
    a + (b - a) * alpha
}
fn lattice(value: f32) -> f32 {
    // Match JVM roundToInt's ties toward positive infinity at BOTH steps.
    let q16 = (value * 16.0 + 0.5).floor();
    ((q16 / 8.0 + 0.5).floor() * 8.0) / 16.0
}
impl RegionState {
    pub fn step(&mut self, m: RegionMeasurement) -> Option<Ellipse> {
        if m.kind > 2 || m.flags > 31 || !m.region.valid() || !m.anchor.valid() || self.flags > 7 {
            return None;
        }
        let initialized = self.flags & 1 != 0;
        let references = self.flags & 2 != 0;
        if initialized
            && (!self.output.valid()
                || !self.anchor.valid()
                || (references
                    && ![
                        self.reference_radius_x,
                        self.reference_radius_y,
                        self.reference_width,
                        self.reference_height,
                    ]
                    .into_iter()
                    .all(|v| v.is_finite() && v > 0.0)))
        {
            return None;
        }
        let width = m.anchor.right - m.anchor.left;
        let height = m.anchor.bottom - m.anchor.top;
        if !width.is_finite() || !height.is_finite() {
            return None;
        }
        if width <= 1.0 || height <= 1.0 {
            return Some(m.region);
        }
        let mut next = *self;
        if m.kind == 0 {
            let update = |previous, observed| {
                if references {
                    lerp(previous, observed, 0.25)
                } else {
                    observed
                }
            };
            next.reference_radius_x = update(self.reference_radius_x, m.region.radius_x);
            next.reference_radius_y = update(self.reference_radius_y, m.region.radius_y);
            next.reference_width = update(self.reference_width, width);
            next.reference_height = update(self.reference_height, height);
            next.flags |= 2;
        }
        let mut target = m.region;
        if m.kind == 2 && next.flags & 2 != 0 {
            let width_ratio = (width / next.reference_width.max(1.0)).max(0.1);
            let height_ratio = (height / next.reference_height.max(1.0)).max(0.1);
            let scale = (width_ratio * height_ratio).sqrt().clamp(0.90, 1.12);
            target.radius_x = (next.reference_radius_x * scale * 1.24)
                .max(next.reference_radius_x * 1.10)
                .min(target.radius_x);
            target.radius_y = (next.reference_radius_y * scale * 1.24)
                .max(next.reference_radius_y * 1.10)
                .min(target.radius_y);
        }
        let translation = if initialized {
            anchor_translation(self.anchor, m.anchor)?
        } else {
            [0.0; 2]
        };
        if initialized && m.flags & 8 != 0 {
            target.center_x = self.output.center_x + translation[0];
            target.center_y = self.output.center_y + translation[1];
        }
        let mut output = target;
        if initialized && m.timestamp_us >= self.timestamp_us {
            let raw_dt = m.timestamp_us.saturating_sub(self.timestamp_us) as f64 / 1_000_000.0;
            let dt = raw_dt.clamp(1.0 / 120.0, 0.25);
            let tau = match m.kind {
                0 if target.radius_x < self.output.radius_x
                    || target.radius_y < self.output.radius_y =>
                {
                    0.24
                }
                0 => 0.075,
                1 => 0.15,
                _ => 0.18,
            };
            let alpha = (1.0 - (-dt / tau).exp()) as f32;
            let radius = self
                .output
                .radius_x
                .max(self.output.radius_y)
                .max(target.radius_x.max(target.radius_y));
            let anchor_step =
                (translation[0] * translation[0] + translation[1] * translation[1]).sqrt();
            let max_anchor_step = 12.0_f32.max(radius * 0.65);
            let trusted_anchor = m.flags & 1 != 0 && self.flags & 4 != 0;
            let anchor_scale = if !trusted_anchor
                && raw_dt <= 0.10
                && anchor_step > max_anchor_step
                && anchor_step > 0.001
            {
                max_anchor_step / anchor_step
            } else {
                1.0
            };
            let expected_x = self.output.center_x + translation[0] * anchor_scale;
            let expected_y = self.output.center_y + translation[1] * anchor_scale;
            let dx = target.center_x - expected_x;
            let dy = target.center_y - expected_y;
            let distance = (dx * dx + dy * dy).sqrt();
            let max_residual = 10.0_f32.max(radius * 0.80);
            if m.flags & 2 == 0 && raw_dt <= 0.10 && distance > max_residual && distance > 0.001 {
                output.center_x = expected_x + dx * (max_residual / distance);
                output.center_y = expected_y + dy * (max_residual / distance);
            }
            let floor = if m.flags & 16 != 0 { 1.0 } else { 0.90 };
            output.radius_x =
                lerp(self.output.radius_x, target.radius_x, alpha).max(target.radius_x * floor);
            output.radius_y =
                lerp(self.output.radius_y, target.radius_y, alpha).max(target.radius_y * floor);
        }
        if m.flags & 4 != 0 {
            output = Ellipse {
                center_x: lattice(output.center_x),
                center_y: lattice(output.center_y),
                radius_x: lattice(output.radius_x).max(1.0),
                radius_y: lattice(output.radius_y).max(1.0),
            };
        }
        if !output.valid() {
            return None;
        }
        next.output = output;
        next.anchor = m.anchor;
        next.timestamp_us = m.timestamp_us;
        next.flags = (next.flags & 2) | 1 | if m.flags & 1 != 0 { 4 } else { 0 };
        *self = next;
        Some(output)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn measurement() -> RegionMeasurement {
        RegionMeasurement {
            region: Ellipse {
                center_x: 100.0,
                center_y: 80.0,
                radius_x: 30.0,
                radius_y: 40.0,
            },
            anchor: Rect {
                left: 0.0,
                top: 0.0,
                right: 200.0,
                bottom: 400.0,
            },
            timestamp_us: 0,
            kind: 0,
            flags: 1,
        }
    }
    #[test]
    fn shape_jitter_is_not_translation_but_symmetric_size_changes_are() {
        let a = measurement().anchor;
        let mut b = a;
        b.bottom += 58.0;
        assert_eq!(anchor_translation(a, b), Some([0.0, 0.0]));
        b = a;
        b.left -= 50.0;
        b.right += 50.0;
        assert_eq!(anchor_translation(a, b), Some([0.0, 0.0]));
        b = a;
        b.left += 12.0;
        b.right += 12.0;
        b.top += 20.0;
        b.bottom += 20.0;
        assert_eq!(anchor_translation(a, b), Some([12.0, 20.0]));
    }
    #[test]
    fn weak_centers_follow_anchor_and_current_extents_are_fully_covered() {
        let mut state = RegionState::default();
        let mut m = measurement();
        state.step(m).unwrap();
        m.kind = 2;
        m.flags = 1 | 8;
        m.region.center_x = 500.0;
        m.timestamp_us = 40_000;
        assert_eq!(state.step(m).unwrap().center_x, 100.0);
        m.kind = 0;
        m.flags = 1 | 2 | 16;
        m.timestamp_us += 40_000;
        m.region.radius_x = 100.0;
        let out = state.step(m).unwrap();
        assert_eq!(out.center_x, 500.0);
        assert!(out.radius_x >= 100.0);
    }
    #[test]
    fn invalid_observation_is_atomic_and_rewind_does_not_gate_center() {
        let mut state = RegionState::default();
        let mut m = measurement();
        state.step(m).unwrap();
        let prior = state;
        m.region.center_x = f32::NAN;
        assert_eq!(state.step(m), None);
        assert_eq!(state, prior);
        m.region.center_x = 500.0;
        m.timestamp_us = -1;
        assert_eq!(state.step(m).unwrap().center_x, 500.0);
        assert_eq!(lattice(-0.25), 0.0);
        assert_eq!(lattice(0.25), 0.5);
    }
}
