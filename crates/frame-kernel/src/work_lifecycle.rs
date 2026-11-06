//! Completion barrier for native workers that own external resources.
//! Events: 0 request cancellation, 1 published success, 2 failure,
//! 3 worker acknowledged cancellation, 4 resources released.
//! States: 0 running, 1 cancellation requested, 2/3/4 pending success/failure/
//! cancellation, 5/6/7 terminal success/failure/cancellation.
//! Publication seals success; cancellation before publication wins over teardown
//! errors. Native adapters own files, locks and cleanup, never state decisions.

pub fn transition(state: u32, event: u32) -> Option<u32> {
    if state > 7 || event > 4 {
        return None;
    }
    Some(match (state, event) {
        (5..=7, _) => state,
        (0, 0) => 1,
        (0, 1) => 2,
        (0, 2) => 3,
        (0, 3) => 4,
        (0, 4) => 6, // A worker that exits without an outcome fails closed.
        (1, 0) => 1,
        (1, 1..=3) => 4,
        (1, 4) => 7,
        (2, 4) => 5,
        (3, 4) => 6,
        (4, 4) => 7,
        _ => state,
    })
}

/// Replacement may queue behind a cancelling/finished worker, never run alongside
/// another active owner. Native owners still serialize actual resource admission.
pub fn can_replace(state: u32) -> Option<bool> {
    (state <= 7).then_some(matches!(state, 1 | 4..=7))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cancel_does_not_publish_a_terminal_before_cleanup() {
        let requested = transition(0, 0).unwrap();
        assert_eq!(requested, 1);
        let acknowledged = transition(requested, 3).unwrap();
        assert_eq!(acknowledged, 4);
        assert_eq!(transition(acknowledged, 4), Some(7));
        assert_eq!(transition(requested, 4), Some(7)); // cancelled before entry
    }
    #[test]
    fn published_success_is_sealed_but_still_waits_for_cleanup() {
        let published = transition(0, 1).unwrap();
        assert_eq!(published, 2);
        assert_eq!(transition(published, 0), Some(2));
        assert_eq!(transition(published, 4), Some(5));
    }
    #[test]
    fn cancellation_wins_over_teardown_errors() {
        assert_eq!(transition(transition(0, 0).unwrap(), 2), Some(4));
        assert_eq!(transition(transition(0, 2).unwrap(), 4), Some(6));
        assert_eq!(transition(0, 4), Some(6));
    }
    #[test]
    fn terminal_results_are_immutable_and_invalid_inputs_rejected() {
        for state in 5..=7 {
            for event in 0..=4 {
                assert_eq!(transition(state, event), Some(state));
            }
        }
        assert_eq!(transition(8, 0), None);
        assert_eq!(transition(0, 5), None);
        assert_eq!(can_replace(0), Some(false));
        assert_eq!(can_replace(1), Some(true));
        assert_eq!(can_replace(2), Some(false));
        assert_eq!(can_replace(8), None);
        assert_eq!(crate::frame_kernel_work_transition(99, 0), -1);
    }
}
