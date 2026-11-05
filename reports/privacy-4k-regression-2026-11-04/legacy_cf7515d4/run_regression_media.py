"""Repeat the same 22 exports and production service lifecycle on the final APK."""
import json
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REMOTE = '/sdcard/Android/data/art.gaoge.dance.engine.test/files/network_privacy_qa/'
RUNNER = 'art.gaoge.dance.engine.test/androidx.test.runner.AndroidJUnitRunner'
CLASS = 'art.gaoge.dance.engine.pipeline.NetworkPrivacyWorkflowInstrumentedTest#'

faces_only = '--faces' in sys.argv
scripts = ('run_network_cases.py',) if faces_only else ('run_network_cases.py', 'run_group_profiles.py')
for script in scripts:
    subprocess.run([sys.executable, str(ROOT/script)] + (['--faces'] if faces_only else []), check=True)
assert all(r['result'] == 'passed' for r in json.loads((ROOT/'network-case-results.json').read_text()))

for name, method, options, timeout, files in [
    ('group_full_length_follow', 'downloadedClipPreservesProductionPipelineArtifacts',
     {'case': 'group_full_length_follow', 'input': 'group-hd-8956295.mp4', 'hero': '6',
      'fill': 'solid', 'endMs': '23240', 'width': '720', 'height': '1280'}, 480,
     ['group_full_length_follow.json', 'group_full_length_follow.mp4']),
    ('lifecycle-final', 'productionApiReleasesResourcesAfterCompletionAndCancellation',
     {'lifecycle': 'true'}, 240, ['lifecycle.json']),
]:
    adb = ['adb', '-s', '334da6a3']
    subprocess.run(adb+['shell', 'am', 'force-stop', 'art.gaoge.dance.engine.test'], check=True)
    command = adb+['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS+method]
    for key, value in options.items():
        command += ['-e', key, value]
    start = time.monotonic()
    result = subprocess.run(command+[RUNNER], check=True, capture_output=True, timeout=timeout)
    output = result.stdout.decode('utf-8', errors='replace')
    (ROOT/(name+'-instrumentation.txt')).write_text(output, encoding='utf-8')
    assert 'OK (1 test)' in output and 'INSTRUMENTATION_STATUS_CODE: 0' in output, output
    for filename in files:
        target = 'lifecycle-final.json' if filename == 'lifecycle.json' else filename
        subprocess.run(adb+['pull', REMOTE+filename, str(ROOT/target)], check=True, capture_output=True)
    print(name+' passed in '+str(round(time.monotonic()-start, 2))+'s', flush=True)
