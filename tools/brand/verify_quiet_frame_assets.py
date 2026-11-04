#!/usr/bin/env python3
"""Deterministic Lime Beat resource/semantics checks; Python stdlib only.

This is source-level QA, not a substitute for Android or iOS device rendering.
"""
from pathlib import Path
import json
import struct
import re
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'mobile/app'
RES = APP / 'android/app/src/main/res'
IOS = APP / 'ios/Runner'
THEME = (APP / 'lib/app/theme.dart').read_text()
BRAND = (APP / 'lib/app/brand.dart').read_text()


def png_size(path):
    data = path.read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', str(path)
    assert data[12:16] == b'IHDR', str(path)
    return struct.unpack('>II', data[16:24])


def luminance(hex_value):
    rgb = [int(hex_value[i:i+2], 16) / 255 for i in (1, 3, 5)]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in rgb]
    return sum(a * b for a, b in zip((0.2126, 0.7152, 0.0722), linear))


def ratio(a, b):
    l1, l2 = sorted((luminance(a), luminance(b)))
    return (l2 + 0.05) / (l1 + 0.05)


assert "english = 'Woah'" in BRAND
assert "chinese = '舞哇'" in BRAND
assert "visualDirection = 'Lime Beat'" in BRAND
assert 'static const Color brandInk = Color(0xFF17201C)' in THEME
assert 'static const Color brandAction = brandLime' in THEME
assert 'onPrimary: c.brandOnAction' in THEME
assert 'static const Color effectPaletteAmber = Color(0xFFF5A623)' in THEME
palette = json.loads((ROOT / 'docs/design/lime-beat/palette.json').read_text())
for role, token in [('lime','brandLime'), ('lavender','brandLavender'),
                    ('chalk','brandChalk'), ('ink','brandInk'),
                    ('mark','brandLime'), ('olive','brandOlive')]:
    assert f'{token} = Color(0xFF{palette["brand"][role][1:]})' in THEME, token
assert ratio('#17201C', '#D8F54C') >= 7
assert ratio('#FFFFFF', '#D8F54C') < 4.5
for mode in ('light', 'dark'):
    colors = palette[mode]
    for role in ('text_primary', 'text_secondary', 'text_muted', 'selection'):
        assert ratio(colors[role], colors['surface']) >= 4.5, (mode, role)
    assert ratio(colors['control_border'], colors['surface']) >= 3, mode

# No legacy amber UI alias may survive in app screens. Literal effect palette
# ARGB values remain untouched, including stored project and render values.
for path in (APP / 'lib').rglob('*.dart'):
    if path.name == 'theme.dart':
        continue
    assert not re.search(r'AppTheme\.(?:gold\w*|coral\w*)', path.read_text()), path
editor = (APP / 'lib/features/protection_editor/presentation/protection_editor_screen.dart').read_text()
assert "(0xFFF5A623, '琥珀金')" in editor
home_catalog = (APP / 'lib/features/import_video/presentation/home_tool_catalog.dart').read_text()
assert 'videoProtection' in home_catalog and 'danceComparison' in home_catalog
for name in ('home-video-protection-lime.jpg', 'home-dance-comparison-lime.jpg'):
    assert name in home_catalog
    assert (APP / 'assets/home' / name).read_bytes().startswith(b'\xff\xd8'), name
home = (APP / 'lib/features/import_video/presentation/import_video_screen.dart').read_text()
assert 'AppBrand.english' in home and 'woah-symbol-256.png' in home
assert 'AppBrand.chinese' not in home
assert 'label: AppBrand.english' in home
assert png_size(APP / 'assets/brand/woah-symbol-256.png') == (256, 160)
assert png_size(APP / 'assets/brand/woah-icon-default-1024.png') == (1024, 1024)

icon_catalog = IOS / 'Assets.xcassets/AppIcon.appiconset'
contents = json.loads((icon_catalog / 'Contents.json').read_text())
for item in contents['images']:
    name = item.get('filename')
    if not name:
        continue
    logical = float(item['size'].split('x')[0])
    scale = float(item.get('scale', '1x').removesuffix('x'))
    required = round(logical * scale)
    assert png_size(icon_catalog / name) == (required, required), name
assert not (IOS / 'zh-Hans.lproj/InfoPlist.strings').exists()
assert 'InfoPlist.strings' not in (APP / 'ios/Runner.xcodeproj/project.pbxproj').read_text()
assert '<string>Woah</string>' in (IOS / 'Info.plist').read_text()
launch = (IOS / 'Base.lproj/LaunchScreen.storyboard').read_text()
ET.fromstring(launch)
assert 'name="WoahLaunchBackground"' in launch and 'image="LaunchImage"' in launch
assert png_size(IOS / 'Assets.xcassets/LaunchImage.imageset/LaunchImage.png') == (168, 105)
assert png_size(IOS / 'Assets.xcassets/LaunchImage.imageset/LaunchImage_dark.png') == (168, 105)

for density, side in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    assert png_size(RES / f'mipmap-{density}/ic_launcher.png') == (side, side)
for relative in ('mipmap-anydpi-v26/ic_launcher.xml', 'mipmap-anydpi-v33/ic_launcher.xml',
                 'drawable/woah_icon_foreground.xml', 'drawable/woah_icon_monochrome.xml',
                 'drawable/launch_background.xml', 'drawable-v21/launch_background.xml',
                 'drawable/woah_launch_mark.xml', 'drawable-night/woah_launch_mark.xml',
                 'values-night-v31/styles.xml', 'values-night/colors.xml',
                 'values-v31/styles.xml', 'values/strings.xml'):
    ET.parse(RES / relative)
mono = (RES / 'mipmap-anydpi-v33/ic_launcher.xml').read_text()
assert 'monochrome' in mono and 'woah_icon_monochrome' in mono
assert 'monochrome' not in (RES / 'mipmap-anydpi-v26/ic_launcher.xml').read_text()
assert 'android:translateX="24.5"' in (RES / 'drawable/woah_icon_foreground.xml').read_text()
assert '#011758' not in (RES / 'values/colors.xml').read_text()
for path in (RES / x for x in ('drawable/launch_background.xml','drawable-v21/launch_background.xml')):
    assert '@android:color/white' not in path.read_text()
    assert '@color/woah_launch_background' in path.read_text()
assert not (RES / 'values-zh-rCN/strings.xml').exists()
assert '<string name="app_name">Woah</string>' in (RES / 'values/strings.xml').read_text()
assert 'android:label="@string/app_name"' in (APP / 'android/app/src/main/AndroidManifest.xml').read_text()
print('LIME_BEAT_ASSETS=PASS (naming, covers, PNG sizes, XML, role contrast, effect ARGB)')
print('DEVICE_VISUAL_QA=NOT_PROVEN')
