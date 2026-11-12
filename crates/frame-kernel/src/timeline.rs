//! Source-time correspondence. Playback speed is intentionally absent.
//! Integer samples and continuous edit ends use distinct constraints.

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AffineMap {
    pub scale: f64,
    pub offset: i64,
}

fn integer(value: f64) -> Option<i64> {
    // Keep integer conversions exact and reserve MIN for the C ABI error value.
    (value.is_finite() && value.abs() <= 9_007_199_254_740_991.0).then_some(value as i64)
}

pub fn round_time(value: f64) -> Option<i64> {
    integer((value + 0.5).floor())
}

impl AffineMap {
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

#[cfg(test)]
mod tests {
    use super::*;
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
