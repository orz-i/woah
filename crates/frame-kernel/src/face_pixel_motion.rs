//! Bounded source-space pixel translation for a previously observed face.
//! This is motion-only evidence: it cannot establish or renew identity, an
//! observation lease, or a privacy exemption. The caller owns the direct
//! observation frame and must discard it on an identity/lifecycle change.
use crate::{face_geometry::valid_seed, geometry::Rect, temporal_region::Ellipse};

const LEASE_US: i64 = 150_000;
const N: usize = 15;
const SAMPLES: usize = N * N;
// A missing current person mask is weaker than current face ownership.
// Require a high correlation AND a spatially distinct second-peak margin.
const MIN_CORRELATION: f64 = 0.85;
const MIN_MARGIN: f64 = 0.06;

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct GrayView {
    pub width: u32,
    pub height: u32,
    pub stride: u32,
    pub source: Rect,
}

impl GrayView {
    pub fn valid(self, pixels: &[u8]) -> bool {
        self.width > 1
            && self.height > 1
            && self.width <= 16384
            && self.height <= 16384
            && self.stride >= self.width
            && self.stride <= 65536
            && self.source.valid()
            && self.source.right - self.source.left > 1.0
            && self.source.bottom - self.source.top > 1.0
            && (self.stride as usize)
                .checked_mul(self.height as usize)
                .is_some_and(|n| pixels.len() == n)
    }

    fn sample(self, pixels: &[u8], x: f32, y: f32) -> Option<f64> {
        let x = ((x - self.source.left) / (self.source.right - self.source.left)
            * self.width as f32)
            .floor();
        let y = ((y - self.source.top) / (self.source.bottom - self.source.top)
            * self.height as f32)
            .floor();
        if !x.is_finite()
            || !y.is_finite()
            || x < 0.0
            || y < 0.0
            || x >= self.width as f32
            || y >= self.height as f32
        {
            return None;
        }
        Some(f64::from(
            pixels[y as usize * self.stride as usize + x as usize],
        ))
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PixelMotion {
    pub dx: f32,
    pub dy: f32,
    pub correlation: f32,
    pub uniqueness: f32,
}

/// Keep the detector-sized measurement and the already-covered render
/// envelope in separate lanes. Returning a render envelope as the next
/// detector measurement would compound current-mask expansion on reacquire.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PixelBridge {
    pub motion: PixelMotion,
    pub measurement: Ellipse,
    pub required: Ellipse,
}

pub fn project(motion: PixelMotion, measured: Ellipse, covered: Ellipse) -> Option<PixelBridge> {
    if !valid_seed(measured)
        || !valid_seed(covered)
        || !motion.dx.is_finite()
        || !motion.dy.is_finite()
        || !motion.correlation.is_finite()
        || !motion.uniqueness.is_finite()
        || motion.correlation < MIN_CORRELATION as f32
        || motion.uniqueness < MIN_MARGIN as f32
    {
        return None;
    }
    let translate = |region: Ellipse| Ellipse {
        center_x: region.center_x + motion.dx,
        center_y: region.center_y + motion.dy,
        ..region
    };
    let measurement = translate(measured);
    let required = translate(covered);
    (valid_seed(measurement) && valid_seed(required)).then_some(PixelBridge {
        motion,
        measurement,
        required,
    })
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MotionError {
    Invalid,
    NoEvidence,
}

fn patch(view: GrayView, pixels: &[u8], x: f32, y: f32, half: f32) -> Option<[f64; SAMPLES]> {
    let mut values = [0.0; SAMPLES];
    for j in 0..N {
        for i in 0..N {
            let ox = half * (2.0 * i as f32 / (N - 1) as f32 - 1.0);
            let oy = half * (2.0 * j as f32 / (N - 1) as f32 - 1.0);
            values[j * N + i] = view.sample(pixels, x + ox, y + oy)?;
        }
    }
    let mean = values.iter().sum::<f64>() / SAMPLES as f64;
    let mut norm = 0.0;
    for value in &mut values {
        *value -= mean;
        norm += *value * *value;
    }
    // At least about three gray levels of RMS contrast; blank patches cannot
    // manufacture a confidence score via normalization.
    if norm < 2000.0 {
        return None;
    }
    let norm = norm.sqrt();
    for value in &mut values {
        *value /= norm;
    }
    Some(values)
}

/// Match the direct detector's original face-centered template in a current
/// source view. Both views may be native ROI readbacks with different scales.
/// Foreign boxes are only a veto, never positive ownership evidence.
/// The immutable direct seed cannot be updated by an accepted motion result.
pub fn estimate(
    previous: &[u8],
    previous_view: GrayView,
    current: &[u8],
    current_view: GrayView,
    seed: Ellipse,
    age_us: i64,
    foreign_boxes: &[Rect],
) -> Result<PixelMotion, MotionError> {
    if !previous_view.valid(previous)
        || !current_view.valid(current)
        || !valid_seed(seed)
        || age_us < 0
        || foreign_boxes.len() > 4096
        || foreign_boxes.iter().any(|r| !r.valid())
    {
        return Err(MotionError::Invalid);
    }
    if age_us == 0 || age_us > LEASE_US {
        return Err(MotionError::NoEvidence);
    }
    let half = seed.radius_x.min(seed.radius_y) * 0.48;
    // No subpixel texture invented by resampling very distant heads.
    let source_per_pixel = |view: GrayView| {
        ((view.source.right - view.source.left) / view.width as f32)
            .max((view.source.bottom - view.source.top) / view.height as f32)
    };
    if half < 4.0 * source_per_pixel(previous_view).max(source_per_pixel(current_view)) {
        return Err(MotionError::NoEvidence);
    }
    let Some(template) = patch(previous_view, previous, seed.center_x, seed.center_y, half) else {
        return Err(MotionError::NoEvidence);
    };
    let search = (0.80 * seed.radius_x.max(seed.radius_y))
        .min(current_view.source.right - current_view.source.left)
        .min(current_view.source.bottom - current_view.source.top);
    if !search.is_finite() || search < 2.0 {
        return Err(MotionError::NoEvidence);
    }
    // Fixed bounded lattice independent of input resolution; the winning
    // coarse peak gets a small hierarchical local refinement.
    let step = (search / 30.0).max(source_per_pixel(current_view)).max(1.0);
    let radius = (search / step).floor().min(30.0) as i32;
    let correlation = |dx: f32, dy: f32| {
        let x = seed.center_x + dx;
        let y = seed.center_y + dy;
        let sample = patch(current_view, current, x, y, half)?;
        Some(
            template
                .iter()
                .zip(sample)
                .map(|(a, b)| a * b)
                .sum::<f64>()
                .clamp(-1.0, 1.0),
        )
    };
    let mut scores = Vec::with_capacity(((radius * 2 + 1) as usize).pow(2));
    let mut best = (0.0_f32, 0.0_f32, -2.0_f64);
    for iy in -radius..=radius {
        for ix in -radius..=radius {
            let dx = ix as f32 * step;
            let dy = iy as f32 * step;
            if let Some(corr) = correlation(dx, dy) {
                scores.push((dx, dy, corr));
                if corr > best.2 {
                    best = (dx, dy, corr);
                }
            }
        }
    }
    if best.2 < MIN_CORRELATION {
        return Err(MotionError::NoEvidence);
    }
    let mut refinement = step * 0.5;
    for _ in 0..6 {
        if refinement < 0.5 * source_per_pixel(current_view).max(1.0) {
            break;
        }
        let (bx, by, _) = best;
        for iy in -1..=1 {
            for ix in -1..=1 {
                let dx = bx + ix as f32 * refinement;
                let dy = by + iy as f32 * refinement;
                if dx.abs() > search || dy.abs() > search {
                    continue;
                }
                if let Some(corr) = correlation(dx, dy)
                    && corr > best.2
                {
                    best = (dx, dy, corr);
                }
            }
        }
        refinement *= 0.5;
    }
    // A boundary peak is an incomplete search, not reliable motion.
    if best.0.abs().max(best.1.abs()) >= search - step {
        return Err(MotionError::NoEvidence);
    }
    let neighborhood = (half / 3.0).max(3.0 * step);
    let second = scores
        .iter()
        .filter(|&&(dx, dy, _)| (dx - best.0).abs().max((dy - best.1).abs()) > neighborhood)
        .map(|&(_, _, score)| score)
        .fold(-1.0_f64, f64::max);
    let uniqueness = best.2 - second;
    if uniqueness < MIN_MARGIN {
        return Err(MotionError::NoEvidence);
    }
    let x = seed.center_x + best.0;
    let y = seed.center_y + best.1;
    // Do not let a current foreign person head become a motion anchor. A
    // stronger foreign overlap does not confer or change either identity.
    if foreign_boxes.iter().any(|r| {
        x >= r.left && x <= r.right && y >= r.top && y <= r.top + (r.bottom - r.top) * 0.60
    }) {
        return Err(MotionError::NoEvidence);
    }
    Ok(PixelMotion {
        dx: best.0,
        dy: best.1,
        correlation: best.2 as f32,
        uniqueness: uniqueness as f32,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn view() -> GrayView {
        GrayView {
            width: 128,
            height: 128,
            stride: 128,
            source: Rect {
                left: 0.0,
                top: 0.0,
                right: 128.0,
                bottom: 128.0,
            },
        }
    }
    fn seed() -> Ellipse {
        Ellipse {
            center_x: 64.0,
            center_y: 64.0,
            radius_x: 24.0,
            radius_y: 28.0,
        }
    }
    fn pattern() -> Vec<u8> {
        // Nonperiodic deterministic texture; the separate ambiguity test
        // deliberately uses a periodic texture with multiple equal peaks.
        let mut state = 0x1234_abcd_u32;
        (0..128 * 128)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 17;
                state ^= state << 5;
                (state >> 24) as u8
            })
            .collect()
    }
    fn shift(p: &[u8], dx: i32, dy: i32) -> Vec<u8> {
        let mut result = vec![128; 128 * 128];
        for y in 0..128_i32 {
            for x in 0..128_i32 {
                let a = x - dx;
                let b = y - dy;
                if (0..128).contains(&a) && (0..128).contains(&b) {
                    result[(y * 128 + x) as usize] = p[(b * 128 + a) as usize];
                }
            }
        }
        result
    }
    #[test]
    fn recovers_current_pixel_motion_without_an_identity_or_size_update() {
        let p = pattern();
        let current = shift(&p, -7, 5);
        let m = estimate(&p, view(), &current, view(), seed(), 100_000, &[]).unwrap();
        assert!(
            (m.dx + 7.0).abs() <= 1.0 && (m.dy - 5.0).abs() <= 1.0,
            "{m:?}"
        );
        assert!(m.correlation >= 0.85);
    }
    #[test]
    fn raw_and_covered_projections_are_separate_and_preserve_each_size() {
        let p = pattern();
        let current = shift(&p, -7, 5);
        let measured = seed();
        let covered = Ellipse {
            center_x: 58.0,
            radius_x: 40.0,
            radius_y: 44.0,
            ..measured
        };
        let motion = estimate(&p, view(), &current, view(), measured, 100_000, &[]).unwrap();
        let bridge = project(motion, measured, covered).unwrap();
        assert_eq!(bridge.measurement.radius_x, measured.radius_x);
        assert_eq!(bridge.measurement.radius_y, measured.radius_y);
        assert_eq!(bridge.required.radius_x, covered.radius_x);
        assert_eq!(bridge.required.radius_y, covered.radius_y);
        assert_eq!(bridge.measurement.center_x, measured.center_x + motion.dx);
        assert_eq!(bridge.required.center_x, covered.center_x + motion.dx);
        let mut invalid = covered;
        invalid.radius_x = f32::NAN;
        assert_eq!(project(motion, measured, invalid), None);
    }

    #[test]
    fn crop_coordinates_are_source_coordinates() {
        let p = pattern();
        let current = shift(&p, -7, 5);
        let crop: Vec<u8> = (24..104)
            .flat_map(|y| current[y * 128 + 24..y * 128 + 104].iter().copied())
            .collect();
        let cropped = GrayView {
            width: 80,
            height: 80,
            stride: 80,
            source: Rect {
                left: 24.0,
                top: 24.0,
                right: 104.0,
                bottom: 104.0,
            },
        };
        let motion = estimate(&p, view(), &crop, cropped, seed(), 100_000, &[]).unwrap();
        assert!(
            (motion.dx + 7.0).abs() <= 1.0 && (motion.dy - 5.0).abs() <= 1.0,
            "{motion:?}"
        );
    }

    #[test]
    fn ffi_rejection_does_not_write_motion_or_geometry() {
        let p = pattern();
        let e = seed();
        let sentinel = PixelBridge {
            motion: PixelMotion {
                dx: 1.0,
                dy: 2.0,
                correlation: 0.9,
                uniqueness: 0.1,
            },
            measurement: e,
            required: e,
        };
        let mut out = sentinel;
        // SAFETY: readable, disjoint input and writable output, no retained pointers.
        let status = unsafe {
            crate::frame_kernel_face_pixel_bridge(
                p.as_ptr(),
                p.len(),
                view(),
                p.as_ptr(),
                p.len(),
                view(),
                e,
                e,
                150_001,
                std::ptr::null(),
                0,
                &mut out,
            )
        };
        assert_eq!(status, 1);
        assert_eq!(out, sentinel);
    }

    #[test]
    fn flat_expired_foreign_and_invalid_evidence_cannot_move_face() {
        let p = pattern();
        let current = shift(&p, -7, 5);
        let foreign = Rect {
            left: 45.0,
            top: 48.0,
            right: 77.0,
            bottom: 95.0,
        };
        assert_eq!(
            estimate(&p, view(), &current, view(), seed(), 150_001, &[]),
            Err(MotionError::NoEvidence)
        );
        assert_eq!(
            estimate(&p, view(), &current, view(), seed(), 100_000, &[foreign]),
            Err(MotionError::NoEvidence)
        );
        assert_eq!(
            estimate(
                &p,
                view(),
                &vec![128; 128 * 128],
                view(),
                seed(),
                100_000,
                &[]
            ),
            Err(MotionError::NoEvidence)
        );
        assert_eq!(
            estimate(&p[..10], view(), &current, view(), seed(), 100_000, &[]),
            Err(MotionError::Invalid)
        );
    }
    #[test]
    fn equal_spatially_distinct_peaks_reject_the_match() {
        let mut p = vec![0; 128 * 128];
        for y in 0..128 {
            for x in 0..128 {
                p[y * 128 + x] = ((x % 16) * 10 + (y % 16) * 5) as u8;
            }
        }
        assert_eq!(
            estimate(&p, view(), &p, view(), seed(), 33_333, &[]),
            Err(MotionError::NoEvidence)
        );
    }
}
