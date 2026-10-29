#!/usr/bin/env python3
"""Deterministic, stdlib-only Woah desktop launcher icon contract checks.

Validates native asset declarations and the authored raster/color layers. This
is not a substitute for launcher-specific real device screenshots.
"""
from __future__ import annotations

import json
import struct
import zlib
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'mobile/app'
ICON = APP / 'ios/Runner/Assets.xcassets/AppIcon.appiconset'
RES = APP / 'android/app/src/main/res'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def read_png(path: Path) -> tuple[int, int, int, bytes]:
    data = path.read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', str(path)
    assert data[12:16] == b'IHDR', str(path)
    width, height = struct.unpack('>II', data[16:24])
    assert data[24] == 8, ('Expected 8-bit component depth', path)
    return width, height, data[25], data


def decode_rgb(path: Path) -> tuple[tuple[int, int, int], set[bytes]]:
    w, h, kind, data = read_png(path)
    assert kind == 2, ('App icon must be 24-bit RGB without alpha', path)
    # Unfilter the scanlines of the PNG to test that Tinted is truly grayscale.
    streams = []
    pos = 8
    while pos + 12 <= len(data):
        size = int.from_bytes(data[pos:pos+4], 'big')
        name = data[pos+4:pos+8]
        if name == b'IDAT':
            streams.append(data[pos+8:pos+8+size])
        pos += size + 12
        if name == b'IEND':
            break
    payload = zlib.decompress(b''.join(streams))
    stride = w * 3
    prev = bytearray(stride)
    cursor = 0
    colors: set[bytes] = set()
    first = (0, 0, 0)
    for row in range(h):
        filter_type = payload[cursor]
        cursor += 1
        line = bytearray(payload[cursor:cursor+stride])
        cursor += stride
        for x in range(stride):
            a = line[x-3] if x >= 3 else 0
            b = prev[x]
            c = prev[x-3] if x >= 3 else 0
            if filter_type == 1: predictor = a
            elif filter_type == 2: predictor = b
            elif filter_type == 3: predictor = (a+b)//2
            elif filter_type == 4:
                p = a+b-c
                da, db, dc = abs(p-a), abs(p-b), abs(p-c)
                predictor = a if da <= db and da <= dc else b if db <= dc else c
            elif filter_type == 0: predictor = 0
            else: raise AssertionError(('Unknown PNG filter', path, filter_type))
            line[x] = (line[x]+predictor)&255
        if row == 0:
            first = tuple(line[:3])
        colors.update(bytes(line[x:x+3]) for x in range(0, stride, 3))
        prev = line
    assert cursor == len(payload), ('PNG decompression length mismatch', path)
    assert len(colors) >= 2, ('Icon has no visible foreground', path)
    return first, colors


catalog = json.loads((ICON / 'Contents.json').read_text())
entries = catalog['images']
assert len(entries) == 3, 'Single Size iOS AppIcon must have Any/Dark/Tinted'
names: dict[str, str] = {}
for e in entries:
    assert e['idiom'] == 'universal', e
    assert e['platform'] == 'ios', e
    assert e['size'] == '1024x1024', e
    assert 'scale' not in e, e
    appearance = e.get('appearances')
    key = 'any' if not appearance else appearance[0]['value']
    if appearance:
        assert len(appearance) == 1
        assert appearance[0]['appearance'] == 'luminosity'
    assert key not in names
    names[key] = e['filename']
assert set(names) == {'any', 'dark', 'tinted'}, names
assert names['any'] == 'Icon-App-1024x1024@1x.png'
expected = {
    'any': ('woah-icon-light-1024.png', (242, 246, 243)),
    'dark': ('woah-icon-dark-1024.png', (11, 20, 21)),
    'tinted': ('woah-icon-tinted-1024.png', (16, 16, 16)),
}
for variant, (source_name, background) in expected.items():
    path = ICON / names[variant]
    w, h, kind, data = read_png(path)
    assert (w, h, kind) == (1024, 1024, 2), path
    assert data == (APP / 'assets/brand' / source_name).read_bytes(), variant
    first_pixel, unique_colors = decode_rgb(path)
    assert first_pixel == background, ('Wrong icon background', variant, first_pixel)
    if variant == 'tinted':
        assert all(r == g == b for r, g, b in unique_colors), 'Tinted must be grayscale'
    print('IOS_APP_ICON='+variant.upper()+':PASS')
assert len(tuple(ICON.glob('*.png'))) == 3, 'Do not ship unassigned legacy icon files'

# The modern mono layer is independent from the light/dark colored foreground.
icon_xml = {}
for version in ('mipmap-anydpi-v26','mipmap-anydpi-v33'):
    xml = ET.parse(RES / version / 'ic_launcher.xml').getroot()
    assert xml.tag == 'adaptive-icon'
    children = {e.tag: e.attrib.get(ANDROID+'drawable') for e in xml}
    assert children['background'] == '@color/woah_launcher_background'
    assert children['foreground'] == '@drawable/woah_icon_foreground'
    icon_xml[version] = children
assert 'monochrome' not in icon_xml['mipmap-anydpi-v26']
assert icon_xml['mipmap-anydpi-v33']['monochrome'] == '@drawable/woah_icon_monochrome'

for dir_name, fill in [('drawable','#216A5E'),('drawable-night','#78DCC7')]:
    path = RES / dir_name / 'woah_icon_foreground.xml'
    root = ET.parse(path).getroot()
    assert root.attrib[ANDROID+'viewportWidth'] == '108'
    assert root.attrib[ANDROID+'viewportHeight'] == '108'
    paths = root.findall('.//path')
    assert len(paths) == 2 and all(e.attrib[ANDROID+'fillColor'] == fill for e in paths)
    assert all(e.attrib.get(ANDROID+'pathData') == paths[0].attrib[ANDROID+'pathData'] for e in paths)
mono = ET.parse(RES / 'drawable/woah_icon_monochrome.xml').getroot()
assert len(mono.findall('.//path')) == 2
assert all(e.attrib[ANDROID+'fillColor'] == '#F2F6F3' for e in mono.findall('.//path'))

for dir_name, color in [('values','#F2F6F3'),('values-night','#0B1415')]:
    values = ET.parse(RES / dir_name / 'colors.xml').getroot()
    d = {v.attrib['name']: v.text for v in values.findall('color')}
    assert d['woah_launcher_background'] == color, dir_name

for density, side in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    light = RES / ('mipmap-'+density) / 'ic_launcher.png'
    night = RES / ('mipmap-night-'+density) / 'ic_launcher.png'
    l = read_png(light)
    n = read_png(night)
    assert l[:2] == n[:2] == (side, side)
    assert l[3] != n[3], ('Day and night legacy icons must differ', density)
    assert l[2] == n[2] == 2, 'Android legacy icons must be opaque RGB'
    assert decode_rgb(light)[0] == (242, 246, 243), density
    assert decode_rgb(night)[0] == (11, 20, 21), density

manifest = (APP / 'android/app/src/main/AndroidManifest.xml').read_text()
assert 'android:icon="@mipmap/ic_launcher"' in manifest
assert 'android:roundIcon="@mipmap/ic_launcher"' in manifest
print('ANDROID_LAUNCHER_ICON=PASS (adaptive day/night, themed mono, legacy)')
print('ANDROID_LAUNCHER_RUNTIME_QA=NOT_PROVEN')
