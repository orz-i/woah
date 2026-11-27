//! Experimental current-mask head cap and compact sticker geometry.
//! A head-shaped connected run is evidence for coverage, not identity. When
//! the topology cannot distinguish the head from arms/torso, the existing
//! conservative direct-face path is retained. No neighboring mask is removed.
use crate::{
    face_geometry::{HeadMaskSpec, current_face_region, legacy_face_support, valid_seed},
    geometry::Rect,
    temporal_region::Ellipse,
};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Run {
    left: usize,
    right: usize,
}
impl Run {
    fn width(self) -> usize {
        self.right - self.left
    }
    fn center(self) -> f32 {
        (self.left + self.right) as f32 * 0.5
    }
    fn overlap(self, other: Self) -> usize {
        self.right
            .min(other.right)
            .saturating_sub(self.left.max(other.left))
    }
}

fn runs(pixels: &[u8], width: usize, y: usize, left: usize, right: usize) -> Vec<Run> {
    let mut out = Vec::new();
    let mut x = left;
    while x < right {
        if pixels[y * width + x] < 96 {
            x += 1;
            continue;
        }
        let start = x;
        while x < right && pixels[y * width + x] >= 96 {
            x += 1;
        }
        if x - start >= 2 {
            out.push(Run {
                left: start,
                right: x,
            });
        }
    }
    out
}

#[derive(Clone, Copy, Debug, PartialEq)]
struct Cap {
    bounds: Rect,
    rows: u32,
    partial: bool,
}

fn cap(pixels: &[u8], spec: HeadMaskSpec, seed: Ellipse, direct: bool) -> Option<Cap> {
    let tr = spec.transform;
    let cell_x = tr.input_size as f32 / (spec.width as f32 * tr.scale);
    let cell_y = tr.input_size as f32 / (spec.height as f32 * tr.scale);
    if !cell_x.is_finite() || !cell_y.is_finite() || cell_x <= 0.0 || cell_y <= 0.0 {
        return None;
    }
    let to_x = |v: f32| (v + tr.pad_left / tr.scale) / cell_x;
    let to_y = |v: f32| (v + tr.pad_top / tr.scale) / cell_y;
    let from_x =
        |v: usize| (v as f32 * cell_x - tr.pad_left / tr.scale).clamp(0.0, tr.source_width as f32);
    let from_y =
        |v: usize| (v as f32 * cell_y - tr.pad_top / tr.scale).clamp(0.0, tr.source_height as f32);
    let w = spec.width as usize;
    let h = spec.height as usize;
    let bh = spec.person.bottom - spec.person.top;
    let left = to_x(spec.person.left - cell_x).floor().clamp(0.0, w as f32) as usize;
    let right = to_x(spec.person.right + cell_x).ceil().clamp(0.0, w as f32) as usize;
    let top = to_y((spec.person.top - 2.0 * cell_y).max(seed.center_y - 2.8 * seed.radius_y))
        .floor()
        .clamp(0.0, h as f32) as usize;
    let bottom = to_y((spec.person.top + bh * 0.48).min(seed.center_y + 2.2 * seed.radius_y))
        .ceil()
        .clamp(0.0, h as f32) as usize;
    if left >= right || bottom.saturating_sub(top) < 5 {
        return None;
    }
    let cx = to_x(seed.center_x);
    let cy = to_y(seed.center_y);
    let rx = seed.radius_x / cell_x;
    let ry = seed.radius_y / cell_y;
    if rx < 1.0 || ry < 1.0 {
        return None;
    }
    // Direct face detections may cover only a profile or visible facial
    // features; their owned mask can supply a wider upper hair/head cap.
    let start_width = rx * if direct { 3.5 } else { 2.8 };
    let near_start = |r: &Run| {
        r.width() as f32 <= start_width.max(3.0) && (r.center() - cx).abs() <= (rx * 1.1).max(1.5)
    };
    let mut path: Vec<(usize, Run)> = Vec::new();
    let mut initial: Vec<usize> = Vec::new();
    let mut widened = 0;
    let mut stopped = false;
    for y in top..bottom {
        let row = runs(pixels, w, y, left, right);
        if path.is_empty() {
            if y as f32 > cy + 0.2 * ry {
                break;
            }
            let mut near = row.iter().copied().filter(near_start);
            let Some(run) = near.next() else { continue };
            if near.next().is_some() {
                return None;
            }
            // Do not start midway along a pre-existing vertical limb or torso.
            if y > 0
                && runs(pixels, w, y - 1, left, right)
                    .iter()
                    .any(|r| r.overlap(run) > 0)
            {
                continue;
            }
            path.push((y, run));
            initial.push(run.width());
            continue;
        }
        let prev = path.last()?.1;
        let mut connected = row
            .iter()
            .copied()
            .filter(|r| r.overlap(prev) * 2 >= r.width().min(prev.width()));
        let Some(run) = connected.next() else { break };
        if connected.next().is_some() {
            return None;
        }
        // A cap is allowed to taper and widen gradually. Estimate its early
        // scale from the upper lobe rather than the entire body bbox.
        if initial.len() < 4 {
            if run.width() > prev.width() * 2 + 1 {
                return None;
            }
            initial.push(run.width());
        }
        let mut ordered = initial.clone();
        ordered.sort_unstable();
        let reference = ordered[ordered.len() / 2] as f32;
        let span = y - path[0].0;
        let center_jump = (run.center() - prev.center()).abs();
        let expanding = initial.len() >= 4
            && span >= 3
            && (run.width() as f32 > reference * 1.55 + 0.5
                || center_jump > reference * 0.60 + 0.5);
        // At low mask resolution the first connected branch may recede by
        // a cell on the next row. Confirm sustained widening relative to
        // the upper lobe, rather than requiring two identical strict peaks.
        let confirms = widened > 0
            && run.width() as f32 > reference * 1.30 + 0.5
            && run.overlap(prev) * 2 >= prev.width();
        if expanding || confirms {
            widened += 1;
            // The first broadened row is withheld; a second consecutive
            // widened row confirms a neck/shoulder or arm junction.
            if widened >= 2 {
                stopped = true;
                break;
            }
            continue;
        }
        if widened > 0 {
            // A one-row widened branch is ambiguous, not a reliable neck.
            return None;
        }
        if (run.center() - cx).abs() > rx * 1.55 + 1.0 {
            return None;
        }
        path.push((y, run));
    }
    if !stopped || path.len() < 3 {
        return None;
    }
    let first = path.first()?.0;
    let last = path.last()?.0;
    let l = path.iter().map(|(_, r)| r.left).min()?;
    let r = path.iter().map(|(_, r)| r.right).max()?;
    let span = (last + 1 - first) as f32;
    let breadth = (r - l) as f32;
    // A short upper cap before an arm merges into the silhouette is usable
    // only with a direct detector anchor that overlaps it. Estimated heads
    // require the full shape; a detached or skinny limb is not a head.
    let aspect = span * cell_y / (breadth * cell_x);
    let partial = path.len() < 4 || aspect < 0.60;
    if aspect > 2.15
        || first as f32 > cy + 0.25 * ry
        || ((last + 1) as f32) + (if partial { 0.5 } else { 0.0 }) < cy - 0.85 * ry
        || (l as f32 + r as f32) * 0.5 < cx - rx * 1.1 - 1.0
        || (l as f32 + r as f32) * 0.5 > cx + rx * 1.1 + 1.0
        || (partial && (!direct || aspect < 0.40 || cx < l as f32 - 1.0 || cx > r as f32 + 1.0))
    {
        return None;
    }
    let bounds = Rect {
        left: from_x(l),
        top: from_y(first),
        right: from_x(r),
        bottom: from_y(last + 1),
    };
    bounds.valid().then_some(Cap {
        bounds,
        rows: path.len() as u32,
        partial,
    })
}

/// This square is a placement requirement, not an ellipse-shaped privacy mask.
/// A square can contain the bounding extents of an ellipse and a support rect
/// without first circumscribing their union by another circle.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct HeadSticker {
    /// Exact current source-space extents including the sampling margin.
    pub bounds: Rect,
    /// Square placement (center + identical half sizes); not a mask ellipse.
    pub square: Ellipse,
    /// 0 = no coherent topology (legacy direct support, or estimated seed),
    /// 1 = connected cap with sustained neck/shoulder widening,
    /// 2 = partial upper cap corroborated by a direct detector.
    pub topology: u32,
    pub rows: u32,
}

pub fn sticker(
    pixels: &[u8],
    spec: HeadMaskSpec,
    measured: Ellipse,
    stabilized: Ellipse,
    kind: u32,
) -> Option<HeadSticker> {
    if !spec.valid(pixels)
        || !valid_seed(measured)
        || !valid_seed(stabilized)
        || !matches!(kind, 0 | 2)
    {
        return None;
    }
    let tr = spec.transform;
    let cell_x = tr.input_size as f32 / (spec.width as f32 * tr.scale);
    let cell_y = tr.input_size as f32 / (spec.height as f32 * tr.scale);
    if !cell_x.is_finite() || !cell_y.is_finite() || cell_x <= 0.0 || cell_y <= 0.0 {
        return None;
    }
    let topology = cap(pixels, spec, measured, kind == 0);
    let support = topology.map(|c| c.bounds).or_else(|| {
        if kind == 0 {
            legacy_face_support(pixels, spec, stabilized)
        } else {
            None
        }
    });
    // Direct detections are measured evidence and cannot be discarded by
    // a temporal position/size gate. Estimated body-box seeds are not
    // measured head extents: RegionState already bounded that weak scale
    // using observed face history. Re-unioning a wide-arm bbox heuristic
    // would undo that decision. The independently selected cap remains
    // required in both lanes. At the clipped top edge keep the complete
    // raw fallback as well, because it can contain a current edge envelope.
    let preserve_raw = kind == 0 || spec.person.top <= cell_y;
    let mut local = stabilized;
    if kind == 2 && !preserve_raw {
        if let Some(c) = topology {
            // A full current head cap supplies a localization anchor for a
            // bbox-based estimate, but not a new identity or a smaller
            // measured size. Only correct a weak center that lies below the
            // cap and whose existing radius reaches its lower central band.
            // A disconnected or far-away cap cannot pull the region away.
            let cy = c.bounds.top + (c.bounds.bottom - c.bounds.top) * 0.75;
            let cx = stabilized.center_x.clamp(c.bounds.left, c.bounds.right);
            let dx = (cx - stabilized.center_x) / stabilized.radius_x;
            let dy = (cy - stabilized.center_y) / stabilized.radius_y;
            if stabilized.center_y > c.bounds.bottom && dx * dx + dy * dy <= 1.0 {
                local.center_y = cy;
            }
        }
    }
    let mut b = Rect {
        left: local.center_x - local.radius_x,
        top: local.center_y - local.radius_y,
        right: local.center_x + local.radius_x,
        bottom: local.center_y + local.radius_y,
    };
    if preserve_raw {
        b.left = b.left.min(measured.center_x - measured.radius_x);
        b.top = b.top.min(measured.center_y - measured.radius_y);
        b.right = b.right.max(measured.center_x + measured.radius_x);
        b.bottom = b.bottom.max(measured.center_y + measured.radius_y);
    }
    if let Some(s) = support {
        b.left = b.left.min(s.left);
        b.top = b.top.min(s.top);
        b.right = b.right.max(s.right);
        b.bottom = b.bottom.max(s.bottom);
    }
    // The current observed detector path always has a sampling margin.
    // When a cap is present, keep the same cell margin in both source lanes.
    if kind == 0 || topology.is_some() {
        b.left -= cell_x;
        b.right += cell_x;
        b.top -= cell_y;
        b.bottom += cell_y;
    }
    if !b.valid() {
        return None;
    }
    // The shortest enclosing square has a range of equally compact
    // centers on its shorter dimension. Prefer the existing conservative
    // region's center within that range instead of a discontinuous recenter.
    let prefer = if kind == 0 {
        current_face_region(Some(pixels), spec, stabilized)?
    } else {
        local
    };
    let half = ((f64::from(b.right) - f64::from(b.left))
        .max(f64::from(b.bottom) - f64::from(b.top))
        * 0.5) as f32;
    let half = half.next_up();
    if !half.is_finite() || half <= 0.0 {
        return None;
    }
    let choose = |low: f32, high: f32, anchor: f32| {
        let lower = f64::from(high) - f64::from(half);
        let upper = f64::from(low) + f64::from(half);
        f64::from(anchor).clamp(lower, upper) as f32
    };
    let cx = choose(b.left, b.right, prefer.center_x);
    let cy = choose(b.top, b.bottom, prefer.center_y);
    let half = half
        .max(cx - b.left)
        .max(b.right - cx)
        .max(cy - b.top)
        .max(b.bottom - cy)
        .next_up();
    let square = Ellipse {
        center_x: cx,
        center_y: cy,
        radius_x: half,
        radius_y: half,
    };
    valid_seed(square).then_some(HeadSticker {
        bounds: b,
        square,
        topology: topology.map_or(0, |c| if c.partial { 2 } else { 1 }),
        rows: topology.map_or(0, |c| c.rows),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geometry::Transform;

    fn spec() -> HeadMaskSpec {
        HeadMaskSpec {
            width: 64,
            height: 64,
            transform: Transform {
                scale: 1.0,
                pad_left: 0.0,
                pad_top: 0.0,
                source_width: 256,
                source_height: 256,
                input_size: 256,
                proto_size: 64,
            },
            person: Rect {
                left: 20.0,
                top: 40.0,
                right: 236.0,
                bottom: 240.0,
            },
        }
    }
    fn region() -> Ellipse {
        Ellipse {
            center_x: 128.0,
            center_y: 76.0,
            radius_x: 20.0,
            radius_y: 24.0,
        }
    }
    fn paint(m: &mut [u8], rows: std::ops::Range<usize>, left: usize, right: usize) {
        for y in rows {
            m[y * 64 + left..y * 64 + right].fill(255);
        }
    }
    fn head_mask() -> Vec<u8> {
        let mut m = vec![0; 64 * 64];
        paint(&mut m, 12..13, 28, 36);
        paint(&mut m, 13..14, 27, 37);
        paint(&mut m, 14..23, 26, 38);
        paint(&mut m, 23..29, 18, 46); // neck/shoulder junction
        paint(&mut m, 29..59, 22, 42);
        m
    }
    fn contains(square: Ellipse, bounds: Rect) {
        let h = square.radius_x;
        assert_eq!(h, square.radius_y);
        assert!(square.center_x - h <= bounds.left + 0.0001);
        assert!(square.center_x + h >= bounds.right - 0.0001);
        assert!(square.center_y - h <= bounds.top + 0.0001);
        assert!(square.center_y + h >= bounds.bottom - 0.0001);
    }
    fn bounds(e: Ellipse) -> Rect {
        Rect {
            left: e.center_x - e.radius_x,
            top: e.center_y - e.radius_y,
            right: e.center_x + e.radius_x,
            bottom: e.center_y + e.radius_y,
        }
    }

    #[test]
    fn connected_upper_cap_stops_before_shoulders_for_both_evidence_lanes() {
        let m = head_mask();
        let seed = region();
        let found = cap(&m, spec(), seed, true).unwrap();
        assert_eq!(
            found.bounds,
            Rect {
                left: 104.0,
                top: 48.0,
                right: 152.0,
                bottom: 92.0
            }
        );
        assert_eq!(found.rows, 11);
        for kind in [0, 2] {
            let out = sticker(&m, spec(), seed, seed, kind).unwrap();
            assert_eq!(out.topology, 1);
            contains(out.square, bounds(seed));
            contains(out.square, found.bounds);
            contains(out.square, out.bounds);
            // The shoulder's outer ends and lower torso must not be included.
            assert!(out.square.center_x - out.square.radius_x > 72.0);
            assert!(out.square.center_x + out.square.radius_x < 184.0);
            assert!(out.square.center_y + out.square.radius_y < 116.0);
        }
    }

    #[test]
    fn separate_raised_hand_does_not_replace_the_head_cap() {
        let mut m = head_mask();
        let original = cap(&m, spec(), region(), true).unwrap();
        paint(&mut m, 10..23, 8, 12);
        paint(&mut m, 23..29, 8, 46); // attaches to the shoulder below the cap
        let raised = cap(&m, spec(), region(), true).unwrap();
        assert_eq!(raised, original);
        // Mirror the entire silhouette and seed to exercise both sides.
        for row in m.chunks_exact_mut(64) {
            row.reverse();
        }
        let mirrored = cap(&m, spec(), region(), true).unwrap();
        assert_eq!(mirrored, original);
    }

    #[test]
    fn ambiguous_two_origins_and_flat_body_have_no_qualified_cap() {
        let mut m = vec![0; 64 * 64];
        paint(&mut m, 12..20, 25, 30);
        paint(&mut m, 12..20, 34, 39);
        paint(&mut m, 20..29, 18, 46);
        assert_eq!(cap(&m, spec(), region(), true), None);
        let solid = vec![255; 64 * 64];
        assert_eq!(cap(&solid, spec(), region(), true), None);
        let out = sticker(&solid, spec(), region(), region(), 0).unwrap();
        assert_eq!(out.topology, 0);
        contains(out.square, bounds(region()));
    }

    #[test]
    fn partial_cap_before_an_arm_merge_requires_a_direct_observation() {
        let mut m = vec![0; 64 * 64];
        paint(&mut m, 10..11, 30, 34);
        paint(&mut m, 11..12, 29, 34);
        paint(&mut m, 12..13, 29, 35);
        paint(&mut m, 13..14, 25, 35);
        paint(&mut m, 14..28, 24, 35);
        let seed = Ellipse {
            center_x: 128.0,
            center_y: 56.0,
            radius_x: 10.0,
            radius_y: 12.0,
        };
        let out = sticker(&m, spec(), seed, seed, 0).unwrap();
        assert_eq!(out.topology, 2);
        assert_eq!(out.rows, 3);
        contains(
            out.square,
            Rect {
                left: 116.0,
                top: 40.0,
                right: 140.0,
                bottom: 52.0,
            },
        );
        contains(out.square, bounds(seed));
        assert_eq!(sticker(&m, spec(), seed, seed, 2).unwrap().topology, 0);
    }

    #[test]
    fn a_wide_arm_bbox_cannot_reintroduce_a_discarded_weak_size_estimate() {
        let m = head_mask();
        let stable = region();
        let weak = Ellipse {
            radius_x: 60.0,
            radius_y: 72.0,
            ..stable
        };
        let out = sticker(&m, spec(), weak, stable, 2).unwrap();
        assert_eq!(out.topology, 1);
        contains(out.square, bounds(stable));
        contains(out.square, cap(&m, spec(), weak, false).unwrap().bounds);
        assert!(out.square.radius_x < weak.radius_x);
        // Direct measurements and a clipped-top estimated coverage envelope
        // remain required even when their size exceeds the temporal seed.
        let direct = sticker(&m, spec(), weak, stable, 0).unwrap();
        contains(direct.square, bounds(weak));
        let mut clipped = spec();
        clipped.person.top = 0.0;
        let edge = sticker(&m, clipped, weak, stable, 2).unwrap();
        contains(edge.square, bounds(weak));
    }

    #[test]
    fn a_branch_that_recedes_one_cell_still_confirms_a_head_junction() {
        let mut m = vec![0; 64 * 64];
        paint(&mut m, 12..20, 28, 36); // reference width 8
        paint(&mut m, 20..21, 24, 38); // width 14, initial expansion
        paint(&mut m, 21..24, 25, 37); // width 12, sustained wider branch
        let c = cap(&m, spec(), region(), true).unwrap();
        assert_eq!(c.rows, 8);
        assert_eq!(c.bounds.bottom, 80.0);
        // A transient branch returning to normal width stays ambiguous.
        paint(&mut m, 21..22, 24, 38);
        m[21 * 64 + 24..21 * 64 + 38].fill(0);
        paint(&mut m, 21..22, 28, 36);
        assert!(cap(&m, spec(), region(), true).is_none());
    }

    #[test]
    fn only_overlapping_full_cap_can_reanchor_a_weak_torso_center() {
        let m = head_mask();
        let measured = Ellipse {
            center_y: 96.0,
            radius_x: 60.0,
            radius_y: 72.0,
            ..region()
        };
        let stable = Ellipse {
            center_y: 96.0,
            radius_y: 30.0,
            ..region()
        };
        let cap = cap(&m, spec(), measured, false).unwrap();
        let candidate = sticker(&m, spec(), measured, stable, 2).unwrap();
        contains(candidate.square, cap.bounds);
        assert!(candidate.bounds.bottom < stable.center_y + stable.radius_y);
        assert!(candidate.bounds.bottom >= cap.bounds.bottom);
        // An observed face is not a bbox-based weak localization.
        let direct = sticker(&m, spec(), measured, stable, 0).unwrap();
        contains(direct.square, bounds(stable));
        let distant = Ellipse {
            center_y: 144.0,
            ..stable
        };
        let rejected = sticker(&m, spec(), measured, distant, 2).unwrap();
        contains(rejected.square, bounds(distant));
    }

    #[test]
    fn genuine_current_head_growth_is_not_capped_by_sticker_history() {
        use crate::face_render_size::FaceRenderSizeState;
        let seed = region();
        let small = sticker(&head_mask(), spec(), seed, seed, 0).unwrap();
        // Independent scaled silhouette at twice the source size with the
        // same source-per-prototype-cell. The observed region really doubles.
        let mut pixels = vec![0; 128 * 128];
        let original = head_mask();
        for y in 0..128 {
            for x in 0..128 {
                pixels[y * 128 + x] = original[(y / 2) * 64 + x / 2];
            }
        }
        let mut large_spec = spec();
        large_spec.width = 128;
        large_spec.height = 128;
        large_spec.transform.input_size = 512;
        large_spec.transform.proto_size = 128;
        large_spec.transform.source_width = 512;
        large_spec.transform.source_height = 512;
        large_spec.person = Rect {
            left: 40.0,
            top: 80.0,
            right: 472.0,
            bottom: 480.0,
        };
        let large_seed = Ellipse {
            center_x: 2.0 * seed.center_x,
            center_y: 2.0 * seed.center_y,
            radius_x: 2.0 * seed.radius_x,
            radius_y: 2.0 * seed.radius_y,
        };
        let large = sticker(&pixels, large_spec, large_seed, large_seed, 0).unwrap();
        contains(large.square, bounds(large_seed));
        assert!(large.square.radius_x > small.square.radius_x * 1.6);
        let mut state = FaceRenderSizeState::default();
        state.step(small.square, 0, 0).unwrap();
        let rendered = state.step(large.square, 33_333, 0).unwrap();
        assert_eq!(rendered, large.square);
    }

    #[test]
    fn compact_square_is_minimal_for_both_required_extents_and_preferred_center() {
        let m = vec![0; 64 * 64];
        let measured = region();
        let stabilized = Ellipse {
            center_x: 142.0,
            radius_x: 22.0,
            radius_y: 30.0,
            ..measured
        };
        let out = sticker(&m, spec(), measured, stabilized, 0).unwrap();
        contains(out.square, bounds(measured));
        contains(out.square, bounds(stabilized));
        contains(out.square, out.bounds);
        let needed = (out.bounds.right - out.bounds.left).max(out.bounds.bottom - out.bounds.top);
        assert!((out.square.radius_x * 2.0 - needed).abs() < 0.001);
        assert!(out.square.center_x >= measured.center_x);
    }

    #[test]
    fn invalid_abi_never_overwrites_the_caller_output() {
        let m = head_mask();
        let seed = region();
        let initial = sticker(&m, spec(), seed, seed, 0).unwrap();
        let mut out = initial;
        let mut bad = seed;
        bad.radius_x = f32::NAN;
        // SAFETY: readable input and disjoint, live output.
        let status = unsafe {
            crate::frame_kernel_head_sticker(m.as_ptr(), m.len(), spec(), bad, seed, 0, &mut out)
        };
        assert_eq!(status, -1);
        assert_eq!(out, initial);
        // SAFETY: same live buffers; kind=1 must use the explicit projection lane.
        let status = unsafe {
            crate::frame_kernel_head_sticker(m.as_ptr(), m.len(), spec(), seed, seed, 1, &mut out)
        };
        assert_eq!(status, -1);
        assert_eq!(out, initial);
    }
}
