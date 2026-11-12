//! Streaming spectral observations; shared by all native analysis adapters.
use std::f64::consts::PI;

pub const RATE: usize = 16000;
pub const HOP: usize = 160;
pub const FFT: usize = 1024;
pub const MAX_FRAMES: usize = 60000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AudioError {
    Invalid,
    TooLong,
    Discontinuous,
    Cancelled,
}

#[derive(Clone, Debug)]
pub struct Frame {
    pub time: i64,
    pub spectrum: [f32; 16],
    pub onset: [f32; 16],
    pub peaks: Vec<i32>,
    pub pitch: [f32; 48],
}

#[derive(Clone, Debug, Default)]
pub struct Features {
    pub frames: Vec<Frame>,
}

impl Features {
    pub fn start(&self) -> i64 {
        self.frames.first().map_or(0, |f| f.time)
    }
    pub fn end(&self) -> i64 {
        self.frames.last().map_or(0, |f| f.time) + 10000
    }
    pub fn encode(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(12 + self.frames.len() * 348);
        out.extend_from_slice(b"FKMF\x01\0\0\0");
        out.extend_from_slice(&(self.frames.len() as u32).to_le_bytes());
        for f in &self.frames {
            out.extend_from_slice(&f.time.to_le_bytes());
            for v in f.spectrum.iter().chain(f.onset.iter()) {
                out.extend_from_slice(&v.to_le_bytes());
            }
            out.extend_from_slice(&(f.peaks.len() as u32).to_le_bytes());
            for i in 0..4 {
                out.extend_from_slice(&f.peaks.get(i).copied().unwrap_or(0).to_le_bytes());
            }
            for v in f.pitch {
                out.extend_from_slice(&v.to_le_bytes());
            }
        }
        out
    }
    pub fn decode(bytes: &[u8]) -> Result<Self, AudioError> {
        if bytes.len() < 12 || &bytes[..8] != b"FKMF\x01\0\0\0" {
            return Err(AudioError::Invalid);
        }
        let count =
            u32::from_le_bytes(bytes[8..12].try_into().map_err(|_| AudioError::Invalid)?) as usize;
        if count > MAX_FRAMES || bytes.len() != 12 + count * 348 {
            return Err(AudioError::Invalid);
        }
        let mut frames = Vec::with_capacity(count);
        for row in bytes[12..].chunks_exact(348) {
            let time = i64::from_le_bytes(row[..8].try_into().map_err(|_| AudioError::Invalid)?);
            if time.unsigned_abs() > 9_007_199_254_740_991
                || frames
                    .last()
                    .is_some_and(|f: &Frame| f.time + 10000 != time)
            {
                return Err(AudioError::Invalid);
            }
            let mut floats = [0f32; 80];
            for (i, v) in floats.iter_mut().enumerate() {
                let offset = if i < 32 {
                    8 + i * 4
                } else {
                    156 + (i - 32) * 4
                };
                *v = f32::from_le_bytes(
                    row[offset..offset + 4]
                        .try_into()
                        .map_err(|_| AudioError::Invalid)?,
                );
                if !v.is_finite() || v.abs() > 1.001 {
                    return Err(AudioError::Invalid);
                }
            }
            let n = u32::from_le_bytes(row[136..140].try_into().map_err(|_| AudioError::Invalid)?)
                as usize;
            if n > 4 {
                return Err(AudioError::Invalid);
            }
            let mut peaks = Vec::with_capacity(n);
            for i in 0..n {
                let offset = 140 + i * 4;
                let peak = i32::from_le_bytes(
                    row[offset..offset + 4]
                        .try_into()
                        .map_err(|_| AudioError::Invalid)?,
                );
                if !(4..500).contains(&peak) {
                    return Err(AudioError::Invalid);
                }
                peaks.push(peak);
            }
            frames.push(Frame {
                time,
                spectrum: floats[..16].try_into().map_err(|_| AudioError::Invalid)?,
                onset: floats[16..32].try_into().map_err(|_| AudioError::Invalid)?,
                peaks,
                pitch: floats[32..].try_into().map_err(|_| AudioError::Invalid)?,
            });
        }
        Ok(Self { frames })
    }
}

pub struct FeatureBuilder {
    origin: i64,
    samples: usize,
    ring: Vec<f64>,
    real: Vec<f64>,
    imaginary: Vec<f64>,
    window: Vec<f64>,
    cosine: Vec<f64>,
    sine: Vec<f64>,
    reverse: Vec<usize>,
    previous: [f32; 16],
    frames: Vec<Frame>,
}

impl FeatureBuilder {
    pub fn new(origin: i64) -> Self {
        Self {
            origin,
            samples: 0,
            ring: vec![0.0; FFT],
            real: vec![0.0; FFT],
            imaginary: vec![0.0; FFT],
            window: (0..FFT)
                .map(|i| 0.5 - 0.5 * (2.0 * PI * i as f64 / (FFT - 1) as f64).cos())
                .collect(),
            cosine: (0..FFT / 2)
                .map(|i| (-2.0 * PI * i as f64 / FFT as f64).cos())
                .collect(),
            sine: (0..FFT / 2)
                .map(|i| (-2.0 * PI * i as f64 / FFT as f64).sin())
                .collect(),
            reverse: (0..FFT)
                .map(|i| (i as u32).reverse_bits() as usize >> 22)
                .collect(),
            previous: [0.0; 16],
            frames: Vec::new(),
        }
    }
    pub fn add(&mut self, pcm: &[f32]) -> Result<(), AudioError> {
        if pcm.iter().any(|v| !v.is_finite()) {
            return Err(AudioError::Invalid);
        }
        for &value in pcm {
            self.ring[self.samples % FFT] = f64::from(value);
            self.samples += 1;
            if self.samples >= FFT && (self.samples - FFT).is_multiple_of(HOP) {
                self.extract()?;
            }
        }
        Ok(())
    }
    fn extract(&mut self) -> Result<(), AudioError> {
        if self.frames.len() >= MAX_FRAMES {
            return Err(AudioError::TooLong);
        }
        let first = self.samples % FFT;
        for i in 0..FFT {
            self.real[self.reverse[i]] = self.ring[(first + i) % FFT] * self.window[i];
            self.imaginary[i] = 0.0;
        }
        let mut length = 2;
        while length <= FFT {
            let half = length / 2;
            let stride = FFT / length;
            for start in (0..FFT).step_by(length) {
                for j in 0..half {
                    let p = start + j;
                    let q = p + half;
                    let c = self.cosine[j * stride];
                    let s = self.sine[j * stride];
                    let r = self.real[q] * c - self.imaginary[q] * s;
                    let im = self.real[q] * s + self.imaginary[q] * c;
                    self.real[q] = self.real[p] - r;
                    self.imaginary[q] = self.imaginary[p] - im;
                    self.real[p] += r;
                    self.imaginary[p] += im;
                }
            }
            length *= 2;
        }
        let power: Vec<_> = (0..FFT / 2)
            .map(|i| self.real[i] * self.real[i] + self.imaginary[i] * self.imaginary[i])
            .collect();
        let edges = [
            2, 4, 6, 9, 13, 19, 27, 38, 54, 76, 108, 152, 214, 300, 380, 450, 512,
        ];
        let spectrum = std::array::from_fn(|b| {
            (1.0 + power[edges[b]..edges[b + 1]].iter().sum::<f64>()
                / (edges[b + 1] - edges[b]) as f64
                * 100.0)
                .ln() as f32
        });
        let onset = std::array::from_fn(|b| (spectrum[b] - self.previous[b]).max(0.0));
        self.previous = spectrum;
        let peaks = spectral_peaks(&power);
        let pitch = std::array::from_fn(|b| {
            let center = 4.0 * 2f64.powf(b as f64 / 8.0);
            let lo = (center / 2f64.powf(1.0 / 8.0)).floor().max(1.0) as usize;
            let hi = (center * 2f64.powf(1.0 / 8.0))
                .ceil()
                .min((power.len() - 1) as f64) as usize;
            let mut sum = 0.0;
            let mut weight = 0.0;
            for (i, &p) in power.iter().enumerate().take(hi + 1).skip(lo) {
                let w = (1.0 - (i as f64 / center).log2().abs() * 8.0).max(0.0);
                sum += p * w;
                weight += w;
            }
            (1.0 + sum / weight.max(0.01) * 100.0).ln() as f32
        });
        self.frames.push(Frame {
            time: self.origin + (self.samples - FFT / 2) as i64 * 1_000_000 / RATE as i64,
            spectrum,
            onset,
            peaks,
            pitch,
        });
        Ok(())
    }
    pub fn finish(mut self) -> Features {
        if self.frames.is_empty() {
            return Features::default();
        }
        let n = self.frames.len() as f64;
        let mut mean = [0f64; 64];
        let mut dev = [0f64; 64];
        for row in &self.frames {
            for (b, v) in row.spectrum.iter().chain(row.pitch.iter()).enumerate() {
                mean[b] += f64::from(*v);
            }
        }
        for m in &mut mean {
            *m /= n;
        }
        for row in &self.frames {
            for (b, v) in row.spectrum.iter().chain(row.pitch.iter()).enumerate() {
                dev[b] += (f64::from(*v) - mean[b]).powi(2);
            }
        }
        for d in &mut dev {
            *d = (*d / n).sqrt().max(0.25);
        }
        for row in &mut self.frames {
            for (b, v) in row
                .spectrum
                .iter_mut()
                .chain(row.pitch.iter_mut())
                .enumerate()
            {
                *v = ((f64::from(*v) - mean[b]) / dev[b]) as f32;
            }
            normalize(&mut row.spectrum);
            normalize(&mut row.pitch);
            normalize(&mut row.onset);
        }
        Features {
            frames: self.frames,
        }
    }
}

fn spectral_peaks(power: &[f64]) -> Vec<i32> {
    let mean = power.iter().sum::<f64>() / power.len() as f64;
    let mut peaks: Vec<_> = (4..500)
        .filter(|&i| power[i] > power[i - 1] && power[i] >= power[i + 1] && power[i] > mean * 3.0)
        .collect();
    peaks.sort_by(|&a, &b| power[b].total_cmp(&power[a]).then(a.cmp(&b)));
    peaks.truncate(4);
    peaks.into_iter().map(|i| i as i32).collect()
}

fn normalize(values: &mut [f32]) {
    let norm = values
        .iter()
        .map(|&v| f64::from(v) * f64::from(v))
        .sum::<f64>()
        .sqrt();
    if norm > 1e-8 {
        for v in values {
            *v = (f64::from(*v) / norm) as f32;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn peak_ties_plateaus_and_strict_threshold_keep_original_contract() {
        let mut power = [0.0; 512];
        for (i, v) in [
            (10, 10.0),
            (20, 10.0),
            (30, 20.0),
            (40, 10.0),
            (41, 10.0),
            (50, 10.0),
        ] {
            power[i] = v;
        }
        assert_eq!(spectral_peaks(&power), [30, 10, 20, 40]);
        let mut boundary = [1.0; 512];
        boundary[10] = 3.0;
        boundary[11] = 0.0;
        boundary[12] = 0.0;
        assert!(spectral_peaks(&boundary).is_empty());
        boundary[10] = 3.01;
        assert_eq!(spectral_peaks(&boundary), [10]);
    }
    #[test]
    fn silence_centers_and_replay_bytes_are_stable() {
        let mut builder = FeatureBuilder::new(123000);
        builder.add(&[0.0; 1184]).unwrap();
        let f = builder.finish();
        assert_eq!(f.frames.len(), 2);
        assert_eq!(f.frames[0].time, 155000);
        assert_eq!(f.frames[1].time, 165000);
        assert!(f.frames.iter().all(|r| r.peaks.is_empty()));
        assert_eq!(Features::decode(&f.encode()).unwrap().encode(), f.encode());
        let mut builder = FeatureBuilder::new(0);
        assert_eq!(builder.add(&[f32::NAN]), Err(AudioError::Invalid));
        assert!(Features::decode(b"bad").is_err());
    }
}
