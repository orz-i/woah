#!/usr/bin/env python3
"""Render the existing native W geometry in Lime Beat colors (requires Pillow).

Run from any directory: python tools/brand/generate_lime_beat_assets.py
Geometry comes from the existing Android vector, not a traced raster or a new
logo. The small M/H/V/L/Q/Z reader intentionally supports only that source path.
"""
import json
import re
from pathlib import Path
from xml.etree import ElementTree as ET

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'mobile/app'
RES = APP / 'android/app/src/main/res'
IOS = APP / 'ios/Runner/Assets.xcassets'
BRAND = APP / 'assets/brand'
PALETTE = json.loads((ROOT / 'docs/design/lime-beat/palette.json').read_text())
NS = '{http://schemas.android.com/apk/res/android}'
PATH = ET.parse(RES / 'drawable/woah_icon_foreground.xml').find('.//path').get(NS + 'pathData')


def outline():
    tokens = iter(re.findall(r'[MHVLQZ]|-?\d+(?:\.\d+)?', PATH))
    points = []
    x = y = 0.0
    for op in tokens:
        if op == 'Z':
            break
        if op in ('M', 'L'):
            x, y = float(next(tokens)), float(next(tokens))
        elif op == 'H':
            x = float(next(tokens))
        elif op == 'V':
            y = float(next(tokens))
        elif op == 'Q':
            cx, cy, nx, ny = (float(next(tokens)) for _ in range(4))
            for i in range(1, 33):
                t = i / 32
                points.append(((1-t)**2*x + 2*(1-t)*t*cx + t*t*nx,
                               (1-t)**2*y + 2*(1-t)*t*cy + t*t*ny))
            x, y = nx, ny
            continue
        else:
            raise ValueError(f'Unsupported W path command: {op}')
        points.append((x, y))
    return points


def render(size, mark_width, fill, background=None):
    scale = 4
    w, h = size
    image = Image.new('RGBA', (w*scale, h*scale), background or (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    factor = mark_width / 256
    ox, oy = (w-mark_width)/2, (h-mark_width*160/256)/2
    for mirror in (False, True):
        draw.polygon([((ox + (256-x if mirror else x)*factor)*scale,
                       (oy + y*factor)*scale) for x, y in outline()], fill=fill)
    image = image.resize(size, Image.Resampling.LANCZOS)
    return image.convert('RGB') if background else image


def save(path, image):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)


def main():
    for mode in ('light', 'dark'):
        colors = PALETTE[mode]
        bg, fg = colors['icon_background'], colors['icon_foreground']
        icon = render((1024, 1024), 720, fg, bg)
        save(BRAND / f'woah-icon-{mode}-1024.png', icon)
        name = 'Icon-App-1024x1024@1x.png' if mode == 'light' else 'Icon-App-1024-dark.png'
        save(IOS / 'AppIcon.appiconset' / name, icon)
        if mode == 'light':
            save(BRAND / 'woah-icon-default-1024.png', icon)
            save(APP / 'branding/woah_launcher_icon.png', icon)
        for density, side in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
            directory = f'mipmap-{density}' if mode == 'light' else f'mipmap-night-{density}'
            save(RES / directory / 'ic_launcher.png', render((side, side), side*720/1024, fg, bg))
        for factor in (1, 2, 3):
            suffix = '' if factor == 1 else f'@{factor}x'
            name = f'LaunchImage{suffix}.png' if mode == 'light' else f'LaunchImage_dark{suffix}.png'
            save(IOS / 'LaunchImage.imageset' / name,
                 render((168*factor, 105*factor), 168*factor*.92, fg))
        for filename in ('woah_icon_foreground.xml', 'woah_launch_mark.xml'):
            directory = 'drawable' if mode == 'light' else 'drawable-night'
            path = RES / directory / filename
            tree = ET.parse(path)
            for node in tree.findall('.//path'):
                node.set(NS + 'fillColor', fg)
            ET.register_namespace('android', NS[1:-1])
            ET.indent(tree, space='    ')
            tree.write(path, encoding='utf-8', xml_declaration=True)
        values = RES / ('values' if mode == 'light' else 'values-night') / 'colors.xml'
        tree = ET.parse(values)
        for node in tree.findall('color'):
            if node.get('name') == 'woah_launcher_background':
                node.text = bg
            elif node.get('name') == 'woah_launch_background':
                node.text = colors['background']
            elif node.get('name') in ('ic_launcher_background', 'woah_ink'):
                node.text = PALETTE['brand']['ink']
        ET.indent(tree, space='    ')
        tree.write(values, encoding='utf-8', xml_declaration=True)
    save(BRAND / 'woah-symbol-256.png', render((256, 160), 256, PALETTE['brand']['mark']))
    mono_path = RES / 'drawable/woah_icon_monochrome.xml'
    mono = ET.parse(mono_path)
    for node in mono.findall('.//path'):
        node.set(NS + 'fillColor', PALETTE['brand']['chalk'])
    ET.indent(mono, space='    ')
    mono.write(mono_path, encoding='utf-8', xml_declaration=True)
    save(BRAND / 'woah-icon-monochrome-1024.png', render((1024, 1024), 720, PALETTE['brand']['chalk']))
    # Tinted assets remain neutral grayscale; the operating system owns tint.
    tinted = render((1024, 1024), 720, '#FAFAFA', '#101010')
    save(BRAND / 'woah-icon-tinted-1024.png', tinted)
    save(IOS / 'AppIcon.appiconset/Icon-App-1024-tinted.png', tinted)
    color_set = IOS / 'WoahLaunchBackground.colorset/Contents.json'
    contents = json.loads(color_set.read_text())
    for item in contents['colors']:
        mode = 'dark' if item.get('appearances') else 'light'
        value = PALETTE[mode]['background'].removeprefix('#')
        item['color']['components'] = {
            channel: f'0x{value[i:i+2]}' for channel, i in (('red', 0), ('green', 2), ('blue', 4))
        } | {'alpha': '1.000'}
    color_set.write_text(json.dumps(contents, indent=2) + '\n')
    # Keep an editable SVG export of the existing W curves in the VI package.
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 160">\n'
           f'  <g fill="{PALETTE["brand"]["mark"]}">\n'
           f'    <path d="{PATH}"/>\n'
           f'    <path d="{PATH}" transform="translate(256 0) scale(-1 1)"/>\n'
           f'  </g>\n</svg>\n')
    (BRAND / 'woah-symbol.svg').write_text(svg)
    print('LIME_BEAT_NATIVE_ASSETS=GENERATED (existing W geometry)')


if __name__ == '__main__':
    main()
