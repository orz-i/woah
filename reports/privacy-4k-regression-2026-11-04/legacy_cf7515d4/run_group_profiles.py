import json
import subprocess
from pathlib import Path
root=Path(__file__).resolve().parent
remote='/sdcard/Android/data/art.gaoge.dance.engine.test/files/network_privacy_qa/'
for profile in ('speed','balanced'):
    name='group_full_'+profile
    args=['adb','-s','334da6a3','shell','am','instrument','-w','-r','-e','class',
          'art.gaoge.dance.engine.pipeline.NetworkPrivacyWorkflowInstrumentedTest#downloadedClipPreservesProductionPipelineArtifacts',
          '-e','case',name,'-e','profile',profile,'-e','input','group-hd-8956295.mp4',
          '-e','fill','solid','-e','faceOnly','false','-e','width','960','-e','height','540',
          'art.gaoge.dance.engine.test/androidx.test.runner.AndroidJUnitRunner']
    result=subprocess.run(args,capture_output=True,timeout=150,check=True).stdout.decode('utf-8',errors='replace')
    (root/(name+'-instrumentation.txt')).write_text(result,encoding='utf-8')
    for suffix in ('.mp4','.json'):
        subprocess.run(['adb','-s','334da6a3','pull',remote+name+suffix,str(root/(name+suffix))],check=True,capture_output=True)
    assert 'OK (1 test)' in result, result[-1500:]
    print(name+' passed',flush=True)
