#!/usr/bin/env python3
"""Run the frozen source-time contract through the real C ABI on every host."""
import ctypes as c
import hashlib
import json
from pathlib import Path
import sys


class TimeRange(c.Structure):
    _fields_ = [('start', c.c_int64), ('end', c.c_int64),
                ('valid', c.c_uint32), ('reserved', c.c_uint32)]


def main():
    lib = c.CDLL(sys.argv[1])
    lib.frame_kernel_time_value.argtypes = [c.c_uint32, c.c_int64, c.c_uint32, c.c_double, c.c_int64]
    lib.frame_kernel_time_value.restype = c.c_int64
    lib.frame_kernel_time_overlap.argtypes = [c.c_int64]*4 + [c.c_double, c.c_int64]
    lib.frame_kernel_time_overlap.restype = TimeRange
    path = Path(__file__).resolve().parents[2] / 'testdata/comparison/timeline-v1.json'
    fixture = json.loads(path.read_text(encoding='utf-8'))
    checks = []
    for case in fixture['map']:
        actual = lib.frame_kernel_time_value(0, case['reference'], 1000000, case['scale'], case['offset'])
        assert actual == case['target'], (case, actual)
        checks.append(actual)
    for case in fixture['overlap']:
        actual = lib.frame_kernel_time_overlap(*case['reference'], *case['target'], case['scale'], case['offset'])
        value = [actual.start, actual.end] if actual.valid else None
        assert value == case['expected'], (case, value)
        checks.append(value)
    assert lib.frame_kernel_time_value(0, 11, 6, .8, 1200000) == 2666667
    print(json.dumps({'schema': fixture['schema'], 'checks': len(checks)+1,
                      'result_sha256': hashlib.sha256(json.dumps(checks).encode()).hexdigest()}))


if __name__ == '__main__':
    main()
