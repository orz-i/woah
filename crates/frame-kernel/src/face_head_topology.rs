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

/// Research-only current head geometry for a separately proven body
/// tracklet. Shoulder observations locate/validate the cap; they are never
/// identity features. No historical pose or head rectangle enters placement.
pub(crate) fn tracklet_current_head(
    masks: &[u8],
    index: usize,
    count: usize,
    spec: HeadMaskSpec,
    shoulders: [f32; 6],
    faces: &[Ellipse],
) -> Option<HeadSticker> {
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    if !(2..=32).contains(&count)
        || index >= count
        || n.checked_mul(count)? != masks.len()
        || faces.len() != count
        || faces
            .iter()
            .any(|&f| !valid_seed(f) && f != Ellipse::default())
        || shoulders.iter().any(|v| !v.is_finite())
        || ![shoulders[2], shoulders[5]]
            .into_iter()
            .all(|v| (0.40..=1.0).contains(&v))
    {
        return None;
    }
    let own = &masks[index * n..(index + 1) * n];
    if !spec.valid(own)
        || spec.width != spec.transform.proto_size
        || spec.height != spec.transform.proto_size
    {
        return None;
    }
    let tr = spec.transform;
    let cell = tr.input_size as f32 / (tr.proto_size as f32 * tr.scale);
    if !cell.is_finite() || cell <= 0.0 {
        return None;
    }
    let (lx, ly, rx, ry) = (shoulders[0], shoulders[1], shoulders[3], shoulders[4]);
    let sx = (lx + rx) * 0.5;
    let sy = (ly + ry) * 0.5;
    let sw = (lx - rx).hypot(ly - ry);
    let bh = spec.person.bottom - spec.person.top;
    if sw < 4.0 * cell || !(0.10..=0.45).contains(&(sw / bh)) || (ly - ry).abs() > sw * 0.40 {
        return None;
    }
    for (x, y) in [(lx, ly), (rx, ry)] {
        if x < spec.person.left
            || x > spec.person.right
            || y < spec.person.top
            || y > spec.person.bottom
            || x < 0.0
            || x >= tr.source_width as f32
            || y < 0.0
            || y >= tr.source_height as f32
        {
            return None;
        }
        let gx = ((x * tr.scale + tr.pad_left) / tr.input_size as f32 * spec.width as f32).floor();
        let gy = ((y * tr.scale + tr.pad_top) / tr.input_size as f32 * spec.height as f32).floor();
        if !gx.is_finite()
            || !gy.is_finite()
            || gx < 0.0
            || gy < 0.0
            || gx >= spec.width as f32
            || gy >= spec.height as f32
        {
            return None;
        }
        let (gx, gy) = (gx as i32, gy as i32);
        let mut support = 0;
        for yy in gy - 1..=gy + 1 {
            for xx in gx - 1..=gx + 1 {
                if xx < 0 || yy < 0 || xx >= spec.width as i32 || yy >= spec.height as i32 {
                    continue;
                }
                let k = yy as usize * spec.width as usize + xx as usize;
                if own[k] >= 128
                    && masks
                        .chunks_exact(n)
                        .enumerate()
                        .all(|(j, m)| j == index || m[k] < 128)
                {
                    support += 1;
                }
            }
        }
        if support < 2 {
            return None;
        }
    }
    let seed = crate::face_geometry::current_head_region(Some(own), spec, None)?.region;
    let (cap, path) = cap_trace(own, spec, seed, false, false, false)?;
    let b = cap.bounds;
    let (w, h) = (b.right - b.left, b.bottom - b.top);
    if cap.partial
        || cap.rows < 6
        || !(0.35..=1.20).contains(&(w / sw))
        || !(0.40..=1.50).contains(&(h / sw))
        || ((b.left + b.right) * 0.5 - sx).abs() > 0.45 * sw
        || b.bottom > sy + 0.12 * sw
        || b.top > sy - 0.40 * sw
    {
        return None;
    }
    finish_tracklet_cap(masks, index, spec, b, &path, faces)
}

fn finish_tracklet_cap(
    masks: &[u8],
    index: usize,
    spec: HeadMaskSpec,
    b: Rect,
    path: &[(usize, Run)],
    faces: &[Ellipse],
) -> Option<HeadSticker> {
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    let own = &masks[index * n..(index + 1) * n];
    let tr = spec.transform;
    let cell = tr.input_size as f32 / (tr.proto_size as f32 * tr.scale);
    let (mut total, mut strong, mut rival) = (0usize, 0usize, 0usize);
    for &(y, run) in path {
        for x in run.left..run.right {
            let k = y * spec.width as usize + x;
            total += 1;
            strong += usize::from(own[k] >= 128);
            rival += usize::from(
                masks
                    .chunks_exact(n)
                    .enumerate()
                    .any(|(j, m)| j != index && m[k] >= 128),
            );
        }
    }
    if total == 0 || strong * 10 < total * 9 || rival * 20 > total {
        return None;
    }
    let b = Rect {
        left: (b.left - cell).max(0.0),
        top: (b.top - cell).max(0.0),
        right: (b.right + cell).min(tr.source_width as f32),
        bottom: (b.bottom + cell).min(tr.source_height as f32),
    };
    finish_tracklet_bounds(masks, index, spec, b, path.len() as u32, faces)
}

fn finish_tracklet_bounds(
    masks: &[u8],
    index: usize,
    spec: HeadMaskSpec,
    mut b: Rect,
    rows: u32,
    faces: &[Ellipse],
) -> Option<HeadSticker> {
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    let own = &masks[index * n..(index + 1) * n];
    let tr = spec.transform;
    // An actual selected-face measurement may not be discarded to make
    // the new cap smaller. It must be inside the connected cap, supported
    // by the current owner, and free of strong competing face-core support.
    let direct = faces[index];
    if valid_seed(direct) {
        use crate::face_observations::mask_support;
        if direct.center_x < b.left
            || direct.center_x > b.right
            || direct.center_y < b.top
            || direct.center_y > b.bottom
            || !mask_support(own, spec, direct).is_some_and(|s| s[0] >= 4 && s[1] >= 12)
            || masks
                .chunks_exact(n)
                .enumerate()
                .any(|(j, m)| j != index && mask_support(m, spec, direct).is_none_or(|s| s[1] > 4))
        {
            return None;
        }
        b.left = b.left.min((direct.center_x - direct.radius_x).max(0.0));
        b.right = b
            .right
            .max((direct.center_x + direct.radius_x).min(tr.source_width as f32));
        b.top = b.top.min((direct.center_y - direct.radius_y).max(0.0));
        b.bottom = b
            .bottom
            .max((direct.center_y + direct.radius_y).min(tr.source_height as f32));
    }
    let half = ((b.right - b.left).max(b.bottom - b.top) * 0.5).next_up();
    let square = Ellipse {
        center_x: (b.left + b.right) * 0.5,
        center_y: (b.top + b.bottom) * 0.5,
        radius_x: half,
        radius_y: half,
    };
    if !b.valid() || !valid_seed(square) {
        return None;
    }
    if rival_face_conflict(square, index, faces) {
        return None;
    }
    Some(HeadSticker {
        bounds: b,
        square,
        topology: 1,
        rows,
    })
}

/// Same render-square sampling margin for the existing and proposed
/// placement. Every current detected rival is retained, even if its mask
/// does not support the selected cap.
fn rival_face_conflict(square: Ellipse, index: usize, faces: &[Ellipse]) -> bool {
    let half = (1.02 * square.radius_x.max(square.radius_y)).max(8.0);
    faces
        .iter()
        .enumerate()
        .filter(|(j, f)| *j != index && valid_seed(**f))
        .any(|(_, f)| {
            let iw = (square.center_x + half).min(f.center_x + f.radius_x)
                - (square.center_x - half).max(f.center_x - f.radius_x);
            let ih = (square.center_y + half).min(f.center_y + f.radius_y)
                - (square.center_y - half).max(f.center_y - f.radius_y);
            iw.max(0.0) * ih.max(0.0) > 0.10 * 4.0 * f.radius_x * f.radius_y
        })
}

/// Diagnostic of a fixed current target extent against current rival
/// head envelopes. Square geometry is never clipped to improve overlap.
/// status: 0 clear within supplied evidence; 1 competing extent remains;
/// 2 no current rival measurement. The best square is diagnostic even on 1.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct HeadCompetition {
    pub square: Ellipse,
    pub required_overlap: f32,
    pub centered_overlap: f32,
    pub best_overlap: f32,
    pub status: u32,
    pub face_count: u32,
    pub cap_count: u32,
    pub unknown_count: u32,
    pub local_count: u32,
}

fn overlap_fraction(a: Rect, b: Rect) -> f64 {
    let w = f64::from(a.right.min(b.right) - a.left.max(b.left)).max(0.);
    let h = f64::from(a.bottom.min(b.bottom) - a.top.max(b.top)).max(0.);
    w * h / (f64::from(b.right - b.left) * f64::from(b.bottom - b.top))
}

/// For the minimum enclosing square, only the shorter axis has slack.
/// On that interval each rival rectangle overlap is piecewise linear.
/// Evaluate all knots and pairwise intersections of those linear segments:
/// this minimizes the worst supplied rival overlap, without shrinking the
/// necessary rectangle. Tie-break by total overlap and distance to center.
fn minimize_head_square(required: Rect, rivals: &[Rect]) -> Option<(Ellipse, f32, f32, f32)> {
    if !required.valid() || rivals.iter().any(|r| !r.valid()) || rivals.len() > 64 {
        return None;
    }
    let (cx, cy) = rect_center(required);
    let w = required.right - required.left;
    let h = required.bottom - required.top;
    let half = (w.max(h) * 0.5).next_up();
    if !half.is_finite() || !cx.is_finite() || !cy.is_finite() {
        return None;
    }
    let horizontal = h >= w;
    let center = if horizontal { cx } else { cy };
    let (lo, hi) = if horizontal {
        (required.right - half, required.left + half)
    } else {
        (required.bottom - half, required.top + half)
    };
    let lo = f64::from(lo.min(center));
    let hi = f64::from(hi.max(center));
    let center = f64::from(center);
    let radius = f64::from((1.02 * half).max(8.0));
    let interval = |b: Rect| {
        if horizontal {
            (
                f64::from(b.left),
                f64::from(b.right),
                f64::from(b.top),
                f64::from(b.bottom),
            )
        } else {
            (
                f64::from(b.top),
                f64::from(b.bottom),
                f64::from(b.left),
                f64::from(b.right),
            )
        }
    };
    let fixed = f64::from(if horizontal { cy } else { cx });
    let value = |b: Rect, x: f64| {
        let (a, z, t, d) = interval(b);
        let v = (d.min(fixed + radius) - t.max(fixed - radius)).max(0.0);
        (z.min(x + radius) - a.max(x - radius)).max(0.0) * v / ((z - a) * (d - t))
    };
    let metric = |x: f64| {
        let (mut worst, mut sum) = (0.0f64, 0.0f64);
        for &r in rivals {
            let q = value(r, x);
            worst = worst.max(q);
            sum += q;
        }
        (worst, sum, (x - center).abs())
    };
    let mut knots = vec![lo, hi, center.clamp(lo, hi)];
    for &b in rivals {
        let (a, z, _, _) = interval(b);
        knots.extend(
            [a - radius, a + radius, z - radius, z + radius]
                .into_iter()
                .map(|q| q.clamp(lo, hi)),
        );
    }
    knots.sort_by(f64::total_cmp);
    knots.dedup();
    let mut options = knots.clone();
    for pair in knots.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        if b - a < 1e-9 {
            continue;
        }
        let values = rivals
            .iter()
            .map(|&r| (value(r, a), value(r, b)))
            .collect::<Vec<_>>();
        for (i, &(u, v)) in values.iter().enumerate() {
            for &(s, t) in &values[i + 1..] {
                let d0 = u - s;
                let d1 = v - t;
                if d0 * d1 < 0.0 {
                    options.push(a + (b - a) * d0 / (d0 - d1));
                }
            }
        }
    }
    let cmp = |a: f64, b: f64| {
        let x = metric(a);
        let y = metric(b);
        for (l, r) in [(x.0, y.0), (x.1, y.1), (x.2, y.2)] {
            if (l - r).abs() > 1e-10 {
                return l.total_cmp(&r);
            }
        }
        a.total_cmp(&b)
    };
    let best = options.into_iter().min_by(|&a, &b| cmp(a, b))?;
    let square = Ellipse {
        center_x: if horizontal { best as f32 } else { cx },
        center_y: if horizontal { cy } else { best as f32 },
        radius_x: half,
        radius_y: half,
    };
    let floor = rivals
        .iter()
        .map(|&b| overlap_fraction(required, b))
        .fold(0., f64::max);
    let actual = f64::from(if horizontal {
        square.center_x
    } else {
        square.center_y
    });
    Some((
        square,
        floor as f32,
        metric(center).0 as f32,
        metric(actual).0 as f32,
    ))
}

/// Two upper-body context views for negative evidence only. Different
/// lateral context helps distinguish a missed adjacent head from the target.
pub(crate) fn competition_views(spec: HeadMaskSpec) -> Option<[Rect; 2]> {
    let b = spec.person;
    let t = spec.transform;
    if !b.valid() || !t.valid() {
        return None;
    }
    let h = b.bottom - b.top;
    let cx = (b.left + b.right) * 0.5;
    let half = ((b.right - b.left) * 0.5).max(0.30 * h);
    let view = |scale: f32| {
        let r = Rect {
            left: (cx - half * scale).floor().max(0.),
            right: (cx + half * scale).floor().min(t.source_width as f32),
            top: (b.top - 0.15 * h).floor().max(0.),
            bottom: (b.top + 0.62 * h).floor().min(t.source_height as f32),
        };
        (r.valid() && r.right - r.left >= 64. && r.bottom - r.top >= 64.).then_some(r)
    };
    let v = [view(1.)?, view(1.5)?];
    (v[0] != v[1]).then_some(v)
}

/// Certify a locally observed rival cap, corroborated by an independently
/// segmented second current view. The first cap must be a complete local
/// head, not a box estimate. No scene proposal may already own its core.
/// These are negative geometry witnesses; no identity is assigned.
pub(crate) fn competition_local_rival(
    scene: &[u8],
    count: usize,
    index: usize,
    spec: HeadMaskSpec,
    cap: HeadSticker,
    first: &[u8],
    second_view: (&[u8], &[f32]),
) -> Option<Rect> {
    let (second, confidence) = second_view;
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    if !(2..=32).contains(&count)
        || index >= count
        || scene.len() != n.checked_mul(count)?
        || first.len() != n
        || confidence.is_empty()
        || confidence.len() > 32
        || second.len() != n.checked_mul(confidence.len())?
        || confidence
            .iter()
            .any(|&v| !v.is_finite() || !(0.0..=1.0).contains(&v))
        || cap.topology != 1
        || cap.rows < 6
        || !cap.bounds.valid()
        || !valid_seed(cap.square)
        || spec.width != spec.transform.proto_size
        || spec.height != spec.transform.proto_size
        || !spec.valid(&scene[index * n..(index + 1) * n])
    {
        return None;
    }
    let views = competition_views(spec)?;
    let b = cap.bounds;
    let t = spec.transform;
    if views.iter().any(|v| {
        b.left < v.left + 2.
            || b.right > v.right - 2.
            || b.top < v.top + 2.
            || b.bottom > v.bottom - 2.
    }) {
        return None;
    }
    let w = spec.width as usize;
    let h = spec.height as usize;
    let mut common = vec![];
    let mut head = vec![];
    for y in 0..h {
        for x in 0..w {
            let sx = ((x as f32 + 0.5) * t.input_size as f32 / t.proto_size as f32 - t.pad_left)
                / t.scale;
            let sy = ((y as f32 + 0.5) * t.input_size as f32 / t.proto_size as f32 - t.pad_top)
                / t.scale;
            if views
                .iter()
                .all(|v| sx >= v.left && sx < v.right && sy >= v.top && sy < v.bottom)
            {
                let k = y * w + x;
                common.push(k);
                if sx >= b.left && sx < b.right && sy >= b.top && sy < b.bottom && first[k] >= 128 {
                    head.push(k);
                }
            }
        }
    }
    if head.len() < 20
        || scene
            .chunks_exact(n)
            .any(|m| head.iter().filter(|&&k| m[k] >= 128).count() * 10 > head.len())
    {
        return None;
    }
    let score = |m: &[u8]| {
        let (mut a, mut u) = (0usize, 0usize);
        for &k in &common {
            a += usize::from(first[k] >= 128 && m[k] >= 128);
            u += usize::from(first[k] >= 128 || m[k] >= 128);
        }
        if u == 0 { 0.0 } else { a as f32 / u as f32 }
    };
    let scores = second.chunks_exact(n).map(score).collect::<Vec<_>>();
    let (j, &best) = scores
        .iter()
        .enumerate()
        .max_by(|a, b| a.1.total_cmp(b.1))?;
    if best < 0.60
        || confidence[j] < 0.45
        || scores
            .iter()
            .enumerate()
            .any(|(k, &s)| k != j && best - s < 0.20)
    {
        return None;
    }
    let m = &second[j * n..(j + 1) * n];
    if head.iter().filter(|&&k| m[k] >= 128).count() * 10 < head.len() * 9 {
        return None;
    }
    Some(b)
}

/// Use current detected rival faces as negative evidence, plus complete
/// current rival caps only if independently mask-owned. A missing rival
/// face is reported separately; no missing observation certifies safety.
pub(crate) fn current_head_competition(
    masks: &[u8],
    index: usize,
    spec: HeadMaskSpec,
    required: Rect,
    boxes: &[Rect],
    faces: &[Ellipse],
    extra: &[Rect],
) -> Option<HeadCompetition> {
    use crate::face_observations::mask_support;
    let count = boxes.len();
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    if !(2..=32).contains(&count)
        || index >= count
        || faces.len() != count
        || n.checked_mul(count)? != masks.len()
        || boxes.iter().any(|b| !b.valid())
        || faces
            .iter()
            .any(|&f| !valid_seed(f) && f != Ellipse::default())
        || !spec.valid(&masks[index * n..(index + 1) * n])
        || boxes[index] != spec.person
        || !required.valid()
        || spec.width != spec.transform.proto_size
        || spec.height != spec.transform.proto_size
    {
        return None;
    }
    let t = spec.transform;
    let clip = |b: Rect| Rect {
        left: b.left.max(0.),
        top: b.top.max(0.),
        right: b.right.min(t.source_width as f32),
        bottom: b.bottom.min(t.source_height as f32),
    };
    if clip(required) != required {
        return None;
    }
    let (cx, cy) = rect_center(required);
    let r = (1.02 * ((required.right - required.left).max(required.bottom - required.top) * 0.5))
        .max(8.);
    let nearby = Rect {
        left: cx - r,
        top: cy - r,
        right: cx + r,
        bottom: cy + r,
    };
    let mut rivals = Vec::new();
    let (mut face_count, mut cap_count, mut unknown_count) = (0, 0, 0);
    for (j, &f) in faces.iter().enumerate() {
        if j == index {
            continue;
        }
        let b = boxes[j];
        let sp = HeadMaskSpec { person: b, ..spec };
        let possible = Rect {
            bottom: b.top + (b.bottom - b.top) * 0.35,
            ..b
        };
        if !valid_seed(f) {
            if crate::geometry::overlap_iou(nearby, possible) > 0. {
                unknown_count += 1;
            }
            continue;
        }
        let face = clip(Rect {
            left: f.center_x - f.radius_x,
            top: f.center_y - f.radius_y,
            right: f.center_x + f.radius_x,
            bottom: f.center_y + f.radius_y,
        });
        if !face.valid() {
            continue;
        }
        // Keep direct rival detector as negative evidence even if its
        // ownership is uncertain. Admit its larger head only on current proof.
        rivals.push(face);
        face_count += 1;
        let m = &masks[j * n..(j + 1) * n];
        if mask_support(m, sp, f).is_some_and(|v| v[0] >= 4 && v[1] >= 12)
            && masks
                .chunks_exact(n)
                .enumerate()
                .all(|(k, q)| k == j || mask_support(q, sp, f).is_some_and(|v| v[1] <= 4))
            && let Some(cap) = sticker(m, sp, f, f, 0)
            && cap.topology == 1
        {
            let b = clip(cap.bounds);
            if b.valid() {
                rivals.push(b);
                cap_count += 1;
            }
        }
    }
    if extra.len() > 2 || extra.iter().any(|&b| !b.valid() || clip(b) != b) {
        return None;
    }
    rivals.extend_from_slice(extra);
    let (square, floor, center, best) = minimize_head_square(required, &rivals)?;
    Some(HeadCompetition {
        square,
        required_overlap: floor,
        centered_overlap: center,
        best_overlap: best,
        status: if rivals.is_empty() {
            2
        } else if best > 0.10 {
            1
        } else {
            0
        },
        face_count,
        cap_count,
        unknown_count,
        local_count: extra.len() as u32,
    })
}

/// A complete current crown recovered from a stable ear-level run rather
/// than its tiny first rows. Geometry only; identity must be independently
/// proven. A wide/branched run at ear level is not accepted as a head.
pub(crate) fn tracklet_ear_cap(
    masks: &[u8],
    index: usize,
    count: usize,
    spec: HeadMaskSpec,
    pose: [f32; 12],
    faces: &[Ellipse],
) -> Option<HeadSticker> {
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    if !(2..=32).contains(&count)
        || index >= count
        || n.checked_mul(count)? != masks.len()
        || faces.len() != count
        || faces
            .iter()
            .any(|&f| !valid_seed(f) && f != Ellipse::default())
        || pose.iter().any(|v| !v.is_finite())
        || pose.chunks_exact(3).any(|v| !(0.40..=1.0).contains(&v[2]))
    {
        return None;
    }
    let own = &masks[index * n..(index + 1) * n];
    let tr = spec.transform;
    if !spec.valid(own) || spec.width != tr.proto_size || spec.height != tr.proto_size {
        return None;
    }
    let cell = tr.input_size as f32 / (tr.proto_size as f32 * tr.scale);
    if !cell.is_finite() || cell <= 0.0 {
        return None;
    }
    let w = spec.width as usize;
    let h = spec.height as usize;
    let (ex, ey) = ((pose[0] + pose[3]) * 0.5, (pose[1] + pose[4]) * 0.5);
    let (sx, sy) = ((pose[6] + pose[9]) * 0.5, (pose[7] + pose[10]) * 0.5);
    let ew = (pose[0] - pose[3]).abs();
    let sw = (pose[6] - pose[9]).hypot(pose[7] - pose[10]);
    let bh = spec.person.bottom - spec.person.top;
    if sw < 4.0 * cell
        || !(0.10..=0.45).contains(&(sw / bh))
        || (pose[7] - pose[10]).abs() > 0.40 * sw
        || ew < 3.0 * cell
        || !(0.25..=0.75).contains(&(ew / sw))
        || (pose[1] - pose[4]).abs() > ew * 0.4
        || (ex - sx).abs() > 0.45 * sw
        || !(0.30..=0.90).contains(&((sy - ey) / sw))
    {
        return None;
    }
    let gx = |x: f32| (x * tr.scale + tr.pad_left) * w as f32 / tr.input_size as f32;
    let gy = |y: f32| (y * tr.scale + tr.pad_top) * h as f32 / tr.input_size as f32;
    let fx =
        |x: usize| (x as f32 * cell - tr.pad_left / tr.scale).clamp(0.0, tr.source_width as f32);
    let fy =
        |y: usize| (y as f32 * cell - tr.pad_top / tr.scale).clamp(0.0, tr.source_height as f32);
    for q in pose.chunks_exact(3) {
        if q[0] < spec.person.left
            || q[0] > spec.person.right
            || q[1] < spec.person.top
            || q[1] > spec.person.bottom
            || q[0] < 0.0
            || q[0] >= tr.source_width as f32
            || q[1] < 0.0
            || q[1] >= tr.source_height as f32
        {
            return None;
        }
        let x = gx(q[0]).floor() as i32;
        let y = gy(q[1]).floor() as i32;
        let mut supported = 0;
        for yy in y - 1..=y + 1 {
            for xx in x - 1..=x + 1 {
                if xx < 0 || yy < 0 || xx >= w as i32 || yy >= h as i32 {
                    continue;
                }
                let k = yy as usize * w + xx as usize;
                if own[k] >= 128
                    && masks
                        .chunks_exact(n)
                        .enumerate()
                        .all(|(j, m)| j == index || m[k] < 128)
                {
                    supported += 1;
                }
            }
        }
        if supported < 2 {
            return None;
        }
    }
    let left = gx(spec.person.left - cell).floor().clamp(0.0, w as f32) as usize;
    let right = gx(spec.person.right + cell).ceil().clamp(0.0, w as f32) as usize;
    let top = gy((spec.person.top - 2.0 * cell).max(ey - sw))
        .floor()
        .clamp(0.0, h as f32) as usize;
    let bottom = gy(sy + cell).ceil().clamp(0.0, h as f32) as usize;
    let y0 = gy(ey).floor() as usize;
    let x0 = gx(ex);
    if left >= right || y0 <= top + 2 || y0 + 2 >= bottom {
        return None;
    }
    let e0 = gx(pose[0].min(pose[3]));
    let e1 = gx(pose[0].max(pose[3]));
    let at = runs(own, w, y0, left, right);
    let core = at
        .into_iter()
        .find(|r| r.left as f32 <= e0 + 1.0 && r.right as f32 >= e1 - 1.0)?;
    let cw = core.width() as f32 * cell;
    if !(1.05..=2.1).contains(&(cw / ew))
        || !(0.35..=0.95).contains(&(cw / sw))
        || (core.center() - x0).abs() * cell > 0.25 * sw
    {
        return None;
    }
    let mut path = vec![(y0, core)];
    let mut prev = core;
    let mut reached_top = false;
    for y in (top..y0).rev() {
        let row = runs(own, w, y, left, right);
        let mut linked = row
            .iter()
            .copied()
            .filter(|r| r.overlap(prev) * 2 >= r.width().min(prev.width()));
        let Some(run) = linked.next() else {
            reached_top = true;
            break;
        };
        if linked.next().is_some()
            || run.width() as f32 > core.width() as f32 * 1.35 + 0.5
            || (run.center() - x0).abs() * cell > 0.35 * sw
        {
            return None;
        }
        path.push((y, run));
        prev = run;
        // At most one tiny tip row may be lost to the two-cell run minimum;
        // margin below covers it. A wider continuing lobe at boundary fails.
    }
    if !reached_top || y0 - path.last()?.0 < 3 {
        return None;
    }
    prev = core;
    let mut widened = false;
    let mut pending = None;
    for y in y0 + 1..bottom {
        let row = runs(own, w, y, left, right);
        let mut linked = row
            .iter()
            .copied()
            .filter(|r| r.overlap(prev) * 2 >= r.width().min(prev.width()));
        let run = linked.next()?;
        if linked.next().is_some() {
            return None;
        }
        let broad = run.width() as f32 > core.width() as f32 * 1.30 + 0.5
            || run.width() as f32 * cell > sw * 0.95;
        if broad {
            if pending.is_some() {
                widened = true;
                break;
            }
            pending = Some(run);
            continue;
        }
        if pending.is_some() || (run.center() - x0).abs() * cell > 0.35 * sw {
            return None;
        }
        path.push((y, run));
        prev = run;
    }
    if !widened || path.len() < 6 || path.iter().map(|(y, _)| *y).max()? <= y0 {
        return None;
    }
    let l = path.iter().map(|(_, r)| r.left).min()?;
    let r = path.iter().map(|(_, r)| r.right).max()?;
    let t = path.iter().map(|(y, _)| *y).min()?;
    let b = path.iter().map(|(y, _)| *y).max()? + 1;
    let bounds = Rect {
        left: fx(l),
        top: fy(t),
        right: fx(r),
        bottom: fy(b),
    };
    let bw = bounds.right - bounds.left;
    let hh = bounds.bottom - bounds.top;
    if !(0.45..=1.15).contains(&(bw / sw))
        || !(0.50..=1.40).contains(&(hh / sw))
        || !(0.65..=1.65).contains(&(hh / bw))
        || bounds.bottom > sy + 0.12 * sw
        || bounds.top > ey - 0.25 * sw
        || (bounds.left + bounds.right - 2.0 * ex).abs() > 0.70 * sw
    {
        return None;
    }
    finish_tracklet_cap(masks, index, spec, bounds, &path, faces)
}

/// A current mirrored pose may fill weak keypoints, but may not relocate
/// a strongly inconsistent head or body. Both poses are on the same source
/// coordinate system; no historical or reflected raw mask is substituted.
pub(crate) fn tracklet_flip_ear_cap(
    masks: &[u8],
    index: usize,
    count: usize,
    spec: HeadMaskSpec,
    poses: [[f32; 12]; 2],
    faces: &[Ellipse],
) -> Option<HeadSticker> {
    let [primary, flipped] = poses;
    if primary.iter().chain(&flipped).any(|v| !v.is_finite())
        || primary
            .chunks_exact(3)
            .any(|v| !(0.25..=1.0).contains(&v[2]))
        || flipped
            .chunks_exact(3)
            .any(|v| !(0.40..=1.0).contains(&v[2]))
    {
        return None;
    }
    let sw = (flipped[6] - flipped[9]).hypot(flipped[7] - flipped[10]);
    if !sw.is_finite() || sw <= 0.0 {
        return None;
    }
    for (a, b) in primary.chunks_exact(3).zip(flipped.chunks_exact(3)) {
        let allowance = if a[2] >= 0.40 { 0.15 } else { 0.25 };
        if (a[0] - b[0]).hypot(a[1] - b[1]) > sw * allowance {
            return None;
        }
    }
    for i in [0, 6] {
        let dx = (primary[i] + primary[i + 3] - flipped[i] - flipped[i + 3]) * 0.5;
        let dy = (primary[i + 1] + primary[i + 4] - flipped[i + 1] - flipped[i + 4]) * 0.5;
        if dx.hypot(dy) > 0.15 * sw {
            return None;
        }
    }
    tracklet_ear_cap(masks, index, count, spec, flipped, faces)
}

/// Current profile head cap sampled in the ear-to-shoulder coordinate
/// system. Side-on or inclined heads need not have horizontally separated
/// ears or shoulders. Both independently mapped current poses must certify
/// a complete cap. The shared finalizer retains direct/rival face constraints.
pub(crate) fn profile_head_cap(
    masks: &[u8],
    index: usize,
    count: usize,
    spec: HeadMaskSpec,
    poses: [[f32; 21]; 2],
    faces: &[Ellipse],
) -> Option<HeadSticker> {
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    if !(3..=32).contains(&count)
        || index >= count
        || n.checked_mul(count)? != masks.len()
        || faces.len() != count
        || faces
            .iter()
            .any(|&f| !valid_seed(f) && f != Ellipse::default())
        || poses.iter().flatten().any(|v| !v.is_finite())
        || poses
            .iter()
            .any(|p| p.chunks_exact(3).any(|q| !(0.0..=1.0).contains(&q[2])))
    {
        return None;
    }
    let own = &masks[index * n..(index + 1) * n];
    let tr = spec.transform;
    if !spec.valid(own) || spec.width != tr.proto_size || spec.height != tr.proto_size {
        return None;
    }
    let cell = tr.input_size as f32 / (tr.proto_size as f32 * tr.scale);
    if !cell.is_finite() || cell <= 0.0 {
        return None;
    }
    let w = spec.width as usize;
    let h = spec.height as usize;
    let bh = spec.person.bottom - spec.person.top;
    let at = |x: f32, y: f32| -> Option<usize> {
        if x < 0.0 || y < 0.0 || x >= tr.source_width as f32 || y >= tr.source_height as f32 {
            return None;
        }
        let u = ((x * tr.scale + tr.pad_left) * w as f32 / tr.input_size as f32).floor();
        let v = ((y * tr.scale + tr.pad_top) * h as f32 / tr.input_size as f32).floor();
        (u >= 0.0 && v >= 0.0 && u < w as f32 && v < h as f32)
            .then_some(v as usize * w + u as usize)
    };
    let rival = |i: usize| {
        masks
            .chunks_exact(n)
            .enumerate()
            .any(|(j, m)| j != index && m[i] >= 128)
    };
    let pose_geom = |p: [f32; 21]| -> Option<([f32; 2], [f32; 2], f32)> {
        // Pose arrays are nose, both eyes, both ears, both shoulders.
        if p[2] < 0.40 || p[11] < 0.40 || p[14] < 0.40 || p[17] < 0.40 || p[20] < 0.40 {
            return None;
        }
        let e = [(p[9] + p[12]) * 0.5, (p[10] + p[13]) * 0.5];
        let s = [(p[15] + p[18]) * 0.5, (p[16] + p[19]) * 0.5];
        let dist = (e[0] - s[0]).hypot(e[1] - s[1]);
        if dist < 4.0 * cell
            || !(0.06..=0.22).contains(&(dist / bh))
            || (p[9] - p[12]).hypot(p[10] - p[13]) > 0.5 * dist
            || !(0.30..=1.35).contains(&((p[0] - e[0]).hypot(p[1] - e[1]) / dist))
        {
            return None;
        }
        for j in [0, 3, 4, 5, 6] {
            let (x, y) = (p[j * 3], p[j * 3 + 1]);
            if x < spec.person.left - cell
                || x > spec.person.right + cell
                || y < spec.person.top - cell
                || y > spec.person.bottom + cell
            {
                return None;
            }
            let mut support = 0;
            for dy in -1..=1 {
                for dx in -1..=1 {
                    if at(x + dx as f32 * cell, y + dy as f32 * cell)
                        .is_some_and(|q| own[q] >= 128 && !rival(q))
                    {
                        support += 1;
                    }
                }
            }
            // The nose can protrude from the coarse segmentation, but both
            // ears/shoulders need positive exclusive local support.
            if j != 0 && support < 2 {
                return None;
            }
        }
        Some((e, s, dist))
    };
    let a = pose_geom(poses[0])?;
    let b = pose_geom(poses[1])?;
    if !(0.75..=1.33).contains(&(a.2 / b.2))
        || (a.0[0] - b.0[0]).hypot(a.0[1] - b.0[1]) > 0.25 * a.2
        || (a.1[0] - b.1[0]).hypot(a.1[1] - b.1[1]) > 0.30 * a.2
    {
        return None;
    }
    let scan = |(e, s, dist): ([f32; 2], [f32; 2], f32)| -> Option<(Rect, u32)> {
        let axis = [(s[0] - e[0]) / dist, (s[1] - e[1]) / dist];
        if axis[1] < 0.20 {
            return None;
        }
        let side = [axis[1], -axis[0]];
        let step = cell * 0.5;
        let gw = (3.6 * dist / step).ceil() as usize + 1;
        let gh = (2.9 * dist / step).ceil() as usize + 1;
        if gw > 160 || gh > 160 || gw < 9 || gh < 9 {
            return None;
        }
        let coord = |x: usize, y: usize| {
            let u = x as f32 * step - 1.8 * dist;
            let v = y as f32 * step - 1.7 * dist;
            [
                e[0] + u * side[0] + v * axis[0],
                e[1] + u * side[1] + v * axis[1],
            ]
        };
        let mut mask = vec![0u8; gw * gh];
        let mut comp = vec![false; gw * gh];
        for y in 0..gh {
            for x in 0..gw {
                let [x0, y0] = coord(x, y);
                if let Some(i) = at(x0, y0) {
                    mask[y * gw + x] = own[i];
                    comp[y * gw + x] = rival(i);
                }
            }
        }
        let x0 = (1.8 * dist / step).round() as usize;
        let y0 = (1.7 * dist / step).round() as usize;
        if y0 < 3 || y0 + 3 >= gh {
            return None;
        }
        let near = |y| {
            runs(&mask, gw, y, 0, gw)
                .into_iter()
                .find(|r| r.left <= x0 && x0 < r.right)
        };
        let seed = near(y0)?;
        let mut widths = (y0 - 2..y0 + 2)
            .filter_map(|y| near(y).map(|q| q.width()))
            .collect::<Vec<_>>();
        if widths.len() < 3 {
            return None;
        }
        widths.sort_unstable();
        let reference = widths[widths.len() / 2] as f32;
        if reference < 4.0 || !(0.85..=2.35).contains(&(reference * step / dist)) {
            return None;
        }
        let linked = |y: usize, prev: Run| {
            runs(&mask, gw, y, 0, gw)
                .into_iter()
                .filter(|q| q.overlap(prev) * 2 >= q.width().min(prev.width()))
                .collect::<Vec<_>>()
        };
        let mut path = vec![(y0, seed)];
        let mut prev = seed;
        let mut crown = false;
        for y in (0..y0).rev() {
            let mut rs = linked(y, prev);
            if rs.is_empty() {
                crown = true;
                break;
            }
            if rs.len() == 2 && rs[1].left.saturating_sub(rs[0].right) <= 1 {
                rs = vec![Run {
                    left: rs[0].left,
                    right: rs[1].right,
                }];
            }
            if rs.len() != 1 {
                return None;
            }
            let r = rs[0];
            if r.left == 0 || r.right == gw || r.width() as f32 > 1.4 * reference + 1.0 {
                return None;
            }
            path.push((y, r));
            prev = r;
        }
        if !crown || ((y0 - path.last()?.0) as f32) * step < 0.55 * dist {
            return None;
        }
        prev = seed;
        let mut widened = 0;
        let mut lower = false;
        for y in y0 + 1..gh {
            let rs = linked(y, prev);
            if rs.len() != 1 {
                return None;
            }
            let r = rs[0];
            let contested = comp[y * gw + r.left..y * gw + r.right]
                .iter()
                .filter(|&&v| v)
                .count();
            let broad = r.width() as f32 > 1.22 * reference + 0.5 || contested * 10 > r.width();
            if broad {
                widened += 1;
                if widened >= 2 {
                    lower = true;
                    break;
                }
                continue;
            }
            if widened > 0
                || r.left == 0
                || r.right == gw
                || r.width() as f32 > 1.4 * reference + 1.0
            {
                return None;
            }
            path.push((y, r));
            prev = r;
        }
        if !lower || path.len() < 8 {
            return None;
        }
        let bottom = path.iter().map(|q| q.0).max()?;
        if !((0.10 * dist)..=(1.05 * dist)).contains(&((bottom - y0) as f32 * step)) {
            return None;
        }
        let mut b = Rect {
            left: f32::INFINITY,
            top: f32::INFINITY,
            right: f32::NEG_INFINITY,
            bottom: f32::NEG_INFINITY,
        };
        let (mut total, mut strong, mut competing) = (0usize, 0usize, 0usize);
        for &(y, r) in &path {
            for x in r.left..r.right {
                let [u, v] = coord(x, y);
                b.left = b.left.min(u);
                b.right = b.right.max(u);
                b.top = b.top.min(v);
                b.bottom = b.bottom.max(v);
                total += 1;
                strong += usize::from(mask[y * gw + x] >= 128);
                competing += usize::from(comp[y * gw + x]);
            }
        }
        if total == 0 || strong * 10 < total * 9 || competing * 20 > total {
            return None;
        }
        b.left = (b.left - step).max(0.);
        b.right = (b.right + step).min(tr.source_width as f32);
        b.top = (b.top - step).max(0.);
        b.bottom = (b.bottom + step).min(tr.source_height as f32);
        let bw = b.right - b.left;
        let hh = b.bottom - b.top;
        if !b.valid()
            || !(0.06..=0.28).contains(&(bw / bh))
            || !(0.06..=0.32).contains(&(hh / bh))
            || !(0.60..=1.75).contains(&(hh / bw))
        {
            return None;
        }
        Some((b, path.len() as u32))
    };
    let (a, ar) = scan(a)?;
    let (b, br) = scan(b)?;
    if crate::geometry::overlap_iou(a, b) < 0.75 {
        return None;
    }
    let union = Rect {
        left: a.left.min(b.left),
        top: a.top.min(b.top),
        right: a.right.max(b.right),
        bottom: a.bottom.max(b.bottom),
    };
    finish_tracklet_bounds(masks, index, spec, union, ar.min(br), faces)
}

/// Only repair an oversized *projected* placement when a current rival
/// face is actually endangered and both fresh pose-axis caps agree. No
/// direct-face measurement, identity clock or historical geometry is changed.
pub(crate) fn profile_head_repair(
    masks: &[u8],
    index: usize,
    count: usize,
    spec: HeadMaskSpec,
    poses: [[f32; 21]; 2],
    faces: &[Ellipse],
    previous: Ellipse,
) -> Option<HeadSticker> {
    if !valid_seed(previous)
        || faces.get(index).is_none_or(|&f| f != Ellipse::default())
        || !rival_face_conflict(previous, index, faces)
    {
        return None;
    }
    let cap = profile_head_cap(masks, index, count, spec, poses, faces)?;
    if cap.square.radius_x > 0.80 * previous.radius_x.max(previous.radius_y) {
        return None;
    }
    Some(cap)
}

/// Current small-head face views driven by two mapped pose observations.
/// Pose locates inference only; no pose radius is used as render evidence.
pub(crate) fn current_pose_face_views(
    masks: &[u8],
    index: usize,
    count: usize,
    spec: HeadMaskSpec,
    poses: [[f32; 21]; 2],
) -> Option<[[f32; 6]; 2]> {
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    if !(3..=32).contains(&count) || index >= count || n.checked_mul(count)? != masks.len() {
        return None;
    }
    let own = &masks[index * n..(index + 1) * n];
    let t = spec.transform;
    if !spec.valid(own) || spec.width != t.proto_size || spec.height != t.proto_size {
        return None;
    }
    let h = spec.person.bottom - spec.person.top;
    let cell = t.input_size as f32 / (t.proto_size as f32 * t.scale);
    if !cell.is_finite() || cell <= 0.0 {
        return None;
    }
    let mut centers = [[0.; 2]; 2];
    let mut out = [[0.; 6]; 2];
    for (i, p) in poses.iter().enumerate() {
        if p.iter().any(|v| !v.is_finite())
            || [0, 3, 4]
                .iter()
                .any(|&j| !(0.35..=1.0).contains(&p[3 * j + 2]))
        {
            return None;
        }
        let e = [(p[9] + p[12]) * 0.5, (p[10] + p[13]) * 0.5];
        if e[0] < spec.person.left
            || e[0] > spec.person.right
            || e[1] < spec.person.top
            || e[1] > spec.person.top + 0.25 * h
        {
            return None;
        }
        let x = ((e[0] * t.scale + t.pad_left) * spec.width as f32 / t.input_size as f32).floor()
            as i32;
        let y = ((e[1] * t.scale + t.pad_top) * spec.height as f32 / t.input_size as f32).floor()
            as i32;
        let mut support = 0;
        for yy in y - 1..=y + 1 {
            for xx in x - 1..=x + 1 {
                if xx < 0 || yy < 0 || xx >= spec.width as i32 || yy >= spec.height as i32 {
                    continue;
                }
                let k = yy as usize * spec.width as usize + xx as usize;
                if own[k] >= 128
                    && masks
                        .chunks_exact(n)
                        .enumerate()
                        .all(|(j, m)| j == index || m[k] < 128)
                {
                    support += 1;
                }
            }
        }
        if support < 2 {
            return None;
        }
        centers[i] = e;
        let side = (h * [0.23, 0.30][i]).min(t.source_width.min(t.source_height) as f32);
        if side < 4.0 * cell {
            return None;
        }
        let cx = e[0];
        let cy = e[1] - 0.02 * h;
        let l = (cx - side * 0.5).clamp(0., t.source_width as f32 - side);
        let top = (cy - side * 0.5).clamp(0., t.source_height as f32 - side);
        out[i] = [
            l,
            top,
            l + side,
            top + side,
            (cx - l) / side,
            (cy - top) / side,
        ];
    }
    if (centers[0][0] - centers[1][0]).hypot(centers[0][1] - centers[1][1]) > cell.max(h * 0.04)
        || out[0] == out[1]
    {
        return None;
    }
    Some(out)
}

/// Independently corroborated current direct faces, from current pose views.
/// Reuse ordinary direct placement and the same final current rival veto.
/// No projected ellipse or historical face extent is fed into this decision.
pub(crate) fn current_pose_face_finish(
    masks: &[u8],
    index: usize,
    count: usize,
    spec: HeadMaskSpec,
    local: [Ellipse; 2],
    faces: &[Ellipse],
) -> Option<HeadSticker> {
    use crate::face_observations::mask_support;
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    if !(3..=32).contains(&count)
        || index >= count
        || n.checked_mul(count)? != masks.len()
        || faces.len() != count
        || faces
            .iter()
            .any(|&f| !valid_seed(f) && f != Ellipse::default())
        || local.iter().any(|&f| !valid_seed(f))
    {
        return None;
    }
    let own = &masks[index * n..(index + 1) * n];
    if !spec.valid(own) {
        return None;
    }
    let a = local[0];
    let b = local[1];
    let h = spec.person.bottom - spec.person.top;
    if ((a.center_x - b.center_x) / a.radius_x).hypot((a.center_y - b.center_y) / a.radius_y) > 0.35
        || !(0.70..=1.45).contains(&(b.radius_x / a.radius_x))
        || !(0.70..=1.45).contains(&(b.radius_y / a.radius_y))
    {
        return None;
    }
    for f in local {
        if f.center_y < spec.person.top
            || f.center_y > spec.person.top + 0.25 * h
            || !mask_support(own, spec, f).is_some_and(|s| s[0] >= 4 && s[1] >= 12)
            || masks
                .chunks_exact(n)
                .enumerate()
                .any(|(j, m)| j != index && mask_support(m, spec, f).is_none_or(|s| s[1] > 4))
        {
            return None;
        }
    }
    let a = sticker(own, spec, a, a, 0)?;
    let b = sticker(own, spec, b, b, 0)?;
    if crate::geometry::overlap_iou(a.bounds, b.bounds) < 0.65 {
        return None;
    }
    let bounds = Rect {
        left: a.bounds.left.min(b.bounds.left).max(0.),
        top: a.bounds.top.min(b.bounds.top).max(0.),
        right: a
            .bounds
            .right
            .max(b.bounds.right)
            .min(spec.transform.source_width as f32),
        bottom: a
            .bounds
            .bottom
            .max(b.bounds.bottom)
            .min(spec.transform.source_height as f32),
    };
    if bounds.right - bounds.left > 0.35 * h || bounds.bottom - bounds.top > 0.35 * h {
        return None;
    }
    let mut result = finish_tracklet_bounds(masks, index, spec, bounds, a.rows.min(b.rows), faces)?;
    result.topology = if a.topology == 1 && b.topology == 1 {
        1
    } else {
        0
    };
    Some(result)
}

/// Two bounded current body-detail views. The current raw body box locates
/// inference; no head labels, old pose or historical mask enter the plan.
pub(crate) fn tracklet_detail_views(spec: HeadMaskSpec) -> Option<[Rect; 2]> {
    let t = spec.transform;
    let b = spec.person;
    if !t.valid() || !b.valid() {
        return None;
    }
    let h = b.bottom - b.top;
    let w = b.right - b.left;
    let cx = (b.left + b.right) * 0.5;
    let radius = (0.70 * w).max(0.32 * h);
    let view = |depth: f32| {
        let r = Rect {
            left: (cx - radius).floor().max(0.0),
            right: (cx + radius).floor().min(t.source_width as f32),
            top: (b.top - 0.10 * h).floor().max(0.0),
            bottom: (b.top + depth * h).floor().min(t.source_height as f32),
        };
        (r.valid() && r.right - r.left >= 64.0 && r.bottom - r.top >= 64.0).then_some(r)
    };
    let v = [view(0.70)?, view(0.85)?];
    (v[0] != v[1]).then_some(v)
}

/// Compare independent current local proposals to the reprojected *current*
/// scene masks, with every raw rival retained. No local index is inferred
/// by relative order. A high-similarity duplicate is ambiguous.
pub(crate) fn tracklet_detail_owner(
    projected: &[u8],
    locals: &[u8],
    scene_count: usize,
    local_count: usize,
    index: usize,
    n: usize,
    confidence: &[f32],
) -> Option<usize> {
    if !(2..=32).contains(&scene_count)
        || !(1..=32).contains(&local_count)
        || index >= scene_count
        || n == 0
        || projected.len() != n.checked_mul(scene_count)?
        || locals.len() != n.checked_mul(local_count)?
        || confidence.len() != local_count
        || confidence
            .iter()
            .any(|&v| !v.is_finite() || !(0.0..=1.0).contains(&v))
    {
        return None;
    }
    let iou = |a: &[u8], b: &[u8]| {
        let (mut inter, mut union) = (0u32, 0u32);
        for (&x, &y) in a.iter().zip(b) {
            inter += u32::from(x >= 128 && y >= 128);
            union += u32::from(x >= 128 || y >= 128);
        }
        if union == 0 {
            0.0
        } else {
            inter as f32 / union as f32
        }
    };
    let own = &projected[index * n..(index + 1) * n];
    let scores = locals
        .chunks_exact(n)
        .map(|q| iou(own, q))
        .collect::<Vec<_>>();
    let (j, &score) = scores
        .iter()
        .enumerate()
        .max_by(|a, b| a.1.total_cmp(b.1))?;
    let candidate = &locals[j * n..(j + 1) * n];
    if score < 0.65
        || confidence[j] < 0.50
        || scores
            .iter()
            .enumerate()
            .any(|(i, &q)| i != j && score - q < 0.20)
        || projected.chunks_exact(n).enumerate().any(|(i, q)| {
            i != index && (iou(q, candidate) > 0.15 || score - iou(q, candidate) < 0.20)
        })
    {
        return None;
    }
    Some(j)
}

/// Two independently localized current caps must agree in scene geometry
/// and still pass original scene ownership and face competition. Their
/// union, not their average, determines placement. No crop-boundary lobe
/// or conflicting direct measurement is used as recovery.
pub(crate) fn tracklet_detail_finish(
    masks: &[u8],
    index: usize,
    count: usize,
    spec: HeadMaskSpec,
    caps: [HeadSticker; 2],
    faces: &[Ellipse],
) -> Option<HeadSticker> {
    use crate::face_observations::mask_support;
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    if !(2..=32).contains(&count)
        || index >= count
        || n.checked_mul(count)? != masks.len()
        || faces.len() != count
        || faces
            .iter()
            .any(|&f| !valid_seed(f) && f != Ellipse::default())
    {
        return None;
    }
    let own = &masks[index * n..(index + 1) * n];
    let t = spec.transform;
    if !spec.valid(own) || spec.width != t.proto_size || spec.height != t.proto_size {
        return None;
    }
    if caps
        .iter()
        .any(|c| c.topology != 1 || c.rows < 6 || !valid_seed(c.square))
    {
        return None;
    }
    let bounds = caps.map(|c| c.bounds);
    let views = tracklet_detail_views(spec)?;
    let cell = t.input_size as f32 / (t.proto_size as f32 * t.scale);
    if !cell.is_finite() || cell <= 0.0 {
        return None;
    }
    let bh = spec.person.bottom - spec.person.top;
    for (r, v) in bounds.iter().zip(views) {
        let w = r.right - r.left;
        let h = r.bottom - r.top;
        if !r.valid()
            || r.left <= v.left + cell
            || r.right >= v.right - cell
            || r.top <= v.top + cell
            || r.bottom >= v.bottom - cell
            || !(0.06..=0.35).contains(&(w / bh))
            || !(0.06..=0.35).contains(&(h / bh))
            || !(0.50..=1.80).contains(&(h / w))
            || r.bottom > spec.person.top + 0.35 * bh
        {
            return None;
        }
        let center = Ellipse {
            center_x: (r.left + r.right) * 0.5,
            center_y: (r.top + r.bottom) * 0.5,
            radius_x: w * 0.5,
            radius_y: h * 0.5,
        };
        if !mask_support(own, spec, center).is_some_and(|s| s[0] >= 4 && s[1] >= 12)
            || masks
                .chunks_exact(n)
                .enumerate()
                .any(|(j, m)| j != index && mask_support(m, spec, center).is_none_or(|s| s[1] > 4))
        {
            return None;
        }
    }
    if crate::geometry::overlap_iou(bounds[0], bounds[1]) < 0.65 {
        return None;
    }
    let b = Rect {
        left: bounds[0].left.min(bounds[1].left),
        top: bounds[0].top.min(bounds[1].top),
        right: bounds[0].right.max(bounds[1].right),
        bottom: bounds[0].bottom.max(bounds[1].bottom),
    };
    finish_tracklet_bounds(masks, index, spec, b, caps[0].rows.min(caps[1].rows), faces)
}

/// Current visible head cap at a clipped top edge. Both raw masks must be
/// supplied, in current detection order; no predicted mask is admissible.
/// The body-box estimate only locates the search, never sizes the result.
pub fn pair_edge_cap(masks: &[u8], index: usize, spec: HeadMaskSpec) -> Option<HeadSticker> {
    let size = (spec.width as usize).checked_mul(spec.height as usize)?;
    if index >= 2 || size == 0 || size.checked_mul(2)? != masks.len() {
        return None;
    }
    let own = &masks[index * size..(index + 1) * size];
    let rival = &masks[(1 - index) * size..(2 - index) * size];
    if !spec.valid(own)
        || spec.width != spec.transform.proto_size
        || spec.height != spec.transform.proto_size
        || rival.iter().filter(|&&v| v >= 128).take(8).count() < 8
    {
        return None;
    }
    let tr = spec.transform;
    let cell_x = tr.input_size as f32 / (spec.width as f32 * tr.scale);
    let cell_y = tr.input_size as f32 / (spec.height as f32 * tr.scale);
    if spec.person.top > cell_y
        || spec.person.top < -cell_y
        || !cell_x.is_finite()
        || !cell_y.is_finite()
    {
        return None;
    }
    let seed = crate::face_geometry::current_head_region(Some(own), spec, None)?.region;
    let (cap, path) = cap_trace(own, spec, seed, false, false, false)?;
    let b = cap.bounds;
    let edge_row = (tr.pad_top / tr.scale / cell_y).floor();
    if !edge_row.is_finite()
        || edge_row < 0.0
        || edge_row >= spec.height as f32
        || !path.iter().any(|(y, _)| *y == edge_row as usize)
    {
        return None;
    }
    if cap.partial
        || b.top > cell_y
        || cap.rows < 6
        || seed.center_x < b.left
        || seed.center_x > b.right
        || seed.center_y < b.top
        || seed.center_y > b.bottom
        || b.bottom - b.top > (spec.person.bottom - spec.person.top) * 0.32
    {
        return None;
    }
    // A coherent edge cap may still include a different person when masks
    // merge at the shoulder. Require strong target support and very little
    // rival support over its actual connected rows, not a bounding box.
    let mut cells = 0usize;
    let mut strong = 0usize;
    let mut contested = 0usize;
    for (y, run) in path {
        for x in run.left..run.right {
            let k = y * spec.width as usize + x;
            cells += 1;
            strong += usize::from(own[k] >= 128);
            contested += usize::from(rival[k] >= 128);
        }
    }
    if cells == 0 || strong * 10 < cells * 9 || contested * 20 > cells {
        return None;
    }
    // Keep one lateral cell and two lower cells of sampling margin. No
    // off-image extension is required for visible square coverage.
    let b = Rect {
        left: (b.left - cell_x).max(0.0),
        top: 0.0,
        right: (b.right + cell_x).min(tr.source_width as f32),
        bottom: (b.bottom + 2.0 * cell_y).min(tr.source_height as f32),
    };
    let half = ((b.right - b.left).max(b.bottom - b.top) * 0.5).next_up();
    let square = Ellipse {
        center_x: (b.left + b.right) * 0.5,
        center_y: (b.top + b.bottom) * 0.5,
        radius_x: half,
        radius_y: half,
    };
    (b.valid() && valid_seed(square)).then_some(HeadSticker {
        bounds: b,
        square,
        topology: 1,
        rows: cap.rows,
    })
}

/// Locator only, drawn from the last genuinely observed separate pair.
/// Neither historical geometry nor an earlier rescue is current coverage.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct MergedEdgeAnchor {
    pub cap: Rect,
    pub rival_body: Rect,
    pub rival_face: Ellipse,
    pub cap_age_us: i64,
    pub rival_age_us: i64,
}

fn rect_center(b: Rect) -> (f32, f32) {
    ((b.left + b.right) * 0.5, (b.top + b.bottom) * 0.5)
}

/// Bounded crop plan for a single merged current body mask. The two target
/// views preserve the neighbor-side safety margin and vary scale and far
/// margin. The third view is a current
/// rival witness, not a source of target geometry.
pub fn merged_edge_views(spec: HeadMaskSpec, a: MergedEdgeAnchor) -> Option<[Rect; 3]> {
    let t = spec.transform;
    if !t.valid()
        || !spec.person.valid()
        || !a.cap.valid()
        || !a.rival_body.valid()
        || !valid_seed(a.rival_face)
        || ![a.cap_age_us, a.rival_age_us]
            .into_iter()
            .all(|v| (1..=150_000).contains(&v))
        || spec.width != t.proto_size
        || spec.height != t.proto_size
        || t.proto_size < 2
    {
        return None;
    }
    let cell = t.input_size as f32 / (t.proto_size as f32 * t.scale);
    let (x, y) = rect_center(a.cap);
    let r = ((a.cap.right - a.cap.left).max(a.cap.bottom - a.cap.top)) * 0.5;
    if !cell.is_finite()
        || cell <= 0.0
        || r < 3.0 * cell
        || a.cap.top.abs() > cell
        || spec.person.top > cell
        || spec.person.top < -cell
        || a.rival_face.center_x < a.rival_body.left
        || a.rival_face.center_x > a.rival_body.right
        || a.rival_face.center_y < a.rival_body.top
        || a.rival_face.center_y
            > a.rival_body.top + 0.60 * (a.rival_body.bottom - a.rival_body.top)
        || (a.rival_face.center_x - x).abs() < r + a.rival_face.radius_x * 0.5
        || (a.rival_face.center_x - x).abs() > 4.0 * r
        || (a.rival_face.center_y - y).abs() > 2.0 * r
        || x < spec.person.left
        || x > spec.person.right
        || a.rival_face.center_x < spec.person.left
        || a.rival_face.center_x > spec.person.right
        || y > spec.person.bottom
        || a.rival_face.center_y > spec.person.bottom
    {
        return None;
    }
    let clip = |l: f32, top: f32, right: f32, bottom: f32| {
        let b = Rect {
            left: l.floor().clamp(0.0, t.source_width as f32),
            top: top.floor().clamp(0.0, t.source_height as f32),
            right: right.ceil().clamp(0.0, t.source_width as f32),
            bottom: bottom.ceil().clamp(0.0, t.source_height as f32),
        };
        (b.valid()
            && b.right - b.left >= 4.0 * r.min(16.0)
            && b.bottom - b.top >= 4.0 * r.min(16.0))
        .then_some(b)
    };
    let left_rival = a.rival_face.center_x < x;
    let target = |near: f32, far: f32, depth: f32| {
        let (l, b) = if left_rival {
            (x - near * r, x + far * r)
        } else {
            (x - far * r, x + near * r)
        };
        clip(l, 0.0, b, depth * r)
    };
    let w = a.rival_body.right - a.rival_body.left;
    let h = a.rival_body.bottom - a.rival_body.top;
    let (l, b) = if left_rival {
        (a.rival_body.left - 0.3 * w, a.rival_body.right + 0.06 * w)
    } else {
        (a.rival_body.left - 0.06 * w, a.rival_body.right + 0.3 * w)
    };
    let views = [
        target(1.5, 2.8, 6.5)?,
        target(1.5, 3.2, 7.5)?,
        clip(
            l,
            a.rival_body.top - 0.12 * h,
            b,
            a.rival_body.top + 0.72 * h,
        )?,
    ];
    (views[0] != views[1]).then_some(views)
}

/// Current-only two-view head cap agreement with a fresh rival detector.
/// Packed scene-grid masks: original merged, two separately inferred target
/// crops, one separately inferred rival crop. No identity/clock update.
pub fn merged_edge_finish(
    masks: &[u8],
    spec: HeadMaskSpec,
    a: MergedEdgeAnchor,
    confidence: [f32; 3],
    rival: Ellipse,
) -> Option<HeadSticker> {
    use crate::face_observations::mask_support;
    let views = merged_edge_views(spec, a)?;
    let n = (spec.width as usize).checked_mul(spec.height as usize)?;
    if n.checked_mul(4)? != masks.len()
        || !confidence
            .into_iter()
            .all(|c| c.is_finite() && (0.5..=1.0).contains(&c))
        || !valid_seed(rival)
    {
        return None;
    }
    let merged = &masks[..n];
    let rival_mask = &masks[3 * n..];
    let t = spec.transform;
    let cell = t.input_size as f32 / (t.proto_size as f32 * t.scale);
    // A crop-reprojected plane may not contain current support outside its
    // actual source view. This rejects substituted full-frame masks.
    for (j, v) in views.iter().enumerate() {
        let m = &masks[(j + 1) * n..(j + 2) * n];
        if !spec.valid(m)
            || m.iter().enumerate().any(|(i, &value)| {
                if value < 96 {
                    return false;
                }
                let sx =
                    (i % spec.width as usize) as f32 * cell + cell * 0.5 - t.pad_left / t.scale;
                let sy = (i / spec.width as usize) as f32 * cell + cell * 0.5 - t.pad_top / t.scale;
                sx < v.left - cell * 0.01
                    || sx >= v.right + cell * 0.01
                    || sy < v.top - cell * 0.01
                    || sy >= v.bottom + cell * 0.01
            })
        {
            return None;
        }
    }
    let (cx, cy) = rect_center(a.cap);
    let r = ((a.cap.right - a.cap.left).max(a.cap.bottom - a.cap.top)) * 0.5;
    if ((rival.center_x - a.rival_face.center_x) / a.rival_face.radius_x)
        .hypot((rival.center_y - a.rival_face.center_y) / a.rival_face.radius_y)
        > 0.65
        || !(0.65..=1.55).contains(&(rival.radius_x / a.rival_face.radius_x))
        || !(0.65..=1.55).contains(&(rival.radius_y / a.rival_face.radius_y))
        || ![merged, rival_mask]
            .into_iter()
            .all(|m| mask_support(m, spec, rival).is_some_and(|s| s[0] >= 4 && s[1] >= 12))
        || rival.center_x - rival.radius_x * 0.5 <= views[2].left
        || rival.center_x + rival.radius_x * 0.5 >= views[2].right
    {
        return None;
    }
    let edge_row = (t.pad_top / t.scale / cell).floor();
    if !edge_row.is_finite() || edge_row < 0.0 || edge_row >= spec.height as f32 {
        return None;
    }
    let seed = Ellipse {
        center_x: cx,
        center_y: cy,
        radius_x: r,
        radius_y: r,
    };
    let mut caps = Vec::new();
    for j in 0..2 {
        let m = &masks[(j + 1) * n..(j + 2) * n];
        if !spec.valid(m) {
            return None;
        }
        let (cap, path) = cap_trace(m, spec, seed, false, false, false)?;
        let b = cap.bounds;
        let v = views[j];
        let (x, y) = rect_center(b);
        if cap.partial
            || cap.rows < 6
            || !path.iter().any(|(y, _)| *y == edge_row as usize)
            || b.top > cell
            || b.left < v.left + cell
            || b.right > v.right - cell
            || b.bottom > v.bottom - cell
            || (x - cx).hypot(y - cy) > r * 0.7
            || !(0.5..=1.5).contains(&((b.bottom - b.top) / (a.cap.bottom - a.cap.top)))
        {
            return None;
        }
        let mut cells = 0;
        let mut supported = 0;
        let mut rival_core = 0;
        for (y, run) in path {
            for x in run.left..run.right {
                let i = y * spec.width as usize + x;
                cells += 1;
                supported += usize::from(m[i] >= 128 && merged[i] >= 96);
                let sx = (x as f32 + 0.5) * cell - t.pad_left / t.scale;
                let sy = (y as f32 + 0.5) * cell - t.pad_top / t.scale;
                rival_core += usize::from(
                    ((sx - rival.center_x) / (rival.radius_x * 0.70))
                        .hypot((sy - rival.center_y) / (rival.radius_y * 0.70))
                        <= 1.0,
                );
            }
        }
        if cells == 0 || supported * 10 < cells * 9 || rival_core > 0 {
            return None;
        }
        caps.push(cap);
    }
    let (a, b) = (caps[0].bounds, caps[1].bounds);
    if crate::geometry::overlap_iou(a, b) < 0.75 {
        return None;
    }
    let b = Rect {
        left: (a.left.min(b.left) - cell).max(0.0),
        top: 0.0,
        right: (a.right.max(b.right) + cell).min(t.source_width as f32),
        bottom: (a.bottom.max(b.bottom) + 2.0 * cell).min(t.source_height as f32),
    };
    let (x, y) = rect_center(b);
    let half = ((b.right - b.left).max(b.bottom - b.top) * 0.5).next_up();
    let square = Ellipse {
        center_x: x,
        center_y: y,
        radius_x: half,
        radius_y: half,
    };
    // The new visible square must not include the fresh rival face center.
    if !b.valid()
        || !valid_seed(square)
        || (x - rival.center_x).abs() <= half && (y - rival.center_y).abs() <= half
    {
        return None;
    }
    Some(HeadSticker {
        bounds: b,
        square,
        topology: 1,
        rows: caps[0].rows.min(caps[1].rows),
    })
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
    fn edge_pair() -> (HeadMaskSpec, Vec<u8>) {
        let mut sp = spec();
        sp.person = Rect {
            left: 80.0,
            top: 0.0,
            right: 176.0,
            bottom: 240.0,
        };
        let mut m = vec![0; 2 * 64 * 64];
        paint(&mut m[..4096], 0..12, 26, 38);
        paint(&mut m[..4096], 12..20, 20, 44);
        paint(&mut m[..4096], 20..60, 23, 41);
        paint(&mut m[4096..], 6..54, 48, 60);
        (sp, m)
    }
    fn ear_cap_fixture() -> (HeadMaskSpec, Vec<u8>, [f32; 12]) {
        let mut sp = spec();
        sp.person = Rect {
            left: 80.,
            top: 40.,
            right: 176.,
            bottom: 240.,
        };
        let mut m = vec![0; 8192];
        paint(&mut m[..4096], 10..11, 30, 34);
        paint(&mut m[..4096], 11..12, 28, 36);
        paint(&mut m[..4096], 12..21, 26, 38);
        paint(&mut m[..4096], 21..26, 20, 44);
        paint(&mut m[..4096], 26..60, 23, 41);
        paint(&mut m[4096..], 10..54, 48, 60);
        (
            sp,
            m,
            [
                110., 64., 0.9, 146., 64., 0.9, 92., 94., 0.9, 164., 94., 0.9,
            ],
        )
    }
    #[test]
    fn required_head_square_shifts_only_in_slack_and_reports_unavoidable_overlap() {
        let target = Rect {
            left: 100.,
            top: 40.,
            right: 150.,
            bottom: 120.,
        };
        let rival = Rect {
            left: 150.,
            top: 80.,
            right: 180.,
            bottom: 130.,
        };
        let (best, floor, centered, remaining) = minimize_head_square(target, &[rival]).unwrap();
        assert_eq!(floor, 0.);
        assert!(remaining < centered && remaining < 0.10);
        assert!((best.center_x - 110.).abs() < 1e-4);
        assert_eq!(best.center_y, 80.);
        assert!(
            best.center_x - best.radius_x <= target.left
                && best.center_x + best.radius_x >= target.right
        );
        let reflect = |b: Rect| Rect {
            left: 256. - b.right,
            right: 256. - b.left,
            ..b
        };
        let (other, _, _, after) =
            minimize_head_square(reflect(target), &[reflect(rival)]).unwrap();
        assert!((256. - best.center_x - other.center_x).abs() < 1e-4);
        assert!((remaining - after).abs() < 1e-5);
        let inside = Rect {
            left: 110.,
            top: 70.,
            right: 130.,
            bottom: 90.,
        };
        let (_, floor, _, after) = minimize_head_square(target, &[inside]).unwrap();
        assert_eq!(floor, 1.);
        assert_eq!(after, 1.);
        let opposite = Rect {
            left: 70.,
            top: 80.,
            right: 100.,
            bottom: 130.,
        };
        let (balanced, _, _, _) = minimize_head_square(target, &[rival, opposite]).unwrap();
        assert!((balanced.center_x - 125.).abs() < 1e-4);
        assert_eq!(minimize_head_square(target, &[]).unwrap().0.center_x, 125.);
    }

    #[test]
    fn local_rival_needs_independent_current_head_support_and_no_scene_owner() {
        let (mut sp, _, _) = ear_cap_fixture();
        sp.person = Rect {
            left: 64.,
            top: 20.,
            right: 160.,
            bottom: 220.,
        };
        let views = competition_views(sp).unwrap();
        let b = Rect {
            left: 140.,
            top: 64.,
            right: 168.,
            bottom: 96.,
        };
        assert!(
            views.iter().all(|v| v.left < b.left
                && v.right > b.right
                && v.top < b.top
                && v.bottom > b.bottom)
        );
        let cap = HeadSticker {
            bounds: b,
            square: Ellipse {
                center_x: 154.,
                center_y: 80.,
                radius_x: 16.,
                radius_y: 16.,
            },
            topology: 1,
            rows: 8,
        };
        let mut first = vec![0; 4096];
        paint(&mut first, 16..24, 35, 42);
        paint(&mut first, 24..31, 34, 43);
        let mut second = vec![0; 8192];
        second[..4096].copy_from_slice(&first);
        paint(&mut second[4096..], 10..20, 20, 28);
        let mut scene = vec![0; 8192];
        paint(&mut scene[..4096], 10..36, 16, 30);
        assert_eq!(
            competition_local_rival(&scene, 2, 0, sp, cap, &first, (&second, &[0.8, 0.8])),
            Some(b)
        );
        let mut duplicate = second.clone();
        duplicate[4096..].copy_from_slice(&first);
        assert!(
            competition_local_rival(&scene, 2, 0, sp, cap, &first, (&duplicate, &[0.8, 0.8]))
                .is_none()
        );
        let mut partial = cap;
        partial.topology = 0;
        assert!(
            competition_local_rival(&scene, 2, 0, sp, partial, &first, (&second, &[0.8, 0.8]))
                .is_none()
        );
        let mut missing = second.clone();
        paint(&mut missing[..4096], 16..24, 35, 42);
        missing[..4096].fill(0);
        assert!(
            competition_local_rival(&scene, 2, 0, sp, cap, &first, (&missing, &[0.8, 0.8]))
                .is_none()
        );
        scene[4096..].copy_from_slice(&first);
        assert!(
            competition_local_rival(&scene, 2, 0, sp, cap, &first, (&second, &[0.8, 0.8]))
                .is_none()
        );
    }

    #[test]
    fn pose_face_views_and_finish_require_current_dual_owned_faces() {
        let (sp, mut m, _) = ear_cap_fixture();
        m.extend(vec![0; 4096]);
        let p = [
            128., 62., 0.9, 120., 58., 0.9, 135., 58., 0.9, 110., 64., 0.9, 146., 64., 0.9, 92.,
            94., 0.9, 164., 94., 0.9,
        ];
        let views = current_pose_face_views(&m, 0, 3, sp, [p, p]).expect("two current views");
        assert_ne!(views[0], views[1]);
        let fs = [Ellipse::default(); 3];
        let f = Ellipse {
            center_x: 128.,
            center_y: 64.,
            radius_x: 17.,
            radius_y: 19.,
        };
        let result =
            current_pose_face_finish(&m, 0, 3, sp, [f, f], &fs).expect("owned current faces");
        assert!(result.bounds.left <= 111. && result.bounds.right >= 145.);
        let mut bad = p;
        bad[9] += 24.;
        bad[12] += 24.;
        assert!(current_pose_face_views(&m, 0, 3, sp, [p, bad]).is_none());
        assert!(current_pose_face_finish(&m, 0, 3, sp, [f, Ellipse::default()], &fs).is_none());
        let mut other = f;
        other.center_x += 20.;
        assert!(current_pose_face_finish(&m, 0, 3, sp, [f, other], &fs).is_none());
        let mut rival = m.clone();
        paint(&mut rival[8192..], 13..19, 29, 35);
        assert!(current_pose_face_finish(&rival, 0, 3, sp, [f, f], &fs).is_none());
        let mut foreign = fs;
        foreign[2] = f;
        assert!(current_pose_face_finish(&m, 0, 3, sp, [f, f], &foreign).is_none());
    }
    #[test]
    fn profile_axis_cap_requires_current_dual_pose_and_exclusive_crown() {
        let (sp, mut m, _) = ear_cap_fixture();
        m.extend(vec![0; 4096]);
        let p = [
            111., 64., 0.9, 116., 60., 0.9, 116., 60., 0.9, 126., 64., 0.9, 130., 64., 0.9, 92.,
            94., 0.9, 164., 94., 0.9,
        ];
        let fs = [Ellipse::default(); 3];
        let cap = profile_head_cap(&m, 0, 3, sp, [p, p], &fs).expect("profile cap");
        assert!(cap.bounds.top <= 40. && cap.bounds.bottom >= 80.);
        let mut bad = p;
        bad[9] += 30.;
        bad[12] += 30.;
        assert!(profile_head_cap(&m, 0, 3, sp, [p, bad], &fs).is_none());
        let mut rival = m.clone();
        paint(&mut rival[8192..], 13..18, 29, 35);
        assert!(profile_head_cap(&rival, 0, 3, sp, [p, p], &fs).is_none());
        let mut rival_face = fs;
        rival_face[2] = Ellipse {
            center_x: 128.,
            center_y: 64.,
            radius_x: 14.,
            radius_y: 14.,
        };
        assert!(profile_head_cap(&m, 0, 3, sp, [p, p], &rival_face).is_none());
    }
    #[test]
    fn ear_level_cap_recovers_tapered_crown_and_checks_current_competition() {
        let (sp, m, p) = ear_cap_fixture();
        let faces = [Ellipse::default(); 2];
        let cap = tracklet_ear_cap(&m, 0, 2, sp, p, &faces).expect("complete taper");
        assert!(
            cap.bounds.top <= 40.
                && cap.bounds.bottom >= 84.
                && cap.bounds.left <= 104.
                && cap.bounds.right >= 152.
        );
        let mut weak = p;
        weak[2] = 0.3;
        assert!(tracklet_ear_cap(&m, 0, 2, sp, weak, &faces).is_none());
        assert_eq!(
            tracklet_flip_ear_cap(&m, 0, 2, sp, [weak, p], &faces),
            Some(cap)
        );
        weak[0] += 28.;
        assert!(tracklet_flip_ear_cap(&m, 0, 2, sp, [weak, p], &faces).is_none());
        let mut sleeve = m.clone();
        paint(&mut sleeve[..4096], 15..19, 20, 38);
        assert!(tracklet_ear_cap(&sleeve, 0, 2, sp, p, &faces).is_none());
        let mut rival = m.clone();
        paint(&mut rival[4096..], 13..18, 29, 35);
        assert!(tracklet_ear_cap(&rival, 0, 2, sp, p, &faces).is_none());
    }
    #[test]
    fn detail_cap_requires_unique_local_owner_two_agreeing_complete_caps() {
        let (sp, m, p) = ear_cap_fixture();
        let faces = [Ellipse::default(); 2];
        assert_eq!(
            tracklet_detail_owner(&m, &m, 2, 2, 0, 4096, &[0.9, 0.9]),
            Some(0)
        );
        let mut duplicate = m.clone();
        duplicate[4096..].copy_from_slice(&m[..4096]);
        assert!(tracklet_detail_owner(&m, &duplicate, 2, 2, 0, 4096, &[0.9, 0.9]).is_none());
        assert!(tracklet_detail_owner(&m, &m, 2, 2, 0, 4096, &[0.4, 0.9]).is_none());
        let cap = tracklet_ear_cap(&m, 0, 2, sp, p, &faces).unwrap();
        let result =
            tracklet_detail_finish(&m, 0, 2, sp, [cap, cap], &faces).expect("same current caps");
        assert_eq!(result, cap);
        let mut wrong = cap;
        wrong.bounds.left += 45.;
        wrong.bounds.right += 45.;
        assert!(tracklet_detail_finish(&m, 0, 2, sp, [cap, wrong], &faces).is_none());
        let mut partial = cap;
        partial.rows = 3;
        assert!(tracklet_detail_finish(&m, 0, 2, sp, [cap, partial], &faces).is_none());
        let mut rival = faces;
        rival[1] = Ellipse {
            center_x: 128.,
            center_y: 62.,
            radius_x: 14.,
            radius_y: 16.,
        };
        assert!(tracklet_detail_finish(&m, 0, 2, sp, [cap, cap], &rival).is_none());
    }
    #[test]
    fn tracklet_current_head_requires_exclusive_cap_and_current_shoulders() {
        let (sp, m) = edge_pair();
        let shoulders = [92., 56., 0.9, 164., 56., 0.9];
        let faces = [Ellipse::default(); 2];
        let r = tracklet_current_head(&m, 0, 2, sp, shoulders, &faces)
            .expect("complete cap with shoulders");
        assert_eq!(r.topology, 1);
        assert!(r.bounds.left <= 104. && r.bounds.right >= 152. && r.bounds.bottom >= 48.);
        let mut weak = shoulders;
        weak[2] = 0.1;
        assert!(tracklet_current_head(&m, 0, 2, sp, weak, &faces).is_none());
        let mut conflict = m.clone();
        paint(&mut conflict[4096..], 0..12, 26, 38);
        assert!(tracklet_current_head(&conflict, 0, 2, sp, shoulders, &faces).is_none());
        let mut rival = faces;
        rival[1] = Ellipse {
            center_x: 128.,
            center_y: 28.,
            radius_x: 14.,
            radius_y: 16.,
        };
        assert!(tracklet_current_head(&m, 0, 2, sp, shoulders, &rival).is_none());
        let mut direct = faces;
        direct[0] = Ellipse {
            center_x: 128.,
            center_y: 30.,
            radius_x: 18.,
            radius_y: 26.,
        };
        let r = tracklet_current_head(&m, 0, 2, sp, shoulders, &direct)
            .expect("direct face agrees with cap");
        assert!(r.bounds.bottom >= 56. && r.bounds.top <= 4.);
        direct[0].center_x = 220.;
        assert!(tracklet_current_head(&m, 0, 2, sp, shoulders, &direct).is_none());
    }
    #[test]
    fn visible_pair_cap_preserves_current_rows_without_offscreen_weak_prior() {
        let (sp, m) = edge_pair();
        let cap = pair_edge_cap(&m, 0, sp).expect("complete connected top-edge head");
        assert_eq!(cap.bounds.top, 0.0);
        assert!(cap.bounds.left <= 104.0 && cap.bounds.right >= 152.0 && cap.bounds.bottom >= 48.0);
        assert!(
            cap.square.center_x - cap.square.radius_x <= cap.bounds.left
                && cap.square.center_x + cap.square.radius_x >= cap.bounds.right
        );
        assert!(
            cap.square.center_y - cap.square.radius_y <= 0.0
                && cap.square.center_y + cap.square.radius_y >= cap.bounds.bottom
        );
        assert!(cap.square.radius_x <= 40.0);
        let swapped = [m[4096..].to_vec(), m[..4096].to_vec()].concat();
        assert_eq!(pair_edge_cap(&swapped, 1, sp), Some(cap));
    }
    #[test]
    fn edge_cap_abstains_on_competing_or_missing_masks_and_no_visible_edge() {
        let (sp, m) = edge_pair();
        let mut rival = m.clone();
        paint(&mut rival[4096..], 0..12, 26, 38);
        assert!(pair_edge_cap(&rival, 0, sp).is_none());
        let mut missing = m.clone();
        missing[4096..].fill(0);
        assert!(pair_edge_cap(&missing, 0, sp).is_none());
        assert!(pair_edge_cap(&m[..4096], 0, sp).is_none());
        assert!(pair_edge_cap(&m, 2, sp).is_none());
        let mut off = m.clone();
        off[..128].fill(0);
        assert!(pair_edge_cap(&off, 0, sp).is_none());
        let mut not_edge = sp;
        not_edge.person.top = 20.0;
        assert!(pair_edge_cap(&m, 0, not_edge).is_none());
    }
    #[test]
    fn edge_face_ownership_rejects_rival_or_torso_without_affecting_nonedge() {
        let (sp, m) = edge_pair();
        let view = Rect {
            left: 0.0,
            top: 0.0,
            right: 256.0,
            bottom: 256.0,
        };
        let face = crate::face_localization::Candidate {
            bounds: Rect {
                left: 112.0,
                top: 12.0,
                right: 144.0,
                bottom: 40.0,
            },
            confidence: 0.9,
            points: Vec::new(),
        };
        use crate::face_observations::pair_face_owned;
        assert_eq!(pair_face_owned(&m, 0, sp, &face, view, 256.0), Some(true));
        let mut rival = m.clone();
        paint(&mut rival[4096..], 0..12, 26, 38);
        assert_eq!(
            pair_face_owned(&rival, 0, sp, &face, view, 256.0),
            Some(false)
        );
        let torso = crate::face_localization::Candidate {
            bounds: Rect {
                left: 112.0,
                top: 78.0,
                right: 144.0,
                bottom: 106.0,
            },
            ..face.clone()
        };
        assert_eq!(pair_face_owned(&m, 0, sp, &torso, view, 256.0), Some(false));
        let mut nonedge = sp;
        nonedge.person.top = 10.0;
        assert_eq!(
            pair_face_owned(&rival, 0, nonedge, &face, view, 256.0),
            Some(true)
        );
    }
    fn merged_edge_fixture() -> (HeadMaskSpec, MergedEdgeAnchor, Vec<u8>, Ellipse) {
        let mut sp = spec();
        sp.person = Rect {
            left: 20.0,
            top: 0.0,
            right: 236.0,
            bottom: 240.0,
        };
        let a = MergedEdgeAnchor {
            cap: Rect {
                left: 96.0,
                top: 0.0,
                right: 160.0,
                bottom: 64.0,
            },
            rival_body: Rect {
                left: 28.0,
                top: 16.0,
                right: 96.0,
                bottom: 200.0,
            },
            rival_face: Ellipse {
                center_x: 60.0,
                center_y: 44.0,
                radius_x: 16.0,
                radius_y: 20.0,
            },
            cap_age_us: 80_000,
            rival_age_us: 40_000,
        };
        let views = merged_edge_views(sp, a).unwrap();
        let mut target = vec![0; 4096];
        paint(&mut target, 0..12, 26, 38);
        paint(&mut target, 12..20, 20, 44);
        let mut rival = vec![0; 4096];
        paint(&mut rival, 7..19, 11, 19);
        let mut packed = vec![0; 4 * 4096];
        for (i, v) in packed[..4096].iter_mut().enumerate() {
            *v = target[i].max(rival[i]);
        }
        for (j, v) in views.iter().enumerate() {
            let m = if j == 2 { &rival } else { &target };
            for (i, &x) in m.iter().enumerate() {
                let sx = (i % 64) as f32 * 4.0 + 2.0;
                let sy = (i / 64) as f32 * 4.0 + 2.0;
                if sx >= v.left && sx < v.right && sy >= v.top && sy < v.bottom {
                    packed[(j + 1) * 4096 + i] = x;
                }
            }
        }
        (sp, a, packed, a.rival_face)
    }
    #[test]
    fn merged_edge_consensus_uses_current_caps_not_history_and_abstains_without_evidence() {
        let (sp, a, m, rival) = merged_edge_fixture();
        let views = merged_edge_views(sp, a).expect("bounded current crops");
        assert_ne!(views[0], views[1]);
        let result = merged_edge_finish(&m, sp, a, [0.9; 3], rival)
            .expect("two current consistent cap crops");
        assert_eq!(result.bounds.top, 0.0);
        assert!(
            result.bounds.left <= 104.0
                && result.bounds.right >= 152.0
                && result.bounds.bottom >= 48.0
        );
        assert!((result.square.center_x - rival.center_x).abs() > result.square.radius_x);
        let mut bad = m.clone();
        bad[4096..8192].fill(0);
        assert!(merged_edge_finish(&bad, sp, a, [0.9; 3], rival).is_none());
        bad = m.clone();
        bad[..4096].fill(0);
        assert!(merged_edge_finish(&bad, sp, a, [0.9; 3], rival).is_none());
        bad = m.clone();
        bad[3 * 4096..].fill(0);
        assert!(merged_edge_finish(&bad, sp, a, [0.9; 3], rival).is_none());
        bad = m.clone();
        bad[4096 + 2] = 255;
        assert!(merged_edge_finish(&bad, sp, a, [0.9; 3], rival).is_none());
        assert!(merged_edge_finish(&m, sp, a, [0.9, 0.49, 0.9], rival).is_none());
        let mut a_expired = a;
        a_expired.cap_age_us = 150_001;
        assert!(merged_edge_views(sp, a_expired).is_none());
        assert!(merged_edge_finish(&m, sp, a_expired, [0.9; 3], rival).is_none());
        let mut rival_moved = rival;
        rival_moved.center_x += 24.0;
        assert!(merged_edge_finish(&m, sp, a, [0.9; 3], rival_moved).is_none());
    }
    #[test]
    fn merged_edge_views_and_current_result_are_horizontally_symmetric() {
        let (sp, a, m, rival) = merged_edge_fixture();
        let flip = |r: Rect| Rect {
            left: 256.0 - r.right,
            right: 256.0 - r.left,
            ..r
        };
        let flipped = |e: Ellipse| Ellipse {
            center_x: 256.0 - e.center_x,
            ..e
        };
        let mut b = a;
        b.cap = flip(a.cap);
        b.rival_body = flip(a.rival_body);
        b.rival_face = flipped(a.rival_face);
        let mut sp2 = sp;
        sp2.person = flip(sp.person);
        let expected = merged_edge_views(sp, a).unwrap().map(flip);
        assert_eq!(merged_edge_views(sp2, b).unwrap(), expected);
        let mut mm = m.clone();
        for row in mm.chunks_exact_mut(64) {
            row.reverse();
        }
        let x = merged_edge_finish(&m, sp, a, [0.9; 3], rival).unwrap();
        let y = merged_edge_finish(&mm, sp2, b, [0.9; 3], flipped(rival)).unwrap();
        assert_eq!(flip(x.bounds), y.bounds);
        assert_eq!(flipped(x.square), y.square);
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
