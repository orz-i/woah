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

fn cap_trace(
    pixels: &[u8],
    spec: HeadMaskSpec,
    seed: Ellipse,
    direct: bool,
    crown_join: bool,
    discrete_junction: bool,
) -> Option<(Cap, Vec<(usize, Run)>)> {
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
    let mut first_branch: Option<Run> = None;
    let mut stopped = false;
    let refine = direct && crown_join;
    for y in top..bottom {
        let row = runs(pixels, w, y, left, right);
        if path.is_empty() {
            if y as f32 > cy + 0.2 * ry {
                break;
            }
            let mut near = row.iter().copied().filter(near_start);
            let Some(mut run) = near.next() else { continue };
            if let Some(other) = near.next() {
                // A one-cell seam in the crown can merge in the following
                // row. Require direct detector evidence, a compact combined
                // width, and a real connecting next row; never join separate
                // upper origins that remain distinct.
                let joined = Run {
                    left: run.left.min(other.left),
                    right: run.right.max(other.right),
                };
                if !refine
                    || near.next().is_some()
                    || other.left.saturating_sub(run.right) > 1
                    || joined.width() as f32 > (rx * 2.6).max(4.0)
                    || y + 1 >= bottom
                    || !runs(pixels, w, y + 1, left, right).iter().any(|next| {
                        next.overlap(run) * 2 >= run.width()
                            && next.overlap(other) * 2 >= other.width()
                    })
                {
                    return None;
                }
                run = joined;
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
            if run.width() > prev.width() * 2 + 1
                && !(refine
                    && run.width() as f32 <= (rx * 2.6).max(4.0)
                    && (run.center() - cx).abs() <= (rx * 1.1).max(1.5))
            {
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
            && (run.width() as f32 > reference * (if refine { 1.40 } else { 1.55 }) + 0.5
                || center_jump > reference * 0.60 + 0.5);
        // At low mask resolution the first connected branch may recede by
        // a cell on the next row. Confirm sustained widening relative to
        // the upper lobe, rather than requiring two identical strict peaks.
        let discrete_lower_branch = refine
            && discrete_junction
            && y as f32 >= cy
            && path.len() >= 5
            && first_branch.is_some_and(|branch| {
                branch.width() >= prev.width() + 3
                    && run.width() >= prev.width() + 2
                    && (prev.left.saturating_sub(branch.left) >= 2
                        || branch.right.saturating_sub(prev.right) >= 2)
                    && run.overlap(prev) * 2 >= prev.width()
            });
        let confirms = widened > 0
            && (run.width() as f32 > reference * 1.30 + 0.5 || discrete_lower_branch)
            && run.overlap(prev) * 2 >= prev.width();
        if expanding || confirms {
            widened += 1;
            if widened == 1 {
                first_branch = Some(run);
            }
            // The first broadened row is withheld; a second consecutive
            // widened row confirms a neck/shoulder or arm junction.
            if widened >= 2 {
                if discrete_lower_branch
                    && let Some(branch) = first_branch
                    && path.last().is_some_and(|(last_y, _)| *last_y + 2 == y)
                {
                    // The first widened row includes a continuous lower
                    // head/neck core. Its lateral branches are uncertain,
                    // but this intersection is directly supported by both
                    // the preceding head run and the current mask row.
                    let core = Run {
                        left: branch.left.max(prev.left),
                        right: branch.right.min(prev.right),
                    };
                    if core.width() * 2 >= prev.width() {
                        path.push((y - 1, core));
                    }
                }
                stopped = true;
                break;
            }
            continue;
        }
        if widened > 0 {
            // A transient lateral branch is distinguishable only after a
            // stable upper lobe, on/under the detector center. The branch
            // must protrude >=2 cells beyond the preceding run and retract
            // on the same side, while the current row still overlaps the
            // prior lobe. The direct face ellipse remains required.
            if refine
                && path.len() >= 4
                && y as f32 >= cy
                && let Some(branch) = first_branch
                && run.overlap(prev) * 2 >= prev.width()
                && ((prev.left.saturating_sub(branch.left) >= 2
                    && run.left.saturating_sub(branch.left) >= 2)
                    || (branch.right.saturating_sub(prev.right) >= 2
                        && branch.right.saturating_sub(run.right) >= 2))
            {
                stopped = true;
                break;
            }
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
    bounds.valid().then_some((
        Cap {
            bounds,
            rows: path.len() as u32,
            partial,
        },
        path,
    ))
}

fn cap(pixels: &[u8], spec: HeadMaskSpec, seed: Ellipse, direct: bool) -> Option<Cap> {
    cap_trace(pixels, spec, seed, direct, false, false).map(|(cap, _)| cap)
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
    if kind == 2
        && !preserve_raw
        && let Some(c) = topology
    {
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

/// The existing cap cutoff confirms a shoulder branch with two wider
/// rows. At low resolution the first bilateral widening row can already
/// be part of that branch while still below the global width threshold.
/// Only narrow the terminal row to its connected core when the preceding
/// lobe is stable, both sides expand and subsequent rows confirm the branch.
/// Preserve the row's full vertical extent (including possible chin pixels).
/// The direct measured ellipse and sampling margin are still required.
fn refine_lower_junction(
    pixels: &[u8],
    spec: HeadMaskSpec,
    seed: Ellipse,
    cap: &mut Cap,
    path: &mut [(usize, Run)],
) {
    if cap.partial || path.len() < 6 {
        return;
    }
    let Some(&(y, last)) = path.last() else {
        return;
    };
    // Compare to the stable lobe rather than a single preceding row;
    // a quantized head/neck silhouette often grows in one-cell steps.
    let upper = &path[path.len().saturating_sub(6)..path.len() - 1];
    let mut widths: Vec<_> = upper.iter().map(|(_, r)| r.width()).collect();
    widths.sort_unstable();
    let breadth = widths[widths.len() / 2];
    if breadth < 3 || widths.iter().filter(|&&w| w.abs_diff(breadth) <= 1).count() < 4 {
        return;
    }
    let Some(core) = upper
        .iter()
        .rev()
        .map(|(_, r)| *r)
        .find(|r| r.width() == breadth)
    else {
        return;
    };
    let cell_y = spec.transform.input_size as f32 / (spec.height as f32 * spec.transform.scale);
    let top = y as f32 * cell_y - spec.transform.pad_top / spec.transform.scale;
    if last.width() < breadth + 2
        || last.left >= core.left
        || last.right <= core.right
        || top < seed.center_y + seed.radius_y - cell_y
        || y + 2 >= spec.height as usize
    {
        return;
    }
    let w = spec.width as usize;
    // Two lower rows must confirm an actual connected bilateral branch;
    // an isolated chin/hair widening that retracts is never dropped.
    let mut previous = last;
    for row in [y + 1, y + 2] {
        let connected = runs(pixels, w, row, 0, w);
        let mut compatible = connected
            .iter()
            .filter(|r| r.overlap(previous) * 2 >= previous.width());
        let Some(r) = compatible.next() else {
            return;
        };
        if compatible.next().is_some()
            || r.width() < last.width()
            || r.left > core.left
            || r.right < core.right
        {
            return;
        }
        previous = *r;
    }
    if previous.width() < last.width() + 1 {
        return;
    }
    if let Some((_, run)) = path.last_mut() {
        // Retain the central lower head corridor. Only the lateral
        // protrusions, not the complete row, are identified as shoulder.
        *run = Run {
            left: run.left.max(core.left),
            right: run.right.min(core.right),
        };
    }
    let cell_x = spec.transform.input_size as f32 / (spec.width as f32 * spec.transform.scale);
    let sx = |x: usize| {
        (x as f32 * cell_x - spec.transform.pad_left / spec.transform.scale)
            .clamp(0.0, spec.transform.source_width as f32)
    };
    let sy = |x: usize| {
        (x as f32 * cell_y - spec.transform.pad_top / spec.transform.scale)
            .clamp(0.0, spec.transform.source_height as f32)
    };
    let (Some(first), Some(last)) = (path.first(), path.last()) else {
        return;
    };
    cap.bounds = Rect {
        left: sx(path.iter().map(|(_, r)| r.left).min().unwrap_or(0)),
        top: sy(first.0),
        right: sx(path.iter().map(|(_, r)| r.right).max().unwrap_or(0)),
        bottom: sy(last.0 + 1),
    };
    cap.rows = path.len() as u32;
}

/// Enclose an actual connected head silhouette, not the four corners of its
/// bounding rectangle. Preserve the complete measured/stabilized face
/// ellipses for direct evidence. In the weak lane the current full cap may
/// correct the projected center only if it overlaps the previous estimate.
/// This is render-only, and cannot renew a face or identity observation.
pub fn ellipse_region(
    pixels: &[u8],
    spec: HeadMaskSpec,
    measured: Ellipse,
    stabilized: Ellipse,
    kind: u32,
) -> Option<Ellipse> {
    if !spec.valid(pixels) || !valid_seed(measured) || !valid_seed(stabilized) || kind > 2 {
        return None;
    }
    let tr = spec.transform;
    let cell_x = tr.input_size as f32 / (spec.width as f32 * tr.scale);
    let cell_y = tr.input_size as f32 / (spec.height as f32 * tr.scale);
    if !cell_x.is_finite() || !cell_y.is_finite() || cell_x <= 0.0 || cell_y <= 0.0 {
        return None;
    }
    let qualified = cap_trace(pixels, spec, measured, kind == 0, false, false).or_else(|| {
        (kind == 0)
            .then(|| {
                cap_trace(pixels, spec, measured, true, true, false).or_else(|| {
                    // This additional discrete confirmation is rendering
                    // only. Require a complete cap with the current
                    // detector center inside it; ambiguous partial
                    // caps keep the pre-existing conservative fallback.
                    cap_trace(pixels, spec, measured, true, true, true).filter(|(c, _)| {
                        !c.partial
                            && measured.center_x >= c.bounds.left
                            && measured.center_x <= c.bounds.right
                            && measured.center_y >= c.bounds.top
                            && measured.center_y <= c.bounds.bottom
                    })
                })
            })
            .flatten()
    });
    let mut base = stabilized;
    let clipped = spec.person.top <= cell_y;
    // Direct measurement must never disappear even if a caller supplied
    // a smaller stabilizer output. Both direct centers normally coincide.
    if kind == 0 || clipped {
        if measured.center_x == base.center_x && measured.center_y == base.center_y {
            base.radius_x = base.radius_x.max(measured.radius_x);
            base.radius_y = base.radius_y.max(measured.radius_y);
        } else {
            let shift =
                (measured.center_x - base.center_x).hypot(measured.center_y - base.center_y);
            let extent = shift + measured.radius_x.max(measured.radius_y);
            base.radius_x = base.radius_x.max(extent);
            base.radius_y = base.radius_y.max(extent);
        }
    }
    // A weak projection can drift sideways while the selected body's
    // current, top-clipped silhouette remains observed. Use the existing
    // independent current-mask head estimate only if a complete supported
    // edge cap contradicts the *entire* previous required ellipse.
    // This changes rendering only; direct observations keep their extent.
    if kind == 1
        && clipped
        && let Some(fresh) = crate::face_geometry::current_head_region(Some(pixels), spec, None)
        && fresh.current_mask_guided == 1
        && let Some((head, path)) = cap_trace(pixels, spec, fresh.region, false, false, false)
        && !head.partial
        && head.rows >= 6
        && head.bounds.top <= cell_y
        && head.bounds.bottom - head.bounds.top <= (spec.person.bottom - spec.person.top) * 0.32
        && fresh.region.center_x >= head.bounds.left
        && fresh.region.center_x <= head.bounds.right
        && fresh.region.center_y >= head.bounds.top
        && fresh.region.center_y <= head.bounds.bottom
    {
        let edge = (tr.pad_top / tr.scale / cell_y).floor();
        let mut cells = 0usize;
        let mut strong = 0usize;
        for &(y, run) in &path {
            for x in run.left..run.right {
                cells += 1;
                strong += usize::from(pixels[y * spec.width as usize + x] >= 128);
            }
        }
        let cx = (head.bounds.left + head.bounds.right) * 0.5;
        let cy = (head.bounds.top + head.bounds.bottom) * 0.5;
        let outside = ((cx - base.center_x) / base.radius_x)
            .hypot((cy - base.center_y) / base.radius_y)
            > 1.0;
        if outside
            && cells > 0
            && strong * 10 >= cells * 9
            && edge >= 0.0
            && path.iter().any(|(y, _)| *y == edge as usize)
        {
            // The estimate is a current render requirement, never a
            // synthetic detector observation. Reuse the same ellipse path.
            return ellipse_region(pixels, spec, fresh.region, fresh.region, 2);
        }
    }
    let Some((mut cap, mut path)) = qualified else {
        // If the upper lobe is ambiguous, do not discard old support. Use
        // the actual detector for the mask search, not temporally enlarged
        // radii that would mistake progressively wider arms for the head.
        return if kind == 0 {
            let support = legacy_face_support(pixels, spec, measured);
            let region = support.map_or(base, |bounds| {
                crate::face_geometry::cover_rect(base, bounds)
            });
            crate::face_geometry::sampled_face_region(region, tr, spec.width, spec.height)
        } else {
            Some(base)
        };
    };
    if kind == 0 && !clipped {
        refine_lower_junction(pixels, spec, measured, &mut cap, &mut path);
    }
    if kind != 0 && !clipped {
        // A current head-like cap within the weak region may correct a
        // downward translation caused by changing body bounds. Keep the
        // existing radii; never move a confirmed detector.
        let cy = cap.bounds.top + (cap.bounds.bottom - cap.bounds.top) * 0.75;
        let cx = base.center_x.clamp(cap.bounds.left, cap.bounds.right);
        let dx = (cx - base.center_x) / base.radius_x;
        let dy = (cy - base.center_y) / base.radius_y;
        if base.center_y > cap.bounds.bottom && dx * dx + dy * dy <= 1.0 {
            base.center_y = cy;
        }
    }
    let sx = |v: usize| {
        (v as f64 * f64::from(cell_x) - f64::from(tr.pad_left / tr.scale))
            .clamp(0.0, f64::from(tr.source_width))
    };
    let sy = |v: usize| {
        (v as f64 * f64::from(cell_y) - f64::from(tr.pad_top / tr.scale))
            .clamp(0.0, f64::from(tr.source_height))
    };
    // Each row contributes the four corners of its own foreground cells,
    // rather than invented corners combining different silhouette rows.
    let points: Vec<[f64; 2]> = path
        .iter()
        .flat_map(|&(y, run)| {
            let (l, r, t, b) = (sx(run.left), sx(run.right), sy(y), sy(y + 1));
            [[l, t], [r, t], [l, b], [r, b]]
        })
        .collect();
    let cx = f64::from(base.center_x);
    let cy = f64::from(base.center_y);
    let rx = f64::from(base.radius_x);
    let ry = f64::from(base.radius_y);
    let covers = |a: f64, b: f64| {
        points
            .iter()
            .all(|p| ((p[0] - cx) / a).hypot((p[1] - cy) / b) <= 1.0)
    };
    let major = rx.max(ry);
    let required = if covers(rx, ry) {
        base
    } else if covers(major, major) {
        let (mut lo, mut hi) = (0.0, 1.0);
        for _ in 0..24 {
            let t = (lo + hi) * 0.5;
            if covers(rx + (major - rx) * t, ry + (major - ry) * t) {
                hi = t;
            } else {
                lo = t;
            }
        }
        Ellipse {
            radius_x: ((rx + (major - rx) * hi) as f32).next_up(),
            radius_y: ((ry + (major - ry) * hi) as f32).next_up(),
            ..base
        }
    } else {
        // Preserve the seed's circumscribed disk when translating. Optimize
        // the maximum of the disk constraint and actual row support along
        // the direction toward the connected cap's center.
        let dx = (f64::from(cap.bounds.left) + f64::from(cap.bounds.right)) * 0.5 - cx;
        let dy = (f64::from(cap.bounds.top) + f64::from(cap.bounds.bottom)) * 0.5 - cy;
        let distance = dx.hypot(dy);
        let (ux, uy) = if distance > 0.0 {
            (dx / distance, dy / distance)
        } else {
            (0.0, 0.0)
        };
        let farthest = |x: f64, y: f64| {
            points
                .iter()
                .map(|p| (p[0] - x).hypot(p[1] - y))
                .fold(0.0, f64::max)
        };
        let objective = |t: f64| (major + t).max(farthest(cx + ux * t, cy + uy * t));
        // Convex maximum of distances: ternary minimization is safe even
        // when the farthest point changes. Include the unshifted solution.
        let (mut lo, mut hi) = (0.0, distance.min((objective(0.0) - major).max(0.0)));
        for _ in 0..24 {
            let a = (2.0 * lo + hi) / 3.0;
            let b = (lo + 2.0 * hi) / 3.0;
            if objective(a) <= objective(b) {
                hi = b;
            } else {
                lo = a;
            }
        }
        let shift = (lo + hi) * 0.5;
        let shift = if objective(shift) < objective(0.0) {
            shift
        } else {
            0.0
        };
        let x = (cx + ux * shift) as f32;
        let y = (cy + uy * shift) as f32;
        let actual_x = f64::from(x);
        let actual_y = f64::from(y);
        let rad = ((major + (actual_x - cx).hypot(actual_y - cy)).max(farthest(actual_x, actual_y))
            as f32)
            .next_up();
        Ellipse {
            center_x: x,
            center_y: y,
            radius_x: rad,
            radius_y: rad,
        }
    };
    crate::face_geometry::sampled_face_region(required, tr, spec.width, spec.height)
}

/// Both detector observations must already have the same unique current owner.
/// A conflicting full-frame detection can be rejected as torso only when the
/// local detector is inside a full current head cap and the coarse center is
/// clearly below that cap. No confidence-only or size-only override.
pub fn prefer_local_face(
    pixels: &[u8],
    spec: HeadMaskSpec,
    primary: Ellipse,
    local: Ellipse,
) -> Option<bool> {
    if !spec.valid(pixels) || !valid_seed(primary) || !valid_seed(local) {
        return None;
    }
    let Some((c, _)) = cap_trace(pixels, spec, local, true, false, false)
        .or_else(|| cap_trace(pixels, spec, local, true, true, false))
    else {
        return Some(false);
    };
    let cell = spec.transform.input_size as f32 / (spec.height as f32 * spec.transform.scale);
    if !cell.is_finite() || cell <= 0.0 {
        return None;
    }
    Some(
        !c.partial
            && local.center_x >= c.bounds.left
            && local.center_x <= c.bounds.right
            && local.center_y >= c.bounds.top
            && local.center_y <= c.bounds.bottom
            && primary.center_y > c.bounds.bottom + cell
            && (primary.center_x - local.center_x).abs() <= primary.radius_x.max(local.radius_x)
            && primary.radius_y >= local.radius_y,
    )
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
    fn complete_clipped_current_head_reanchors_only_a_disjoint_weak_projection() {
        let spec = HeadMaskSpec {
            width: 160,
            height: 160,
            transform: Transform {
                scale: 2.0 / 3.0,
                pad_left: 0.0,
                pad_top: 140.0,
                source_width: 960,
                source_height: 540,
                input_size: 640,
                proto_size: 160,
            },
            person: Rect {
                left: 582.0,
                top: 0.5,
                right: 838.0,
                bottom: 536.5,
            },
        };
        let mut mask = vec![0; 160 * 160];
        // A connected clipped crown followed by sustained shoulder widening.
        for y in 35..50 {
            let half = if y < 48 { 9 } else { 11 };
            mask[y * 160 + 121 - half..y * 160 + 121 + half].fill(255);
        }
        for y in 50..63 {
            mask[y * 160 + 100..y * 160 + 139].fill(255);
        }
        for y in 63..124 {
            mask[y * 160 + 105..y * 160 + 134].fill(255);
        }
        let wrong = Ellipse {
            center_x: 664.6,
            center_y: 25.4,
            radius_x: 51.5,
            radius_y: 55.0,
        };
        let fresh = crate::face_geometry::current_head_region(Some(&mask), spec, None)
            .unwrap()
            .region;
        let current = ellipse_region(&mask, spec, fresh, fresh, 2).unwrap();
        assert_eq!(ellipse_region(&mask, spec, wrong, wrong, 1), Some(current));
        // Agreeing projection, direct evidence, missing mask and non-edge
        // cases must not be reanchored through this current-only path.
        let direct = ellipse_region(&mask, spec, wrong, wrong, 0).unwrap();
        assert_ne!(direct, current);
        let agreeing = Ellipse {
            center_x: fresh.center_x,
            center_y: fresh.center_y,
            radius_x: 120.0,
            radius_y: 120.0,
        };
        let out = ellipse_region(&mask, spec, agreeing, agreeing, 1).unwrap();
        assert!(out.radius_x >= agreeing.radius_x && out.radius_y >= agreeing.radius_y);
        assert_eq!(
            ellipse_region(&vec![0; 160 * 160], spec, wrong, wrong, 1),
            Some(wrong)
        );
        let mut non_edge = spec;
        non_edge.person.top = 20.0;
        assert_ne!(
            ellipse_region(&mask, non_edge, wrong, wrong, 1),
            Some(current)
        );
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
