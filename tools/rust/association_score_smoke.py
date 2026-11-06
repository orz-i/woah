#!/usr/bin/env python3
"""Cross-host float-bit corpus, also checked against frozen Kotlin arithmetic."""
import argparse
import ctypes as c
import hashlib
import json
import struct
from abi_smoke import Rect

EXPECTED = '9970b76bf03ed4aeb7f0fcc3df51b8b2a41c1dea8f5cfa0f744c255bfe4fa55d'

class Weights(c.Structure):
    _fields_ = [(n, c.c_float) for n in ('bounds', 'mask', 'motion', 'direction', 'gating_threshold')]

class Evidence(c.Structure):
    _fields_ = [('predicted', Rect), ('observed', Rect), ('candidate', Rect)] + [
        (n, c.c_float) for n in ('mask_iou', 'gating_distance', 'velocity_x', 'velocity_y', 'history_x', 'history_y')
    ] + [('history_age_us', c.c_int64), ('flags', c.c_uint32)]

def corpus():
    state = 0x13579bdf
    def next_int(limit):
        nonlocal state
        state = (state * 1664525 + 1013904223) & 0xffffffff
        return (state >> 8) % limit
    f = lambda x: c.c_float(x).value
    for _ in range(10000):
        p = [next_int(8000)/4, next_int(5000)/4, 10+next_int(1200)/4, 20+next_int(2000)/4]
        v = [p[0], p[1], f(p[0]+p[2]), f(p[1]+p[3])]
        v += [p[0]+(next_int(800)-400)/4, p[1]+(next_int(400)-200)/4]
        v += [f(v[4]+p[2]), f(v[5]+p[3])]
        v += [p[0]+(next_int(1600)-800)/4, p[1]+(next_int(1200)-600)/4]
        v += [f(v[8]+f(f(p[2]*(next_int(150)+50))/100)), f(v[9]+f(f(p[3]*(next_int(150)+50))/100))]
        v += [f(next_int(65536)/65535), f(next_int(6400)/100)]
        v += [(next_int(2400)-1200)/4, (next_int(2400)-1200)/4]
        v += [(next_int(800)-400)/4, (next_int(400)-200)/4]
        flags, age = next_int(32), next_int(800001)
        yield v, age, flags

def main():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('library')
    lib = c.CDLL(p.parse_args().library)
    lib.frame_kernel_association_defaults.restype = Weights
    lib.frame_kernel_association_score.argtypes = [Evidence, Weights, c.POINTER(c.c_float)]
    lib.frame_kernel_reservation_release.argtypes = [c.c_uint32, c.c_uint32, c.c_float, c.c_float]
    weights = lib.frame_kernel_association_defaults()
    assert bytes(weights) == struct.pack('<5f', .35, .25, .25, .15, 16)
    digest = hashlib.sha256()
    for v, age, flags in corpus():
        evidence = Evidence(Rect(*v[:4]), Rect(*v[4:8]), Rect(*v[8:12]), *v[12:], age, flags)
        score = c.c_float(42)
        assert lib.frame_kernel_association_score(evidence, weights, c.byref(score)) == 0
        digest.update(struct.pack('<f', score.value))
    evidence.mask_iou = float('nan'); score.value = 42
    assert lib.frame_kernel_association_score(evidence, weights, c.byref(score)) == -1 and score.value == 42
    count = 0
    for flags in range(8):
        for groups in range(4):
            for box in [0, .499, .5, .9]:
                for mask in [0, .449, .45, .9]:
                    expected = flags & 3 == 3 and groups > 0 and (groups >= 2 or (flags & 4 == 0 and box >= .5 and mask >= .45))
                    result = lib.frame_kernel_reservation_release(flags, groups, box, mask)
                    assert result == int(expected)
                    digest.update(struct.pack('<i', result)); count += 1
    assert lib.frame_kernel_reservation_release(3, 1, float('nan'), .9) == -1
    result = digest.hexdigest()
    assert not EXPECTED or result == EXPECTED, result
    print(json.dumps({'score_cases': 10000, 'reservation_cases': count, 'identity_score_vector_sha256': result}))

if __name__ == '__main__':
    main()
