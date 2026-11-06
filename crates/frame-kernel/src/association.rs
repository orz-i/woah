//! Deterministic thresholded, globally sorted greedy association. This preserves
//! the established matching semantics; it is not an optimal Hungarian solver.
use crate::geometry::{Rect, overlap_iou};

#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MatchPair {
    pub row: u32,
    pub column: u32,
}

pub(crate) fn dimensions(rows: u32, columns: u32) -> Option<usize> {
    if rows > 100_000 || columns > 100_000 {
        return None;
    }
    let len = (rows as usize).checked_mul(columns as usize)?;
    if len > 1_000_000 { None } else { Some(len) }
}

pub fn match_costs(
    costs: &[f32],
    rows: u32,
    columns: u32,
    max_cost: f32,
) -> Option<Vec<MatchPair>> {
    if dimensions(rows, columns) != Some(costs.len()) || max_cost.is_nan() {
        return None;
    }
    let mut candidates = Vec::new();
    for row in 0..rows {
        for column in 0..columns {
            let cost = costs[(row * columns + column) as usize];
            if cost <= max_cost {
                candidates.push((MatchPair { row, column }, cost));
            }
        }
    }
    candidates.sort_by(|a, b| {
        a.1.total_cmp(&b.1)
            .then(a.0.row.cmp(&b.0.row))
            .then(a.0.column.cmp(&b.0.column))
    });
    let mut matched_rows = vec![false; rows as usize];
    let mut matched_columns = vec![false; columns as usize];
    let mut pairs = Vec::new();
    for (pair, _) in candidates {
        if !matched_rows[pair.row as usize] && !matched_columns[pair.column as usize] {
            matched_rows[pair.row as usize] = true;
            matched_columns[pair.column as usize] = true;
            pairs.push(pair);
        }
    }
    Some(pairs)
}

pub fn match_rects(rows: &[Rect], columns: &[Rect], max_cost: f32) -> Option<Vec<MatchPair>> {
    let nr = u32::try_from(rows.len()).ok()?;
    let nc = u32::try_from(columns.len()).ok()?;
    dimensions(nr, nc)?;
    if rows.iter().chain(columns).any(|r| {
        ![r.left, r.top, r.right, r.bottom]
            .iter()
            .all(|x| x.is_finite())
    }) {
        return None;
    }
    let costs = rows
        .iter()
        .flat_map(|&a| columns.iter().map(move |&b| 1.0 - overlap_iou(a, b)))
        .collect::<Vec<_>>();
    match_costs(&costs, nr, nc, max_cost)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stable_ties_threshold_and_greedy_semantics_are_preserved() {
        assert_eq!(
            match_costs(&[0.1, 0.2, 0.11, 0.9], 2, 2, 0.5),
            Some(vec![MatchPair { row: 0, column: 0 }])
        );
        assert_eq!(
            match_costs(&[0.5, 0.5, 0.5, 0.5], 2, 2, 0.5),
            Some(vec![
                MatchPair { row: 0, column: 0 },
                MatchPair { row: 1, column: 1 }
            ])
        );
        assert_eq!(
            match_costs(&[f32::NAN, 0.2, 0.1, f32::INFINITY], 2, 2, 0.2),
            Some(vec![
                MatchPair { row: 1, column: 0 },
                MatchPair { row: 0, column: 1 }
            ])
        );
        assert_eq!(match_costs(&[], 3, 0, 0.5), Some(vec![]));
        assert_eq!(match_costs(&[0.1], 1, 2, 0.5), None);
    }
    #[test]
    fn subpixel_rectangle_cost_has_no_detail_tile_area_clamp() {
        let a = Rect {
            left: 0.0,
            top: 0.0,
            right: 0.1,
            bottom: 0.1,
        };
        assert_eq!(
            match_rects(&[a], &[a], 0.0),
            Some(vec![MatchPair { row: 0, column: 0 }])
        );
    }
}
