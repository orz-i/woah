//! Pairwise music correspondence. No pose, playback speed, BPM grid or database.
use crate::audio_features::{AudioError, Features};
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
    let constant = constant(t, l, current)?;
    if constant.status == 0 {
        return Ok(constant);
    }
    Ok(affine(t, l, current)?.unwrap_or(constant))
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
            results.push(c);
        }
    }
    results.sort_by(compare);
    let mut selected: Vec<Candidate> = Vec::new();
    for c in results {
        if selected
            .iter()
            .all(|v| (v.scale - c.scale).abs() >= 0.003 || (v.offset - c.offset).abs() >= 150000)
        {
            selected.push(c);
        }
        if selected.len() == 3 {
            break;
        }
    }
    let Some(best) = selected.first() else {
        return Ok(None);
    };
    let status =
        if best.score < 0.64 || best.windows < 4 || best.residual > 35.0 || best.support < 6.0 {
            2
        } else if selected
            .iter()
            .skip(1)
            .any(|v| v.score >= best.score - 0.08 && v.windows >= 4)
        {
            3
        } else {
            0
        };
    Ok(Some(Alignment {
        status,
        candidates: selected,
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
    let shift = -s.scale.log2() * 8.0;
    while time < end {
        let i = round((time - t.start()) as f64 / 10000.0);
        let j = round((time as f64 * s.scale + s.offset as f64 - l.start() as f64) / 10000.0);
        if let (Some(a), Some(b)) = (
            usize::try_from(i).ok().and_then(|i| t.frames.get(i)),
            usize::try_from(j).ok().and_then(|j| l.frames.get(j)),
        ) {
            let onset = dot(&a.onset, &b.onset);
            let mut spectral = 0.0;
            if s.pitch {
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
                sum += 0.9 * spectral + 0.1 * onset;
            } else {
                sum += 0.75 * dot(&a.spectrum, &b.spectrum) + 0.25 * onset;
            }
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
    use crate::audio_features::FeatureBuilder;
    fn music(seconds: f64, rate: f64) -> Vec<f32> {
        let mut seed = 7u64;
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
}
