//! Canonical head ROI and candidate ownership from the Android real-device path.
use crate::geometry::Rect;

#[derive(Clone, Copy, Debug)]
pub struct HeadPlan {
    pub rect: Rect,
    pub anchor: [f32; 2],
}

#[derive(Clone, Debug)]
pub struct Candidate {
    pub bounds: Rect,
    pub confidence: f32,
    pub points: Vec<[f32; 2]>,
}
impl Candidate {
    pub fn center(&self) -> [f32; 2] {
        if self.points.len() >= 4 {
            let [a, b, n, m] = [
                self.points[0],
                self.points[1],
                self.points[2],
                self.points[3],
            ];
            [
                ((a[0] + b[0]) * 0.5 + n[0] + m[0]) / 3.0,
                ((a[1] + b[1]) * 0.5 + n[1] + m[1]) / 3.0,
            ]
        } else {
            [
                self.bounds.left + (self.bounds.right - self.bounds.left).max(0.0) * 0.5,
                self.bounds.top + (self.bounds.bottom - self.bounds.top).max(0.0) * 0.5,
            ]
        }
    }
}

pub fn plan(person: Rect, width: u32, height: u32) -> Option<HeadPlan> {
    let w = person.right - person.left;
    let h = person.bottom - person.top;
    if !person.valid() || w <= 1.0 || h <= 1.0 || width <= 1 || height <= 1 {
        return None;
    }
    let side = (w * 2.20)
        .max(h * 0.90)
        .min(width.min(height) as f32)
        .max(2.0);
    let ax = (person.left + w * 0.5).clamp(0.0, width as f32);
    let ay = (person.top + h * 0.22).clamp(0.0, height as f32);
    let left = (ax - side * 0.5).clamp(0.0, (width as f32 - side).max(0.0));
    let top = (ay - side * 0.5).clamp(0.0, (height as f32 - side).max(0.0));
    Some(HeadPlan {
        rect: Rect {
            left,
            top,
            right: left + side,
            bottom: top + side,
        },
        anchor: [
            ((ax - left) / side).clamp(0.0, 1.0),
            ((ay - top) / side).clamp(0.0, 1.0),
        ],
    })
}

/// View bounds describe the candidate coordinate space (ROI pixels or source pixels).
pub fn select(
    faces: &[Candidate],
    view: Rect,
    anchor: [f32; 2],
    maximum: f32,
    separation: f32,
) -> Option<(usize, f32)> {
    if !view.valid()
        || view.right - view.left <= 1.0
        || view.bottom - view.top <= 1.0
        || !anchor.iter().all(|v| v.is_finite())
        || !maximum.is_finite()
        || maximum < 0.0
        || !separation.is_finite()
        || separation < 0.0
    {
        return None;
    }
    let w = view.right - view.left;
    let h = view.bottom - view.top;
    let ax = view.left + anchor[0].clamp(0.0, 1.0) * w;
    let ay = view.top + anchor[1].clamp(0.0, 1.0) * h;
    let mut ranked: Vec<_> = faces
        .iter()
        .enumerate()
        .filter_map(|(i, f)| {
            let center = f.center();
            let dx = center[0] - ax;
            let dy = center[1] - ay;
            let distance = (dx * dx + dy * dy).sqrt() / w.min(h).max(1.0);
            (distance.is_finite() && distance <= maximum && f.confidence.is_finite())
                .then_some((i, distance))
        })
        .collect();
    ranked.sort_by(|a, b| {
        a.1.total_cmp(&b.1)
            .then_with(|| faces[b.0].confidence.total_cmp(&faces[a.0].confidence))
    });
    let best = *ranked.first()?;
    if ranked
        .get(1)
        .is_some_and(|second| second.1 - best.1 < separation)
    {
        return None;
    }
    Some(best)
}

/// Map a local detector center back to the owner's source crop before gating.
pub fn owned(face: &Candidate, source: Rect, output_size: f32, person: Rect) -> bool {
    if !source.valid() || !person.valid() || !output_size.is_finite() || output_size <= 0.0 {
        return false;
    }
    let c = face.center();
    let w = person.right - person.left;
    let h = person.bottom - person.top;
    let x = source.left + c[0] / output_size * (source.right - source.left);
    let y = source.top + c[1] / output_size * (source.bottom - source.top);
    x >= person.left - w * 0.08
        && x <= person.right + w * 0.08
        && y >= person.top - h * 0.08
        && y <= person.top + h * 0.60
}

#[cfg(test)]
mod tests {
    use super::*;
    fn candidate(x: f32, y: f32) -> Candidate {
        Candidate {
            bounds: Rect {
                left: x - 20.0,
                top: y - 20.0,
                right: x + 20.0,
                bottom: y + 20.0,
            },
            confidence: 0.9,
            points: vec![],
        }
    }
    #[test]
    fn actual_raised_arm_waist_and_hand_must_not_replace_head_fallback() {
        let waist = Rect {
            left: 152.49,
            top: 430.80,
            right: 1942.18,
            bottom: 3455.41,
        };
        let p = plan(waist, 2160, 4096).unwrap();
        assert!(select(&[candidate(1000.16, 1914.05)], p.rect, p.anchor, 0.22, 0.04).is_none());
        let hand = plan(
            Rect {
                left: 172.46,
                top: 637.08,
                right: 2103.70,
                bottom: 3438.19,
            },
            2160,
            4096,
        )
        .unwrap();
        assert!(
            select(
                &[candidate(1499.36, 761.19)],
                hand.rect,
                hand.anchor,
                0.22,
                0.04
            )
            .is_none()
        );
        assert_eq!(
            select(&[candidate(1262.10, 1177.52)], p.rect, p.anchor, 0.22, 0.04)
                .unwrap()
                .0,
            0
        );
    }
    #[test]
    fn equal_candidates_are_ambiguous_even_with_high_confidence() {
        let view = Rect {
            left: 0.0,
            top: 0.0,
            right: 256.0,
            bottom: 256.0,
        };
        let mut b = candidate(135.0, 127.0);
        b.confidence = 1.0;
        assert!(select(&[candidate(121.0, 121.0), b], view, [0.5, 0.5], 0.22, 0.04).is_none());
    }
    #[test]
    fn points_drive_localization_and_edge_crop_stays_square() {
        let mut f = candidate(200.0, 200.0);
        f.points = vec![[90.0, 80.0], [110.0, 80.0], [100.0, 100.0], [100.0, 120.0]];
        assert_eq!(f.center(), [100.0, 100.0]);
        let p = plan(
            Rect {
                left: 0.0,
                top: 0.0,
                right: 100.0,
                bottom: 300.0,
            },
            500,
            400,
        )
        .unwrap();
        assert_eq!(p.rect.left, 0.0);
        assert_eq!(p.rect.top, 0.0);
        assert_eq!(p.rect.right - p.rect.left, p.rect.bottom - p.rect.top);
        assert!((p.anchor[0] * 270.0 - 50.0).abs() < 0.0001);
    }
}
