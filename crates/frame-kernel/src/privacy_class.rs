//! Identity-independent privacy-class continuity for current observations.
//! The first hard root is immutable. Unknown observations protect by default
//! and never teach prototypes. Motion and class evidence do not mint identities.
use crate::{
    association::match_costs,
    geometry::{Rect, overlap_iou},
    tracking::{ClassEvidence, Detection, SelectionClass, TrackingError},
    tracking_mask::{self, WarpedSamples},
};

#[derive(Clone, Copy)]
pub struct ClassConfig {
    pub minimum: f32,
    pub single_minimum: f32,
    pub margin: f32,
    pub maximum_misses: u32,
    pub reuse_similarity: bool,
    pub reuse_warp: bool,
    pub count_evaluations: bool,
}
impl Default for ClassConfig {
    fn default() -> Self {
        Self {
            minimum: 0.42,
            single_minimum: 0.65,
            margin: 0.12,
            maximum_misses: 4,
            reuse_similarity: true,
            reuse_warp: true,
            count_evaluations: false,
        }
    }
}
impl ClassConfig {
    pub fn valid(self) -> bool {
        [self.minimum, self.single_minimum, self.margin]
            .into_iter()
            .all(|v| v.is_finite() && v >= 0.0)
            && self.maximum_misses <= 1_000_000
    }
}
#[derive(Clone)]
struct Prototype {
    class: SelectionClass,
    detection: Detection,
    velocity: [f32; 2],
    misses: u32,
    reliability: f32,
}
impl Prototype {
    fn predicted(&self) -> Rect {
        offset(self.detection.bounds, self.velocity)
    }
}
fn offset(r: Rect, v: [f32; 2]) -> Rect {
    Rect {
        left: r.left + v[0],
        top: r.top + v[1],
        right: r.right + v[0],
        bottom: r.bottom + v[1],
    }
}
fn dimensions(r: Rect) -> [f32; 2] {
    [(r.right - r.left).max(0.0), (r.bottom - r.top).max(0.0)]
}
fn center(r: Rect) -> [f32; 2] {
    let d = dimensions(r);
    [r.left + d[0] / 2.0, r.top + d[1] / 2.0]
}
struct Scores {
    old: Vec<Prototype>,
    values: Vec<Vec<Option<f32>>>,
    warped: Vec<Option<WarpedSamples>>,
    config: ClassConfig,
    evaluations: u32,
}
impl Scores {
    fn new(old: Vec<Prototype>, count: usize, config: ClassConfig) -> Self {
        let n = old.len();
        Self {
            old,
            values: vec![vec![None; count]; n],
            warped: (0..n).map(|_| None).collect(),
            config,
            evaluations: 0,
        }
    }
    fn similarity(&mut self, p: usize, index: usize, d: &Detection) -> f32 {
        if self.config.reuse_similarity
            && let Some(value) = self.values[p][index]
        {
            return value;
        }
        if self.config.count_evaluations {
            self.evaluations += 1;
        }
        let old = &self.old[p];
        let predicted = old.predicted();
        let pc = center(predicted);
        let dc = center(d.bounds);
        let dx = pc[0] - dc[0];
        let dy = pc[1] - dc[1];
        let distance = (dx * dx + dy * dy).sqrt();
        let dim = dimensions(predicted)
            .into_iter()
            .chain(dimensions(d.bounds))
            .fold(1.0_f32, f32::max);
        let distance_score = (1.0 - distance / (dim * 1.5)).clamp(0.0, 1.0);
        let mask_iou = if self.config.reuse_warp {
            if self.warped[p].is_none() {
                self.warped[p] = old.detection.mask.as_ref().and_then(|m| {
                    tracking_mask::prepare_warped_samples(m, old.detection.bounds, predicted, 4)
                });
            }
            self.warped[p]
                .as_ref()
                .map_or(0.0, |w| tracking_mask::prepared_iou(w, d.mask.as_ref()))
        } else {
            tracking_mask::warped_iou(
                old.detection.mask.as_ref(),
                old.detection.bounds,
                predicted,
                d.mask.as_ref(),
                4,
            )
        };
        let value =
            (0.40 * overlap_iou(predicted, d.bounds) + 0.40 * mask_iou + 0.20 * distance_score)
                .clamp(0.0, 1.0);
        if self.config.reuse_similarity {
            self.values[p][index] = Some(value);
        }
        value
    }
    fn best(&mut self, class: SelectionClass, index: usize, d: &Detection) -> f32 {
        let mut best = 0.0_f32;
        for p in 0..self.old.len() {
            if self.old[p].class == class {
                let reliability = self.old[p].reliability.clamp(0.0, 1.0);
                best = best.max(self.similarity(p, index, d) * reliability);
            }
        }
        best
    }
}
#[derive(Clone)]
pub struct ClassTracker {
    config: ClassConfig,
    prototypes: Vec<Prototype>,
    root_seeded: bool,
    pub last_evaluations: u32,
    pub summary: [u32; 8],
}
impl ClassTracker {
    pub fn new(config: ClassConfig) -> Option<Self> {
        config.valid().then_some(Self {
            config,
            prototypes: vec![],
            root_seeded: false,
            last_evaluations: 0,
            summary: [0; 8],
        })
    }
    pub fn reset(&mut self) {
        self.prototypes.clear();
        self.root_seeded = false;
        self.last_evaluations = 0;
        self.summary = [0; 8];
    }
    pub fn update(
        &mut self,
        detections: &[Detection],
        hard: &[(usize, SelectionClass)],
    ) -> Result<Vec<ClassEvidence>, TrackingError> {
        if detections.len() > 4096
            || self
                .prototypes
                .len()
                .checked_mul(detections.len().max(1))
                .is_none_or(|n| n > 4_194_304)
            || detections.iter().any(|d| !d.valid())
        {
            return Err(TrackingError::InvalidObservation);
        }
        self.last_evaluations = 0;
        if detections.is_empty() {
            self.decay(&[]);
            self.summary = [
                0,
                0,
                0,
                0,
                0,
                self.prototypes
                    .iter()
                    .filter(|p| p.class == SelectionClass::Selected)
                    .count() as u32,
                self.prototypes
                    .iter()
                    .filter(|p| p.class == SelectionClass::Unselected)
                    .count() as u32,
                0,
            ];
            return Ok(vec![]);
        }
        let root = if !self.root_seeded && !hard.is_empty() {
            self.root_seeded = true;
            hard
        } else {
            &[]
        };
        // Preserve root map insertion order; sorting equal-class roots changes ties.
        let mut classified: Vec<(usize, SelectionClass)> = vec![];
        for &(i, class) in root {
            if i < detections.len() {
                if let Some(row) = classified.iter_mut().find(|r| r.0 == i) {
                    row.1 = class;
                } else {
                    classified.push((i, class));
                }
            }
        }
        let has_selected = self
            .prototypes
            .iter()
            .any(|p| p.class == SelectionClass::Selected);
        let has_unselected = self
            .prototypes
            .iter()
            .any(|p| p.class == SelectionClass::Unselected);
        let mut scores = Scores::new(self.prototypes.clone(), detections.len(), self.config);
        for (i, d) in detections.iter().enumerate() {
            if classified.iter().any(|r| r.0 == i) {
                continue;
            }
            let selected = scores.best(SelectionClass::Selected, i, d);
            let unselected = scores.best(SelectionClass::Unselected, i, d);
            let class = if has_selected
                && has_unselected
                && selected >= self.config.minimum
                && selected - unselected >= self.config.margin
            {
                Some(SelectionClass::Selected)
            } else if has_selected
                && has_unselected
                && unselected >= self.config.minimum
                && unselected - selected >= self.config.margin
            {
                Some(SelectionClass::Unselected)
            } else if has_selected && !has_unselected && selected >= self.config.single_minimum {
                Some(SelectionClass::Selected)
            } else if has_unselected && !has_selected && unselected >= self.config.single_minimum {
                Some(SelectionClass::Unselected)
            } else {
                None
            };
            if let Some(class) = class {
                classified.push((i, class));
            }
        }
        let mut updated = vec![false; self.prototypes.len()];
        for class in [SelectionClass::Selected, SelectionClass::Unselected] {
            let old: Vec<usize> = self
                .prototypes
                .iter()
                .enumerate()
                .filter(|(_, p)| p.class == class)
                .map(|(i, _)| i)
                .collect();
            let current: Vec<usize> = classified
                .iter()
                .filter(|r| r.1 == class)
                .map(|r| r.0)
                .collect();
            let mut matched = vec![false; current.len()];
            if !old.is_empty() && !current.is_empty() {
                let mut costs = Vec::with_capacity(old.len() * current.len());
                for &p in &old {
                    for &i in &current {
                        costs.push(1.0 - scores.similarity(p, i, &detections[i]));
                    }
                }
                let pairs = match_costs(&costs, old.len() as u32, current.len() as u32, 0.75)
                    .ok_or(TrackingError::InvalidObservation)?;
                for pair in pairs {
                    let p = old[pair.row as usize];
                    let i = current[pair.column as usize];
                    let prototype = &mut self.prototypes[p];
                    let a = center(prototype.detection.bounds);
                    let b = center(detections[i].bounds);
                    prototype.velocity = [
                        prototype.velocity[0] * 0.45 + (b[0] - a[0]) * 0.55,
                        prototype.velocity[1] * 0.45 + (b[1] - a[1]) * 0.55,
                    ];
                    prototype.detection = detections[i].clone();
                    prototype.misses = 0;
                    prototype.reliability = if root.contains(&(i, class)) {
                        1.0
                    } else {
                        (prototype.reliability + 0.08).min(0.92)
                    };
                    updated[p] = true;
                    matched[pair.column as usize] = true;
                }
            }
            for (column, &i) in current.iter().enumerate() {
                if !matched[column] {
                    self.prototypes.push(Prototype {
                        class,
                        detection: detections[i].clone(),
                        velocity: [0.0; 2],
                        misses: 0,
                        reliability: if root.contains(&(i, class)) {
                            1.0
                        } else {
                            0.78
                        },
                    });
                    updated.push(true);
                }
            }
        }
        self.decay(&updated);
        self.last_evaluations = scores.evaluations;
        let hard_selected = classified
            .iter()
            .filter(|r| r.1 == SelectionClass::Selected && root.iter().any(|(i, _)| *i == r.0))
            .count() as u32;
        let hard_unselected = classified
            .iter()
            .filter(|r| r.1 == SelectionClass::Unselected && root.iter().any(|(i, _)| *i == r.0))
            .count() as u32;
        self.summary = [
            hard_selected,
            hard_unselected,
            classified
                .iter()
                .filter(|r| r.1 == SelectionClass::Selected)
                .count() as u32
                - hard_selected,
            classified
                .iter()
                .filter(|r| r.1 == SelectionClass::Unselected)
                .count() as u32
                - hard_unselected,
            (detections.len() - classified.len()) as u32,
            self.prototypes
                .iter()
                .filter(|p| p.class == SelectionClass::Selected)
                .count() as u32,
            self.prototypes
                .iter()
                .filter(|p| p.class == SelectionClass::Unselected)
                .count() as u32,
            scores.evaluations,
        ];
        Ok(detections
            .iter()
            .enumerate()
            .filter(|(_, d)| d.mask.is_some())
            .map(|(i, d)| {
                let class = classified.iter().find(|r| r.0 == i).map(|r| r.1);
                ClassEvidence {
                    selection: class.unwrap_or(SelectionClass::Selected),
                    detection_index: i,
                    detection: d.clone(),
                    residual_ids: vec![],
                    conservative_unknown: class.is_none(),
                }
            })
            .collect())
    }
    fn decay(&mut self, updated: &[bool]) {
        for (i, p) in self.prototypes.iter_mut().enumerate() {
            if !updated.get(i).copied().unwrap_or(false) {
                p.detection.bounds = p.predicted();
                p.velocity[0] *= 0.75;
                p.velocity[1] *= 0.75;
                p.misses += 1;
                p.reliability *= 0.72;
            }
        }
        self.prototypes
            .retain(|p| p.misses <= self.config.maximum_misses && p.reliability >= 0.18);
    }
}

#[cfg(test)]
mod reference {
    use super::*;
    use crate::{geometry::Transform, tracking::Mask};
    use std::sync::Arc;
    struct Reader {
        data: &'static [u8],
        at: usize,
    }
    impl Reader {
        fn int(&mut self) -> i32 {
            let x = i32::from_be_bytes(self.data[self.at..self.at + 4].try_into().unwrap());
            self.at += 4;
            x
        }
        fn float(&mut self) -> f32 {
            f32::from_bits(self.int() as u32)
        }
        fn flag(&mut self) -> bool {
            let x = self.data[self.at] != 0;
            self.at += 1;
            x
        }
        fn rect(&mut self) -> Rect {
            Rect {
                left: self.float(),
                top: self.float(),
                right: self.float(),
                bottom: self.float(),
            }
        }
        fn mask(&mut self) -> Option<Mask> {
            let w = self.int();
            if w < 0 {
                return None;
            }
            let h = self.int();
            let n = (w * h) as usize;
            let pixels = Arc::from(&self.data[self.at..self.at + n]);
            self.at += n;
            Some(Mask {
                width: w as usize,
                height: h as usize,
                pixels,
                transform: Transform {
                    scale: 1.0,
                    pad_left: 0.0,
                    pad_top: 0.0,
                    source_width: 640,
                    source_height: 640,
                    input_size: 640,
                    proto_size: w as u32,
                },
            })
        }
        fn class(&mut self) -> SelectionClass {
            match self.int() {
                0 => SelectionClass::Selected,
                1 => SelectionClass::Unselected,
                _ => panic!("invalid frozen class"),
            }
        }
        fn equal_float(&mut self, actual: f32) {
            assert_eq!(
                actual.to_bits(),
                self.float().to_bits(),
                "Float32 field at {}",
                self.at - 4
            );
        }
        fn equal_rect(&mut self, r: Rect) {
            for x in [r.left, r.top, r.right, r.bottom] {
                self.equal_float(x);
            }
        }
        fn equal_mask(&mut self, actual: Option<&Mask>) {
            let expected = self.mask();
            match (actual, expected) {
                (None, None) => {}
                (Some(a), Some(b)) => {
                    assert_eq!((a.width, a.height), (b.width, b.height));
                    assert_eq!(&*a.pixels, &*b.pixels);
                }
                _ => panic!("mask presence"),
            }
        }
    }
    #[test]
    fn every_native_class_transition_and_prototype_matches() {
        let data = include_bytes!("../testdata/privacy-class-native-v1.bin");
        let mut r = Reader { data, at: 0 };
        assert_eq!(r.int(), 0x464B4331);
        let modes = r.int();
        let mut total = 0;
        for _ in 0..modes {
            let cache = r.flag();
            let mode = r.int();
            let frames = r.int();
            let mut tracker = ClassTracker::new(ClassConfig {
                reuse_similarity: cache,
                reuse_warp: cache,
                count_evaluations: true,
                ..ClassConfig::default()
            })
            .unwrap();
            for frame in 0..frames {
                let _high = r.int();
                let _low = r.int();
                let n = r.int();
                let mut detections = vec![];
                for _ in 0..n {
                    let bounds = r.rect();
                    let confidence = r.float();
                    let mask = r.mask();
                    detections.push(Detection {
                        bounds,
                        confidence,
                        foot_y: bounds.bottom,
                        mask,
                    });
                }
                let n = r.int();
                let mut hard = vec![];
                for _ in 0..n {
                    let index = r.int() as usize;
                    let class = r.class();
                    hard.push((index, class));
                }
                let actual = tracker.update(&detections, &hard).unwrap();
                let count = r.int();
                assert_eq!(actual.len(), count as usize, "mode {mode} frame {frame}");
                for e in actual {
                    assert_eq!(e.detection_index, r.int() as usize);
                    assert_eq!(e.selection, r.class());
                    assert_eq!(e.conservative_unknown, r.flag());
                }
                assert_eq!(tracker.root_seeded, r.flag());
                assert_eq!(
                    tracker.last_evaluations,
                    r.int() as u32,
                    "cache={cache} mode={mode} frame={frame}"
                );
                assert_eq!(
                    tracker.prototypes.len(),
                    r.int() as usize,
                    "mode {mode} frame {frame}"
                );
                for p in &tracker.prototypes {
                    assert_eq!(p.class, r.class());
                    r.equal_rect(p.detection.bounds);
                    r.equal_mask(p.detection.mask.as_ref());
                    r.equal_float(p.velocity[0]);
                    r.equal_float(p.velocity[1]);
                    assert_eq!(p.misses, r.int() as u32);
                    r.equal_float(p.reliability);
                }
                total += 1;
            }
        }
        assert_eq!(total, 1024);
        assert_eq!(r.at, data.len());
    }
}
