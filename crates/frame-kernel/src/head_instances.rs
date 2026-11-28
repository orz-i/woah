//! Independent current head detections and anonymous short tracklets.
//! A head rectangle is a geometry observation, not a biometric identity.
use crate::{
    geometry::{Rect, Transform, overlap_iou},
    instances::InstanceDetection as Head,
    temporal_region::Ellipse,
};

fn center(r: Rect) -> [f32; 2] {
    [(r.left + r.right) * 0.5, (r.top + r.bottom) * 0.5]
}
fn size(r: Rect) -> f32 {
    (r.right - r.left).max(r.bottom - r.top)
}
fn valid(heads: &[Head]) -> bool {
    heads.len() <= 128
        && heads
            .iter()
            .all(|h| h.bounds.valid() && (0.0..=1.0).contains(&h.confidence))
}
fn union(a: Rect, b: Rect) -> Rect {
    Rect {
        left: a.left.min(b.left),
        top: a.top.min(b.top),
        right: a.right.max(b.right),
        bottom: a.bottom.max(b.bottom),
    }
}
/// Single-class xywh/score tensor, attribute-major, explicit pixel units.
pub(crate) fn decode(
    head: &[f32],
    anchors: usize,
    tr: Transform,
    flipped: bool,
) -> Option<Vec<Head>> {
    if !tr.valid()
        || !(1..=100_000).contains(&anchors)
        || head.len() != anchors.checked_mul(5)?
        || head.iter().any(|x| !x.is_finite())
    {
        return None;
    }
    let mut out = Vec::new();
    for i in 0..anchors {
        let conf = head[4 * anchors + i];
        if !(0.0..=1.0).contains(&conf) {
            return None;
        }
        if conf < 0.15 {
            continue;
        }
        let (x, y, w, h) = (
            head[i],
            head[anchors + i],
            head[2 * anchors + i],
            head[3 * anchors + i],
        );
        if w <= 0. || h <= 0. {
            continue;
        }
        let sx = |v: f32| ((v - tr.pad_left) / tr.scale).clamp(0., tr.source_width as f32);
        let sy = |v: f32| ((v - tr.pad_top) / tr.scale).clamp(0., tr.source_height as f32);
        let mut b = Rect {
            left: sx(x - w * 0.5),
            top: sy(y - h * 0.5),
            right: sx(x + w * 0.5),
            bottom: sy(y + h * 0.5),
        };
        if flipped {
            (b.left, b.right) = (
                tr.source_width as f32 - b.right,
                tr.source_width as f32 - b.left,
            );
        }
        if b.valid() && b.right - b.left >= 4. && b.bottom - b.top >= 4. {
            out.push(Head {
                bounds: b,
                confidence: conf,
            });
        }
    }
    out.sort_by(|a, b| b.confidence.total_cmp(&a.confidence));
    let mut kept: Vec<Head> = vec![];
    for h in out {
        if kept.iter().all(|q| overlap_iou(h.bounds, q.bounds) <= 0.45) {
            kept.push(h);
        }
        if kept.len() == 128 {
            break;
        }
    }
    Some(kept)
}

fn unique_best(
    values: impl Iterator<Item = (usize, f32)>,
    minimum: f32,
    margin: f32,
) -> Option<usize> {
    let mut best = (-1i32, f32::NEG_INFINITY);
    let mut second = f32::NEG_INFINITY;
    for (j, s) in values {
        if s > best.1 {
            second = best.1;
            best = (j as i32, s);
        } else {
            second = second.max(s);
        }
    }
    (best.0 >= 0 && best.1 >= minimum && best.1 - second >= margin).then_some(best.0 as usize)
}

fn reciprocal(
    a: &[Head],
    b: &[Head],
    score: impl Fn(Rect, Rect) -> f32,
    minimum: f32,
    margin: f32,
) -> Vec<i32> {
    let forward: Vec<_> = a
        .iter()
        .map(|x| {
            unique_best(
                b.iter()
                    .enumerate()
                    .map(|(j, y)| (j, score(x.bounds, y.bounds))),
                minimum,
                margin,
            )
        })
        .collect();
    let backward: Vec<_> = b
        .iter()
        .map(|y| {
            unique_best(
                a.iter()
                    .enumerate()
                    .map(|(i, x)| (i, score(x.bounds, y.bounds))),
                minimum,
                margin,
            )
        })
        .collect();
    forward
        .into_iter()
        .enumerate()
        .map(|(i, j)| match j {
            Some(j) if backward[j] == Some(i) => j as i32,
            _ => -1,
        })
        .collect()
}

/// Two mapped views: a reciprocal overlapping pair survives at low scores;
/// a single-view detection needs higher confidence. Preserve both extents.
pub(crate) fn fuse(a: &[Head], b: &[Head]) -> Option<Vec<Head>> {
    if !valid(a) || !valid(b) {
        return None;
    }
    let links = reciprocal(a, b, overlap_iou, 0.55, 0.12);
    let mut used = vec![false; b.len()];
    let mut out = vec![];
    for (i, h) in a.iter().enumerate() {
        if links[i] >= 0 {
            let j = links[i] as usize;
            used[j] = true;
            out.push(Head {
                bounds: union(h.bounds, b[j].bounds),
                confidence: h.confidence.max(b[j].confidence),
            });
        } else if h.confidence >= 0.45 && b.iter().all(|q| overlap_iou(h.bounds, q.bounds) < 0.55) {
            out.push(*h);
        }
    }
    for (j, h) in b.iter().enumerate() {
        if !used[j]
            && h.confidence >= 0.45
            && a.iter().all(|q| overlap_iou(h.bounds, q.bounds) < 0.55)
        {
            out.push(*h);
        }
    }
    out.sort_by(|a, b| b.confidence.total_cmp(&a.confidence));
    if out.len() > 128 {
        return None;
    }
    Some(out)
}

/// Short geometry-only links; competition on both sides prevents a head
/// from inheriting two tracks or crossing a near-tie.
pub(crate) fn links(a: &[Head], b: &[Head], delta_us: i64) -> Option<Vec<i32>> {
    if !valid(a) || !valid(b) || delta_us <= 0 || delta_us > 100_000 {
        return None;
    }
    let score = |x: Rect, y: Rect| {
        let sx = size(x);
        let sy = size(y);
        let ratio = sx.min(sy) / sx.max(sy);
        let [ax, ay] = center(x);
        let [bx, by] = center(y);
        let dist = (ax - bx).hypot(ay - by) / (sx + sy) * 2.;
        let iou = overlap_iou(x, y);
        if ratio < 0.65 || dist > 0.45 || iou < 0.20 {
            return f32::NEG_INFINITY;
        }
        0.7 * iou + 0.3 * (1. - dist)
    };
    Some(reciprocal(a, b, score, 0.43, 0.12))
}

/// Current all-body head ownership evidence. A body may own at most one
/// head, and a head may be assigned to at most one body. Head support
/// samples come from the independent detection's central visible core.
pub(crate) fn owners(
    heads: &[Head],
    bodies: &[Rect],
    masks: &[u8],
    tr: Transform,
) -> Option<Vec<i32>> {
    let n = (tr.proto_size as usize).checked_pow(2)?;
    if !valid(heads)
        || !tr.valid()
        || bodies.len() > 32
        || n.checked_mul(bodies.len())? != masks.len()
        || bodies.iter().any(|b| !b.valid())
    {
        return None;
    }
    let mut scores = vec![vec![f32::NEG_INFINITY; bodies.len()]; heads.len()];
    for (i, h) in heads.iter().enumerate() {
        let r = h.bounds;
        let [cx, cy] = center(r);
        let (w, hh) = (r.right - r.left, r.bottom - r.top);
        let mut counts = vec![0u32; bodies.len()];
        let mut exclusive = vec![0u32; bodies.len()];
        let mut total = 0;
        for iy in 0..5 {
            for ix in 0..5 {
                let x = cx + (ix as f32 - 2.) * 0.12 * w;
                let y = cy + (iy as f32 - 2.) * 0.12 * hh;
                let u = ((x * tr.scale + tr.pad_left) / tr.input_size as f32 * tr.proto_size as f32)
                    .floor() as i32;
                let v = ((y * tr.scale + tr.pad_top) / tr.input_size as f32 * tr.proto_size as f32)
                    .floor() as i32;
                if u < 0 || v < 0 || u >= tr.proto_size as i32 || v >= tr.proto_size as i32 {
                    continue;
                }
                let k = v as usize * tr.proto_size as usize + u as usize;
                total += 1;
                let mut own = None;
                let mut num = 0;
                for (j, m) in masks.chunks_exact(n).enumerate() {
                    if m[k] >= 128 {
                        counts[j] += 1;
                        own = Some(j);
                        num += 1;
                    }
                }
                if num == 1 {
                    exclusive[own.unwrap()] += 1;
                }
            }
        }
        if total < 16 {
            continue;
        }
        for (j, &b) in bodies.iter().enumerate() {
            let bh = b.bottom - b.top;
            let relative = (cy - b.top) / bh;
            let own = counts[j] as f32 / total as f32;
            let alone = exclusive[j] as f32 / total as f32;
            if cx < b.left
                || cx > b.right
                || !(-0.04..=0.28).contains(&relative)
                || !(0.055..=0.32).contains(&(hh / bh))
                || own < 0.76
                || alone < 0.60
            {
                continue;
            }
            // Crown proximity resolves only strong differences; no nearest
            // head fallback when two candidates belong to a merged mask.
            let top = ((r.top - b.top).abs() / hh).min(1.);
            scores[i][j] = 0.55 * alone + 0.30 * own + 0.15 * (1. - top);
        }
    }
    let forward: Vec<_> = scores
        .iter()
        .map(|v| unique_best(v.iter().copied().enumerate(), 0.72, 0.12))
        .collect();
    let backward: Vec<_> = (0..bodies.len())
        .map(|j| {
            unique_best(
                scores.iter().enumerate().map(|(i, v)| (i, v[j])),
                0.72,
                0.12,
            )
        })
        .collect();
    Some(
        forward
            .into_iter()
            .enumerate()
            .map(|(i, j)| match j {
                Some(j) if backward[j] == Some(i) => j as i32,
                _ => -1,
            })
            .collect(),
    )
}

/// Flattened anonymous paths: owner values are protected body-root IDs,
/// -1 unknown. Only directly witnessed nodes or gaps bounded by two
/// consistent witnesses on each side may be assigned. No extrapolation.
pub(crate) fn assign_path(times: &[i64], owner: &[i32], target: i32) -> Option<Vec<u32>> {
    if times.len() != owner.len()
        || times.len() > 100_000
        || target < 0
        || owner.iter().any(|&x| x < -1)
        || times
            .windows(2)
            .any(|w| w[1] <= w[0] || w[1] - w[0] > 100_000)
    {
        return None;
    }
    let mut out = vec![0; owner.len()];
    for (i, &id) in owner.iter().enumerate() {
        if id == target {
            out[i] = 1;
        }
    }
    // Process unknown runs once. Only bridge between repeated witnesses,
    // never across a contradictory observation or unbounded lost identity.
    let mut i = 0;
    while i < owner.len() {
        if owner[i] != -1 {
            i += 1;
            continue;
        }
        let start = i;
        while i < owner.len() && owner[i] == -1 {
            i += 1;
        }
        if start < 2 || i + 1 >= owner.len() {
            continue;
        }
        let a = start - 1;
        let b = i;
        if owner[a - 1] == target
            && owner[a] == target
            && owner[b] == target
            && owner[b + 1] == target
            && times[b] - times[a] <= 2_000_000
        {
            out[start..b].fill(2);
        }
    }
    Some(out)
}

/// Direct witnesses are qualified by a second adjacent observation on
/// the same reciprocal path. A contradictory neighbor cancels the direct
/// claim; an unknown run may still be covered only by the two-sided proof.
pub(crate) fn certified_path(times: &[i64], owner: &[i32], target: i32) -> Option<Vec<u32>> {
    let mut result = assign_path(times, owner, target)?;
    for (i, code) in result.iter_mut().enumerate() {
        if *code != 1 {
            continue;
        }
        let adjacent = [
            i.checked_sub(1).and_then(|j| owner.get(j)),
            owner.get(i + 1),
        ];
        let repeated = adjacent.into_iter().flatten().any(|&id| id == target);
        let contradictory = adjacent
            .into_iter()
            .flatten()
            .any(|&id| id != -1 && id != target);
        if !repeated || contradictory {
            *code = 0;
        }
    }
    Some(result)
}

/// Detector envelope with one source prototype cell or 8% size margin.
/// Rival detections constrain only the position of the enclosing square.
pub(crate) fn placement(
    heads: &[Head],
    index: usize,
    tr: Transform,
) -> Option<(Rect, Ellipse, f32)> {
    if !valid(heads) || index >= heads.len() || !tr.valid() {
        return None;
    }
    let h = heads[index].bounds;
    let cell = tr.input_size as f32 / (tr.proto_size as f32 * tr.scale);
    let pad = (size(h) * 0.08).max(cell);
    let required = Rect {
        left: (h.left - pad).max(0.),
        top: (h.top - pad).max(0.),
        right: (h.right + pad).min(tr.source_width as f32),
        bottom: (h.bottom + pad).min(tr.source_height as f32),
    };
    // Only intersecting possible rivals are relevant to the minimax solver.
    let [cx, cy] = center(required);
    let radius = size(required) * 0.52 + 8.;
    let possible = Rect {
        left: cx - radius,
        top: cy - radius,
        right: cx + radius,
        bottom: cy + radius,
    };
    let rivals: Vec<_> = heads
        .iter()
        .enumerate()
        .filter(|(i, q)| *i != index && overlap_iou(q.bounds, possible) > 0.)
        .map(|(_, q)| q.bounds)
        .collect();
    let (square, _, _, overlap) =
        crate::face_head_topology::minimize_head_square(required, &rivals)?;
    Some((required, square, overlap))
}

fn area(r: Rect) -> f32 {
    (r.right - r.left) * (r.bottom - r.top)
}
fn fraction(a: Rect, b: Rect) -> f32 {
    (a.right.min(b.right) - a.left.max(b.left)).max(0.)
        * (a.bottom.min(b.bottom) - a.top.max(b.top)).max(0.)
        / area(b)
}
fn square_bounds(s: Ellipse) -> Rect {
    let r = (1.02 * s.radius_x.max(s.radius_y)).max(8.);
    Rect {
        left: s.center_x - r,
        top: s.center_y - r,
        right: s.center_x + r,
        bottom: s.center_y + r,
    }
}
fn rect_contains(r: Rect, x: f32, y: f32) -> bool {
    x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
}
fn ellipse_valid(s: Ellipse) -> bool {
    [s.center_x, s.center_y, s.radius_x, s.radius_y]
        .into_iter()
        .all(f32::is_finite)
        && s.radius_x > 0.
        && s.radius_y > 0.
}

/// Arbitration is separate from the head-only ablation. An independently
/// associated head must have repeated local path evidence. Retain existing
/// coverage unless there is conflicting position, uncovered current extent,
/// or independently observed excess rival overlap. Never shrink to a
/// smaller agreeing detector box merely because it is newer.
pub(crate) fn arbitrate(
    heads: &[Head],
    index: usize,
    tr: Transform,
    previous: Ellipse,
    required_before: Rect,
    face: Ellipse,
) -> Option<(Rect, Ellipse, f32, u32)> {
    let (mut required, head_square, head_overlap) = placement(heads, index, tr)?;
    if heads[index].confidence < 0.45 {
        return None;
    }
    if previous == Ellipse::default() {
        return Some((required, head_square, head_overlap, 1));
    }
    if !ellipse_valid(previous) {
        return None;
    }
    let old = square_bounds(previous);
    let detected = heads[index].bounds;
    let agreeing = rect_contains(required, previous.center_x, previous.center_y);
    let rival_overlap = |r: Rect| {
        heads
            .iter()
            .enumerate()
            .filter(|(j, _)| *j != index)
            .map(|(_, h)| fraction(r, h.bounds))
            .fold(0., f32::max)
    };
    let old_rival = rival_overlap(old);
    let clip = |b: Rect| Rect {
        left: b.left.max(0.),
        top: b.top.max(0.),
        right: b.right.min(tr.source_width as f32),
        bottom: b.bottom.min(tr.source_height as f32),
    };
    // A compatible current directly measured face remains necessary unless
    // its center and most of its extent belong to a different head.
    if ellipse_valid(face) {
        let f = clip(Rect {
            left: face.center_x - face.radius_x,
            top: face.center_y - face.radius_y,
            right: face.center_x + face.radius_x,
            bottom: face.center_y + face.radius_y,
        });
        if f.valid()
            && rect_contains(detected, face.center_x, face.center_y)
            && fraction(detected, f) >= 0.5
        {
            required = union(required, f);
        }
    }
    let mode = if !agreeing {
        2
    } else if old_rival > 0.10 && old_rival - head_overlap >= 0.10 {
        3
    } else if fraction(old, required) < 0.95 {
        // When no competing head explains the old extent, preserve it
        // while adding the independently observed missing head extent.
        let prior = if required_before.valid() {
            clip(required_before)
        } else {
            clip(old)
        };
        if !prior.valid() {
            return None;
        }
        required = union(required, prior);
        4
    } else {
        return None;
    };
    let [cx, cy] = center(required);
    let radius = size(required) * 0.52 + 8.;
    let possible = Rect {
        left: cx - radius,
        top: cy - radius,
        right: cx + radius,
        bottom: cy + radius,
    };
    let rivals: Vec<_> = heads
        .iter()
        .enumerate()
        .filter(|(j, h)| *j != index && overlap_iou(h.bounds, possible) > 0.)
        .map(|(_, h)| h.bounds)
        .collect();
    let (square, _, _, overlap) =
        crate::face_head_topology::minimize_head_square(required, &rivals)?;
    if mode == 3 && old_rival - overlap < 0.10 {
        return None;
    }
    if mode == 4 && overlap > old_rival + 0.10 {
        return None;
    }
    Some((required, square, overlap, mode))
}

#[cfg(test)]
mod tests {
    use super::*;
    fn h(x: f32, y: f32) -> Head {
        Head {
            bounds: Rect {
                left: x,
                top: y,
                right: x + 30.,
                bottom: y + 35.,
            },
            confidence: 0.8,
        }
    }
    #[test]
    fn reciprocal_head_tracks_reject_ties_and_competing_identity() {
        assert_eq!(
            links(
                &[h(10., 10.), h(80., 10.)],
                &[h(13., 10.), h(78., 10.)],
                40_000
            ),
            Some(vec![0, 1])
        );
        assert_eq!(
            links(&[h(10., 10.)], &[h(10., 10.), h(10., 10.)], 40_000),
            Some(vec![-1])
        );
        assert_eq!(
            assign_path(&[0, 40_000, 80_000, 120_000, 160_000], &[7, 7, -1, 7, 7], 7),
            Some(vec![1, 1, 2, 1, 1])
        );
        assert_eq!(
            assign_path(
                &[0, 40_000, 80_000, 120_000, 160_000, 200_000],
                &[7, 7, -1, 8, 7, 7],
                7
            ),
            Some(vec![1, 1, 0, 0, 1, 1])
        );
    }
    #[test]
    fn certified_path_needs_adjacent_witness_and_abstains_on_competition() {
        assert_eq!(
            certified_path(&[0, 40_000, 80_000, 120_000, 160_000], &[7, 7, -1, 7, 7], 7),
            Some(vec![1, 1, 2, 1, 1])
        );
        assert_eq!(
            certified_path(&[0, 40_000, 80_000, 120_000, 160_000], &[7, -1, 7, 8, 7], 7),
            Some(vec![0, 0, 0, 0, 0])
        );
    }
    #[test]
    fn independent_head_arbitration_does_not_shrink_agreeing_cover_without_rival() {
        let tr = Transform {
            source_width: 256,
            source_height: 256,
            input_size: 256,
            proto_size: 64,
            scale: 1.,
            pad_left: 0.,
            pad_top: 0.,
        };
        let a = h(90., 40.);
        let prior = Ellipse {
            center_x: 105.,
            center_y: 57.5,
            radius_x: 40.,
            radius_y: 40.,
        };
        assert!(arbitrate(&[a], 0, tr, prior, Rect::default(), Ellipse::default()).is_none());
        let wrong = Ellipse {
            center_x: 35.,
            ..prior
        };
        assert_eq!(
            arbitrate(&[a], 0, tr, wrong, Rect::default(), Ellipse::default())
                .unwrap()
                .3,
            2
        );
        assert_eq!(
            arbitrate(
                &[a],
                0,
                tr,
                Ellipse::default(),
                Rect::default(),
                Ellipse::default()
            )
            .unwrap()
            .3,
            1
        );
    }

    #[test]
    fn two_view_fusion_keeps_distinct_heads() {
        let mut weak = h(12., 10.);
        weak.confidence = 0.2;
        let mut missing = h(80., 10.);
        missing.confidence = 0.2;
        let v = fuse(&[weak, missing], &[h(10., 10.), h(150., 10.)]).unwrap();
        assert_eq!(v.len(), 2);
        assert!(
            v.iter()
                .any(|h| h.bounds.left == 10. && h.bounds.right == 42.)
        );
        assert!(v.iter().any(|h| h.bounds.left == 150.));
    }
}
