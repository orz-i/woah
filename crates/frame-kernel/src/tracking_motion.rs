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
