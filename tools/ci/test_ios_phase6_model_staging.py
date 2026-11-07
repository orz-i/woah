"""Prevent clean CI from building an app before its pinned model assets exist."""
from pathlib import Path
import unittest
from unittest.mock import patch
from tools.ios import run_phase6_macos_gate as gate

class IOSPhase6ModelStagingTests(unittest.TestCase):
    def test_pinned_models_are_staged_before_pods_and_verified_in_the_built_app(self):
        events=[]
        def run(command,**kwargs):events.append(command)
        def verify(path):events.append(["verify_built_models",str(path)])
        with patch.object(gate.sys,"platform","darwin"),patch.object(gate,"run",side_effect=run),patch.object(gate,"verify_ios_model",side_effect=verify):
            self.assertEqual(0,gate.main())
        def index(fragment):return next(i for i,c in enumerate(events) if any(fragment in str(v) for v in c))
        staged=index("sync_ios_yolo_model.py")
        checked=next(i for i,c in enumerate(events) if "--verify-only" in c)
        dependencies=index("resolve_ios_dependencies.py")
        app_build=next(i for i,c in enumerate(events) if c[:3]==["flutter","build","ios"])
        bundle=index("verify_built_models")
        smoke=index("run_phase4_simulator_smoke.py")
        self.assertLess(staged,checked)
        self.assertLess(checked,dependencies)
        self.assertLess(dependencies,app_build)
        self.assertLess(app_build,bundle)
        self.assertLess(bundle,smoke)
        bundled=Path(events[bundle][1])
        self.assertEqual("Runner.app",bundled.parent.name)
        self.assertEqual("dance_native_models.bundle",bundled.name)

if __name__=="__main__":unittest.main()
