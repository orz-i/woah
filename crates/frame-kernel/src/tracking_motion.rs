//! Ordered consensus motion estimation for tracking observations.
use crate::{
    geometry::Rect,
    tracking::{Detection, TrackingConfig},
};
use std::collections::BTreeSet;

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct SceneMotion {
    pub dx: f32,
    pub dy: f32,
    pub inliers: usize,
    pub confidence: f32,
}
#[derive(Clone, Copy)]
struct Pair {
    id: i64,
    detection: usize,
    dx: f32,
    dy: f32,
}

fn median(mut values: Vec<f32>) -> f32 {
    values.sort_by(f32::total_cmp);
    let mid = values.len() / 2;
    if values.len() % 2 == 1 {
        values[mid]
    } else {
        (values[mid - 1] + values[mid]) / 2.0
    }
}

pub fn estimate(
    tracks: &[(i64, Rect)],
    detections: &[Detection],
    config: &TrackingConfig,
) -> Option<SceneMotion> {
    if config.min_scene_motion_inliers == 0
        || !config.scene_motion_tolerance.is_finite()
        || config.scene_motion_tolerance < 0.0
        || !config.max_global_shift.is_finite()
        || config.max_global_shift < 0.0
        || tracks.iter().any(|(_, b)| !b.valid())
        || detections.iter().any(|d| !d.valid())
    {
        return None;
    }
    let zero = SceneMotion::default();
    if tracks.len() < config.min_scene_motion_inliers
        || detections.len() < config.min_scene_motion_inliers
    {
        return Some(zero);
    }
    let mut pairs = Vec::new();
    for &(id, p) in tracks {
        let pw = (p.right - p.left).max(1.0);
        let ph = (p.bottom - p.top).max(1.0);
        let pa = pw / ph;
        for (index, detection) in detections.iter().enumerate() {
            let d = detection.bounds;
            let dw = (d.right - d.left).max(1.0);
            let dh = (d.bottom - d.top).max(1.0);
            if ph.min(dh) / ph.max(dh) < 0.5 || (pa - dw / dh).abs() > 0.4 {
                continue;
            }
            // Preserve native Float32 center and residual operation order.
            let dx = (d.left + (d.right - d.left).max(0.0) / 2.0)
                - (p.left + (p.right - p.left).max(0.0) / 2.0);
            let dy = (d.top + (d.bottom - d.top).max(0.0) / 2.0)
                - (p.top + (p.bottom - p.top).max(0.0) / 2.0);
            if dx.abs() <= config.max_global_shift && dy.abs() <= config.max_global_shift {
                pairs.push(Pair {
                    id,
                    detection: index,
                    dx,
                    dy,
                });
            }
        }
    }
    let mut best = Vec::new();
    let mut best_count = 0;
    for hypothesis in &pairs {
        let mut seen_tracks = BTreeSet::new();
        let mut seen_detections = BTreeSet::new();
        let mut inliers = Vec::new();
        for p in &pairs {
            if seen_tracks.contains(&p.id) || seen_detections.contains(&p.detection) {
                continue;
            }
            if hypothesis.dx.abs() > 10.0 && p.dx * hypothesis.dx < 0.0 {
                continue;
            }
            if hypothesis.dy.abs() > 10.0 && p.dy * hypothesis.dy < 0.0 {
                continue;
            }
            let dx = p.dx - hypothesis.dx;
            let dy = p.dy - hypothesis.dy;
            if (dx * dx + dy * dy).sqrt() <= config.scene_motion_tolerance {
                inliers.push(*p);
                seen_tracks.insert(p.id);
                seen_detections.insert(p.detection);
            }
        }
        if seen_tracks.len() > best_count {
            best_count = seen_tracks.len();
            best = inliers;
        }
    }
    if best_count < config.min_scene_motion_inliers {
        return Some(zero);
    }
    let dx = median(best.iter().map(|p| p.dx).collect());
    let dy = median(best.iter().map(|p| p.dy).collect());
    if dx.abs() < 5.0 && dy.abs() < 5.0 {
        return Some(zero);
    }
    Some(SceneMotion {
        dx,
        dy,
        inliers: best_count,
        confidence: best_count as f32 / tracks.len() as f32,
    })
}

// Short-lived body texture correspondences. Retain only the immediately
// preceding reduced RGB view, never a face embedding or persistent image.
#[derive(Clone)]
pub(crate) struct BodyPixelFrame {
    pub at: i64,
    pub detections: Vec<Detection>,
    width: usize,
    height: usize,
    rgb: Vec<[u8; 3]>,
    owner: Vec<u8>,
    strong: Vec<bool>,
    transform: crate::geometry::Transform,
}
impl BodyPixelFrame {
    pub(crate) fn new(
        at: i64,
        d: &[Detection],
        rgb: &[u8],
        tr: crate::geometry::Transform,
    ) -> Option<Self> {
        let sw = tr.source_width as usize;
        let sh = tr.source_height as usize;
        if !tr.valid() || d.len() > 64 || rgb.len() != sw.checked_mul(sh)?.checked_mul(3)? {
            return None;
        }
        let divisor = sw.max(sh).div_ceil(480).max(1);
        let width = sw.div_ceil(divisor);
        let height = sh.div_ceil(divisor);
        if width < 9 || height < 9 {
            return None;
        }
        let n = tr.proto_size as usize;
        let mut owner_grid = vec![0u8; n * n];
        let mut strong_grid = vec![false; n * n];
        for (j, q) in d.iter().enumerate() {
            let m = q.mask.as_ref()?;
            if !m.valid() || m.width != n || m.height != n || m.transform != tr {
                return None;
            }
            for (k, &v) in m.pixels.iter().enumerate() {
                if v >= 128 {
                    if owner_grid[k] != 0 {
                        owner_grid[k] = 255;
                        strong_grid[k] = false;
                    } else {
                        owner_grid[k] = (j + 1) as u8;
                        strong_grid[k] = v >= 160;
                    }
                }
            }
        }
        let mut pixels = Vec::with_capacity(width * height);
        let mut owner = Vec::with_capacity(width * height);
        let mut strong = Vec::with_capacity(width * height);
        for y in 0..height {
            for x in 0..width {
                let l = x * divisor;
                let top = y * divisor;
                let r = (l + divisor).min(sw);
                let b = (top + divisor).min(sh);
                let mut sums = [0u64; 3];
                let mut count = 0;
                for yy in top..b {
                    for xx in l..r {
                        let k = (yy * sw + xx) * 3;
                        for c in 0..3 {
                            sums[c] += u64::from(rgb[k + c]);
                        }
                        count += 1;
                    }
                }
                pixels.push(sums.map(|v| (v / count) as u8));
                let sx = (l + r) as f32 * 0.5;
                let sy = (top + b) as f32 * 0.5;
                let px = ((sx * tr.scale + tr.pad_left) * n as f32 / tr.input_size as f32).floor();
                let py = ((sy * tr.scale + tr.pad_top) * n as f32 / tr.input_size as f32).floor();
                if px >= 0.0 && py >= 0.0 && px < n as f32 && py < n as f32 {
                    let k = py as usize * n + px as usize;
                    owner.push(owner_grid[k]);
                    strong.push(strong_grid[k]);
                } else {
                    owner.push(0);
                    strong.push(false);
                }
            }
        }
        Some(Self {
            at,
            detections: d.to_vec(),
            width,
            height,
            rgb: pixels,
            owner,
            strong,
            transform: tr,
        })
    }
    fn points(&self, index: usize) -> Vec<(usize, usize)> {
        let Some(d) = self.detections.get(index) else {
            return vec![];
        };
        let body = d.bounds;
        let h = body.bottom - body.top;
        let sx = self.transform.source_width as f32 / self.width as f32;
        let sy = self.transform.source_height as f32 / self.height as f32;
        let mut pts = Vec::new();
        for y in (4..self.height - 4).step_by(3) {
            let source_y = (y as f32 + 0.5) * sy;
            if source_y < body.top + 0.18 * h || source_y >= body.top + 0.83 * h {
                continue;
            }
            for x in (4..self.width - 4).step_by(3) {
                let source_x = (x as f32 + 0.5) * sx;
                if source_x < body.left || source_x >= body.right {
                    continue;
                }
                if !(y - 3..=y + 3).all(|yy| {
                    (x - 3..=x + 3).all(|xx| {
                        let k = yy * self.width + xx;
                        self.owner[k] == (index + 1) as u8 && self.strong[k]
                    })
                }) {
                    continue;
                }
                let lum = |k: usize| self.rgb[k].iter().map(|&q| i32::from(q)).sum::<i32>();
                let (mut gx, mut gy) = (0u32, 0u32);
                for yy in y - 3..=y + 3 {
                    for xx in x - 3..=x + 3 {
                        let k = yy * self.width + xx;
                        if xx < x + 3 {
                            gx += (lum(k + 1) - lum(k)).unsigned_abs();
                        }
                        if yy < y + 3 {
                            gy += (lum(k + self.width) - lum(k)).unsigned_abs();
                        }
                    }
                }
                let texture = gx.min(gy);
                if texture >= 315 {
                    pts.push((texture, x, y));
                }
            }
        }
        pts.sort_unstable_by(|a, b| b.cmp(a));
        let mut out = Vec::new();
        for (_, x, y) in pts {
            if out.iter().all(|&(xx, yy): &(usize, usize)| {
                x.abs_diff(xx).pow(2) + y.abs_diff(yy).pow(2) >= 25
            }) {
                out.push((x, y));
            }
            if out.len() >= 64 {
                break;
            }
        }
        out
    }
    fn patch_error(&self, x: usize, y: usize, other: &Self, u: usize, v: usize) -> u32 {
        let mut e = 0u32;
        for dy in 0..7 {
            for dx in 0..7 {
                let a = self.rgb[(y + dy - 3) * self.width + x + dx - 3];
                let b = other.rgb[(v + dy - 3) * other.width + u + dx - 3];
                e += a
                    .into_iter()
                    .zip(b)
                    .map(|(p, q)| u32::from(p.abs_diff(q)))
                    .sum::<u32>();
            }
        }
        e
    }
    fn best(&self, other: &Self, x: usize, y: usize) -> Option<(usize, usize)> {
        let l = x.saturating_sub(12).max(3);
        let r = (x + 12).min(other.width - 4);
        let top = y.saturating_sub(12).max(3);
        let bottom = (y + 12).min(other.height - 4);
        let mut best = (u32::MAX, 0, 0);
        let mut errors = Vec::with_capacity((r - l + 1) * (bottom - top + 1));
        // The entire bounded search is evaluated. A spatially separate
        // second minimum is required in addition to forward/backward agreement.
        for v in top..=bottom {
            for u in l..=r {
                let e = self.patch_error(x, y, other, u, v);
                if e < best.0 {
                    best = (e, u, v);
                }
                errors.push((e, u, v));
            }
        }
        if best.0 > 22 * 147 {
            return None;
        }
        let rival = errors
            .into_iter()
            .filter(|&(_, u, v)| u.abs_diff(best.1) > 2 || v.abs_diff(best.2) > 2)
            .map(|(e, _, _)| e)
            .min()?;
        if rival < best.0 + 221 {
            return None;
        }
        Some((best.1, best.2))
    }
    pub(crate) fn votes(&self, other: &Self, index: usize) -> Option<(usize, Vec<usize>)> {
        if self.width != other.width
            || self.height != other.height
            || self.transform != other.transform
            || !(1..=100_000).contains(&other.at.checked_sub(self.at)?)
        {
            return None;
        }
        let mut matched = 0;
        let mut counts = vec![0; other.detections.len()];
        for (x, y) in self.points(index) {
            let Some((u, v)) = self.best(other, x, y) else {
                continue;
            };
            let Some((xx, yy)) = other.best(self, u, v) else {
                continue;
            };
            if xx.abs_diff(x) > 1 || yy.abs_diff(y) > 1 {
                continue;
            }
            matched += 1;
            let owner = other.owner[v * other.width + u];
            if owner > 0 && owner != 255 {
                counts[owner as usize - 1] += 1;
            }
        }
        Some((matched, counts))
    }
}
fn containment(a: &crate::tracking::Mask, b: &crate::tracking::Mask) -> f32 {
    if a.transform != b.transform || a.width != b.width || a.height != b.height {
        return 0.0;
    }
    let (mut n, mut both) = (0u32, 0u32);
    for (&x, &y) in a.pixels.iter().zip(b.pixels.iter()) {
        if x >= 128 {
            n += 1;
            both += u32::from(y >= 128);
        }
    }
    if n == 0 { 0.0 } else { both as f32 / n as f32 }
}
#[derive(Clone, Debug)]
pub(crate) struct BodyMerge {
    pub index: usize,
    pub ids: Vec<i64>,
    pub votes: Vec<usize>,
}
/// Confirm only a current raw mask with two independent prior owners'
/// exclusive current texture support. Never create a match from flow.
pub(crate) fn confirmed_body_merges(
    before: &crate::tracking::Tracker,
    old: &BodyPixelFrame,
    current: &BodyPixelFrame,
    eligible: &BTreeSet<i64>,
) -> Vec<BodyMerge> {
    if !(1..=100_000).contains(&current.at.saturating_sub(old.at))
        || old.transform != current.transform
    {
        return vec![];
    }
    let previous = before
        .tracks
        .iter()
        .filter_map(|t| {
            if !t.observed_this_frame
                || t.observed_mask_at_us != Some(old.at)
                || t.offscreen_dormant
                || t.confidence < 0.50
            {
                return None;
            }
            let m = t.observed_mask.as_ref()?;
            let mut exact = old.detections.iter().enumerate().filter(|(_, q)| {
                q.bounds == t.observed_bounds
                    && q.mask
                        .as_ref()
                        .is_some_and(|v| v.transform == m.transform && v.pixels == m.pixels)
            });
            let (j, _) = exact.next()?;
            if exact.next().is_some() {
                return None;
            }
            Some((t.id, j, m))
        })
        .collect::<Vec<_>>();
    let mut out = Vec::new();
    let mut cache = std::collections::BTreeMap::new();
    for (j, d) in current.detections.iter().enumerate() {
        if d.confidence < 0.50 {
            continue;
        }
        let Some(m) = d.mask.as_ref() else {
            continue;
        };
        let owners = previous
            .iter()
            .filter(|(_, _, p)| containment(p, m) >= 0.70)
            .collect::<Vec<_>>();
        if owners.len() < 2 || !owners.iter().any(|(id, _, _)| eligible.contains(id)) {
            continue;
        }
        let mut supported = Vec::new();
        for &&(id, k, p) in &owners {
            if !owners.iter().any(|&&(other, _, q)| {
                other != id && crate::tracking_mask::raw_mask_iou(p, q) <= 0.20
            }) {
                continue;
            }
            let Some((matched, votes)) = cache
                .entry(k)
                .or_insert_with(|| old.votes(current, k))
                .as_ref()
            else {
                continue;
            };
            let n = votes[j];
            if n >= 4 && n * 5 >= matched * 3 {
                supported.push((id, n, p));
            }
        }
        let distinct = supported.iter().enumerate().any(|(k, (id, _, a))| {
            supported[k + 1..].iter().any(|(other, _, b)| {
                (eligible.contains(id) || eligible.contains(other))
                    && crate::tracking_mask::raw_mask_iou(a, b) <= 0.20
            })
        });
        if distinct {
            out.push(BodyMerge {
                index: j,
                ids: supported.iter().map(|(id, _, _)| *id).collect(),
                votes: supported.iter().map(|(_, n, _)| *n).collect(),
            });
        }
    }
    out
}

/// Bounded offline two-anchor tracklet proof. Every frame must have a real,
/// unique, reciprocal raw instance link. No interpolation across absent
/// observations, and no identity inference from an isolated end point.
pub(crate) fn offline_window(
    frames: &[&[Detection]],
    times: &[i64],
    left: usize,
    right: usize,
) -> Option<Option<Vec<usize>>> {
    if right >= frames.last()?.len() {
        return None;
    }
    // Require independent canonical right-hand anchor for actual coverage.
    Some(offline_path(frames, times, left)?.filter(|p| p.last().copied() == Some(right)))
}

/// Anonymous raw triplets for research descriptor pooling only. This is not
/// an identity or coverage proof; the endpoints are not canonical anchors.
pub(crate) fn research_triplet_paths(
    frames: &[&[Detection]],
    times: &[i64],
) -> Option<Vec<Option<[usize; 2]>>> {
    if frames.len() != 3
        || times.len() != 3
        || frames.iter().any(|f| f.len() > 64)
        || times.windows(2).any(|w| {
            w[1].checked_sub(w[0])
                .is_none_or(|v| !(1..=100_000).contains(&v))
        })
    {
        return None;
    }
    let mut out = vec![None; frames[1].len()];
    let mut ambiguous = vec![false; out.len()];
    if frames.iter().any(|d| d.is_empty()) {
        return Some(out);
    }
    for i in 0..frames[0].len() {
        if let Some(path) = offline_path(frames, times, i)? {
            let m = path[1];
            if ambiguous[m] {
                continue;
            }
            if out[m].replace([path[0], path[2]]).is_some() {
                out[m] = None;
                ambiguous[m] = true;
            }
        }
    }
    Some(out)
}

/// A research node in a consecutive raw-mask triplet graph. Previous and
/// next are frame-local indices, or u32::MAX for no qualified triplet.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct ResearchTrackletNode {
    pub previous: u32,
    pub next: u32,
    // bit 0: pooled dual-cue vote, bit 1: unpooled dual-cue vote,
    // bit 2: conflicting owner/vote/known merge.
    pub flags: u32,
}

/// Recover only continuous runs of dual-cue votes with mutually agreeing
/// raw-mask triplets. At least three unpooled witnesses three sample steps
/// apart prevent a single overlapping triplet from authorizing the run.
/// No positive output across missing votes; no identity/coverage mutation.
pub(crate) fn research_tracklet_consensus(
    counts: &[u32],
    times: &[i64],
    nodes: &[ResearchTrackletNode],
) -> Option<Vec<u32>> {
    if counts.is_empty()
        || counts.len() > 100_000
        || times.len() != counts.len()
        || counts.iter().any(|&n| n > 64)
        || times.windows(2).any(|w| w[1] <= w[0])
        || nodes.iter().any(|n| n.flags > 7)
    {
        return None;
    }
    let mut offsets = vec![0usize];
    for &n in counts {
        offsets.push(offsets.last()?.checked_add(n as usize)?);
    }
    if *offsets.last()? != nodes.len() || nodes.len() > 1_000_000 {
        return None;
    }
    let none = u32::MAX;
    for i in 0..counts.len() {
        for n in &nodes[offsets[i]..offsets[i + 1]] {
            if (n.previous == none) != (n.next == none) {
                return None;
            }
            if n.previous != none
                && (i == 0
                    || i + 1 == counts.len()
                    || n.previous >= counts[i - 1]
                    || n.next >= counts[i + 1])
            {
                return None;
            }
        }
    }
    let mut chosen = vec![0u32; nodes.len()];
    let qualifies =
        |n: &ResearchTrackletNode| n.previous != none && n.flags & 1 != 0 && n.flags & 4 == 0;
    let mut used = vec![false; nodes.len()];
    for i in 0..counts.len() {
        for j in 0..counts[i] as usize {
            let k = offsets[i] + j;
            if used[k] || !qualifies(&nodes[k]) {
                continue;
            }
            let mut path = vec![(i, k)];
            used[k] = true;
            let (mut frame, mut index) = (i, k);
            while frame + 1 < counts.len() {
                if times[frame + 1]
                    .checked_sub(times[frame])
                    .is_none_or(|dt| dt > 100_000)
                {
                    break;
                }
                let n = nodes[index];
                if n.next == none {
                    break;
                }
                let next = offsets[frame + 1] + n.next as usize;
                let q = nodes[next];
                if used[next] || !qualifies(&q) || q.previous as usize != index - offsets[frame] {
                    break;
                }
                frame += 1;
                index = next;
                used[index] = true;
                path.push((frame, index));
            }
            let mut witnesses = 0usize;
            let mut last = None;
            for &(f, k) in &path {
                if nodes[k].flags & 2 != 0 && last.is_none_or(|previous| f - previous >= 3) {
                    witnesses += 1;
                    last = Some(f);
                }
            }
            if path.len() >= 7 && witnesses >= 3 {
                for (_, k) in path {
                    chosen[k] = 1;
                }
            }
        }
    }
    // At most one candidate for the selected root on any frame. Reject the
    // full conflicting result rather than breaking a validated span.
    if (0..counts.len()).any(|i| {
        chosen[offsets[i]..offsets[i + 1]]
            .iter()
            .filter(|&&v| v != 0)
            .count()
            > 1
    }) {
        return Some(vec![0; nodes.len()]);
    }
    Some(chosen)
}

fn offline_path(frames: &[&[Detection]], times: &[i64], left: usize) -> Option<Option<Vec<usize>>> {
    use crate::geometry::overlap_iou;
    use crate::tracking_mask::raw_mask_iou;
    if !(3..=8).contains(&frames.len())
        || times.len() != frames.len()
        || frames.iter().any(|d| {
            d.len() > 64
                || d.iter()
                    .any(|q| !q.valid() || q.mask.as_ref().is_none_or(|m| !m.valid()))
        })
        || left >= frames[0].len()
        || times.windows(2).any(|w| {
            w[1].checked_sub(w[0])
                .is_none_or(|v| !(1..=100_000).contains(&v))
        })
        || times.last()?.checked_sub(times[0])? > 250_000
    {
        return None;
    }
    if frames.iter().any(|d| d.is_empty()) {
        return Some(None);
    }
    let base = frames[0][left].mask.as_ref()?;
    if frames.iter().flat_map(|d| d.iter()).any(|q| {
        q.mask.as_ref().is_none_or(|m| {
            m.transform != base.transform || m.width != base.width || m.height != base.height
        })
    }) {
        return None;
    }
    let mut path = vec![left];
    for pair in frames.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        let k = *path.last()?;
        let am = a[k].mask.as_ref()?;
        let scores = b
            .iter()
            .map(|q| raw_mask_iou(am, q.mask.as_ref().unwrap()))
            .collect::<Vec<_>>();
        let (j, &score) = scores
            .iter()
            .enumerate()
            .max_by(|a, b| a.1.total_cmp(b.1))?;
        let bm = b[j].mask.as_ref()?;
        if score < 0.55
            || a[k].confidence < 0.50
            || b[j].confidence < 0.50
            || overlap_iou(a[k].bounds, b[j].bounds) < 0.45
            || scores
                .iter()
                .enumerate()
                .any(|(q, &v)| q != j && score - v < 0.15)
            || a.iter()
                .enumerate()
                .any(|(q, d)| q != k && score - raw_mask_iou(d.mask.as_ref().unwrap(), bm) < 0.15)
        {
            return Some(None);
        }
        // A merged/split proposal may have a high IoU with its larger owner.
        // Test both directions against all raw competitors before accepting.
        let split = |source: &crate::tracking::Mask, other: &[Detection]| {
            let included = other
                .iter()
                .filter(|q| {
                    q.confidence >= 0.45 && containment(q.mask.as_ref().unwrap(), source) >= 0.70
                })
                .collect::<Vec<_>>();
            included.iter().enumerate().any(|(i, q)| {
                included[i + 1..].iter().any(|r| {
                    raw_mask_iou(q.mask.as_ref().unwrap(), r.mask.as_ref().unwrap()) <= 0.20
                })
            })
        };
        if split(am, b) || split(bm, a) {
            return Some(None);
        }
        path.push(j);
    }
    Some(Some(path))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn bounds(x: f32) -> Rect {
        Rect {
            left: x,
            top: 50.0,
            right: x + 100.0,
            bottom: 250.0,
        }
    }
    fn detection(x: f32) -> Detection {
        Detection {
            bounds: bounds(x),
            mask: None,
            confidence: 0.9,
            foot_y: 250.0,
        }
    }
    fn pixel_detection(x: usize, y: usize, w: usize, h: usize) -> Detection {
        let tr = crate::geometry::Transform {
            scale: 1.0,
            pad_left: 0.0,
            pad_top: 0.0,
            source_width: 128,
            source_height: 128,
            input_size: 128,
            proto_size: 128,
        };
        let mut m = vec![0; 128 * 128];
        for j in y..y + h {
            m[j * 128 + x..j * 128 + x + w].fill(255);
        }
        Detection {
            bounds: Rect {
                left: x as f32,
                top: y as f32,
                right: (x + w) as f32,
                bottom: (y + h) as f32,
            },
            confidence: 0.9,
            foot_y: (y + h) as f32,
            mask: Some(crate::tracking::Mask {
                width: 128,
                height: 128,
                pixels: m.into(),
                transform: tr,
            }),
        }
    }
    #[test]
    fn research_consensus_needs_consecutive_links_and_separated_unpooled_votes() {
        let counts = [1; 9];
        let times = (0..9).map(|i| i * 40_000).collect::<Vec<_>>();
        let mut nodes = vec![
            ResearchTrackletNode {
                previous: 0,
                next: 0,
                flags: 3
            };
            9
        ];
        nodes[0].previous = u32::MAX;
        nodes[0].next = u32::MAX;
        nodes[8].previous = u32::MAX;
        nodes[8].next = u32::MAX;
        assert_eq!(
            research_tracklet_consensus(&counts, &times, &nodes),
            Some(vec![0, 1, 1, 1, 1, 1, 1, 1, 0])
        );
        let mut conflict = nodes.clone();
        conflict[4].flags |= 4;
        assert_eq!(
            research_tracklet_consensus(&counts, &times, &conflict),
            Some(vec![0; 9])
        );
        let mut pooled = nodes.clone();
        for n in &mut pooled {
            n.flags = 1;
        }
        assert_eq!(
            research_tracklet_consensus(&counts, &times, &pooled),
            Some(vec![0; 9])
        );
        let mut broken = nodes.clone();
        broken[4].previous = u32::MAX;
        broken[4].next = u32::MAX;
        assert_eq!(
            research_tracklet_consensus(&counts, &times, &broken),
            Some(vec![0; 9])
        );
        let mut bad = nodes;
        bad[2].next = 1;
        assert!(research_tracklet_consensus(&counts, &times, &bad).is_none());
    }
    #[test]
    fn offline_window_requires_both_anchors_and_every_reciprocal_current_mask() {
        let a = pixel_detection(10, 10, 35, 95);
        let b = pixel_detection(75, 10, 35, 95);
        let c = pixel_detection(12, 11, 35, 95);
        let d = pixel_detection(77, 11, 35, 95);
        let e = pixel_detection(14, 12, 35, 95);
        let f = pixel_detection(79, 12, 35, 95);
        let first = [a, b];
        let middle = [d.clone(), c.clone()];
        let last = [e, f];
        let times = [0, 40_000, 80_000];
        let frames = [first.as_slice(), middle.as_slice(), last.as_slice()];
        assert_eq!(
            offline_window(&frames, &times, 0, 0),
            Some(Some(vec![0, 1, 0]))
        );
        assert_eq!(
            research_triplet_paths(&frames, &times),
            Some(vec![Some([1, 1]), Some([0, 0])])
        );
        assert_eq!(offline_window(&frames, &times, 0, 1), Some(None));
        assert_eq!(offline_window(&frames, &[0, 40_000, 150_001], 0, 0), None);
        let duplicate = [d.clone(), c.clone(), c.clone()];
        assert_eq!(
            offline_window(&[&first, &duplicate, &last], &times, 0, 0),
            Some(None)
        );
        assert_eq!(
            offline_window(&[&first, &[], &last], &times, 0, 0),
            Some(None)
        );
        let mut merged = pixel_detection(12, 11, 100, 95);
        merged.mask.as_mut().unwrap().pixels = c
            .mask
            .as_ref()
            .unwrap()
            .pixels
            .iter()
            .zip(d.mask.as_ref().unwrap().pixels.iter())
            .map(|(&a, &b)| a.max(b))
            .collect::<Vec<_>>()
            .into();
        assert_eq!(
            offline_window(&[&first, &[merged], &last], &times, 0, 0),
            Some(None)
        );
    }
    #[test]
    fn independent_body_texture_confirms_merge_but_not_separate_or_stale_masks() {
        let a = pixel_detection(10, 10, 35, 95);
        let b = pixel_detection(75, 10, 35, 95);
        let tr = a.mask.as_ref().unwrap().transform;
        let mut seed = 17u32;
        let mut pixels = vec![0; 128 * 128 * 3];
        for v in &mut pixels {
            seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
            *v = (seed >> 24) as u8;
        }
        let mut current = vec![0; pixels.len()];
        for y in 1..128 {
            for x in 2..128 {
                let k = (y * 128 + x) * 3;
                let j = ((y - 1) * 128 + x - 2) * 3;
                current[k..k + 3].copy_from_slice(&pixels[j..j + 3]);
            }
        }
        let mut tracker = crate::tracking::Tracker::new(TrackingConfig::default());
        tracker
            .initialize(vec![a.clone(), b.clone()], &[0, 1])
            .unwrap();
        for q in &mut tracker.tracks {
            q.observed_mask_at_us = Some(0);
        }
        let old = BodyPixelFrame::new(0, &[a, b], &pixels, tr).unwrap();
        let c = pixel_detection(12, 11, 35, 95);
        let d = pixel_detection(77, 11, 35, 95);
        let mut merged = pixel_detection(12, 11, 100, 95);
        let union = c
            .mask
            .as_ref()
            .unwrap()
            .pixels
            .iter()
            .zip(d.mask.as_ref().unwrap().pixels.iter())
            .map(|(&a, &b)| a.max(b))
            .collect::<Vec<_>>();
        merged.mask.as_mut().unwrap().pixels = union.into();
        let eligible = BTreeSet::from([0]);
        let new = BodyPixelFrame::new(40_000, std::slice::from_ref(&merged), &current, tr).unwrap();
        let m = confirmed_body_merges(&tracker, &old, &new, &eligible);
        assert_eq!(m.len(), 1);
        assert_eq!(m[0].index, 0);
        assert_eq!(m[0].ids, vec![0, 1]);
        assert!(m[0].votes.iter().all(|&v| v >= 4));
        let separate = BodyPixelFrame::new(40_000, &[c, d], &current, tr).unwrap();
        assert!(confirmed_body_merges(&tracker, &old, &separate, &eligible).is_empty());
        let blank = BodyPixelFrame::new(
            40_000,
            std::slice::from_ref(&merged),
            &vec![127; pixels.len()],
            tr,
        )
        .unwrap();
        assert!(confirmed_body_merges(&tracker, &old, &blank, &eligible).is_empty());
        let stale =
            BodyPixelFrame::new(100_001, std::slice::from_ref(&merged), &current, tr).unwrap();
        assert!(confirmed_body_merges(&tracker, &old, &stale, &eligible).is_empty());
        assert!(confirmed_body_merges(&tracker, &old, &new, &BTreeSet::new()).is_empty());
        tracker.tracks[0].observed_this_frame = false;
        assert!(confirmed_body_merges(&tracker, &old, &new, &eligible).is_empty());
    }
    #[test]
    fn repeating_or_flat_texture_cannot_supply_unique_correspondences() {
        let d = pixel_detection(10, 10, 80, 95);
        let tr = d.mask.as_ref().unwrap().transform;
        let pixels = vec![120; 128 * 128 * 3];
        let a = BodyPixelFrame::new(0, std::slice::from_ref(&d), &pixels, tr).unwrap();
        let b = BodyPixelFrame::new(40_000, std::slice::from_ref(&d), &pixels, tr).unwrap();
        assert_eq!(a.votes(&b, 0), Some((0, vec![0])));
        let mut tiled = vec![0; pixels.len()];
        for y in 0..128 {
            for x in 0..128 {
                let k = (y * 128 + x) * 3;
                tiled[k..k + 3].fill(if (x + y) % 4 < 2 { 240 } else { 15 });
            }
        }
        let a = BodyPixelFrame::new(0, std::slice::from_ref(&d), &tiled, tr).unwrap();
        let b = BodyPixelFrame::new(40_000, std::slice::from_ref(&d), &tiled, tr).unwrap();
        assert!(!a.points(0).is_empty());
        assert_eq!(a.votes(&b, 0), Some((0, vec![0])));
    }
    #[test]
    fn ordered_equal_consensus_keeps_the_first_hypothesis() {
        let tracks: Vec<_> = (0..4).map(|i| (i, bounds(i as f32 * 1000.0))).collect();
        let detections: Vec<_> = [20.0, 1020.0, 1980.0, 2980.0]
            .into_iter()
            .map(detection)
            .collect();
        let config = TrackingConfig {
            max_global_shift: 30.0,
            scene_motion_tolerance: 5.0,
            ..Default::default()
        };
        assert_eq!(
            estimate(&tracks, &detections, &config),
            Some(SceneMotion {
                dx: 20.0,
                dy: 0.0,
                inliers: 2,
                confidence: 0.5
            })
        );
    }
    #[test]
    fn jitter_is_not_motion_and_one_observation_cannot_supply_two_inliers() {
        let tracks = [(1, bounds(0.0)), (2, bounds(1000.0))];
        let config = TrackingConfig::default();
        assert_eq!(
            estimate(&tracks, &[detection(4.0), detection(1004.0)], &config),
            Some(SceneMotion::default())
        );
        assert_eq!(
            estimate(&tracks, &[detection(30.0)], &config),
            Some(SceneMotion::default())
        );
        assert!(
            estimate(
                &tracks,
                &[],
                &TrackingConfig {
                    scene_motion_tolerance: f32::NAN,
                    ..Default::default()
                }
            )
            .is_none()
        );
    }
}
