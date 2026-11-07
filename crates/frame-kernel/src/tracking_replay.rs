//! Source-time replay planning. A requested frame may carry a known identity
//! only after replay from the observed analysis anchor under the same policy.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ReplayPlan {
    pub required: u32,
    pub reset: u32,
    pub start_us: i64,
    pub step_us: i64,
    pub regular_count: u32,
    pub append_target: u32,
    pub target_us: i64,
}
impl ReplayPlan {
    pub fn len(&self) -> usize {
        self.regular_count as usize + self.append_target as usize
    }
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
    pub fn time(&self, index: usize) -> Option<i64> {
        if index < self.regular_count as usize {
            self.step_us
                .checked_mul(index as i64)?
                .checked_add(self.start_us)
        } else if self.append_target != 0 && index == self.regular_count as usize {
            Some(self.target_us)
        } else {
            None
        }
    }
}
/// Flags: bit 0 explicit camera subject; bit 1 named privacy targets. Uniform
/// scene protection without a hero does not require a historical identity.
/// An unknown anchor or an earlier-than-anchor request fails closed; it cannot
/// be repaired by assigning a fresh person the old selection's numeric ID.
pub fn plan(
    scope: u32,
    flags: u32,
    anchor: i64,
    last: i64,
    target: i64,
    step: i64,
) -> Option<ReplayPlan> {
    if scope > 7 || flags > 3 || target < 0 || step <= 0 {
        return None;
    }
    let required = flags & 1 != 0 || (flags & 2 != 0 && scope == 0);
    if !required {
        return Some(ReplayPlan {
            target_us: target,
            step_us: step,
            ..Default::default()
        });
    }
    if anchor < 0 || target < anchor {
        return None;
    }
    let reset = last < anchor || target < last;
    let start = if reset {
        anchor
    } else {
        let frame = (last.checked_sub(anchor)? / step).checked_add(1)?;
        anchor.checked_add(frame.checked_mul(step)?)?
    };
    let count = if start <= target {
        ((target - start) / step).checked_add(1)?
    } else {
        0
    };
    if count > 1_000_000 {
        return None;
    }
    let reached = if count > 0 {
        start.checked_add((count - 1).checked_mul(step)?)?
    } else {
        last
    };
    let append = u32::from(reached != target);
    if count + append as i64 > 1_000_000 {
        return None;
    }
    Some(ReplayPlan {
        required: 1,
        reset: u32::from(reset),
        start_us: start,
        step_us: step,
        regular_count: count as u32,
        append_target: append,
        target_us: target,
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn a_late_subject_preview_replays_all_real_source_steps() {
        let p = plan(4, 3, 0, -1, 23_200_000, 40_000).unwrap();
        assert_eq!((p.required, p.reset, p.len()), (1, 1, 581));
        assert_eq!(p.time(0), Some(0));
        assert_eq!(p.time(580), Some(23_200_000));
        assert_eq!(p.time(581), None);
    }
    #[test]
    fn a_non_grid_preview_resumes_from_the_anchor_grid_and_rewind_resets() {
        let p = plan(0, 2, 1_000_000, -1, 1_780_000, 40_000).unwrap();
        assert_eq!(p.len(), 21);
        assert_eq!(p.time(20), Some(1_780_000));
        let q = plan(0, 2, 1_000_000, 1_780_000, 2_560_000, 40_000).unwrap();
        assert_eq!(
            (q.reset, q.time(0), q.time(q.len() - 1)),
            (0, Some(1_800_000), Some(2_560_000))
        );
        let rewind = plan(0, 2, 1_000_000, 2_560_000, 1_780_000, 40_000).unwrap();
        assert_eq!(rewind, p);
    }
    #[test]
    fn identity_independent_coverage_does_not_invent_or_require_an_anchor() {
        let p = plan(2, 2, -1, -1, 13_720_000, 40_000).unwrap();
        assert_eq!(p.required, 0);
        assert!(p.is_empty());
        assert!(plan(0, 2, -1, -1, 13_720_000, 40_000).is_none());
        assert!(plan(0, 1, 1_000_000, -1, 960_000, 40_000).is_none());
    }
    #[test]
    fn repeated_time_has_no_extra_observation_and_overflow_is_rejected() {
        let p = plan(0, 2, 0, 780_000, 780_000, 40_000).unwrap();
        assert!(p.is_empty());
        assert_eq!(p.required, 1);
        assert!(plan(0, 1, 0, -1, i64::MAX, 1).is_none());
        assert!(plan(0, 1, 0, i64::MAX, i64::MAX, i64::MAX).is_none());
    }
}
