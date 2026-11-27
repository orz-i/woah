//! Experimental coverage-only probe for a retained selected identity whose
//! canonical tracker has no current committed observation. A successful
//! probe is NOT an identity assignment, exemption, or permission to update
//! the direct-observation clock. A caller must rerun the current detector and
//! current mask checks for every result, and stop on uncertainty.
use crate::{
    face_geometry::{HeadMaskSpec, valid_seed},
    face_head_topology::{HeadSticker, sticker},
    face_observations::mask_support,
    face_pixel_motion::{GrayView, estimate},
    geometry::{Rect, overlap_iou},
    temporal_region::Ellipse,
};

// Match the existing native face reacquisition hard age. This is NOT the
// 150 ms projection lease, which remains unchanged.
pub const MAX_PROBE_AGE_US: i64 = 800_000;
const CURRENT_GAP_US: i64 = 150_000;
const MIN_PREDICTED_IOU: f32 = 0.50;
const MIN_ROOT_IOU: f32 = 0.40;
const RIVAL_IOU: f32 = 0.35;
const RIVAL_MARGIN: f32 = 0.12;

#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct RecoveryAnchor {
    /// Last canonically owned body and directly detected face.
    pub root_body: Rect,
    pub root_face: Ellipse,
    /// Current prediction of that same retained selected track.
    pub predicted_body: Rect,
    pub age_us: i64,
}

/// The immediately preceding successful ROOT-ONLY recovery (not a projected
/// face, canonical identity update, or another continuation). The caller
/// clears this evidence on any gap, rejection, seek or identity change.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct RecoveryPrevious {
    pub body: Rect,
    pub face: Ellipse,
    pub root_age_us: i64,
}

pub struct CurrentPixels<'a> {
    pub previous: RecoveryPrevious,
    pub previous_gray: &'a [u8],
    pub previous_view: GrayView,
    pub current_gray: &'a [u8],
    pub current_view: GrayView,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RecoveryError {
    Invalid,
    NoEvidence,
}

fn anchor_valid(a: RecoveryAnchor) -> bool {
    a.root_body.valid()
        && a.predicted_body.valid()
        && valid_seed(a.root_face)
        && a.age_us >= 0
        && a.root_face.center_x >= a.root_body.left
        && a.root_face.center_x <= a.root_body.right
        && a.root_face.center_y >= a.root_body.top
        && a.root_face.center_y <= a.root_body.top + (a.root_body.bottom - a.root_body.top) * 0.60
}

fn in_head_band(x: f32, y: f32, b: Rect) -> bool {
    x >= b.left && x <= b.right && y >= b.top && y <= b.top + (b.bottom - b.top) * 0.60
}

/// Select at most one strong body candidate before running local face
/// inference. A second plausible raw observation blocks recovery, even if
/// that observation's detector would miss its face. All competing track
/// boxes (including retained unobserved tracks) are negative evidence only.
fn probe_geometry(
    anchor: RecoveryAnchor,
    bodies: &[Rect],
    foreign: &[Rect],
) -> Result<usize, RecoveryError> {
    if !anchor_valid(anchor)
        || bodies.len() > 4096
        || foreign.len() > 4096
        || bodies.iter().chain(foreign).any(|b| !b.valid())
    {
        return Err(RecoveryError::Invalid);
    }
    let mut plausible = bodies.iter().enumerate().filter_map(|(i, &b)| {
        let pred = overlap_iou(anchor.predicted_body, b);
        let root = overlap_iou(anchor.root_body, b);
        (pred >= RIVAL_IOU && root >= RIVAL_IOU).then_some((i, b, pred, root))
    });
    let Some((index, body, pred, root)) = plausible.next() else {
        return Err(RecoveryError::NoEvidence);
    };
    if plausible.next().is_some()
        || pred < MIN_PREDICTED_IOU
        || root < MIN_ROOT_IOU
        || foreign.iter().any(|&f| {
            let score = overlap_iou(f, body);
            score >= 0.50 || score + RIVAL_MARGIN >= pred.min(root)
        })
    {
        return Err(RecoveryError::NoEvidence);
    }
    Ok(index)
}

pub fn probe(
    anchor: RecoveryAnchor,
    bodies: &[Rect],
    foreign: &[Rect],
) -> Result<usize, RecoveryError> {
    let result = probe_geometry(anchor, bodies, foreign)?;
    if anchor.age_us == 0 || anchor.age_us > MAX_PROBE_AGE_US {
        return Err(RecoveryError::NoEvidence);
    }
    Ok(result)
}

fn close_face(
    reference: Ellipse,
    current: Ellipse,
    distance: f32,
    min_scale: f32,
    max_scale: f32,
) -> bool {
    valid_seed(reference)
        && valid_seed(current)
        && ((current.center_x - reference.center_x) / reference.radius_x)
            .hypot((current.center_y - reference.center_y) / reference.radius_y)
            <= distance
        && (min_scale..=max_scale).contains(&(current.radius_x / reference.radius_x))
        && (min_scale..=max_scale).contains(&(current.radius_y / reference.radius_y))
}

/// A single fresh-current continuation after the root-only hard age.
/// No result from this lane is eligible to start another continuation.
/// The total age can never exceed 800ms + one 150ms current evidence gap.
pub fn probe_continuation(
    anchor: RecoveryAnchor,
    previous: RecoveryPrevious,
    bodies: &[Rect],
    foreign: &[Rect],
) -> Result<usize, RecoveryError> {
    let index = probe_geometry(anchor, bodies, foreign)?;
    if !previous.body.valid() || !valid_seed(previous.face) || previous.root_age_us < 0 {
        return Err(RecoveryError::Invalid);
    }
    let gap = anchor.age_us.saturating_sub(previous.root_age_us);
    if anchor.age_us <= MAX_PROBE_AGE_US
        || !(1..=MAX_PROBE_AGE_US).contains(&previous.root_age_us)
        || !(1..=CURRENT_GAP_US).contains(&gap)
        || !close_face(anchor.root_face, previous.face, 1.0, 0.65, 1.55)
        || !in_head_band(
            previous.face.center_x,
            previous.face.center_y,
            previous.body,
        )
        || overlap_iou(previous.body, bodies[index]) < 0.65
    {
        return Err(RecoveryError::NoEvidence);
    }
    Ok(index)
}

/// Finish only from a fresh detector observation inside the chosen CURRENT
/// raw mask. Repeat the entire selection check so a stale/different index
/// cannot be substituted. The result can cover a current face in a uniquely
/// supported local vicinity, but never establishes canonical identity.
fn finish_current(
    anchor: RecoveryAnchor,
    bodies: &[Rect],
    foreign: &[Rect],
    index: usize,
    pixels: &[u8],
    spec: HeadMaskSpec,
    measured: Ellipse,
) -> Result<HeadSticker, RecoveryError> {
    if index >= bodies.len()
        || !spec.valid(pixels)
        || spec.person != bodies[index]
        || !valid_seed(measured)
    {
        return Err(RecoveryError::Invalid);
    }
    if probe_geometry(anchor, bodies, foreign)? != index {
        return Err(RecoveryError::NoEvidence);
    }
    // Independent current face measurement; bounded displacement/size is
    // a rejection test only, never a cap on genuine measured coverage.
    if !close_face(anchor.root_face, measured, 1.0, 0.65, 1.55)
        || !in_head_band(measured.center_x, measured.center_y, spec.person)
        || !in_head_band(measured.center_x, measured.center_y, anchor.predicted_body)
        || foreign
            .iter()
            .any(|&f| in_head_band(measured.center_x, measured.center_y, f))
        || bodies
            .iter()
            .enumerate()
            .any(|(i, &b)| i != index && in_head_band(measured.center_x, measured.center_y, b))
        || !mask_support(pixels, spec, measured).is_some_and(|s| s[0] >= 4 && s[1] >= 12)
    {
        return Err(RecoveryError::NoEvidence);
    }
    // Use current measurement, not a historical predicted ellipse, as the
    // sticker coverage seed. No per-identity temporal state is touched.
    sticker(pixels, spec, measured, measured, 0).ok_or(RecoveryError::Invalid)
}

pub fn finish(
    anchor: RecoveryAnchor,
    bodies: &[Rect],
    foreign: &[Rect],
    index: usize,
    pixels: &[u8],
    spec: HeadMaskSpec,
    measured: Ellipse,
) -> Result<HeadSticker, RecoveryError> {
    if probe(anchor, bodies, foreign)? != index {
        return Err(RecoveryError::NoEvidence);
    }
    finish_current(anchor, bodies, foreign, index, pixels, spec, measured)
}

#[allow(clippy::too_many_arguments)] // Explicit immutable evidence, no mutable session.
pub fn finish_continuation(
    anchor: RecoveryAnchor,
    current: CurrentPixels<'_>,
    bodies: &[Rect],
    foreign: &[Rect],
    index: usize,
    pixels: &[u8],
    spec: HeadMaskSpec,
    measured: Ellipse,
) -> Result<HeadSticker, RecoveryError> {
    if probe_continuation(anchor, current.previous, bodies, foreign)? != index {
        return Err(RecoveryError::NoEvidence);
    }
    let result = finish_current(anchor, bodies, foreign, index, pixels, spec, measured)?;
    let competitors = foreign
        .iter()
        .copied()
        .chain(
            bodies
                .iter()
                .enumerate()
                .filter_map(|(i, &b)| (i != index).then_some(b)),
        )
        .collect::<Vec<_>>();
    let motion = estimate(
        current.previous_gray,
        current.previous_view,
        current.current_gray,
        current.current_view,
        current.previous.face,
        anchor.age_us - current.previous.root_age_us,
        &competitors,
    )
    .map_err(|e| match e {
        crate::face_pixel_motion::MotionError::Invalid => RecoveryError::Invalid,
        crate::face_pixel_motion::MotionError::NoEvidence => RecoveryError::NoEvidence,
    })?;
    let prediction = Ellipse {
        center_x: current.previous.face.center_x + motion.dx,
        center_y: current.previous.face.center_y + motion.dy,
        ..current.previous.face
    };
    // The independent current detector must agree with a unique current
    // pixel peak. Pixel evidence cannot invent a face or adjust its size.
    if !close_face(prediction, measured, 0.35, 0.80, 1.25) {
        return Err(RecoveryError::NoEvidence);
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geometry::Transform;

    fn body() -> Rect {
        Rect {
            left: 40.0,
            top: 24.0,
            right: 120.0,
            bottom: 240.0,
        }
    }
    fn root() -> Ellipse {
        Ellipse {
            center_x: 80.0,
            center_y: 52.0,
            radius_x: 16.0,
            radius_y: 20.0,
        }
    }
    fn anchor() -> RecoveryAnchor {
        RecoveryAnchor {
            root_body: body(),
            root_face: root(),
            predicted_body: body(),
            age_us: 400_000,
        }
    }
    fn spec(b: Rect) -> HeadMaskSpec {
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
            person: b,
        }
    }
    fn mask() -> Vec<u8> {
        let mut p = vec![0; 4096];
        for y in 6..20 {
            p[y * 64 + 15..y * 64 + 25].fill(255);
        }
        for y in 20..60 {
            p[y * 64 + 10..y * 64 + 30].fill(255);
        }
        p
    }
    #[test]
    fn a_fresh_local_face_can_supply_coverage_without_renewing_identity() {
        let p = mask();
        let a = anchor();
        let f = Ellipse {
            center_y: 50.0,
            ..root()
        };
        assert_eq!(probe(a, &[body()], &[]), Ok(0));
        let result = finish(a, &[body()], &[], 0, &p, spec(body()), f).unwrap();
        assert!(result.square.radius_x >= f.radius_y);
        assert!(result.bounds.top <= f.center_y - f.radius_y);
        assert!(result.bounds.bottom >= f.center_y + f.radius_y);
        assert_eq!(a.age_us, 400_000);
    }
    #[test]
    fn only_one_bounded_fresh_continuation_requires_detector_pixel_agreement() {
        let a = RecoveryAnchor {
            age_us: MAX_PROBE_AGE_US + 66_667,
            ..anchor()
        };
        let prev = RecoveryPrevious {
            body: body(),
            face: root(),
            root_age_us: MAX_PROBE_AGE_US,
        };
        assert_eq!(probe(a, &[body()], &[]), Err(RecoveryError::NoEvidence));
        assert_eq!(probe_continuation(a, prev, &[body()], &[]), Ok(0));
        assert_eq!(
            probe_continuation(
                RecoveryAnchor {
                    age_us: MAX_PROBE_AGE_US + CURRENT_GAP_US + 1,
                    ..a
                },
                prev,
                &[body()],
                &[]
            ),
            Err(RecoveryError::NoEvidence)
        );
        assert_eq!(
            probe_continuation(
                RecoveryAnchor {
                    age_us: a.age_us + 30_000,
                    ..a
                },
                RecoveryPrevious {
                    root_age_us: a.age_us,
                    ..prev
                },
                &[body()],
                &[]
            ),
            Err(RecoveryError::NoEvidence)
        );
        let mut state = 0x9312_abcd_u32;
        let gray: Vec<u8> = (0..256 * 256)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 17;
                state ^= state << 5;
                (state >> 24) as u8
            })
            .collect();
        let mut moved = vec![128; 256 * 256];
        for y in 0..254 {
            moved[y * 256 + 1..y * 256 + 256]
                .copy_from_slice(&gray[(y + 2) * 256..(y + 2) * 256 + 255]);
        }
        let view = GrayView {
            width: 256,
            height: 256,
            stride: 256,
            source: Rect {
                left: 0.0,
                top: 0.0,
                right: 256.0,
                bottom: 256.0,
            },
        };
        let mask = mask();
        let current = || CurrentPixels {
            previous: prev,
            previous_gray: &gray,
            previous_view: view,
            current_gray: &moved,
            current_view: view,
        };
        let face = Ellipse {
            center_x: 81.0,
            center_y: 50.0,
            ..root()
        };
        assert!(
            finish_continuation(a, current(), &[body()], &[], 0, &mask, spec(body()), face).is_ok()
        );
        // Still within the immutable root gate, but not the pixel peak.
        let mismatched = Ellipse {
            center_x: 91.0,
            ..face
        };
        assert_eq!(
            finish_continuation(
                a,
                current(),
                &[body()],
                &[],
                0,
                &mask,
                spec(body()),
                mismatched
            ),
            Err(RecoveryError::NoEvidence)
        );
        let blank = vec![128; 256 * 256];
        let no_texture = CurrentPixels {
            current_gray: &blank,
            ..current()
        };
        assert_eq!(
            finish_continuation(a, no_texture, &[body()], &[], 0, &mask, spec(body()), face),
            Err(RecoveryError::NoEvidence)
        );
    }

    #[test]
    fn rivals_foreign_owner_and_expired_anchor_fail_closed() {
        let a = anchor();
        let other = Rect {
            left: 44.0,
            right: 124.0,
            ..body()
        };
        assert_eq!(
            probe(a, &[body(), other], &[]),
            Err(RecoveryError::NoEvidence)
        );
        assert_eq!(
            probe(a, &[body()], &[other]),
            Err(RecoveryError::NoEvidence)
        );
        assert_eq!(
            probe(
                RecoveryAnchor {
                    age_us: MAX_PROBE_AGE_US + 1,
                    ..a
                },
                &[body()],
                &[]
            ),
            Err(RecoveryError::NoEvidence)
        );
        assert_eq!(
            probe(
                RecoveryAnchor {
                    age_us: MAX_PROBE_AGE_US,
                    ..a
                },
                &[body()],
                &[]
            ),
            Ok(0)
        );
        let p = mask();
        let blank = vec![0; p.len()];
        assert_eq!(
            finish(a, &[body()], &[], 0, &blank, spec(body()), root()),
            Err(RecoveryError::NoEvidence)
        );
        let far = Ellipse {
            center_x: 110.0,
            ..root()
        };
        assert_eq!(
            finish(a, &[body()], &[], 0, &p, spec(body()), far),
            Err(RecoveryError::NoEvidence)
        );
        let neighbor = Rect {
            left: 76.0,
            top: 36.0,
            right: 145.0,
            bottom: 100.0,
        };
        assert_eq!(
            finish(a, &[body(), neighbor], &[], 0, &p, spec(body()), root()),
            Err(RecoveryError::NoEvidence)
        );
    }
}
