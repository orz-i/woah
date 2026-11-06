//! Additive spatial continuity when every current region must be protected.
//! This lane never grants identity ownership or an exemption. Current estimates
//! are retained; recent observations can only enlarge an estimated region.
use crate::{
    geometry::overlap_iou,
    temporal_region::{Ellipse, RegionMeasurement, anchor_translation},
};
const WINDOW_US: i64 = 500_000;
fn valid(m: &RegionMeasurement) -> bool {
    m.kind <= 2
        && m.flags <= 31
        && m.anchor.valid()
        && [
            m.region.center_x,
            m.region.center_y,
            m.region.radius_x,
            m.region.radius_y,
        ]
        .into_iter()
        .all(f32::is_finite)
        && m.region.radius_x > 0.0
        && m.region.radius_y > 0.0
}
fn enclose(a: Ellipse, b: Ellipse) -> Ellipse {
    // A sufficient containment test in the first ellipse's normalized space.
    let dx = (b.center_x - a.center_x) / a.radius_x;
    let dy = (b.center_y - a.center_y) / a.radius_y;
    if (dx * dx + dy * dy).sqrt() + (b.radius_x / a.radius_x).max(b.radius_y / a.radius_y) <= 1.0 {
        return a;
    }
    let left = (a.center_x - a.radius_x).min(b.center_x - b.radius_x);
    let right = (a.center_x + a.radius_x).max(b.center_x + b.radius_x);
    let top = (a.center_y - a.radius_y).min(b.center_y - b.radius_y);
    let bottom = (a.center_y + a.radius_y).max(b.center_y + b.radius_y);
    // The bounding rectangle's circumscribed ellipse contains both complete input
    // ellipses, including corners. A half-width/height ellipse would not.
    Ellipse {
        center_x: (left + right) * 0.5,
        center_y: (top + bottom) * 0.5,
        radius_x: (right - left) * std::f32::consts::FRAC_1_SQRT_2,
        radius_y: (bottom - top) * std::f32::consts::FRAC_1_SQRT_2,
    }
}
/// Returns retained observed measurements followed by one result per current
/// measurement. The caller owns the value buffers. Disabled history is cleared.
/// Seek/rewind drops future measurements; stale observations never resurrect.
pub fn step(
    history: &[RegionMeasurement],
    current: &[RegionMeasurement],
    now: i64,
    enabled: bool,
) -> Option<Vec<RegionMeasurement>> {
    if history.len() > 1024
        || current.len() > 1024
        || history.iter().chain(current).any(|m| !valid(m))
        || current.iter().any(|m| m.timestamp_us != now)
    {
        return None;
    }
    let retained = if enabled {
        history
            .iter()
            .copied()
            .filter(|m| {
                m.kind == 0
                    && now >= m.timestamp_us
                    && now.saturating_sub(m.timestamp_us) <= WINDOW_US
            })
            .collect::<Vec<_>>()
    } else {
        Vec::new()
    };
    let mut resolved = current.to_vec();
    if enabled {
        for m in &mut resolved {
            if m.kind != 2 {
                continue;
            }
            let plausible = retained
                .iter()
                .filter(|p| p.timestamp_us < now && overlap_iou(p.anchor, m.anchor) >= 0.30)
                .collect::<Vec<_>>();
            let Some(latest) = plausible.iter().map(|p| p.timestamp_us).max() else {
                continue;
            };
            for previous in plausible.into_iter().filter(|p| p.timestamp_us == latest) {
                let [dx, dy] = anchor_translation(previous.anchor, m.anchor)?;
                let scale = 1.10 + (now - previous.timestamp_us) as f32 / 1_000_000.0 * 0.30;
                let projected = Ellipse {
                    center_x: previous.region.center_x + dx,
                    center_y: previous.region.center_y + dy,
                    radius_x: previous.region.radius_x * scale,
                    radius_y: previous.region.radius_y * scale,
                };
                m.region = enclose(m.region, projected);
            }
        }
    }
    let mut out = retained;
    if enabled {
        out.extend(current.iter().copied().filter(|m| m.kind == 0));
    }
    if out.len() > 1024 {
        return None;
    }
    out.extend(resolved);
    Some(out)
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::geometry::Rect;
    fn measured() -> RegionMeasurement {
        RegionMeasurement {
            region: Ellipse {
                center_x: 1298.2534,
                center_y: 1206.1788,
                radius_x: 116.943756,
                radius_y: 131.11876,
            },
            anchor: Rect {
                left: 246.7338,
                top: 1044.3633,
                right: 2142.255,
                bottom: 3449.7703,
            },
            timestamp_us: 22_040_000,
            kind: 0,
            flags: 1,
        }
    }
    fn fallback() -> RegionMeasurement {
        RegionMeasurement {
            region: Ellipse {
                center_x: 1070.4006,
                center_y: 829.6046,
                radius_x: 306.32767,
                radius_y: 362.0236,
            },
            anchor: Rect {
                left: 129.13925,
                top: 373.52557,
                right: 1521.5377,
                bottom: 3438.6226,
            },
            timestamp_us: 22_360_000,
            kind: 2,
            flags: 1,
        }
    }
    fn contains(e: Ellipse, x: f32, y: f32) -> bool {
        ((x - e.center_x) / e.radius_x).powi(2) + ((y - e.center_y) / e.radius_y).powi(2) <= 1.0
    }
    #[test]
    fn real_raised_arm_frame_keeps_face_when_body_top_follows_the_hand() {
        let old = measured();
        let input = fallback();
        assert!(!contains(input.region, 1272.0, 1176.0));
        let out = step(&[old], &[input], input.timestamp_us, true).unwrap();
        let fixed = out.last().unwrap().region;
        assert!(contains(fixed, 1272.0, 1176.0));
        for angle in 0..360 {
            let a = (angle as f32).to_radians();
            assert!(contains(
                fixed,
                input.region.center_x + input.region.radius_x * a.cos(),
                input.region.center_y + input.region.radius_y * a.sin()
            ));
        }
    }
    #[test]
    fn expiry_rewind_and_exemption_boundary_do_not_reuse_observations() {
        let old = measured();
        let input = fallback();
        assert_eq!(
            step(&[old], &[input], input.timestamp_us, false)
                .unwrap()
                .len(),
            1
        );
        let mut stale = input;
        stale.timestamp_us = old.timestamp_us + 500_001;
        assert_eq!(
            step(&[old], &[stale], stale.timestamp_us, true)
                .unwrap()
                .len(),
            1
        );
        let mut future = old;
        future.timestamp_us = input.timestamp_us + 1;
        assert_eq!(
            step(&[future], &[input], input.timestamp_us, true)
                .unwrap()
                .len(),
            1
        );
    }
    #[test]
    fn ambiguous_current_anchor_preserves_every_plausible_last_observation() {
        let a = measured();
        let mut b = a;
        b.region.center_x += 250.0;
        let input = fallback();
        let out = step(&[a, b], &[input], input.timestamp_us, true).unwrap();
        let e = out.last().unwrap().region;
        assert!(contains(
            e,
            a.region.center_x - 117.59455,
            a.region.center_y - 11.1477
        ));
        assert!(contains(
            e,
            b.region.center_x - 117.59455,
            b.region.center_y - 11.1477
        ));
    }
    #[test]
    fn c_capacity_query_and_invalid_input_do_not_partially_write() {
        let input = fallback();
        let old = measured();
        let sentinel = measured();
        let mut out = sentinel;
        // SAFETY: all inputs/outputs are valid, aligned, disjoint stack values.
        let count = unsafe {
            crate::frame_kernel_continue_regions(
                &old,
                1,
                &input,
                1,
                input.timestamp_us,
                1,
                &mut out,
                1,
            )
        };
        assert_eq!(count, -3);
        assert_eq!(out.region, sentinel.region);
        let mut bad = input;
        bad.region.radius_x = f32::NAN;
        // SAFETY: invalid numeric contents still occupy valid disjoint values.
        let count = unsafe {
            crate::frame_kernel_continue_regions(&old, 1, &bad, 1, bad.timestamp_us, 1, &mut out, 1)
        };
        assert_eq!(count, -1);
        assert_eq!(out.region, sentinel.region);
    }
}
