#!/usr/bin/env python3
"""Source-level checks for Wavi character and the private easter-egg page.

All checks use Python's standard library. Device visual acceptance is separate.
"""
from pathlib import Path
import hashlib
import struct

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'mobile/app'
ASSETS = APP / 'assets/ip/wavi'
WIDGET = APP / 'lib/features/import_video/presentation/woah_easter_egg_screen.dart'
HOME = APP / 'lib/features/import_video/presentation/import_video_screen.dart'
PUBSPEC = (APP / 'pubspec.yaml').read_text()
widget = WIDGET.read_text()
home = HOME.read_text()

assert 'assets/ip/wavi/' in PUBSPEC
assert 'assets/easter/poses/' not in PUBSPEC
assert 'onLongPress: onLongPress' in home
assert 'WoahEasterEggScreen()' in home
assert "AppTheme.brandInk" in widget
assert 'Future<void>.delayed(const Duration(milliseconds: 240))' in widget
assert 'MediaQuery.disableAnimationsOf(context)' in widget
assert 'AnimationController(' not in widget
assert 'primaryActionKey' not in widget
assert '_StarFieldPainter' not in widget
assert '_NightBackground' not in widget
assert '_RevealCard' not in widget
assert '_Signature' not in widget
assert 'WoahBuildInfo' not in widget
assert 'WOAH  /  A LITTLE MOMENT' not in widget
assert '免费开源' not in widget
assert '轻触小精灵，开启一份小惊喜' not in widget
assert 'Wavi 节奏小精灵' in widget
assert (ROOT / 'tools/brand/generate_wavi_mascot.swift').exists()

hashes = set()
for name in ('idle', 'peek', 'joy', 'wave'):
    path = ASSETS / f'wavi_{name}.png'
    data = path.read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', name
    assert data[12:16] == b'IHDR', name
    assert struct.unpack('>II', data[16:24]) == (512, 512), name
    assert data[25] == 6, ('Expected RGBA transparent PNG', name)
    digest = hashlib.sha256(data).hexdigest()
    assert digest not in hashes, ('Duplicate pose', name)
    hashes.add(digest)
    assert f"assets/ip/wavi/wavi_{name}.png" in widget, name

old = APP / 'assets/easter/poses'
assert not old.exists() or not any(old.glob('*.png'))
print('WAVI_ASSET_CHECK=PASS (4 RGBA poses, new copy, no old Buddy)')
print('MASCOT_DEVICE_VISUAL=REQUIRES_REVIEW')
