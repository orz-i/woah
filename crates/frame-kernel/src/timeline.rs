//! Source-time correspondence. Playback speed is intentionally absent.
//! Integer samples and continuous edit ends use distinct constraints.

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AffineMap {
    pub scale: f64,
    pub offset: i64,
}

/// Native continuous edits must retain sub-microsecond affine endpoints.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct EditRange {
    pub start: i64,
    pub duration: i64,
    pub timescale: u32,
}

fn integer(value: f64) -> Option<i64> {
    // Keep integer conversions exact and reserve MIN for the C ABI error value.
    (value.is_finite() && value.abs() <= 9_007_199_254_740_991.0).then_some(value as i64)
}

pub fn round_time(value: f64) -> Option<i64> {
    integer((value + 0.5).floor())
}

impl AffineMap {
    pub fn edit_range(self, reference: Interval) -> Option<EditRange> {
        if !self.valid() || !reference.valid() || reference.end <= reference.start {
            return None;
        }
        // Preserve common exact rates (4/5, 5/4, etc.) within CMTime's signed
        // 32-bit time base. Do not round each endpoint to a microsecond first.
        for denominator in 1..=2147i64 {
            let numerator = (self.scale * denominator as f64).round();
            if (numerator / denominator as f64 - self.scale).abs() > 1e-12 {
                continue;
            }
            let numerator = integer(numerator)?;
            let start = i128::from(reference.start) * i128::from(numerator)
                + i128::from(self.offset) * i128::from(denominator);
            let duration = i128::from(reference.end - reference.start) * i128::from(numerator);
            return Some(EditRange {
                start: i64::try_from(start).ok()?,
                duration: i64::try_from(duration).ok()?,
                timescale: (denominator * 1_000_000) as u32,
            });
        }
        // An arbitrary inferred Double may not have a representable exact
        // rational edit. Quantize only its final endpoints to nanoseconds;
        // keep the original scale for individual sample lookups.
        let mapped = |v: i64| round_time((v as f64 * self.scale + self.offset as f64) * 1000.0);
        let start = mapped(reference.start)?;
        let duration = mapped(reference.end)?.checked_sub(start)?;
        (duration > 0).then_some(EditRange {
            start,
            duration,
            timescale: 1_000_000_000,
        })
    }
    pub fn valid(self) -> bool {
        self.scale.is_finite() && self.scale > 0.0 && self.offset.unsigned_abs() < (1u64 << 53)
    }

    pub fn map(self, value: i64) -> Option<i64> {
        self.map_ticks(value, 1_000_000)
    }

    /// Convert native source ticks only after applying the music correspondence.
    pub fn map_ticks(self, value: i64, timescale: u32) -> Option<i64> {
        if !self.valid() || timescale == 0 {
            return None;
        }
        round_time(value as f64 * (1_000_000.0 / f64::from(timescale)) * self.scale)?
            .checked_add(self.offset)
    }

    pub fn reference(self, target: i64) -> Option<i64> {
        if !self.valid() {
            return None;
        }
        round_time(target.checked_sub(self.offset)? as f64 / self.scale)
    }

    pub fn inverse(self) -> Option<Self> {
        if !self.valid() {
            return None;
        }
        let value = Self {
            scale: 1.0 / self.scale,
            offset: round_time(-(self.offset as f64) / self.scale)?,
        };
        value.valid().then_some(value)
    }

    pub fn anchors(r1: i64, t1: i64, r2: i64, t2: i64) -> Option<Self> {
        let rd = r2.checked_sub(r1)?;
        let td = t2.checked_sub(t1)?;
        if rd <= 0 || td <= 0 {
            return None;
        }
        let scale = td as f64 / rd as f64;
        let value = Self {
            scale,
            offset: round_time(t1 as f64 - r1 as f64 * scale)?,
        };
        value.valid().then_some(value)
    }

    pub fn overlap(self, reference: Interval, target: Interval) -> Option<Interval> {
        if !self.valid() || !reference.valid() || !target.valid() {
            return None;
        }
        let start = reference.start.max(integer(
            (target.start.checked_sub(self.offset)? as f64 / self.scale).ceil(),
        )?);
        let sample_end = integer(
            (target.end.checked_sub(1)?.checked_sub(self.offset)? as f64 / self.scale).floor(),
        )?
        .checked_add(1)?;
        let edit_end = integer((target.end.checked_sub(self.offset)? as f64 / self.scale).floor())?;
        let end = reference.end.min(sample_end).min(edit_end);
        (end > start && self.map(start)? >= target.start && self.map(end)? <= target.end)
            .then_some(Interval { start, end })
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Interval {
    pub start: i64,
    pub end: i64,
}

impl Interval {
    pub fn valid(self) -> bool {
        self.start >= 0 && self.end >= self.start
    }
}

/// Sorted, unique source PTS; mode 0 displayed, 1 next, 2 previous displayed.
pub fn frame_index(pts: &[i64], time: i64, range: Interval, mode: u32) -> Option<usize> {
    if !range.valid() {
        return None;
    }
    let upper = pts.partition_point(|&p| p <= time);
    let index = match mode {
        0 => upper.checked_sub(1)?,
        1 => upper,
        2 => upper.checked_sub(2)?,
        _ => return None,
    };
    let &value = pts.get(index)?;
    (value >= range.start && value < range.end).then_some(index)
}

/// A decoder may supply the previous interval lazily; indexed sources supply it
/// immediately. Edge stepping lands on the clipped visible first/last frame.
pub fn frame_step(
    frame: Interval,
    range: Interval,
    forward: bool,
    previous: Option<Interval>,
) -> Option<(i64, bool)> {
    if !frame.valid()
        || !range.valid()
        || frame.end <= frame.start
        || frame.end <= range.start
        || frame.start >= range.end
    {
        return None;
    }
    if forward {
        return Some((
            if frame.end < range.end {
                frame.end
            } else {
                frame.start.max(range.start)
            },
            false,
        ));
    }
    if frame.start <= range.start {
        return Some((range.start, false));
    }
    let probe = frame.start - 1;
    let Some(before) = previous else {
        return Some((probe, true));
    };
    if !before.valid() || before.start >= frame.start || probe < before.start || probe >= before.end
    {
        return None;
    }
    Some((range.start.max(before.start), false))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn continuous_affine_trim_does_not_select_the_previous_boundary_frame() {
        let map = AffineMap {
            scale: 0.8,
            offset: 1_200_000,
        };
        let reference = Interval {
            start: 21_765_433,
            end: 23_000_000,
        };
        let edit = map.edit_range(reference).unwrap();
        assert_eq!(
            edit,
            EditRange {
                start: 93_061_732,
                duration: 4_938_268,
                timescale: 5_000_000
            }
        );
        // 22.5s reference is exactly target frame 576 (19.2s), not frame 575.
        assert_eq!(edit.start + 734_567 * 4, 19_200_000 * 5);
        let rounded_start = map.map(reference.start).unwrap();
        let rounded_duration = map.map(reference.end).unwrap() - rounded_start;
        assert!(
            (rounded_start as f64 + 734_567.0 * rounded_duration as f64 / 1_234_567.0)
                < 19_200_000.0
        );
    }

    #[test]
    fn arbitrary_edit_rate_keeps_nanosecond_endpoints_and_rejects_invalid_ranges() {
        let map = AffineMap {
            scale: 0.8006440297488754,
            offset: 1_199_144,
        };
        let edit = map
            .edit_range(Interval {
                start: 123456,
                end: 4987654,
            })
            .unwrap();
        assert_eq!(edit.timescale, 1_000_000_000);
        for (source, actual) in [(123456, edit.start), (4987654, edit.start + edit.duration)] {
            let exact = (source as f64 * map.scale + map.offset as f64) * 1000.0;
            assert!((actual as f64 - exact).abs() <= 0.5);
        }
        assert!(map.edit_range(Interval { start: 10, end: 10 }).is_none());
        assert!(
            AffineMap {
                scale: f64::NAN,
                offset: 0
            }
            .edit_range(Interval { start: 0, end: 1 })
            .is_none()
        );
    }
    #[test]
    fn stepping_clipped_first_and_last_frames_never_leaves_range() {
        let range = Interval {
            start: 15000,
            end: 110000,
        };
        assert_eq!(
            frame_step(
                Interval {
                    start: 0,
                    end: 33333
                },
                range,
                false,
                None
            ),
            Some((15000, false))
        );
        assert_eq!(
            frame_step(
                Interval {
                    start: 100000,
                    end: 150000
                },
                range,
                true,
                None
            ),
            Some((100000, false))
        );
        assert_eq!(
            frame_step(
                Interval {
                    start: 100000,
                    end: 150000
                },
                range,
                false,
                None
            ),
            Some((99999, true))
        );
        assert_eq!(
            frame_step(
                Interval {
                    start: 100000,
                    end: 150000
                },
                range,
                false,
                Some(Interval {
                    start: 33333,
                    end: 100000
                })
            ),
            Some((33333, false))
        );
        assert_eq!(
            frame_step(
                Interval {
                    start: 100000,
                    end: 150000
                },
                range,
                false,
                Some(Interval {
                    start: 0,
                    end: 33333
                })
            ),
            None
        );
    }
    #[test]
    fn half_rounds_before_offset_and_ticks_keep_precision() {
        let map = AffineMap {
            scale: 0.5,
            offset: -2,
        };
        assert_eq!(map.map(1), Some(-1));
        assert_eq!(map.map(-1), Some(-2));
        let map = AffineMap {
            scale: 0.8,
            offset: 1_200_000,
        };
        assert_eq!(map.map_ticks(11, 6), Some(2_666_667));
        assert_eq!(map.map(1_833_333), Some(2_666_666));
    }
    #[test]
    fn continuous_end_cannot_exceed_source() {
        let r = Interval { start: 0, end: 20 };
        let t = Interval { start: 0, end: 11 };
        assert_eq!(
            AffineMap {
                scale: 2.0,
                offset: 0
            }
            .overlap(r, t),
            Some(Interval { start: 0, end: 5 })
        );
        assert_eq!(
            AffineMap {
                scale: f64::NAN,
                offset: 0
            }
            .overlap(r, t),
            None
        );
        assert_eq!(
            AffineMap {
                scale: 1.0,
                offset: i64::MIN
            }
            .map(0),
            None
        );
    }
    #[test]
    fn navigation_uses_displayed_vfr_intervals() {
        let pts = [0, 33_333, 100_000, 116_667];
        let range = Interval {
            start: 0,
            end: 116_667,
        };
        assert_eq!(frame_index(&pts, 80_000, range, 0), Some(1));
        assert_eq!(frame_index(&pts, 80_000, range, 1), Some(2));
        assert_eq!(frame_index(&pts, 80_000, range, 2), Some(0));
        assert_eq!(frame_index(&pts, 100_000, range, 1), None);
        assert_eq!(frame_index(&pts, -1, range, 0), None);
    }
}
