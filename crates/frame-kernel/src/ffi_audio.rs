//! Typed analysis sessions. Native adapters retain PCM and callback ownership.
use crate::{
    audio_alignment::{Candidate, align},
    audio_features::{AudioError, FeatureBuilder, Features},
    audio_pcm::Normalizer,
};
use std::{
    collections::BTreeMap,
    ffi::c_void,
    sync::{Arc, Mutex, OnceLock},
};

struct Session {
    builder: Option<FeatureBuilder>,
    normalizer: Option<Normalizer>,
    format: Option<(u32, u32)>,
    origin: i64,
    input_frames: i64,
    raw: bool,
    encoded: Option<Vec<u8>>,
}
impl Session {
    fn pcm(
        &mut self,
        values: &[f32],
        rate: u32,
        channels: u32,
        time: i64,
    ) -> Result<(), AudioError> {
        if self.raw
            || self.encoded.is_some()
            || channels == 0
            || !values.len().is_multiple_of(channels as usize)
        {
            return Err(AudioError::Invalid);
        }
        if values.is_empty() {
            return Ok(());
        }
        if let Some(format) = self.format {
            if format != (rate, channels) {
                return Err(AudioError::Discontinuous);
            }
            let expected = self.origin + self.input_frames * 1_000_000 / i64::from(rate);
            if expected.abs_diff(time) > 20_000 {
                return Err(AudioError::Discontinuous);
            }
        } else {
            self.normalizer = Some(Normalizer::new(rate as usize, channels as usize)?);
            self.builder = Some(FeatureBuilder::new(time));
            self.origin = time;
            self.format = Some((rate, channels));
        }
        self.input_frames += (values.len() / channels as usize) as i64;
        if self.input_frames > i64::from(rate) * 601 {
            return Err(AudioError::TooLong);
        }
        let normalized = self
            .normalizer
            .as_mut()
            .ok_or(AudioError::Invalid)?
            .push(values)?;
        self.builder
            .as_mut()
            .ok_or(AudioError::Invalid)?
            .add(&normalized)
    }
    fn finish(&mut self) -> Result<&[u8], AudioError> {
        if self.encoded.is_none() {
            if let Some(n) = self.normalizer.as_mut() {
                let tail = n.finish()?;
                self.builder
                    .as_mut()
                    .ok_or(AudioError::Invalid)?
                    .add(&tail)?;
            }
            let builder = self.builder.take().ok_or(AudioError::Invalid)?;
            self.encoded = Some(builder.finish().encode());
        }
        self.encoded.as_deref().ok_or(AudioError::Invalid)
    }
}
type Owned = Arc<Mutex<Session>>;
static SESSIONS: OnceLock<Mutex<BTreeMap<u64, Owned>>> = OnceLock::new();
fn registry() -> &'static Mutex<BTreeMap<u64, Owned>> {
    SESSIONS.get_or_init(|| Mutex::new(BTreeMap::new()))
}
fn owned(id: u64) -> Option<Owned> {
    registry().lock().ok()?.get(&id).cloned()
}
fn error(e: AudioError) -> i32 {
    match e {
        AudioError::Invalid => -1,
        AudioError::TooLong => -2,
        AudioError::Discontinuous => -3,
        AudioError::Cancelled => -4,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_audio_create(origin: i64) -> u64 {
    if origin.unsigned_abs() > (1u64 << 52) {
        return 0;
    }
    let Ok(mut all) = registry().lock() else {
        return 0;
    };
    if all.len() >= 64 {
        return 0;
    }
    let Some(id) = crate::ffi_tracking::next_session_id() else {
        return 0;
    };
    all.insert(
        id,
        Arc::new(Mutex::new(Session {
            builder: Some(FeatureBuilder::new(origin)),
            normalizer: None,
            format: None,
            origin,
            input_frames: 0,
            raw: false,
            encoded: None,
        })),
    );
    id
}

#[unsafe(no_mangle)]
pub extern "C" fn frame_kernel_audio_destroy(id: u64) {
    if let Ok(mut all) = registry().lock() {
        all.remove(&id);
    }
}

/// # Safety
/// Values point to len readable Float32 samples retained for this call. mode 0
/// is mono/16k normalized samples; mode 1 is native interleaved source PCM.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_audio_push(
    id: u64,
    values: *const f32,
    len: usize,
    mode: u32,
    rate: u32,
    channels: u32,
    time: i64,
) -> i32 {
    if len > 1_048_576 || (values.is_null() && len != 0) || time.unsigned_abs() > (1u64 << 52) {
        return -1;
    }
    let samples = if len == 0 {
        &[]
    } else {
        // SAFETY: The caller retains readable PCM, and null/length were checked.
        unsafe { std::slice::from_raw_parts(values, len) }
    };
    let Some(s) = owned(id) else {
        return -1;
    };
    let Ok(mut s) = s.lock() else {
        return -1;
    };
    let result = match mode {
        0 if s.format.is_none() && s.encoded.is_none() => {
            s.raw = true;
            s.builder
                .as_mut()
                .ok_or(AudioError::Invalid)
                .and_then(|b| b.add(samples))
        }
        1 => s.pcm(samples, rate, channels, time),
        _ => Err(AudioError::Invalid),
    };
    result.map_or_else(error, |_| 0)
}

/// # Safety
/// Null with capacity=0 queries the required byte count, finalizing once. A
/// non-null output owns capacity writable bytes and cannot alias session inputs.
/// Returns byte count, or -1 invalid/-2 too long/-3 discontinuous. Destroy after use.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_audio_finish(id: u64, out: *mut u8, capacity: usize) -> i64 {
    let Some(s) = owned(id) else {
        return -1;
    };
    let Ok(mut s) = s.lock() else {
        return -1;
    };
    let bytes = match s.finish() {
        Ok(v) => v,
        Err(e) => return i64::from(error(e)),
    };
    if out.is_null() && capacity != 0 {
        return -1;
    }
    if capacity >= bytes.len() && !out.is_null() {
        // SAFETY: The caller owns sufficient writable output, disjoint from the session.
        unsafe {
            std::ptr::copy_nonoverlapping(bytes.as_ptr(), out, bytes.len());
        }
    }
    bytes.len() as i64
}

#[repr(C)]
#[derive(Default)]
pub struct AlignmentOutput {
    pub status: u32,
    pub count: u32,
    pub candidates: [Candidate; 3],
}
pub type CurrentCallback = Option<extern "C" fn(*mut c_void) -> u32>;

/// # Safety
/// Both byte buffers are readable and retained for the call. The optional
/// callback/context remain valid, run synchronously on the calling thread, and
/// cannot unwind across the C boundary. Return 1 to continue, 0 to cancel.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn frame_kernel_audio_align(
    reference: *const u8,
    reference_len: usize,
    target: *const u8,
    target_len: usize,
    current: CurrentCallback,
    context: *mut c_void,
) -> AlignmentOutput {
    let invalid = || AlignmentOutput {
        status: 5,
        ..AlignmentOutput::default()
    };
    if reference.is_null()
        || target.is_null()
        || reference_len > 20_880_012
        || target_len > 20_880_012
    {
        return invalid();
    }
    // SAFETY: Callers retain the two readable feature buffers for the stated lengths.
    let (r, t) = unsafe {
        (
            std::slice::from_raw_parts(reference, reference_len),
            std::slice::from_raw_parts(target, target_len),
        )
    };
    let (Ok(r), Ok(t)) = (Features::decode(r), Features::decode(t)) else {
        return invalid();
    };
    let active = || current.is_none_or(|callback| callback(context) != 0);
    match align(&r, &t, &active) {
        Ok(result) => {
            let mut output = AlignmentOutput {
                status: result.status,
                count: result.candidates.len() as u32,
                ..AlignmentOutput::default()
            };
            for (dst, src) in output.candidates.iter_mut().zip(result.candidates) {
                *dst = src;
            }
            output
        }
        Err(AudioError::Cancelled) => AlignmentOutput {
            status: 4,
            ..AlignmentOutput::default()
        },
        Err(_) => invalid(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn feature_handles_finalize_once_and_reject_stale_ids() {
        let id = frame_kernel_audio_create(123000);
        assert_ne!(id, 0);
        let pcm = [0f32; 1184];
        // SAFETY: PCM and later output arrays are owned and live for each call.
        unsafe {
            assert_eq!(
                frame_kernel_audio_push(id, pcm.as_ptr(), pcm.len(), 0, 16000, 1, 0),
                0
            );
            let needed = frame_kernel_audio_finish(id, std::ptr::null_mut(), 0);
            assert_eq!(needed, 12 + 2 * 348);
            let mut bytes = vec![0; needed as usize];
            assert_eq!(
                frame_kernel_audio_finish(id, bytes.as_mut_ptr(), bytes.len()),
                needed
            );
            assert_eq!(Features::decode(&bytes).unwrap().frames[0].time, 155000);
            assert_eq!(
                frame_kernel_audio_push(id, pcm.as_ptr(), pcm.len(), 0, 16000, 1, 0),
                -1
            );
            frame_kernel_audio_destroy(id);
            assert_eq!(frame_kernel_audio_finish(id, std::ptr::null_mut(), 0), -1);
        }
    }
    extern "C" fn cancelled(_: *mut c_void) -> u32 {
        0
    }
    #[test]
    fn cancellation_and_invalid_replays_have_distinct_status() {
        let f = Features::default().encode();
        // SAFETY: Both immutable feature buffers remain live; callback does not dereference context.
        unsafe {
            assert_eq!(
                frame_kernel_audio_align(
                    f.as_ptr(),
                    f.len(),
                    f.as_ptr(),
                    f.len(),
                    Some(cancelled),
                    std::ptr::null_mut()
                )
                .status,
                4
            );
            assert_eq!(
                frame_kernel_audio_align(
                    f.as_ptr(),
                    3,
                    f.as_ptr(),
                    f.len(),
                    None,
                    std::ptr::null_mut()
                )
                .status,
                5
            );
        }
    }
}
