#!/usr/bin/env python3
"""Small PCM alignment check used by the real-media regression auditor."""
import numpy as np

def compare_audio_samples(reference, actual, rate=16000, maximum_lag_seconds=.1):
    reference=np.asarray(reference,dtype=np.float64)
    actual=np.asarray(actual,dtype=np.float64)
    count=min(len(reference),len(actual))
    if count<32:raise ValueError('Insufficient decoded audio samples')
    reference=reference[:count];actual=actual[:count]
    reference=reference-reference.mean();actual=actual-actual.mean()
    ref_rms=float(np.sqrt(np.mean(reference*reference)))
    out_rms=float(np.sqrt(np.mean(actual*actual)))
    if ref_rms<1e-7:
        return {'correlation':1.0 if out_rms<1e-6 else 0.0,'lag_ms':0.0,'reference_rms':ref_rms,'output_rms':out_rms,'gain_ratio':1.0 if out_rms<1e-6 else float('inf')}
    size=1<<(2*count-1).bit_length()
    spectrum=np.fft.rfft(actual,size)*np.conj(np.fft.rfft(reference,size))
    cross=np.fft.irfft(spectrum,size)
    limit=min(count//4,round(rate*maximum_lag_seconds))
    lags=np.arange(-limit,limit+1)
    best=int(lags[np.argmax(cross[lags % size])])
    if best>=0:
        left=reference[:count-best];right=actual[best:]
    else:
        left=reference[-best:];right=actual[:count+best]
    denominator=float(np.linalg.norm(left)*np.linalg.norm(right))
    correlation=float(np.dot(left,right)/denominator) if denominator else 0.0
    return {'correlation':correlation,'lag_ms':best*1000/rate,
            'reference_rms':ref_rms,'output_rms':out_rms,'gain_ratio':out_rms/ref_rms}
