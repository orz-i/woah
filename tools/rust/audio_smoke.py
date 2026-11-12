#!/usr/bin/env python3
"""Exercise actual packed-feature replay, matching, and cancellation through C ABI."""
from array import array
import ctypes as c
import hashlib
import json
import math
import sys


class Candidate(c.Structure):
    _fields_ = [('offset', c.c_int64), ('scale', c.c_double), ('score', c.c_double),
                ('windows', c.c_uint32), ('reserved', c.c_uint32),
                ('support', c.c_double), ('residual', c.c_double)]


class Alignment(c.Structure):
    _fields_ = [('status', c.c_uint32), ('count', c.c_uint32), ('candidates', Candidate*3)]


Callback = c.CFUNCTYPE(c.c_uint32, c.c_void_p)


def music(seconds, rate=1):
    seed = 7
    notes = []
    for _ in range(640):
        seed = (seed*6364136223846793005+1442695040888963407) & ((1 << 64)-1)
        notes.append(160+math.floor((seed >> 11)/(1 << 53)*800))
    pcm = array('f')
    for i in range(round(seconds*16000)):
        t = i/16000
        beat = t*rate*8
        phase = t*notes[int(beat)]*2*math.pi
        pcm.append((1-beat % 1)**.4*(.25*math.sin(phase)+.12*math.sin(phase*1.5)+.07*math.sin(phase*2.23)))
    return pcm


def main():
    lib = c.CDLL(sys.argv[1])
    lib.frame_kernel_audio_create.argtypes = [c.c_int64]
    lib.frame_kernel_audio_create.restype = c.c_uint64
    lib.frame_kernel_audio_destroy.argtypes = [c.c_uint64]
    lib.frame_kernel_audio_push.argtypes = [c.c_uint64, c.POINTER(c.c_float), c.c_size_t,
                                          c.c_uint32, c.c_uint32, c.c_uint32, c.c_int64]
    lib.frame_kernel_audio_finish.argtypes = [c.c_uint64, c.POINTER(c.c_uint8), c.c_size_t]
    lib.frame_kernel_audio_finish.restype = c.c_int64
    lib.frame_kernel_audio_align.argtypes = [c.POINTER(c.c_uint8), c.c_size_t,
                                           c.POINTER(c.c_uint8), c.c_size_t, Callback, c.c_void_p]
    lib.frame_kernel_audio_align.restype = Alignment
    assert c.sizeof(Candidate) == 48 and c.sizeof(Alignment) == 152

    def features(pcm, origin):
        handle = lib.frame_kernel_audio_create(origin)
        assert handle
        try:
            for start in range(0, len(pcm), 4096):
                part = pcm[start:start+4096]
                view = (c.c_float*len(part)).from_buffer(part)
                assert lib.frame_kernel_audio_push(handle, view, len(part), 0, 16000, 1, 0) == 0
            size = lib.frame_kernel_audio_finish(handle, None, 0)
            assert 12 <= size <= 20880012
            output = (c.c_uint8*size)()
            assert lib.frame_kernel_audio_finish(handle, output, size) == size
            return output
        finally:
            lib.frame_kernel_audio_destroy(handle)
            assert lib.frame_kernel_audio_finish(handle, None, 0) == -1

    reference_pcm = music(10)
    reference = features(reference_pcm, 30000)
    proofs = []
    for rate in (1, 1.25):
        pcm = array('f', [0.0]*11200)
        pcm.extend(music(10/rate, rate))
        target = features(pcm, 90000)
        result = lib.frame_kernel_audio_align(reference, len(reference), target, len(target), Callback(lambda _: 1), None)
        assert result.status == 0 and 1 <= result.count <= 3, (rate, result.status, result.count)
        best = result.candidates[0]
        a, b = 1/rate, 790000-30000/rate
        errors = [abs(best.scale*t+best.offset-(a*t+b)) for t in (3000000, 6000000, 9000000)]
        assert max(errors) < 35000, (rate, errors)
        proofs.append({'rate': rate, 'status': result.status, 'offset': best.offset,
                       'scale': best.scale, 'max_truth_error_us': max(errors),
                       'pcm_sha256': hashlib.sha256(pcm.tobytes()).hexdigest(),
                       'features_sha256': hashlib.sha256(bytes(target)).hexdigest()})
    cancelled = lib.frame_kernel_audio_align(reference, len(reference), reference, len(reference), Callback(lambda _: 0), None)
    assert cancelled.status == 4
    invalid = lib.frame_kernel_audio_align(reference, 3, reference, len(reference), Callback(lambda _: 1), None)
    assert invalid.status == 5
    print(json.dumps({'schema': 'frame-kernel.audio-replay.v1', 'cases': proofs, 'cancelled': True, 'invalid_rejected': True}))


if __name__ == '__main__':
    main()
