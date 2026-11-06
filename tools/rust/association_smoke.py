#!/usr/bin/env python3
"""Deterministic association corpus; no framework or model dependencies."""
import argparse
import ctypes as c
import hashlib
import json
import struct
from abi_smoke import Rect

EXPECTED = '36cc72a47f5b17181987fe0c0dcc23f86fe08755e819ec2b685817e6b7ff637a'

class Pair(c.Structure):
    _fields_ = [('row', c.c_uint32), ('column', c.c_uint32)]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library')
    lib = c.CDLL(parser.parse_args().library)
    lib.frame_kernel_match_costs.argtypes = [c.POINTER(c.c_float), c.c_size_t,
        c.c_uint32, c.c_uint32, c.c_float, c.POINTER(Pair), c.c_size_t]
    lib.frame_kernel_match_rects.argtypes = [c.POINTER(Rect), c.c_uint32,
        c.POINTER(Rect), c.c_uint32, c.c_float, c.POINTER(Pair), c.c_size_t]
    digest = hashlib.sha256()
    state = 0x13579bdf
    for trial in range(400):
        nr, nc = trial % 9, trial % 13
        values = []
        for _ in range(nr * nc):
            state = (state * 1664525 + 1013904223) & 0xffffffff
            values.append(float('nan') if state & 31 == 0 else float('inf')
                if state & 31 == 1 else .5 if state & 31 == 2
                else c.c_float((state >> 16) / 65535).value)
        costs = (c.c_float * len(values))(*values)
        output = (Pair * min(nr, nc))()
        n = lib.frame_kernel_match_costs(costs, len(values), nr, nc,
            .5 if trial % 2 == 0 else .7, output, len(output))
        assert n >= 0
        digest.update(struct.pack('<I', n))
        for p in output[:n]:
            digest.update(struct.pack('<II', p.row, p.column))
    a = (Rect * 1)(Rect(0, 0, .1, .1))
    out = (Pair * 1)(Pair(42, 42))
    assert lib.frame_kernel_match_rects(a, 1, a, 1, 0, out, 0) == -2
    assert (out[0].row, out[0].column) == (42, 42)
    assert lib.frame_kernel_match_rects(a, 1, a, 1, 0, out, 1) == 1
    assert (out[0].row, out[0].column) == (0, 0)
    a[0].left = float('nan')
    assert lib.frame_kernel_match_rects(a, 1, a, 1, 0, out, 1) == -1
    result = digest.hexdigest()
    assert not EXPECTED or result == EXPECTED, result
    print(json.dumps({'cost_matrices': 400, 'association_vector_sha256': result}))

if __name__ == '__main__':
    main()
