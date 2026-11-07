//! Stateful region identity. This module is being migrated from the frozen
//! canonical tracker; native consumers switch only after complete replay parity.
use crate::{
    association_score::AssociationWeights,
    box_filter::BoxFilter,
    geometry::{Rect, Transform},
};
use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;

#[derive(Clone)]
pub struct TrackingConfig {
    pub max_missed_frames: u32,
    pub post_occlusion_grace_frames: u32,
    pub min_match_score: f32,
    pub weights: AssociationWeights,
    pub occlusion_overlap_ratio: f32,
    pub occlusion_max_duration_frames: u32,
    pub association_ambiguity_margin: f32,
    pub min_scene_motion_inliers: usize,
    pub scene_motion_tolerance: f32,
    pub max_global_shift: f32,
    pub scene_motion_compensation: bool,
    pub foreground_foot_margin_ratio: f32,
    pub privacy_occluder_erosion_radius: u32,
}
impl Default for TrackingConfig {
    fn default() -> Self {
        Self {
            max_missed_frames: 15,
            post_occlusion_grace_frames: 10,
            min_match_score: 0.20,
            weights: AssociationWeights::default(),
            occlusion_overlap_ratio: 0.30,
            occlusion_max_duration_frames: 90,
            association_ambiguity_margin: 0.05,
            min_scene_motion_inliers: 2,
            scene_motion_tolerance: 25.0,
            max_global_shift: 300.0,
            scene_motion_compensation: false,
            foreground_foot_margin_ratio: 0.05,
            privacy_occluder_erosion_radius: 1,
        }
    }
}
#[derive(Clone)]
pub struct Mask {
    pub width: usize,
    pub height: usize,
    pub pixels: Arc<[u8]>,
    pub transform: Transform,
}
impl Mask {
    pub fn valid(&self) -> bool {
        self.width > 0
            && self.height > 0
            && self.width.checked_mul(self.height) == Some(self.pixels.len())
            && self.transform.valid()
    }
}
#[derive(Clone)]
pub struct Detection {
    pub bounds: Rect,
    pub mask: Option<Mask>,
    pub confidence: f32,
    pub foot_y: f32,
}
impl Detection {
    pub fn valid(&self) -> bool {
        self.bounds.valid()
            && self.confidence.is_finite()
            && self.foot_y.is_finite()
            && self.mask.as_ref().is_none_or(Mask::valid)
    }
}
#[repr(u32)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TrackState {
    New = 0,
    Active = 1,
    Occluded = 2,
    Reacquiring = 3,
    Lost = 4,
    Removed = 5,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SelectionClass {
    Selected,
    Unselected,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GroupState {
    ActiveOverlap,
    Reacquiring,
}
#[derive(Clone)]
pub struct Group {
    pub members: Vec<i64>,
    pub started_at_us: i64,
    pub last_overlap_us: i64,
    pub state: GroupState,
    pub reacquire_frames: u32,
}
#[derive(Clone)]
pub struct Track {
    pub id: i64,
    pub observed_bounds: Rect,
    pub observed_mask: Option<Mask>,
    pub predicted_bounds: Rect,
    pub render_mask: Option<Mask>,
    pub confidence: f32,
    pub filter: BoxFilter,
    pub state: TrackState,
    pub lost_frames: u32,
    pub occluded_frames: u32,
    pub reacquire_frames: u32,
    pub frames_since_observation: u32,
    pub occluder_ids: Vec<i64>,
    pub occlusion_motion_bounds: Option<Rect>,
    pub age: u32,
    pub observed_this_frame: bool,
    pub observed_previous_frame: bool,
    pub fresh_motion: Option<[f32; 2]>,
    pub observed_foot_y: f32,
    pub current_foot_y: Option<f32>,
    pub reliable_motion: [f32; 2],
    pub reliable_motion_at_us: i64,
    pub offscreen_dormant: bool,
}
impl Track {
    pub(crate) fn observed(id: i64, d: Detection) -> Self {
        let mut filter = BoxFilter::default();
        filter.init(d.bounds, -1);
        Self {
            id,
            observed_bounds: d.bounds,
            observed_mask: d.mask.clone(),
            predicted_bounds: d.bounds,
            render_mask: d.mask,
            confidence: d.confidence,
            filter,
            state: TrackState::Active,
            lost_frames: 0,
            occluded_frames: 0,
            reacquire_frames: 0,
            frames_since_observation: 0,
            occluder_ids: vec![],
            occlusion_motion_bounds: None,
            age: 1,
            observed_this_frame: true,
            observed_previous_frame: false,
            fresh_motion: None,
            observed_foot_y: d.foot_y,
            current_foot_y: Some(d.foot_y),
            reliable_motion: [0.0; 2],
            reliable_motion_at_us: i64::MIN,
            offscreen_dormant: false,
        }
    }
    pub fn missed_frames(&self) -> u32 {
        match self.state {
            TrackState::Occluded => self.occluded_frames,
            TrackState::Reacquiring => self.reacquire_frames,
            _ => self.lost_frames,
        }
    }
}
#[derive(Clone)]
pub struct ClassEvidence {
    pub selection: SelectionClass,
    pub detection_index: usize,
    pub detection: Detection,
    pub residual_ids: Vec<i64>,
    pub conservative_unknown: bool,
}
#[derive(Clone)]
pub struct MotionEvidence {
    pub id: i64,
    pub detection_index: usize,
    pub detection: Detection,
    pub score: f32,
    pub bbox_iou: f32,
    pub mask_iou: f32,
    pub timestamp_us: i64,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TrackingError {
    InvalidObservation,
    DuplicateIdentity,
    IdentityOverflow,
}
#[derive(Clone)]
pub struct Tracker {
    pub config: TrackingConfig,
    pub tracks: Vec<Track>,
    pub groups: Vec<Group>,
    pub protected_ids: BTreeSet<i64>,
    pub selected_ids: BTreeSet<i64>,
    pub face_selected_ids: BTreeSet<i64>,
    pub class_evidence: Vec<ClassEvidence>,
    pub face_class_evidence: Vec<ClassEvidence>,
    pub motion_evidence: BTreeMap<i64, MotionEvidence>,
    pub uncertain_occluders: BTreeMap<i64, BTreeSet<i64>>,
    pub suppressed_selected_ids: BTreeSet<i64>,
    pub hard_classes: BTreeMap<usize, SelectionClass>,
    pub next_id: i64,
    pub initialized: bool,
    pub offscreen_dormancy: bool,
}
impl Tracker {
    pub fn new(config: TrackingConfig) -> Self {
        Self {
            config,
            tracks: vec![],
            groups: vec![],
            protected_ids: BTreeSet::new(),
            selected_ids: BTreeSet::new(),
            face_selected_ids: BTreeSet::new(),
            class_evidence: vec![],
            face_class_evidence: vec![],
            motion_evidence: BTreeMap::new(),
            uncertain_occluders: BTreeMap::new(),
            suppressed_selected_ids: BTreeSet::new(),
            hard_classes: BTreeMap::new(),
            next_id: 0,
            initialized: false,
            offscreen_dormancy: false,
        }
    }
    pub fn selection_class(&self, id: i64) -> SelectionClass {
        if self.selected_ids.contains(&id) {
            SelectionClass::Selected
        } else {
            SelectionClass::Unselected
        }
    }
    pub fn clear_frame_evidence(&mut self) {
        self.class_evidence.clear();
        self.face_class_evidence.clear();
        self.motion_evidence.clear();
        self.uncertain_occluders.clear();
        self.suppressed_selected_ids.clear();
        self.hard_classes.clear();
    }
    pub fn initialize(
        &mut self,
        detections: Vec<Detection>,
        assigned_ids: &[i64],
    ) -> Result<(), TrackingError> {
        if detections.iter().any(|d| !d.valid()) {
            return Err(TrackingError::InvalidObservation);
        }
        let mut next = 0_i64;
        let mut used = BTreeSet::new();
        let mut tracks = Vec::with_capacity(detections.len());
        for (index, detection) in detections.into_iter().enumerate() {
            let id = assigned_ids.get(index).copied().unwrap_or(next);
            if !used.insert(id) {
                return Err(TrackingError::DuplicateIdentity);
            }
            next = next.max(id.checked_add(1).ok_or(TrackingError::IdentityOverflow)?);
            tracks.push(Track::observed(id, detection));
        }
        self.tracks = tracks;
        self.groups.clear();
        self.clear_frame_evidence();
        self.next_id = next;
        self.hard_classes = self
            .tracks
            .iter()
            .enumerate()
            .map(|(i, t)| (i, self.selection_class(t.id)))
            .collect();
        self.initialized = true;
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn detection() -> Detection {
        Detection {
            bounds: Rect {
                left: 10.0,
                top: 20.0,
                right: 100.0,
                bottom: 220.0,
            },
            mask: None,
            confidence: 0.9,
            foot_y: 220.0,
        }
    }
    #[test]
    fn assigned_identity_and_selection_are_distinct_and_order_is_preserved() {
        let mut tracker = Tracker::new(TrackingConfig::default());
        tracker.protected_ids.extend([5, 2]);
        tracker.selected_ids.insert(2);
        tracker
            .initialize(vec![detection(), detection(), detection()], &[5, 2])
            .unwrap();
        assert_eq!(
            tracker.tracks.iter().map(|t| t.id).collect::<Vec<_>>(),
            [5, 2, 6]
        );
        assert_eq!(tracker.hard_classes[&0], SelectionClass::Unselected);
        assert_eq!(tracker.hard_classes[&1], SelectionClass::Selected);
        assert!(
            tracker
                .tracks
                .iter()
                .all(|t| t.observed_this_frame && t.age == 1 && t.missed_frames() == 0)
        );
    }
    #[test]
    fn rejected_observation_or_alias_does_not_partially_replace_live_state() {
        let mut tracker = Tracker::new(TrackingConfig::default());
        tracker.initialize(vec![detection()], &[7]).unwrap();
        assert_eq!(
            tracker.initialize(vec![detection(), detection()], &[3, 3]),
            Err(TrackingError::DuplicateIdentity)
        );
        let mut invalid = detection();
        invalid.bounds.left = f32::NAN;
        assert_eq!(
            tracker.initialize(vec![invalid], &[1]),
            Err(TrackingError::InvalidObservation)
        );
        assert_eq!(tracker.tracks[0].id, 7);
        assert_eq!(tracker.next_id, 8);
    }
}
