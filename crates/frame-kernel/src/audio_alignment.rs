//! Pairwise music correspondence. No pose, playback speed, BPM grid or database.
use crate::audio_features::{AudioError, Features, Frame};
use std::collections::{BTreeMap, BTreeSet};

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
pub struct Candidate {
    pub offset: i64,
    pub scale: f64,
    pub score: f64,
    pub windows: u32,
    pub reserved: u32,
    pub support: f64,
    pub residual: f64,
}
#[derive(Clone, Debug)]
pub struct Alignment {
    pub status: u32,
    pub candidates: Vec<Candidate>,
}
// Status: 0 matched, 1 no match, 2 inconsistent, 3 ambiguous. Errors are separate.
fn check(current: &dyn Fn() -> bool) -> Result<(), AudioError> {
    if current() {
        Ok(())
    } else {
        Err(AudioError::Cancelled)
    }
}
fn compare(a: &Candidate, b: &Candidate) -> std::cmp::Ordering {
    b.score
        .total_cmp(&a.score)
        .then(a.offset.cmp(&b.offset))
        .then(a.scale.total_cmp(&b.scale))
}
fn landmarks(f: &Features) -> Vec<(i32, i64)> {
    let mut out = Vec::new();
    for i in (0..f.frames.len()).step_by(4) {
        let row = &f.frames[i];
        for anchor in row.peaks.iter().take(3) {
            let mut fanout = 0;
            for distance in [8, 16, 28, 44, 64] {
                if let Some(target) = f.frames.get(i + distance).and_then(|r| r.peaks.first()) {
                    out.push((
                        ((anchor / 2) << 16) | ((target / 2) << 8) | (distance as i32 / 2),
                        row.time,
                    ));
                    fanout += 1;
                    if fanout == 4 {
                        break;
                    }
                }
            }
        }
    }
    out
}

pub fn align(
    t: &Features,
    l: &Features,
    current: &dyn Fn() -> bool,
) -> Result<Alignment, AudioError> {
    check(current)?;
    let mut constant = constant(t, l, current)?;
    if constant.status == 0 {
        let best = constant.candidates[0];
        if !local_conflict(t, l, best.seed(false), current)? {
            return Ok(constant);
        }
        // Three long windows can hide a small steady drift or a late edit.
        // Refit this passage at a different rate, not a new, shorter passage
        // whose overlap might simply omit the conflicting end of the song.
        constant.status = 2;
        if let Some(model) = refit_passage(t, l, best, current)? {
            return Ok(Alignment {
                status: 0,
                candidates: vec![model],
            });
        }
        return Ok(constant);
    }
    if constant.status == 3 && constant.candidates.len() > 1 {
        return verify_constant_review(t, l, constant, current);
    }
    if let Some(model) = affine(t, l, current)? {
        return Ok(model);
    }
    if constant.status == 3 {
        // A single provisional choice is still confirmable in the UI. Do not
        // bypass verification just because the global rate search found none.
        return verify_constant_review(t, l, constant, current);
    }
    Ok(constant)
}

fn verify_constant_review(
    t: &Features,
    l: &Features,
    mut result: Alignment,
    current: &dyn Fn() -> bool,
) -> Result<Alignment, AudioError> {
    // Preserve the passage choices, not their provisional same-speed maps.
    // Never promote a remaining review choice to an automatic application.
    let mut verified: Vec<Candidate> = Vec::new();
    for candidate in result.candidates {
        let model = if candidate.support >= 6.0 {
            refit_passage(t, l, candidate, current)?
        } else if candidate.windows == 3
            && candidate.residual <= 30.0
            && !local_conflict(t, l, candidate.seed(false), current)?
        {
            // Short stable passages cannot meet the affine fitter's six seconds.
            Some(candidate)
        } else {
            None
        };
        if let Some(model) = model
            && verified.iter().all(|v| {
                (v.scale - model.scale).abs() >= 0.003 || (v.offset - model.offset).abs() >= 150000
            })
        {
            verified.push(model);
        }
    }
    verified.sort_by(compare);
    result.candidates = verified;
    if result.candidates.is_empty() {
        result.status = 2;
    }
    Ok(result)
}

fn constant(
    t: &Features,
    l: &Features,
    current: &dyn Fn() -> bool,
) -> Result<Alignment, AudioError> {
    let none = || Alignment {
        status: 1,
        candidates: vec![],
    };
    if t.frames.len() < 200 || l.frames.len() < 200 {
        return Ok(none());
    }
    let tl = landmarks(t);
    let ll = landmarks(l);
    if tl.iter().map(|r| r.0).collect::<BTreeSet<_>>().len() < 12
        || ll.iter().map(|r| r.0).collect::<BTreeSet<_>>().len() < 12
    {
        return Ok(none());
    }
    let mut index: BTreeMap<i32, Vec<i64>> = BTreeMap::new();
    for (key, time) in tl {
        let bucket = index.entry(key).or_default();
        if bucket.len() < 80 {
            bucket.push(time);
        }
    }
    let mut histogram: BTreeMap<i64, u32> = BTreeMap::new();
    for (i, (hash, time)) in ll.into_iter().enumerate() {
        if i % 256 == 0 {
            check(current)?;
        }
        let a = hash >> 16;
        let b = (hash >> 8) & 255;
        let dt = hash & 255;
        for da in -1..=1 {
            for db in -1..=1 {
                for d in -1..=1 {
                    if !(0..=255).contains(&(a + da))
                        || !(0..=255).contains(&(b + db))
                        || !(0..=255).contains(&(dt + d))
                    {
                        continue;
                    }
                    if let Some(times) = index.get(&(((a + da) << 16) | ((b + db) << 8) | (dt + d)))
                    {
                        for &reference in times {
                            let lag = ((time - reference) as f64 / 20000.0).round_ties_even()
                                as i64
                                * 20000;
                            *histogram.entry(lag).or_default() += 1;
                        }
                    }
                }
            }
        }
    }
    let mut ranked: Vec<_> = histogram.into_iter().collect();
    ranked.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
    let mut offsets: Vec<i64> = Vec::new();
    for (offset, votes) in ranked {
        if votes < 6 {
            break;
        }
        if offsets.iter().all(|&old| (old - offset).abs() >= 180000) {
            offsets.push(offset);
        }
        if offsets.len() == 8 {
            break;
        }
    }
    let mut candidates = Vec::new();
    for offset in offsets {
        check(current)?;
        let mut best_offset = offset;
        let mut best = -1.0;
        for delta in (-120000..=120000).step_by(10000) {
            let value = constant_score(t, l, offset + delta, None);
            if value > best {
                best = value;
                best_offset = offset + delta;
            }
        }
        let start = t.start().max(l.start() - best_offset);
        let end = t.end().min(l.end() - best_offset);
        if end - start < 2000000 || best < 0.35 {
            continue;
        }
        let window = (end - start) / 3;
        let mut estimates = Vec::new();
        for i in 0..3 {
            let mut local_offset = best_offset;
            let mut local_score = -1.0;
            for delta in (-100000..=100000).step_by(10000) {
                let value = constant_score(
                    t,
                    l,
                    best_offset + delta,
                    Some((start + i * window, start + (i + 1) * window)),
                );
                if value > local_score {
                    local_score = value;
                    local_offset = best_offset + delta;
                }
            }
            if local_score >= 0.45 {
                estimates.push(local_offset);
            }
        }
        let residual = estimates
            .iter()
            .map(|&v| (v - best_offset).abs() as f64 / 1000.0)
            .reduce(f64::max)
            .unwrap_or(1e6);
        candidates.push(Candidate {
            offset: best_offset,
            scale: 1.0,
            score: best,
            windows: estimates
                .iter()
                .filter(|&&v| (v - best_offset).abs() <= 30000)
                .count() as u32,
            support: (end - start) as f64 / 1e6,
            residual,
            reserved: 0,
        });
    }
    candidates.sort_by(compare);
    let mut selected: Vec<Candidate> = Vec::new();
    for c in candidates {
        if selected
            .iter()
            .all(|v| (v.offset - c.offset).abs() >= 150000)
        {
            selected.push(c);
        }
        if selected.len() == 3 {
            break;
        }
    }
    let Some(best) = selected.first() else {
        return Ok(none());
    };
    let runner = selected.get(1).map_or(0.0, |r| r.score);
    let room = best.score >= 0.54
        && best.support >= 8.0
        && best.windows == 3
        && best.residual <= 20.0
        && best.score - runner >= 0.15;
    let status = if best.score < 0.64 && !room {
        1
    } else if best.residual > 60.0 {
        2
    } else if best.windows < 3
        || selected
            .iter()
            .skip(1)
            .any(|v| v.score >= best.score - 0.08 && v.score > 0.60)
    {
        3
    } else {
        0
    };
    if status == 3 {
        let best_score = best.score;
        selected.truncate(
            selected
                .iter()
                .take_while(|v| v.score >= best_score - 0.08 && v.score > 0.60)
                .count(),
        );
    }
    Ok(Alignment {
        status,
        candidates: selected,
    })
}

// Keep arithmetic accumulation at the original Float-product/Double-sum boundary.
fn dot(a: &[f32], b: &[f32]) -> f64 {
    a.iter().zip(b).map(|(&x, &y)| f64::from(x * y)).sum()
}
fn constant_score(t: &Features, l: &Features, offset: i64, range: Option<(i64, i64)>) -> f64 {
    let (from, until) = range.unwrap_or((
        t.start().max(l.start() - offset),
        t.end().min(l.end() - offset),
    ));
    if until - from < 500000 {
        return -1.0;
    }
    let first = (((from - t.start()) as f64 / 10000.0).ceil() as i64).max(0) as usize;
    let last = t
        .frames
        .len()
        .min((((until - t.start()) as f64 / 10000.0).ceil() as i64).max(0) as usize);
    let mut sum = 0.0;
    let mut count = 0;
    for i in (first..last).step_by(2) {
        let j = ((t.frames[i].time + offset - l.start()) as f64 / 10000.0).round_ties_even() as i64;
        let Some(b) = usize::try_from(j).ok().and_then(|j| l.frames.get(j)) else {
            continue;
        };
        let a = &t.frames[i];
        sum += 0.75 * dot(&a.spectrum, &b.spectrum) + 0.25 * dot(&a.onset, &b.onset);
        count += 1;
    }
    if count >= 25 {
        (sum / f64::from(count)).clamp(0.0, 1.0)
    } else {
        -1.0
    }
}

#[derive(Clone, Copy)]
struct Seed {
    scale: f64,
    offset: i64,
    pitch: bool,
}

impl Candidate {
    fn seed(self, pitch: bool) -> Seed {
        Seed {
            scale: self.scale,
            offset: self.offset,
            pitch,
        }
    }
}

fn strong_affine(candidate: Candidate) -> bool {
    candidate.score >= 0.64
        && candidate.windows == 4
        && candidate.residual <= 35.0
        && candidate.support >= 6.0
}

fn refit_passage(
    t: &Features,
    l: &Features,
    candidate: Candidate,
    current: &dyn Fn() -> bool,
) -> Result<Option<Candidate>, AudioError> {
    let mut verified = Vec::new();
    for pitch in [false, true] {
        if let Some(model) = refine(t, l, candidate.seed(pitch), current)?
            && strong_affine(model)
            && model.support >= candidate.support * 0.95
            && !local_conflict(t, l, model.seed(pitch), current)?
        {
            verified.push(model);
        }
    }
    Ok(verified.into_iter().min_by(compare))
}

struct Votes {
    count: u32,
    x: f64,
    y: f64,
    xx: f64,
    xy: f64,
    first: Option<f64>,
    last: f64,
}
impl Default for Votes {
    fn default() -> Self {
        Self {
            count: 0,
            x: 0.0,
            y: 0.0,
            xx: 0.0,
            xy: 0.0,
            first: None,
            last: f64::NEG_INFINITY,
        }
    }
}
impl Votes {
    fn add(&mut self, reference: i64, target: i64) {
        let a = reference as f64 / 1e6;
        let b = target as f64 / 1e6;
        self.count += 1;
        self.x += a;
        self.y += b;
        self.xx += a * a;
        self.xy += a * b;
        self.first = Some(self.first.map_or(a, |f| f.min(a)));
        self.last = self.last.max(a);
    }
    fn seed(&self, around: f64, pitch: bool) -> Option<Seed> {
        if self.count < 12 || self.last - self.first? < 4.0 {
            return None;
        }
        let n = f64::from(self.count);
        let denominator = self.xx - self.x * self.x / n;
        if denominator < 1e-6 {
            return None;
        }
        let scale = (self.xy - self.x * self.y / n) / denominator;
        if !(0.48..=2.02).contains(&scale) || (scale - around).abs() > 0.04 {
            return None;
        }
        let bounded = scale.clamp(0.5, 2.0);
        Some(Seed {
            scale: bounded,
            offset: round((self.y - bounded * self.x) / n * 1e6),
            pitch,
        })
    }
}
fn round(v: f64) -> i64 {
    (v + 0.5).floor() as i64
}
fn key(a: i32, b: i32, d: i32) -> i32 {
    (a << 16) | (b << 8) | d
}
const DISTANCES: [usize; 4] = [8, 16, 28, 44];

fn affine(
    t: &Features,
    l: &Features,
    current: &dyn Fn() -> bool,
) -> Result<Option<Alignment>, AudioError> {
    if (t.end() - t.start()).min(l.end() - l.start()) < 6000000
        || t.frames.len() < 600
        || l.frames.len() < 600
    {
        return Ok(None);
    }
    let mut index: BTreeMap<i32, Vec<i64>> = BTreeMap::new();
    for i in (0..t.frames.len()).step_by(4) {
        let row = &t.frames[i];
        for p in row.peaks.iter().take(3) {
            for (d, &dt) in DISTANCES.iter().enumerate() {
                let Some(other) = t.frames.get(i + dt).and_then(|f| f.peaks.first()) else {
                    continue;
                };
                let bucket = index.entry(key(p / 4, other / 4, d as i32)).or_default();
                if bucket.len() < 80 {
                    bucket.push(row.time);
                }
            }
        }
    }
    if index.len() < 12 {
        return Ok(None);
    }
    let mut seeds = Vec::new();
    for step in 0..=60 {
        check(current)?;
        let scale = 0.5 + f64::from(step) * 0.025;
        for pitch in [false, true] {
            if pitch && step == 20 {
                continue;
            }
            let frequency_scale = if pitch { scale } else { 1.0 };
            let mut histogram: BTreeMap<i64, Votes> = BTreeMap::new();
            for i in (0..l.frames.len()).step_by(8) {
                if i % 1024 == 0 {
                    check(current)?;
                }
                let row = &l.frames[i];
                for p in row.peaks.iter().take(3) {
                    for (d, &dt) in DISTANCES.iter().enumerate() {
                        let Some(other) = l
                            .frames
                            .get(i + round(dt as f64 * scale) as usize)
                            .and_then(|f| f.peaks.first())
                        else {
                            continue;
                        };
                        let a = round(f64::from(*p) * frequency_scale / 4.0) as i32;
                        let b = round(f64::from(*other) * frequency_scale / 4.0) as i32;
                        for da in -1..=1 {
                            for db in -1..=1 {
                                if !(0..=255).contains(&(a + da)) || !(0..=255).contains(&(b + db))
                                {
                                    continue;
                                }
                                if let Some(times) = index.get(&key(a + da, b + db, d as i32)) {
                                    for &reference in times {
                                        let lag = round(
                                            (row.time as f64 - scale * reference as f64) / 100000.0,
                                        );
                                        histogram.entry(lag).or_default().add(reference, row.time);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            let mut ranked: Vec<_> = histogram.into_iter().collect();
            ranked.sort_by(|a, b| b.1.count.cmp(&a.1.count).then(a.0.cmp(&b.0)));
            seeds.extend(
                ranked
                    .iter()
                    .take(4)
                    .filter_map(|(_, v)| v.seed(scale, pitch)),
            );
        }
    }
    let mut ranked = Vec::new();
    for s in seeds {
        check(current)?;
        let score = affine_score(t, l, s, 100000, None);
        if score >= 0.35 {
            ranked.push((s, score));
        }
    }
    ranked.sort_by(|a, b| {
        b.1.total_cmp(&a.1)
            .then(a.0.offset.cmp(&b.0.offset))
            .then(a.0.scale.total_cmp(&b.0.scale))
            .then(a.0.pitch.cmp(&b.0.pitch))
    });
    let mut coarse: Vec<Seed> = Vec::new();
    for (s, _) in ranked {
        if coarse.iter().all(|v| {
            (v.scale - s.scale).abs() >= 0.003
                || (v.offset - s.offset).abs() >= 120000
                || v.pitch != s.pitch
        }) {
            coarse.push(s);
        }
        if coarse.len() == 8 {
            break;
        }
    }
    let mut results = Vec::new();
    for s in coarse {
        if let Some(c) = refine(t, l, s, current)? {
            results.push((c, s.pitch));
        }
    }
    results.sort_by(|a, b| compare(&a.0, &b.0));
    let mut selected: Vec<(Candidate, bool)> = Vec::new();
    for (c, pitch) in results {
        if selected.iter().all(|(v, _)| {
            (v.scale - c.scale).abs() >= 0.003 || (v.offset - c.offset).abs() >= 150000
        }) {
            selected.push((c, pitch));
        }
        if selected.len() == 3 {
            break;
        }
    }
    let Some(&(best, _)) = selected.first() else {
        return Ok(None);
    };
    let status = if !strong_affine(best) {
        2
    } else {
        selected.retain(|(v, _)| v.score >= best.score - 0.08 && strong_affine(*v));
        let review = selected.len() > 1;
        let mut verified = Vec::new();
        for (candidate, pitch) in selected {
            if !local_conflict(t, l, candidate.seed(pitch), current)? {
                verified.push((candidate, pitch));
            }
        }
        selected = verified;
        if selected.is_empty() {
            2
        } else if review {
            3 // Removing a conflicting choice must not auto-apply its runner-up.
        } else {
            0
        }
    };
    Ok(Some(Alignment {
        status,
        candidates: selected.into_iter().map(|(v, _)| v).collect(),
    }))
}

fn range(t: &Features, l: &Features, s: Seed) -> (i64, i64) {
    (
        t.start()
            .max(((l.start() - s.offset) as f64 / s.scale).ceil() as i64),
        t.end()
            .min(((l.end() - s.offset) as f64 / s.scale).floor() as i64),
    )
}

/// Probe short windows all the way to the shared interval's edges. Large
/// windows used for ranking are deliberately tolerant of silence and noise,
/// but can average away even several seconds of a conflicting passage.
fn local_conflict(
    t: &Features,
    l: &Features,
    s: Seed,
    current: &dyn Fn() -> bool,
) -> Result<bool, AudioError> {
    if directional_conflict(t, l, s, current)? {
        return Ok(true);
    }
    // An insertion in one source is a removal in the other. The overlap can
    // otherwise clip the only reference frames that reveal an edge deletion.
    directional_conflict(
        l,
        t,
        Seed {
            scale: 1.0 / s.scale,
            offset: round(-(s.offset as f64) / s.scale),
            pitch: s.pitch,
        },
        current,
    )
}

fn directional_conflict(
    t: &Features,
    l: &Features,
    s: Seed,
    current: &dyn Fn() -> bool,
) -> Result<bool, AudioError> {
    let Some((t_start, t_end)) = musical_bounds(t) else {
        return Ok(false);
    };
    let Some((l_start, l_end)) = musical_bounds(l) else {
        return Ok(false);
    };
    // Leading/trailing silence is not a conflicting passage. Restrict only
    // verification probes to musical evidence, not the fitted map or support.
    // Internal silent edits remain inside these bounds and are still checked.
    let start = t_start.max(((l_start - s.offset) as f64 / s.scale).ceil() as i64);
    let end = t_end.min(((l_end - s.offset) as f64 / s.scale).floor() as i64);
    let width = ((end - start) / 4).min(1_200_000);
    if width < 500_000 {
        return Ok(false);
    }
    let travel = end - start - width;
    let intervals = ((travel + width - 1) / width).max(2);
    let shift = s.pitch.then(|| -s.scale.log2() * 8.0);
    let mut peak_times = None;
    // A partial second after a late edit must not disappear into a 1.2 s
    // average. Keep the longer probes for noise tolerance and also inspect
    // the first/last 200 and 300 ms. The shortest probes require a stronger
    // displaced peak so a brief noisy edge is not mistaken for an edit.
    let probes = (0..=intervals)
        .map(|i| (start + travel * i / intervals, width))
        .chain(
            [200_000, 300_000]
                .into_iter()
                .flat_map(|edge_width| [(start, edge_width), (end - edge_width, edge_width)]),
        );
    for (from, probe_width) in probes {
        check(current)?;
        let reference: Vec<_> = (from..from + probe_width)
            .step_by(20_000)
            .filter_map(|time| {
                let a = t
                    .frames
                    .get(round((time - t.start()) as f64 / 10000.0) as usize)?;
                if a.peaks.is_empty() {
                    return None;
                }
                let target = (time as f64 * s.scale + s.offset as f64 - l.start() as f64) / 10000.0;
                Some((a, target))
            })
            .collect();
        let minimum_frames = if probe_width < 300_000 { 10 } else { 15 };
        if reference.len() < minimum_frames {
            continue; // No musical evidence here, not evidence of a shift.
        }
        let score = |lag: i32| {
            let mut sum = 0.0;
            for &(a, at) in &reference {
                if let Some(b) = usize::try_from(round(at + f64::from(lag)))
                    .ok()
                    .and_then(|j| l.frames.get(j))
                    .filter(|b| !b.peaks.is_empty())
                {
                    sum += similarity(a, b, shift);
                }
            }
            sum / reference.len() as f64
        };
        let peak_agreement = |lag: i32| {
            let frequency_scale = if s.pitch { s.scale } else { 1.0 };
            let mut sum = 0.0;
            for &(a, at) in &reference {
                if let Some(b) = usize::try_from(round(at + f64::from(lag)))
                    .ok()
                    .and_then(|j| l.frames.get(j))
                {
                    let count = a.peaks.len().min(2);
                    let matches = a
                        .peaks
                        .iter()
                        .take(count)
                        .filter(|&&peak| {
                            b.peaks.iter().take(3).any(|&other| {
                                (f64::from(peak) - f64::from(other) * frequency_scale).abs() <= 2.0
                            })
                        })
                        .count();
                    sum += matches as f64 / count as f64;
                }
            }
            sum / reference.len() as f64
        };
        let near = (-1..=1).map(score).fold(0.0, f64::max);
        if near >= 0.94 {
            continue;
        }
        let near_peaks = (-1..=1).map(peak_agreement).fold(0.0, f64::max);
        let displaced_peaks = |lag| {
            let far_peaks = peak_agreement(lag);
            far_peaks >= 0.75 && far_peaks - near_peaks >= 0.05
        };
        // A clearly better local lag outside 30 ms is inconsistent with this
        // mapping. A weak correlation alone can be temporary recording noise,
        // not evidence of a different timeline; require a strong displaced peak.
        // Short edge probes also need improved raw peak correspondence: a
        // spectrally similar steady note alone cannot establish a different lag.
        let minimum_score = if probe_width < 300_000 { 0.8 } else { 0.65 };
        for lag in (-80i32..=80).filter(|lag| lag.abs() > 3) {
            let far = score(lag);
            if far >= minimum_score
                && far - near >= 0.06
                && (probe_width >= 500_000 || displaced_peaks(lag))
            {
                return Ok(true);
            }
        }
        // Larger edits need displaced evidence too, not a return to rejecting
        // weak/noisy windows. Propose lags from spectral peaks across the whole
        // target, then verify a bounded set at full time/spectral resolution.
        // Perfect current peak correspondence cannot be improved by the
        // required margin; avoid building/querying an index in that case.
        if near_peaks > 0.95 {
            continue;
        }
        if peak_times.is_none() {
            peak_times = Some(PeakTimes::new(l, s, current)?);
        }
        for lag in peak_times.as_ref().unwrap().lags(&reference, current)? {
            check(current)?;
            for refined in lag - 5..=lag + 5 {
                let far = score(refined);
                // An entire song exposes more spectral aliases. Require the
                // raw peak pattern to independently favor the displaced lag.
                if far >= minimum_score && far - near >= 0.06 && displaced_peaks(refined) {
                    return Ok(true);
                }
            }
        }
    }
    Ok(false)
}

fn musical_bounds(features: &Features) -> Option<(i64, i64)> {
    let first = features
        .frames
        .iter()
        .find(|frame| !frame.peaks.is_empty())?;
    let last = features
        .frames
        .iter()
        .rfind(|frame| !frame.peaks.is_empty())?;
    // FFT windows straddle a sound/silence boundary. Their partial-note frames
    // do not correspond to an untrimmed recording even at the correct offset.
    let window_us =
        crate::audio_features::FFT as i64 * 1_000_000 / crate::audio_features::RATE as i64;
    let start = first.time
        + if first.time > features.start() {
            window_us
        } else {
            0
        };
    let end = last.time + 10_000
        - if last.time + 10_000 < features.end() {
            window_us
        } else {
            0
        };
    (start < end).then_some((start, end))
}

struct PeakTimes {
    bands: Vec<Vec<usize>>,
}

impl PeakTimes {
    fn new(features: &Features, s: Seed, current: &dyn Fn() -> bool) -> Result<Self, AudioError> {
        let mut bands = vec![Vec::new(); 256];
        let frequency_scale = if s.pitch { s.scale } else { 1.0 };
        for (i, frame) in features.frames.iter().enumerate().step_by(2) {
            if i % 1024 == 0 {
                check(current)?;
            }
            for &peak in frame.peaks.iter().take(3) {
                let band = round(f64::from(peak) * frequency_scale / 2.0) as usize;
                if let Some(times) = bands.get_mut(band) {
                    times.push(i);
                }
            }
        }
        Ok(Self { bands })
    }

    fn lags(
        &self,
        reference: &[(&Frame, f64)],
        current: &dyn Fn() -> bool,
    ) -> Result<Vec<i32>, AudioError> {
        let mut votes: BTreeMap<i32, u32> = BTreeMap::new();
        for &(frame, at) in reference.iter().step_by(2) {
            check(current)?;
            for &peak in frame.peaks.iter().take(2) {
                let band = round(f64::from(peak) / 2.0) as i32;
                for adjacent in band - 1..=band + 1 {
                    let Some(times) = usize::try_from(adjacent)
                        .ok()
                        .and_then(|i| self.bands.get(i))
                    else {
                        continue;
                    };
                    for &time in times {
                        let lag = round((time as f64 - at) / 5.0) as i32 * 5;
                        if lag.abs() > 80 {
                            *votes.entry(lag).or_default() += 1;
                        }
                    }
                }
            }
        }
        let mut ranked: Vec<_> = votes.into_iter().collect();
        ranked.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(&b.0)));
        let mut selected: Vec<i32> = Vec::new();
        for (lag, count) in ranked {
            if count < 3 {
                break;
            }
            if selected.iter().all(|old| (old - lag).abs() >= 10) {
                selected.push(lag);
            }
            if selected.len() == 12 {
                break;
            }
        }
        Ok(selected)
    }
}

fn similarity(a: &Frame, b: &Frame, pitch_shift: Option<f64>) -> f64 {
    let onset = dot(&a.onset, &b.onset);
    let Some(shift) = pitch_shift else {
        return 0.75 * dot(&a.spectrum, &b.spectrum) + 0.25 * onset;
    };
    let mut spectral = 0.0;
    let mut na = 0.0;
    let mut nb = 0.0;
    for band in 0..48 {
        let p = band as f64 + shift;
        let k = p.floor() as i32;
        if !(0..=46).contains(&k) {
            continue;
        }
        let k = k as usize;
        let f = p - k as f64;
        let x = f64::from(a.pitch[band]);
        let y = f64::from(b.pitch[k]) * (1.0 - f) + f64::from(b.pitch[k + 1]) * f;
        spectral += x * y;
        na += x * x;
        nb += y * y;
    }
    spectral /= (na * nb).sqrt().max(1e-8);
    0.9 * spectral + 0.1 * onset
}

fn affine_score(t: &Features, l: &Features, s: Seed, step: i64, bounds: Option<(i64, i64)>) -> f64 {
    let r = range(t, l, s);
    let (from, until) = bounds.unwrap_or(r);
    if until - from < 500000 {
        return -1.0;
    }
    let mut time = from.max(r.0);
    let end = until.min(r.1);
    let mut sum = 0.0;
    let mut count = 0;
    let shift = s.pitch.then(|| -s.scale.log2() * 8.0);
    while time < end {
        let i = round((time - t.start()) as f64 / 10000.0);
        let j = round((time as f64 * s.scale + s.offset as f64 - l.start() as f64) / 10000.0);
        if let (Some(a), Some(b)) = (
            usize::try_from(i).ok().and_then(|i| t.frames.get(i)),
            usize::try_from(j).ok().and_then(|j| l.frames.get(j)),
        ) {
            sum += similarity(a, b, shift);
            count += 1;
        }
        time += step;
    }
    if count < 5 {
        -1.0
    } else {
        (sum / f64::from(count)).clamp(0.0, 1.0)
    }
}

fn refine(
    t: &Features,
    l: &Features,
    initial: Seed,
    current: &dyn Fn() -> bool,
) -> Result<Option<Candidate>, AudioError> {
    let mut s = initial;
    let mut pairs = Vec::new();
    for _ in 0..3 {
        check(current)?;
        let (start, end) = range(t, l, s);
        if end - start < 6000000 {
            return Ok(None);
        }
        pairs.clear();
        for k in 0..5 {
            let center = start + (end - start) * (2 * k + 1) / 10;
            let half = 1000000.min((end - start) / 12);
            let mut best = -1.0;
            let mut delta_best = 0i64;
            for delta in (-150000..=150000).step_by(10000) {
                let value = affine_score(
                    t,
                    l,
                    Seed {
                        offset: s.offset + delta,
                        ..s
                    },
                    40000,
                    Some((center - half, center + half)),
                );
                if value > best {
                    best = value;
                    delta_best = delta;
                }
            }
            if best >= 0.5 && delta_best.abs() < 150000 {
                pairs.push((
                    center as f64 / 1e6,
                    (center as f64 * s.scale + s.offset as f64 + delta_best as f64) / 1e6,
                ));
            }
        }
        if pairs.len() < 4 {
            return Ok(None);
        }
        let mx = pairs.iter().map(|p| p.0).sum::<f64>() / pairs.len() as f64;
        let my = pairs.iter().map(|p| p.1).sum::<f64>() / pairs.len() as f64;
        let denominator = pairs.iter().map(|p| (p.0 - mx).powi(2)).sum::<f64>();
        let scale = pairs.iter().map(|p| (p.0 - mx) * (p.1 - my)).sum::<f64>() / denominator;
        if !(0.495..=2.005).contains(&scale) {
            return Ok(None);
        }
        let bounded = scale.clamp(0.5, 2.0);
        s = Seed {
            scale: bounded,
            offset: round((my - bounded * mx) * 1e6),
            ..s
        };
    }
    let mut residual = pairs
        .iter()
        .map(|p| (p.1 - (s.scale * p.0 + s.offset as f64 / 1e6)).abs() * 1000.0)
        .fold(0.0, f64::max);
    let (start, end) = range(t, l, s);
    let mut verified = 0;
    for k in 1..=4 {
        check(current)?;
        let center = start + (end - start) * k / 5;
        let half = 500000.min((end - start) / 24);
        let mut best = -1.0;
        let mut delta_best = 0i64;
        for delta in (-100000..=100000).step_by(10000) {
            let value = affine_score(
                t,
                l,
                Seed {
                    offset: s.offset + delta,
                    ..s
                },
                20000,
                Some((center - half, center + half)),
            );
            if value > best {
                best = value;
                delta_best = delta;
            }
        }
        if best >= 0.5 && delta_best.abs() <= 30000 {
            verified += 1;
        }
        residual = residual.max(delta_best.abs() as f64 / 1000.0);
    }
    Ok(Some(Candidate {
        offset: s.offset,
        scale: s.scale,
        score: affine_score(t, l, s, 20000, None),
        windows: verified,
        reserved: 0,
        support: (end - start) as f64 / 1e6,
        residual,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::audio_features::{FeatureBuilder, RATE};
    fn music(seconds: f64, rate: f64) -> Vec<f32> {
        music_seed(seconds, rate, 7)
    }

    fn music_seed(seconds: f64, rate: f64, mut seed: u64) -> Vec<f32> {
        let notes: Vec<_> = (0..640)
            .map(|_| {
                seed = seed
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(1442695040888963407);
                160.0 + (((seed >> 11) as f64 / (1u64 << 53) as f64) * 800.0).floor()
            })
            .collect();
        (0..(seconds * 16000.0) as usize)
            .map(|i| {
                let t = i as f64 / 16000.0;
                let phase = t * notes[(t * rate * 8.0) as usize] * 2.0 * std::f64::consts::PI;
                ((1.0 - (t * rate * 8.0) % 1.0).powf(0.4)
                    * (0.25 * phase.sin()
                        + 0.12 * (phase * 1.5).sin()
                        + 0.07 * (phase * 2.23).sin())) as f32
            })
            .collect()
    }
    fn features(pcm: &[f32], origin: i64) -> Features {
        let mut b = FeatureBuilder::new(origin);
        b.add(pcm).unwrap();
        b.finish()
    }

    fn stretch(pcm: &[f32], scale: f64) -> Vec<f32> {
        (0..((pcm.len() - 1) as f64 * scale) as usize)
            .map(|i| {
                let position = i as f64 / scale;
                let index = position as usize;
                let fraction = (position - index as f64) as f32;
                pcm[index] * (1.0 - fraction) + pcm[index + 1] * fraction
            })
            .collect()
    }

    #[test]
    fn known_music_offset_and_affine_rate_preserve_truth() {
        let r = features(&music(20.0, 1.0), 30000);
        for rate in [1.0, 1.25] {
            let mut pcm = vec![0.0; 11200];
            pcm.extend(music(20.0 / rate, rate));
            let l = features(&pcm, 90000);
            let result = align(&r, &l, &|| true).unwrap();
            assert_eq!(result.status, 0, "{rate}: {result:?}");
            let best = result.candidates[0];
            let a = 1.0 / rate;
            let b = 790000.0 - a * 30000.0;
            for t in [3000000.0, 10000000.0, 18000000.0] {
                assert!(
                    (best.scale * t + best.offset as f64 - (a * t + b)).abs() < 35000.0,
                    "{rate}: {best:?}"
                );
            }
        }
        assert_eq!(align(&r, &r, &|| false).unwrap_err(), AudioError::Cancelled);
    }

    #[test]
    fn edits_near_either_edge_cannot_be_masked_by_a_long_matching_passage() {
        let pcm = music(20.0, 1.0);
        let reference = features(&pcm, 0);
        for (at, gap_ms) in [(15, 400), (50, 80), (85, 400)] {
            let cut = pcm.len() * at / 100;
            let mut edited = pcm[..cut].to_vec();
            edited.resize(cut + gap_ms * 16, 0.0);
            edited.extend(&pcm[cut..]);
            let target = features(&edited, 0);
            let result = align(&reference, &target, &|| true).unwrap();
            assert_eq!(result.status, 2, "edit at {at}%: {result:?}");
            let removed = [&pcm[..cut], &pcm[cut + gap_ms * 16..]].concat();
            let result = align(&reference, &features(&removed, 0), &|| true).unwrap();
            assert_ne!(result.status, 0, "removal at {at}%: {result:?}");
        }
    }

    #[test]
    fn local_verification_still_observes_cancellation() {
        let reference = features(&music(8.0, 1.0), 0);
        let calls = std::cell::Cell::new(0);
        let current = || {
            calls.set(calls.get() + 1);
            calls.get() < 3
        };
        assert_eq!(
            local_conflict(
                &reference,
                &reference,
                Candidate {
                    scale: 1.0,
                    ..Candidate::default()
                }
                .seed(false),
                &current
            ),
            Err(AudioError::Cancelled),
        );
    }

    #[test]
    fn small_drift_is_measured_at_both_ends_not_as_one_average_offset() {
        let pcm = music(23.0, 1.0);
        let reference = features(&pcm, 0);
        let delay = 5328;
        for scale in [0.995, 1.003] {
            let mut target = vec![0.0; delay];
            target.extend((0..((pcm.len() - 1) as f64 * scale) as usize).map(|i| {
                let pos = i as f64 / scale;
                let idx = pos as usize;
                let fraction = (pos - idx as f64) as f32;
                pcm[idx] * (1.0 - fraction) + pcm[idx + 1] * fraction
            }));
            let result = align(&reference, &features(&target, 0), &|| true).unwrap();
            assert_eq!(result.status, 0, "{scale}: {result:?}");
            let best = result.candidates[0];
            assert_ne!(best.scale, 1.0, "{scale}: {best:?}");
            for t in [0.0, 23_000_000.0] {
                let error = best.scale * t + best.offset as f64 - (scale * t + 333000.0);
                assert!(error.abs() < 35_000.0, "{scale}: {error} us, {best:?}");
            }
        }
    }

    #[test]
    fn repeated_passages_remain_separate_review_choices() {
        let pcm = music(8.0, 1.0);
        let mut repeated = vec![0.0; RATE];
        repeated.extend(&pcm);
        repeated.resize(10 * RATE, 0.0);
        repeated.extend(&pcm);
        let result = align(&features(&pcm, 0), &features(&repeated, 0), &|| true).unwrap();
        assert_eq!(result.status, 3, "{result:?}");
        for expected in [1_000_000, 10_000_000] {
            assert!(
                result
                    .candidates
                    .iter()
                    .any(|c| c.offset.abs_diff(expected) < 20_000),
                "{result:?}"
            );
        }
    }

    #[test]
    fn repeated_passages_are_refitted_without_losing_the_review_choices() {
        let seconds = 30.0;
        let pcm = music(seconds, 0.25);
        let reference = features(&pcm, 0);
        for scale in [0.995, 1.004] {
            let passage = stretch(&pcm, scale);
            let offsets = [
                1_000_000.0,
                (2 * RATE + passage.len()) as f64 * 1e6 / RATE as f64,
            ];
            let mut target = vec![0.0; RATE];
            target.extend(&passage);
            target.resize(target.len() + RATE, 0.0);
            target.extend(&passage);
            let result = align(&reference, &features(&target, 0), &|| true).unwrap();
            assert_eq!(result.status, 3, "{scale}: {result:?}");
            assert_eq!(result.candidates.len(), 2, "{scale}: {result:?}");
            for expected_offset in offsets {
                assert!(
                    result.candidates.iter().any(|candidate| {
                        [0.0, seconds * 1e6].iter().all(|&time| {
                            (candidate.scale * time + candidate.offset as f64
                                - (scale * time + expected_offset))
                                .abs()
                                < 35_000.0
                        })
                    }),
                    "{scale}, offset {expected_offset}: {result:?}",
                );
            }
        }
    }

    #[test]
    fn short_stable_repeats_still_offer_review_without_an_affine_fit() {
        let pcm = music(3.5, 1.0);
        let mut target = vec![0.0; RATE];
        target.extend(&pcm);
        target.resize(target.len() + RATE, 0.0);
        let second_offset = target.len() as i64 * 1_000_000 / RATE as i64;
        target.extend(&pcm);
        let result = align(&features(&pcm, 0), &features(&target, 0), &|| true).unwrap();
        assert_eq!(result.status, 3, "{result:?}");
        for offset in [1_000_000, second_offset] {
            assert!(
                result
                    .candidates
                    .iter()
                    .any(|candidate| candidate.offset.abs_diff(offset) < 20_000),
                "{offset}: {result:?}",
            );
        }
    }

    #[test]
    fn a_rejected_repeat_does_not_promote_the_other_choice_to_automatic() {
        let pcm = music(30.0, 0.25);
        let reference = features(&pcm, 0);
        let passage = stretch(&pcm, 0.995);
        let mut target = vec![0.0; RATE];
        target.extend(&passage);
        target.resize(target.len() + RATE, 0.0);
        let cut = passage.len() - RATE / 2;
        target.extend(&passage[..cut]);
        target.resize(target.len() + 400 * 16, 0.0);
        target.extend(&passage[cut..]);
        let target = features(&target, 0);
        let provisional = constant(&reference, &target, &|| true).unwrap();
        assert_eq!(provisional.status, 3, "{provisional:?}");
        assert!(provisional.candidates.len() > 1, "{provisional:?}");
        let result = align(&reference, &target, &|| true).unwrap();
        assert_eq!(result.status, 3, "{result:?}");
        assert_eq!(result.candidates.len(), 1, "{result:?}");
        let best = result.candidates[0];
        for time in [0.0, 30_000_000.0] {
            assert!(
                (best.scale * time + best.offset as f64 - (0.995 * time + 1_000_000.0)).abs()
                    < 35_000.0,
                "{best:?}",
            );
        }
    }

    #[test]
    fn temporary_recording_noise_is_not_a_timeline_discontinuity() {
        let pcm = music(23.0, 1.0);
        let clean = features(&pcm, 0);
        for at in [0, 10, 21] {
            let mut noisy = pcm.clone();
            let mut seed = 11u64;
            for sample in noisy.iter_mut().skip(at * RATE).take(2 * RATE) {
                seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
                *sample += ((seed >> 32) as f64 / u32::MAX as f64 * 2.0 - 1.0) as f32 * 0.5;
            }
            let noisy = features(&noisy, 0);
            for (reference, target) in [(&clean, &noisy), (&noisy, &clean)] {
                let result = align(reference, target, &|| true).unwrap();
                assert_eq!(result.status, 0, "noise at {at}s: {result:?}");
                let best = result.candidates[0];
                for time in [0.0, 23_000_000.0] {
                    assert!(
                        (best.scale * time + best.offset as f64 - time).abs() < 35_000.0,
                        "noise at {at}s: {best:?}",
                    );
                }
            }
        }
    }

    #[test]
    fn subsecond_edge_edits_cannot_hide_in_a_longer_verification_window() {
        let pcm = music(20.0, 1.0);
        let reference = features(&pcm, 0);
        for at_ms in [250, 500, 19_500, 19_750] {
            for gap_ms in [80, 400] {
                let cut = at_ms * 16;
                let mut target = pcm[..cut].to_vec();
                target.resize(cut + gap_ms * 16, 0.0);
                target.extend(&pcm[cut..]);
                let result = align(&reference, &features(&target, 0), &|| true).unwrap();
                assert_ne!(
                    result.status, 0,
                    "{gap_ms}ms insertion at {at_ms}ms: {result:?}",
                );
            }
        }
        for at_ms in [250, 500, 19_000] {
            let cut = at_ms * 16;
            let target = [&pcm[..cut], &pcm[cut + 400 * 16..]].concat();
            let result = align(&reference, &features(&target, 0), &|| true).unwrap();
            assert_ne!(result.status, 0, "400ms removal at {at_ms}ms: {result:?}");
        }
    }

    #[test]
    fn large_edits_require_displaced_evidence_beyond_the_nearby_lag_search() {
        let pcm = music(20.0, 1.0);
        let reference = features(&pcm, 0);
        for at_ms in [500, 12_000, 17_000, 19_000] {
            for gap_ms in [801, 1000, 1234, 1500, 3000, 12_000] {
                let cut = at_ms * 16;
                let mut target = pcm[..cut].to_vec();
                target.resize(cut + gap_ms * 16, 0.0);
                target.extend(&pcm[cut..]);
                let result = align(&reference, &features(&target, 0), &|| true).unwrap();
                assert!(
                    result.status != 0 && result.status != 3,
                    "{gap_ms}ms insertion at {at_ms}ms: {result:?}",
                );
                if cut + (gap_ms + 500) * 16 < pcm.len() {
                    let target = [&pcm[..cut], &pcm[cut + gap_ms * 16..]].concat();
                    let result = align(&reference, &features(&target, 0), &|| true).unwrap();
                    assert!(
                        result.status != 0 && result.status != 3,
                        "{gap_ms}ms removal at {at_ms}ms: {result:?}",
                    );
                }
            }
        }
    }

    #[test]
    fn partial_overlap_with_silent_padding_remains_a_match() {
        for rate in [0.25, 0.5, 1.0] {
            for seed in [7, 18, 290] {
                let pcm = music_seed(24.0, rate, seed);
                let reference = features(&pcm, 0);
                for (from, until) in [(1, 23), (3, 19), (7, 17)] {
                    let mut target = vec![0.0; 5328];
                    target.extend(&pcm[from * RATE..until * RATE]);
                    let result = align(&reference, &features(&target, 0), &|| true).unwrap();
                    assert_eq!(
                        result.status, 0,
                        "rate={rate}, seed={seed}, {from}..{until}: {result:?}",
                    );
                    let best = result.candidates[0];
                    for time in [from as f64 * 1e6, until as f64 * 1e6] {
                        assert!(
                            (best.scale * time + best.offset as f64
                                - (time + 333_000.0 - from as f64 * 1e6))
                                .abs()
                                < 35_000.0,
                            "{best:?}",
                        );
                    }
                }
            }
        }
        let pcm = music(24.0, 0.25);
        let reference = features(&pcm, 0);
        for padding in [0, 1600, 5328, RATE] {
            let mut target = vec![0.0; padding];
            target.extend(&pcm[RATE..23 * RATE]);
            target.resize(target.len() + padding, 0.0);
            let result = align(&reference, &features(&target, 0), &|| true).unwrap();
            assert_eq!(result.status, 0, "{padding} silent samples: {result:?}");
        }
    }

    #[test]
    fn every_affine_review_candidate_is_checked_for_local_edits() {
        let pcm = music(20.0, 1.0);
        let reference = features(&pcm, 0);
        for scale in [0.8, 1.25] {
            let passage = stretch(&pcm, scale);
            for gap_ms in [80, 400] {
                let cut = passage.len() - (0.5 * scale * RATE as f64) as usize;
                let mut edited = passage[..cut].to_vec();
                edited.resize(cut + gap_ms * 16, 0.0);
                edited.extend(&passage[cut..]);
                let mut target = vec![0.0; RATE];
                target.extend(&edited);
                let one = align(&reference, &features(&target, 0), &|| true).unwrap();
                assert_eq!(one.status, 2, "{scale}, {gap_ms}ms: {one:?}");
                target.resize(target.len() + RATE, 0.0);
                target.extend(&edited);
                let repeated = align(&reference, &features(&target, 0), &|| true).unwrap();
                assert_eq!(repeated.status, 2, "{scale}, {gap_ms}ms: {repeated:?}");
                assert!(repeated.candidates.is_empty(), "{repeated:?}");
            }
        }
    }

    #[test]
    fn one_verified_affine_choice_still_requires_review() {
        let pcm = music(20.0, 1.0);
        let reference = features(&pcm, 0);
        let passage = stretch(&pcm, 0.8);
        let mut target = vec![0.0; RATE];
        target.extend(&passage);
        target.resize(target.len() + RATE, 0.0);
        let cut = passage.len() - 6400;
        target.extend(&passage[..cut]);
        target.resize(target.len() + 400 * 16, 0.0);
        target.extend(&passage[cut..]);
        let result = align(&reference, &features(&target, 0), &|| true).unwrap();
        assert_eq!(result.status, 3, "{result:?}");
        assert_eq!(result.candidates.len(), 1, "{result:?}");
        let candidate = result.candidates[0];
        for time in [0.0, 20_000_000.0] {
            assert!(
                (candidate.scale * time + candidate.offset as f64 - (0.8 * time + 1_000_000.0))
                    .abs()
                    < 35_000.0,
                "{candidate:?}",
            );
        }
    }

    #[test]
    fn a_single_provisional_constant_fallback_cannot_bypass_verification() {
        let pcm = music(20.0, 1.0);
        let reference = features(&pcm, 0);
        let mut target = pcm[..12 * RATE].to_vec();
        target.resize(target.len() + RATE, 0.0);
        target.extend(&pcm[12 * RATE..]);
        let target = features(&target, 0);
        let provisional = constant(&reference, &target, &|| true).unwrap();
        assert_eq!(provisional.status, 3, "{provisional:?}");
        assert_eq!(provisional.candidates.len(), 1, "{provisional:?}");
        let result = align(&reference, &target, &|| true).unwrap();
        assert!(result.status != 0 && result.status != 3, "{result:?}");
    }

    #[test]
    fn wide_lag_proposals_observe_cancellation() {
        let reference = features(&music(8.0, 1.0), 0);
        let seed = Seed {
            scale: 1.0,
            offset: 0,
            pitch: false,
        };
        assert!(matches!(
            PeakTimes::new(&reference, seed, &|| false),
            Err(AudioError::Cancelled),
        ));
        let index = PeakTimes::new(&reference, seed, &|| true).unwrap();
        assert_eq!(
            index.lags(&[(&reference.frames[0], 0.0)], &|| false),
            Err(AudioError::Cancelled),
        );
    }
}
