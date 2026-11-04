import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

root = Path(__file__).resolve().parent
repo = root.parents[1]
def adb(*args):
    return subprocess.run(['adb', '-s', '334da6a3', *args], check=True, capture_output=True).stdout.decode().strip()
apk = repo/'mobile/app/build/app/outputs/apk/debug/app-debug.apk'
device_apk = adb('shell', 'pm', 'path', 'art.gaoge.dance').split('package:', 1)[1]
device_hash = adb('shell', 'sha256sum', device_apk).split()[0]
local_hash = hashlib.sha256(apk.read_bytes()).hexdigest()
assert device_hash == local_hash
fixtures = {}
for name in ('duo-dance-6940536.mp4', 'solo-hd-7570258.mp4', 'solo-portrait-7570258.mp4', 'group-hd-8956295.mp4', 'group-mirror-8956295.mp4'):
    remote = '/sdcard/Android/data/art.gaoge.dance.engine.test/files/network_privacy_qa/' + name
    actual = adb('shell', 'sha256sum', remote).split()[0]
    expected = hashlib.sha256((root.parent/'privacy-e2e-2026-11-04'/name).read_bytes()).hexdigest()
    assert actual == expected, name
    fixtures[name] = actual
for name,remote in (
    ('no-person-flower.mp4','/sdcard/Movies/WoahPrivacyQA/no-person-flower.mp4'),
    ('custom-sticker.png','/sdcard/Download/WoahQA-sticker.png'),
    ('corrupt-video.mp4','/sdcard/Download/WoahQA-corrupt-video.mp4'),
):
    actual=adb('shell','sha256sum',remote).split()[0]
    expected=hashlib.sha256((root.parent/'privacy-e2e-2026-11-04'/name).read_bytes()).hexdigest()
    assert actual==expected, name
    fixtures[name]=actual
data = {'recorded_at': datetime.now(timezone.utc).isoformat(), 'regression_started_ms': int(datetime.now(timezone.utc).timestamp()*1000), 'serial': '334da6a3',
        'model': adb('shell', 'getprop', 'ro.product.model'), 'android': adb('shell', 'getprop', 'ro.build.version.release'),
        'apk_sha256': local_hash, 'installed_apk_matches': True, 'fixture_sha256_matches_original_run': fixtures,
        'package': [l.strip() for l in adb('shell', 'dumpsys', 'package', 'art.gaoge.dance').splitlines() if any(x in l for x in ('versionCode=', 'versionName=', 'lastUpdateTime='))]}
(root/'environment.json').write_text(json.dumps(data, indent=2), encoding='utf-8')
print(json.dumps(data, indent=2))
