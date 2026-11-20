//! Candidate verification. A search miss is never an observation of agreement.
use super::{Audition, PeakTimes, Probe, Seed, check, musical_bounds, round};
use crate::audio_features::{AudioError, Features};

const CELL: i64 = 200_000;
const MAX_LAG: i32 = 80;
const MIN_AUDITION: i64 = 650_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Decision {
    Supported,
    Conflict,
    Insufficient,
}

#[derive(Clone, Copy, Debug)]
pub(super) struct Evidence {
    pub decision: Decision,
    pub support_us: i64,
    pub reference_span: (i64, i64),
    pub quality: f64,
    pub audition: Option<Audition>,
}

impl Evidence {
    pub(super) fn rejected(decision: Decision) -> Self {
        Self {
            decision,
            support_us: 0,
            reference_span: (0, 0),
            quality: 0.0,
            audition: None,
        }
    }
}

/// Work units count sample comparisons and inspected index postings, not wall
/// time. One budget covers all hypotheses and both directions of an analysis.
pub(super) struct VerificationWork {
    remaining: usize,
    exhausted: bool,
}

impl VerificationWork {
    pub fn new(r: &Features, l: &Features) -> Self {
        Self {
            remaining: 4_000_000 + 950 * (r.frames.len() + l.frames.len()),
            exhausted: false,
        }
    }

    pub fn spend(&mut self, count: usize) -> bool {
        if let Some(remaining) = self.remaining.checked_sub(count) {
            self.remaining = remaining;
            true
        } else {
            self.exhausted = true;
            false
        }
    }

    pub fn exhausted(&self) -> bool {
        self.exhausted
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Observation {
    // Both harmonic and spectral evidence support the proposed time.
    Direct,
    // Original notes remain, but interference weakens the time evidence.
    Covered,
    // No trustworthy timing evidence here. May coexist with valid coverage
    // elsewhere, but never contributes to support or to an audition.
    Unobserved,
    // Alternative timing explains the notes and the original is absent.
    Displaced(i32),
}

struct Cell {
    start: i64,
    end: i64,
    observation: Observation,
    quality: f64,
}

struct Trace {
    cells: Vec<Cell>,
    span: (i64, i64),
    conflict: bool,
}

impl Trace {
    fn usable(&self) -> bool {
        let duration = self.span.1 - self.span.0;
        let mut direct = 0;
        let mut covered = 0;
        let mut gap = 0;
        let mut longest_gap = 0;
        for cell in &self.cells {
            let width = cell.end - cell.start;
            match cell.observation {
                Observation::Direct => {
                    direct += width;
                    gap = 0;
                }
                Observation::Covered => {
                    covered += width;
                    gap = 0;
                }
                _ => {
                    gap += width;
                    longest_gap = longest_gap.max(gap);
                }
            }
        }
        duration >= 1_850_000
            && direct * 100 >= duration * 56
            && (direct + covered) * 100 >= duration * 76
            && longest_gap <= 2_000_000
    }

    fn direct_ranges(&self) -> Vec<(i64, i64)> {
        let mut ranges: Vec<(i64, i64)> = Vec::new();
        for cell in self
            .cells
            .iter()
            .filter(|cell| cell.observation == Observation::Direct)
        {
            if let Some(last) = ranges.last_mut()
                && last.1 == cell.start
            {
                last.1 = cell.end;
            } else {
                ranges.push((cell.start, cell.end));
            }
        }
        ranges
    }
}

pub(super) fn verify(
    reference: &Features,
    target: &Features,
    map: Seed,
    work: &mut VerificationWork,
    current: &dyn Fn() -> bool,
) -> Result<Evidence, AudioError> {
    verify_scope(reference, target, map, None, work, current)
}

pub(super) fn verify_scope(
    reference: &Features,
    target: &Features,
    map: Seed,
    scope: Option<(i64, i64)>,
    work: &mut VerificationWork,
    current: &dyn Fn() -> bool,
) -> Result<Evidence, AudioError> {
    let insufficient = || Evidence::rejected(Decision::Insufficient);
    let conflict = || Evidence::rejected(Decision::Conflict);
    let Some(r) = trace(reference, target, map, scope, work, current)? else {
        return Ok(insufficient());
    };
    if r.conflict {
        return Ok(conflict());
    }
    let inverse = Seed {
        scale: 1.0 / map.scale,
        offset: round(-(map.offset as f64) / map.scale),
        pitch: map.pitch,
    };
    let reverse_scope = scope.map(|(a, b)| (mapped(map, a), mapped(map, b)));
    let Some(t) = trace(target, reference, inverse, reverse_scope, work, current)? else {
        return Ok(insufficient());
    };
    if t.conflict {
        return Ok(conflict());
    }
    if !r.usable() || !t.usable() {
        return Ok(insufficient());
    }

    // A playback interval is part of the proof, not inferred by the UI from
    // support length. An observation must be direct in both sources.
    let mut support_us = 0;
    let mut audition = None;
    let mut duration = 0;
    let (rr, tr) = (r.direct_ranges(), t.direct_ranges());
    let mut j = 0;
    for (a, b) in rr {
        let target_start = mapped(map, a);
        let target_end = mapped(map, b);
        while j < tr.len() && tr[j].1 <= target_start {
            j += 1;
        }
        for &(x, y) in tr[j..].iter().take_while(|(x, _)| *x < target_end) {
            let start = a.max(((x - map.offset) as f64 / map.scale).ceil() as i64);
            let end = b.min(((y - map.offset) as f64 / map.scale).floor() as i64);
            if end <= start {
                continue;
            }
            support_us += end - start;
            if start < 0 || mapped(map, start) < 0 {
                continue;
            }
            // Keep a small margin at both ends for FFT centering and native
            // players which report/seek whole milliseconds.
            let start = start + 35_000;
            let end = end - 35_000;
            let length = (end - start).min(mapped(map, end) - mapped(map, start));
            if length >= MIN_AUDITION && length > duration {
                duration = length;
                let width = (end - start).min(2_200_000);
                let begin = start + (end - start - width) / 2;
                audition = Some(Audition {
                    reference_start_us: begin,
                    reference_end_us: begin + width,
                    target_start_us: mapped(map, begin),
                    target_end_us: mapped(map, begin + width),
                });
            }
        }
    }
    if audition.is_none() {
        return Ok(insufficient());
    }
    let mean = |trace: &Trace| {
        trace
            .cells
            .iter()
            .map(|c| c.quality * (c.end - c.start) as f64)
            .sum::<f64>()
            / (trace.span.1 - trace.span.0) as f64
    };
    Ok(Evidence {
        decision: Decision::Supported,
        support_us,
        reference_span: r.span,
        quality: (mean(&r) + mean(&t)) * 0.5,
        audition,
    })
}

fn mapped(s: Seed, t: i64) -> i64 {
    round(s.scale * t as f64) + s.offset
}

fn trace(
    reference: &Features,
    target: &Features,
    s: Seed,
    scope: Option<(i64, i64)>,
    work: &mut VerificationWork,
    current: &dyn Fn() -> bool,
) -> Result<Option<Trace>, AudioError> {
    check(current)?;
    let Some(((rs, re), (ts, te))) = musical_bounds(reference).zip(musical_bounds(target)) else {
        return Ok(None);
    };
    let start = rs
        .max(((ts - s.offset) as f64 / s.scale).ceil() as i64)
        .max(scope.map_or(i64::MIN, |range| range.0));
    let end = re
        .min(((te - s.offset) as f64 / s.scale).floor() as i64)
        .min(scope.map_or(i64::MAX, |range| range.1));
    if end - start < 1_850_000 {
        return Ok(None);
    }
    // Anchor to both ends so the tail never has an unused fractional cell.
    let n = ((end - start + CELL / 2) / CELL).max(1);
    let times = |i| start + (end - start) * i / n;
    let mut cells = Vec::with_capacity(n as usize);
    for i in 0..n {
        check(current)?;
        let (a, b) = (times(i), times(i + 1));
        let p = Probe::new(reference, target, s, a, b - a);
        let len = p.frames.len();
        let enough_notes = len >= 6;
        let (mut peak, mut score, mut audibility) = (0.0_f64, 0.0_f64, 0.0_f64);
        if enough_notes {
            if !work.spend(len * 15) {
                return Ok(None);
            }
            for lag in -2..=2 {
                let (corr, agreement, audible) = p.measure(lag);
                score = score.max(corr);
                peak = peak.max(agreement);
                audibility = audibility.max(audible);
            }
        }
        let observation = if enough_notes && (score >= 0.67 || (score >= 0.48 && peak >= 0.60)) {
            Observation::Direct
        } else if enough_notes && peak >= 0.72 {
            Observation::Covered
        } else {
            Observation::Unobserved
        };

        // A 200 ms cell is intentionally cheap for the normal path, but an
        // edit at a cell edge can otherwise be diluted by its neighboring
        // music. Recheck both halves only when the proposed map is already
        // weak; a short echo/noise patch must not become a conflict merely
        // because another lag happens to score well.
        let fine_conflict = if enough_notes {
            let half = (b - a) / 2;
            let mut halves = Vec::new();
            let mut direct_peaks = Vec::with_capacity(2);
            for from in [a, a + half] {
                let probe = Probe::new(reference, target, s, from, half);
                if probe.frames.len() < 4 {
                    halves.push(None);
                    direct_peaks.push(0.0);
                    continue;
                }
                let (near, agreement, _) = probe.measure(0);
                direct_peaks.push(agreement);
                if near >= 0.45 {
                    halves.push(None);
                    continue;
                }
                if !work.spend(161 * probe.frames.len()) {
                    return Ok(None);
                }
                let best = (-MAX_LAG..=MAX_LAG)
                    .filter(|lag| lag.abs() >= 4)
                    .filter_map(|lag| {
                        let (far, peaks, _) = probe.measure(lag);
                        (far >= 0.70 && far - near >= 0.18 && peaks >= 0.75)
                            .then_some((lag, far - near))
                    })
                    .max_by(|a, b| a.1.total_cmp(&b.1));
                halves.push(best.map(|(lag, _)| lag));
            }
            // A short echo can dominate displaced-lag correlation while the
            // original notes remain audible. This extra check is for boundary
            // edits, where one half of the cell may still be directly aligned.
            let displaced = matches!(halves.as_slice(), [Some(a), Some(b)] if (a - b).abs() <= 1);
            let direct_anchor =
                (a == start || b == end) && direct_peaks.iter().any(|&agreement| agreement >= 0.60);
            displaced && direct_anchor
        } else {
            false
        };

        // A sustained hole *inside* one source's music cannot be a difference
        // in source trim. Keep edge padding outside the comparison span.
        if (len >= 8 && audibility <= 0.12) || fine_conflict {
            return Ok(Some(Trace {
                cells,
                span: (start, end),
                conflict: true,
            }));
        }
        cells.push(Cell {
            start: a,
            end: b,
            observation,
            quality: score.max(0.45 * peak),
        });
    }

    // Positively supported cells are not reinterpreted just because some
    // other recording (echo / repeated motif) has an even higher peak.
    let mut index = None;
    for i in 0..cells.len() {
        check(current)?;
        if cells[i].observation != Observation::Unobserved {
            continue;
        }
        let a = cells[i].start;
        let b = cells[i].end;
        let probe = Probe::new(reference, target, s, a, b - a);
        if probe.frames.len() < 7 {
            continue;
        }
        let size = probe.frames.len();
        let near = cells[i].quality;
        let mut matches = Vec::new();
        if !work.spend(161 * size) {
            return Ok(None);
        }
        for lag in (-MAX_LAG..=MAX_LAG).filter(|lag| lag.abs() >= 4) {
            let far = probe.score(lag);
            if far >= 0.70 && far - near >= 0.14 && probe.peak_agreement(lag) >= 0.72 {
                matches.push((far, lag));
            }
        }
        let peaks = match &index {
            Some(existing) => existing,
            None => index.insert(PeakTimes::new(target, s, current)?),
        };
        let broad = Probe::new(
            reference,
            target,
            s,
            (a - 100_000).max(start),
            (b + 100_000).min(end) - (a - 100_000).max(start),
        );
        let Some(proposed) = peaks.bounded_lags(&broad.frames, work, current)? else {
            return Ok(None);
        };
        if !work.spend(proposed.len() * 11 * size) {
            return Ok(None);
        }
        for guess in proposed {
            for lag in (guess - 5..=guess + 5).filter(|lag| lag.abs() > MAX_LAG) {
                let far = probe.score(lag);
                if far >= 0.70 && far - near >= 0.14 && probe.peak_agreement(lag) >= 0.72 {
                    matches.push((far, lag));
                }
            }
        }
        matches.sort_by(|a, b| b.0.total_cmp(&a.0).then(a.1.abs().cmp(&b.1.abs())));
        for (_, lag) in matches.into_iter().take(5) {
            // Corroborate within the displaced interval (or an immediately
            // adjacent cell losing original support). Do not require the
            // edit to extend into the unaffected neighboring passage.
            let mid = a + (b - a) / 2;
            let split = [(a, mid), (mid, b)].into_iter().all(|(from, to)| {
                let p = Probe::new(reference, target, s, from, to - from);
                p.frames.len() >= 4 && p.score(lag) >= 0.74 && p.peak_agreement(lag) >= 0.80
            });
            let previous = i.checked_sub(1).and_then(|j| cells.get(j));
            let next = cells.get(i + 1);
            let neighbor = [previous, next].into_iter().flatten().any(|cell| {
                if !matches!(
                    cell.observation,
                    Observation::Unobserved | Observation::Displaced(_)
                ) {
                    return false;
                }
                let p = Probe::new(reference, target, s, cell.start, cell.end - cell.start);
                p.frames.len() >= 6 && p.score(lag) >= 0.66 && p.peak_agreement(lag) >= 0.72
            });
            if neighbor || (split && (near < 0.32 || i == 0 || i + 1 == cells.len())) {
                cells[i].observation = Observation::Displaced(lag);
                return Ok(Some(Trace {
                    cells,
                    span: (start, end),
                    conflict: true,
                }));
            }
        }
    }
    Ok(Some(Trace {
        cells,
        span: (start, end),
        conflict: false,
    }))
}
