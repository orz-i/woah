"""Tiny deterministic RGB timecode rasterizer; Python standard library only.

The 20-bit source-frame strip also permits screen-capture measurements without
OCR or installed fonts. Coordinates are in the 640x360 reference canvas.
"""
from __future__ import annotations

FONT = {
    '0': ('01110','10001','10011','10101','11001','10001','01110'),
    '1': ('00100','01100','00100','00100','00100','00100','01110'),
    '2': ('01110','10001','00001','00010','00100','01000','11111'),
    '3': ('11110','00001','00001','01110','00001','00001','11110'),
    '4': ('00010','00110','01010','10010','11111','00010','00010'),
    '5': ('11111','10000','10000','11110','00001','00001','11110'),
    '6': ('01110','10000','10000','11110','10001','10001','01110'),
    '7': ('11111','00001','00010','00100','01000','01000','01000'),
    '8': ('01110','10001','10001','01110','10001','10001','01110'),
    '9': ('01110','10001','10001','01111','00001','00001','01110'),
    'A': ('01110','10001','10001','11111','10001','10001','10001'),
    'C': ('01111','10000','10000','10000','10000','10000','01111'),
    'E': ('11111','10000','10000','11110','10000','10000','11111'),
    'F': ('11111','10000','10000','11110','10000','10000','10000'),
    'H': ('10001','10001','10001','11111','10001','10001','10001'),
    'I': ('01110','00100','00100','00100','00100','00100','01110'),
    'L': ('10000','10000','10000','10000','10000','10000','11111'),
    'M': ('10001','11011','10101','10101','10001','10001','10001'),
    'N': ('10001','11001','10101','10011','10001','10001','10001'),
    'P': ('11110','10001','10001','11110','10000','10000','10000'),
    'R': ('11110','10001','10001','11110','10100','10010','10001'),
    'S': ('01111','10000','10000','01110','00001','00001','11110'),
    'T': ('11111','00100','00100','00100','00100','00100','00100'),
    'U': ('10001','10001','10001','10001','10001','10001','01110'),
    ' ': ('00000',)*7,
    '.': ('00000','00000','00000','00000','00000','00110','00110'),
    '+': ('00000','00100','00100','11111','00100','00100','00000'),
    '-': ('00000','00000','00000','11111','00000','00000','00000'),
}
FRAME_CODE = {'bits': 20, 'x': 24, 'y': 240, 'cellWidth': 26,
              'height': 48, 'order': 'most_significant_bit_first',
              'referenceSize': [640, 360]}


def rect(frame: bytearray, width: int, x: int, y: int, w: int, h: int,
         color: bytes) -> None:
    row = color * w
    for yy in range(y, y + h):
        start = (yy * width + x) * 3
        frame[start:start + len(row)] = row


def text(frame: bytearray, width: int, value: str, x: int, y: int,
         scale: int) -> None:
    for char in value:
        for yy, row in enumerate(FONT[char]):
            for xx, bit in enumerate(row):
                if bit == '1':
                    rect(frame, width, x + xx * scale, y + yy * scale,
                         scale, scale, b'\xff\xff\xff')
        x += 6 * scale


def timecode(pts_us: int) -> str:
    sign = '-' if pts_us < 0 else '+'
    seconds, micros = divmod(abs(pts_us), 1_000_000)
    return f'{sign}{seconds:03d}.{micros:06d}S'


def render_frame(label: str, color: str, width: int, height: int,
                 index: int, fps: int, lead_us: int) -> bytearray:
    if not 0 <= index < 2 ** FRAME_CODE['bits']:
        raise ValueError('Source frame exceeds 20-bit timecode capacity')
    factor = width // 640
    if width != 640 * factor or height != 360 * factor or factor < 1:
        raise ValueError('Expected a supported 16:9 canvas')
    frame = bytearray(bytes.fromhex(color.removeprefix('0x')) * (width * height))
    pts_us = index * 1_000_000 // fps
    for value, y in ((label, 24), (f'FRAME {index:06d}', 78),
                     (f'PTS {timecode(pts_us)}', 128),
                     (f'MUSIC {timecode(pts_us - lead_us)}', 178)):
        text(frame, width, value, 24 * factor, y * factor, 4 * factor)
    for bit in range(FRAME_CODE['bits']):
        color_bytes = (b'\xff\xff\xff' if index & (1 << (19 - bit))
                       else b'\x00\x00\x00')
        rect(frame, width, (24 + bit * 26) * factor, 240 * factor,
             26 * factor, 48 * factor, color_bytes)
    return frame
