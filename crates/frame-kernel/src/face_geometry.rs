//! Canonical detector regions and current-mask head geometry. No identity or I/O.
use crate::{
    face_localization::Candidate,
    geometry::{Rect, Transform},
    temporal_region::{Ellipse, anchor_translation},
};

pub fn detected(face: &Candidate, view: Option<(Rect, f32)>, yaw: f32) -> Option<Ellipse> {
    if !face.bounds.valid()
        || face.bounds.right - face.bounds.left <= 1.0
        || face.bounds.bottom - face.bounds.top <= 1.0
    {
        return None;
    }
    if let Some((r, size)) = view
        && (!r.valid() || r.right - r.left <= 1.0 || !size.is_finite() || size <= 0.0)
    {
        return None;
    }
    let x = |value: f32| {
        view.map_or(value, |(r, size)| {
            r.left + (value / size) * (r.right - r.left)
        })
    };
    let y = |value: f32| {
        view.map_or(value, |(r, size)| {
            r.top + (value / size) * (r.bottom - r.top)
        })
    };
    let left = x(face.bounds.left);
    let right = x(face.bounds.right);
    let top = y(face.bounds.top);
    let bottom = y(face.bounds.bottom);
    let w = (right - left).max(1.0);
    let h = (bottom - top).max(1.0);
    let bx = (left + right) * 0.5;
    let by = (top + bottom) * 0.5 + h * (-0.04);
    let (mut cx, mut cy) = (bx, by);
    if face.points.len() >= 4 {
        let center = face.center();
        let prior = 1.0_f32 - 0.72;
        cx = bx * prior + x(center[0]) * 0.72;
        cy = by * prior + y(center[1]) * 0.72;
    }
    let yaw = if yaw.is_finite() {
        yaw.clamp(-std::f32::consts::FRAC_PI_2, std::f32::consts::FRAC_PI_2)
    } else {
        0.0
    };
    let profile = (1.0 / yaw.cos().max(1.0 / 1.75)).min(1.75);
    Some(Ellipse {
        center_x: cx - yaw.sin() * w * 0.5,
        center_y: cy + (profile - 1.0) * h * 0.5,
        radius_x: w * 0.66 * profile,
        radius_y: h * 0.74 * profile,
    })
}
pub fn fallback(person: Rect) -> Option<Ellipse> {
    let w = person.right - person.left;
    let h = person.bottom - person.top;
    if !person.valid() || w <= 1.0 || h <= 1.0 {
        return None;
    }
    Some(Ellipse {
        center_x: person.left + w * 0.5,
        center_y: person.top + h * 0.14,
        radius_x: (w * 0.22).max(h * 0.045),
        radius_y: (w * 0.26).max(h * 0.060),
    })
}
#[repr(C)]
#[derive(Clone, Copy)]
pub struct HeadMaskSpec {
    pub width: u32,
    pub height: u32,
    pub transform: Transform,
    pub person: Rect,
}
impl HeadMaskSpec {
    pub(crate) fn valid(self, pixels: &[u8]) -> bool {
        self.width > 1
            && self.height > 1
            && self.width <= 1024
            && self.height <= 1024
            && pixels.len() == self.width as usize * self.height as usize
            && self.transform.valid()
            && self.person.valid()
    }
    fn source_x(self, x: f32) -> f32 {
        (self.transform.pad_left + x * self.transform.scale) / self.transform.input_size as f32
            * self.transform.proto_size as f32
    }
    fn source_y(self, y: f32) -> f32 {
        (self.transform.pad_top + y * self.transform.scale) / self.transform.input_size as f32
            * self.transform.proto_size as f32
    }
    fn proto_x(self, x: f32) -> f32 {
        (((x / self.width as f32) * self.transform.input_size as f32 - self.transform.pad_left)
            / self.transform.scale)
            .clamp(0.0, self.transform.source_width as f32)
    }
    fn proto_y(self, y: f32) -> f32 {
        (((y / self.height as f32) * self.transform.input_size as f32 - self.transform.pad_top)
            / self.transform.scale)
            .clamp(0.0, self.transform.source_height as f32)
    }
}
pub(crate) fn valid_seed(e: Ellipse) -> bool {
    [e.center_x, e.center_y, e.radius_x, e.radius_y]
        .iter()
        .all(|v| v.is_finite())
        && e.radius_x > 0.0
        && e.radius_y > 0.0
}
/// Narrow supported scanline runs near a trusted seed, preserving Android's
/// Float32 products and mixed Float64 accumulation exactly.
pub fn estimate(pixels: &[u8], spec: HeadMaskSpec, seed: Ellipse) -> Option<[f32; 2]> {
    let b = spec.person;
    let w = b.right - b.left;
    let h = b.bottom - b.top;
    if !spec.valid(pixels)
        || !valid_seed(seed)
        || w <= 1.0
        || h <= 1.0
        || !w.is_finite()
        || !h.is_finite()
    {
        return None;
    }
    let sx = seed.center_x;
    let sy = seed.center_y;
    let rx = seed.radius_x;
    let ry = seed.radius_y;
    let half_w = (rx * 2.2).max(w * 0.34).max(24.0);
    let half_h = (ry * 2.0).max(h * 0.15).max(24.0);
    let min_sx = (b.left - w * 0.08).max(sx - half_w);
    let max_sx = (b.right + w * 0.08).min(sx + half_w);
    let min_sy = (b.top - h * 0.10).max(sy - half_h);
    let max_sy = (b.top + h * 0.42).min(sy + half_h);
    if max_sx <= min_sx || max_sy <= min_sy {
        return None;
    }
    let min_x = spec
        .source_x(min_sx)
        .floor()
        .clamp(0.0, (spec.width - 1) as f32) as usize;
    let max_x = spec
        .source_x(max_sx)
        .ceil()
        .clamp(0.0, (spec.width - 1) as f32) as usize;
    let min_y = spec
        .source_y(min_sy)
        .floor()
        .clamp(0.0, (spec.height - 1) as f32) as usize;
    let max_y = spec
        .source_y(max_sy)
        .ceil()
        .clamp(0.0, (spec.height - 1) as f32) as usize;
    if max_x < min_x || max_y < min_y {
        return None;
    }
    let px = spec.source_x(sx);
    let py = spec.source_y(sy);
    let expected_w = (spec.source_x(sx + rx * 0.70) - spec.source_x(sx - rx * 0.70))
        .abs()
        .max(2.0);
    let expected_h = (spec.source_y(sy + ry * 0.72) - py).abs().max(2.0);
    let (mut weighted_x, mut weighted_y, mut weights) = (0.0_f64, 0.0_f64, 0.0_f64);
    let mut rows = 0;
    for y in min_y..=max_y {
        let (mut best, mut best_width, mut distance) = (None, 0.0_f32, f32::INFINITY);
        let mut x = min_x;
        while x <= max_x {
            while x <= max_x && pixels[y * spec.width as usize + x] < 96 {
                x += 1
            }
            if x > max_x {
                break;
            }
            let start = x;
            while x <= max_x && pixels[y * spec.width as usize + x] >= 96 {
                x += 1
            }
            let width = (x - start) as f32;
            let ratio = width / expected_w;
            if !(0.30..=1.75).contains(&ratio) {
                continue;
            }
            let center = (start + x) as f32 * 0.5;
            let d = (center - px).abs() / expected_w;
            if d > 1.05 {
                continue;
            }
            if d < distance {
                distance = d;
                best = Some(center);
                best_width = width
            }
        }
        let Some(center) = best else { continue };
        let yd = ((y as f32 + 0.5) - py).abs() / expected_h;
        if yd > 1.75 {
            continue;
        }
        let error = (best_width - expected_w).abs() / expected_w;
        let weight = 1.0_f32 / (1.0 + distance * 2.2 + yd * 0.55 + error * 0.75);
        weighted_x += (center * weight) as f64;
        weighted_y += (y as f64 + 0.5) * weight as f64;
        weights += weight as f64;
        rows += 1;
    }
    if rows < 2 || weights <= 0.50 {
        return None;
    }
    let cx = spec.proto_x((weighted_x / weights) as f32);
    let cy = spec.proto_y((weighted_y / weights) as f32);
    let max_dx = (rx * 0.80).max(w * 0.10);
    let max_dy = (ry * 0.70).max(h * 0.055);
    Some([
        cx.clamp(sx - max_dx, sx + max_dx),
        cy.clamp(sy - max_dy, sy + max_dy),
    ])
}
pub fn trusted_fallback(
    pixels: &[u8],
    spec: HeadMaskSpec,
    previous: Rect,
    trusted: Ellipse,
    expansion: f32,
) -> Option<Ellipse> {
    if !valid_seed(trusted) || !expansion.is_finite() || expansion <= 0.0 {
        return None;
    }
    let [dx, dy] = anchor_translation(previous, spec.person)?;
    let rx = (trusted.radius_x * expansion).max(1.0);
    let ry = (trusted.radius_y * expansion).max(1.0);
    let [x, y] = estimate(
        pixels,
        spec,
        Ellipse {
            center_x: trusted.center_x + dx,
            center_y: trusted.center_y + dy,
            radius_x: rx,
            radius_y: ry,
        },
    )?;
    Some(Ellipse {
        center_x: x,
        center_y: y,
        radius_x: rx,
        radius_y: ry,
    })
}

/// Geometry-only projection. The caller still enforces the observation lease.
/// A stale region is never evidence that a current face belongs to an identity.
#[derive(Clone, Copy)]
pub struct ProjectionOptions {
    pub lease_us: i64,
    pub minimum_scale: f32,
    pub maximum_scale: f32,
    pub age_expansion: f32,
}
impl Default for ProjectionOptions {
    fn default() -> Self {
        Self {
            lease_us: 150_000,
            minimum_scale: 0.88,
            maximum_scale: 1.12,
            age_expansion: 0.10,
        }
    }
}
pub fn project_trusted(
    current: Rect,
    previous: Rect,
    trusted: Ellipse,
    age_us: i64,
    options: ProjectionOptions,
) -> Option<Ellipse> {
    let width = current.right - current.left;
    let height = current.bottom - current.top;
    let previous_width = previous.right - previous.left;
    let previous_height = previous.bottom - previous.top;
    if !current.valid()
        || !previous.valid()
        || !valid_seed(trusted)
        || width <= 1.0
        || height <= 1.0
        || previous_width <= 1.0
        || previous_height <= 1.0
        || options.lease_us <= 0
        || !options.minimum_scale.is_finite()
        || !options.maximum_scale.is_finite()
        || !options.age_expansion.is_finite()
        || options.minimum_scale <= 0.0
        || options.maximum_scale < options.minimum_scale
        || options.age_expansion < 0.0
    {
        return None;
    }
    let wr = (width / previous_width.max(1.0)).max(0.1);
    let hr = (height / previous_height.max(1.0)).max(0.1);
    let scale = (wr * hr)
        .sqrt()
        .clamp(options.minimum_scale, options.maximum_scale);
    let expansion = 1.0
        + (age_us.clamp(0, options.lease_us) as f32 / options.lease_us as f32)
            * options.age_expansion;
    let [dx, dy] = anchor_translation(previous, current)?;
    Some(Ellipse {
        center_x: trusted.center_x + dx,
        center_y: trusted.center_y + dy,
        radius_x: (trusted.radius_x * scale * expansion).max(1.0),
        radius_y: (trusted.radius_y * scale * expansion).max(1.0),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn profile_extension_is_bounded_and_invalid_body_has_no_region() {
        let face = Candidate {
            bounds: Rect {
                left: 14.0,
                top: 8.0,
                right: 22.0,
                bottom: 20.0,
            },
            confidence: 0.9,
            points: vec![],
        };
        let profile = detected(&face, None, -std::f32::consts::FRAC_PI_4).unwrap();
        assert!(
            profile.center_x > 18.0
                && profile.center_y > 13.52
                && profile.radius_x > 6.6
                && profile.radius_y > 8.88
        );
        assert!(fallback(Rect::default()).is_none());
    }
}
