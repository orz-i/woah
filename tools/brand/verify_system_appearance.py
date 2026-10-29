#!/usr/bin/env python3
"""Static cross-platform light/dark appearance acceptance checks.

Does not claim real device behavior or exact Android launcher rendering.
"""
from pathlib import Path
from xml.etree import ElementTree as ET
import json
import re

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'mobile/app'
RES = APP / 'android/app/src/main/res'
IOS_ASSETS = APP / 'ios/Runner/Assets.xcassets'
PALETTE = (APP / 'lib/app/theme.dart').read_text()
ROOT_APP = (APP / 'lib/app/app.dart').read_text()
SYS = (APP / 'lib/app/system_ui.dart').read_text()

assert 'themeMode: ThemeMode.system' in ROOT_APP
assert 'theme: AppTheme.lightTheme' in ROOT_APP
assert 'darkTheme: AppTheme.darkTheme' in ROOT_APP
assert 'didChangePlatformBrightness' in ROOT_APP
assert 'appSystemUiStyleFor(Brightness brightness)' in SYS
assert 'platformDispatcher.platformBrightness' in SYS
assert 'static const lightColors' in PALETTE
assert 'static const darkColors' in PALETTE
assert 'effectPaletteAmber = Color(0xFFF5A623)' in PALETTE
assert 'canvas = Color(0xFF000000)' in PALETTE
assert 'static WoahPalette of(BuildContext context)' in PALETTE

ns = '{http://schemas.android.com/apk/res/android}'
for relative in ('values/colors.xml','values-night/colors.xml',
                 'values/styles.xml','values-night/styles.xml',
                 'values-v31/styles.xml','values-night-v31/styles.xml',
                 'drawable/launch_background.xml','drawable-v21/launch_background.xml',
                 'drawable/woah_launch_mark.xml','drawable-night/woah_launch_mark.xml'):
    ET.parse(RES / relative)
light_colors = (RES / 'values/colors.xml').read_text()
night_colors = (RES / 'values-night/colors.xml').read_text()
assert '<color name="woah_launch_background">#F2F6F3</color>' in light_colors
assert '<color name="woah_launch_background">#0B1415</color>' in night_colors
assert '#216A5E' in (RES / 'drawable/woah_launch_mark.xml').read_text()
assert '#78DCC7' in (RES / 'drawable-night/woah_launch_mark.xml').read_text()
for resdir in ('values','values-night','values-v31','values-night-v31'):
    s=(RES / resdir / 'styles.xml').read_text()
    assert '@color/woah_launch_background' in s
    if 'v31' in resdir:
        assert '@drawable/woah_launch_mark' in s

# iOS uses named colors + image variants; no fixed UIUserInterfaceStyle override.
plist = (APP / 'ios/Runner/Info.plist').read_text()
assert 'UIUserInterfaceStyle' not in plist
storyboard = (APP / 'ios/Runner/Base.lproj/LaunchScreen.storyboard').read_text()
ET.fromstring(storyboard)
assert 'WoahLaunchBackground' in storyboard
colors = json.loads((IOS_ASSETS / 'WoahLaunchBackground.colorset/Contents.json').read_text())
assert len(colors['colors']) == 2
assert any(v.get('appearances',[{}])[0].get('value')=='dark' for v in colors['colors'])
icons = json.loads((IOS_ASSETS / 'LaunchImage.imageset/Contents.json').read_text())
assert len(icons['images']) == 6
assert sum('appearances' in i for i in icons['images']) == 3
assert all((IOS_ASSETS/'LaunchImage.imageset'/i['filename']).exists() for i in icons['images'])

# Source-level separation: media/effect roles are not multiplied by theme.
for file in (APP/'lib').rglob('*.dart'):
    if file.name in ('theme.dart','palette.dart','woah_easter_egg_screen.dart'):
        continue
    content=file.read_text()
    assert not re.search(r'AppTheme\.(?:flowBackground|flowSurface|flowTextPrimary|flowTextSecondary|textPrimary|textSecondary)\b',content),file
print('APPEARANCE_RESOURCE_CHECK=PASS (system, palettes, Android, iOS)')
print('NATIVE_THEME_SWITCH_QA=NOT_YET_PROVEN')
