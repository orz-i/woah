//! Fixed-rate analysis resampling. Playback time stretching stays native.
use crate::audio_features::{AudioError, RATE};
use rubato::audioadapter_buffers::direct::InterleavedSlice;
use rubato::{Fft, FixedSync, Resampler, WindowFunction};

pub struct Normalizer {
    rate: usize,
    channels: usize,
    resampler: Option<Fft<f32>>,
    pending: Vec<f32>,
    output: Vec<f32>,
    delay: usize,
    total: usize,
    emitted: usize,
}
impl Normalizer {
    pub fn new(rate: usize, channels: usize) -> Result<Self, AudioError> {
        if !(8000..=192000).contains(&rate) || !(1..=8).contains(&channels) {
            return Err(AudioError::Invalid);
        }
        let resampler = if rate == RATE {
            None
        } else {
            {
                let mut a = rate;
                let mut b = RATE;
                while b != 0 {
                    let next = a % b;
                    a = b;
                    b = next;
                }
                let input_base = rate / a;
                let output_base = RATE / a;
                let mut blocks = 512usize.div_ceil(input_base);
                // Even input/output FFT blocks avoid fractional filter group delay.
                if !(input_base * blocks).is_multiple_of(2)
                    || !(output_base * blocks).is_multiple_of(2)
                {
                    blocks += 1;
                }
                Some(
                    Fft::<f32>::new_custom(
                        rate,
                        RATE,
                        input_base * blocks,
                        1,
                        1,
                        WindowFunction::BlackmanHarris2,
                        FixedSync::Both,
                    )
                    .map_err(|_| AudioError::Invalid)?,
                )
            }
        };
        let delay = resampler.as_ref().map_or(0, |r| r.output_delay());
        let capacity = resampler.as_ref().map_or(0, |r| r.output_frames_max());
        Ok(Self {
            rate,
            channels,
            resampler,
            pending: vec![],
            output: vec![0.0; capacity],
            delay,
            total: 0,
            emitted: 0,
        })
    }
    pub fn push(&mut self, input: &[f32]) -> Result<Vec<f32>, AudioError> {
        if !input.len().is_multiple_of(self.channels) || input.iter().any(|v| !v.is_finite()) {
            return Err(AudioError::Invalid);
        }
        let mono: Vec<_> = input
            .chunks_exact(self.channels)
            .map(|c| {
                (c.iter().map(|&v| f64::from(v)).sum::<f64>() / self.channels as f64)
                    .clamp(-1.0, 1.0) as f32
            })
            .collect();
        self.total += mono.len();
        if self.resampler.is_none() {
            self.emitted += mono.len();
            return Ok(mono);
        }
        self.pending.extend(mono);
        self.process()
    }
    fn process(&mut self) -> Result<Vec<f32>, AudioError> {
        let r = self.resampler.as_mut().ok_or(AudioError::Invalid)?;
        let mut cursor = 0;
        let mut out = Vec::new();
        while self.pending.len() - cursor >= r.input_frames_next() {
            let need = r.input_frames_next();
            let capacity = self.output.len();
            let input = InterleavedSlice::new(&self.pending[cursor..cursor + need], 1, need)
                .map_err(|_| AudioError::Invalid)?;
            let mut output = InterleavedSlice::new_mut(&mut self.output, 1, capacity)
                .map_err(|_| AudioError::Invalid)?;
            let (used, written) = r
                .process_into_buffer(&input, &mut output, None)
                .map_err(|_| AudioError::Invalid)?;
            if used != need {
                return Err(AudioError::Invalid);
            }
            let skip = written.min(self.delay);
            self.delay -= skip;
            out.extend_from_slice(&self.output[skip..written]);
            cursor += used;
        }
        self.pending.drain(..cursor);
        self.emitted += out.len();
        Ok(out)
    }
    pub fn finish(&mut self) -> Result<Vec<f32>, AudioError> {
        if self.resampler.is_none() {
            return Ok(vec![]);
        }
        let target =
            ((self.total as u64 * RATE as u64 + self.rate as u64 / 2) / self.rate as u64) as usize;
        let before = self.emitted;
        let mut tail = Vec::new();
        while self.emitted < target {
            let need = self
                .resampler
                .as_ref()
                .ok_or(AudioError::Invalid)?
                .input_frames_next();
            self.pending.resize(need, 0.0);
            tail.extend(self.process()?);
        }
        tail.truncate(target.saturating_sub(before));
        self.emitted = target;
        Ok(tail)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn same_rate_keeps_samples_and_downmix_has_one_policy() {
        let input = [-1.0, -0.5, 0.0, 0.5, 32767.0 / 32768.0];
        let mut n = Normalizer::new(16000, 1).unwrap();
        assert_eq!(n.push(&input).unwrap(), input);
        assert!(n.finish().unwrap().is_empty());
        let mut n = Normalizer::new(16000, 2).unwrap();
        assert_eq!(n.push(&[0.5, -0.5, 0.25, 0.75]).unwrap(), [0.0, 0.5]);
    }
    #[test]
    fn chunks_and_tail_preserve_phase_and_exact_sample_count() {
        for rate in [44100, 48000] {
            let input: Vec<_> = (0..rate)
                .map(|i| {
                    (2.0 * std::f64::consts::PI * 440.0 * i as f64 / rate as f64).sin() as f32 * 0.5
                })
                .collect();
            let mut a = Normalizer::new(rate, 1).unwrap();
            let mut whole = a.push(&input).unwrap();
            whole.extend(a.finish().unwrap());
            let mut b = Normalizer::new(rate, 1).unwrap();
            let mut chunks = Vec::new();
            for part in input.chunks(317) {
                chunks.extend(b.push(part).unwrap());
            }
            chunks.extend(b.finish().unwrap());
            assert_eq!(whole.len(), 16000);
            assert_eq!(whole, chunks);
            let error = (100..15900)
                .map(|i| {
                    (f64::from(whole[i])
                        - 0.5 * (2.0 * std::f64::consts::PI * 440.0 * i as f64 / 16000.0).sin())
                    .abs()
                })
                .fold(0.0, f64::max);
            assert!(error < 0.005, "{rate}: {error}");
        }
    }
}
