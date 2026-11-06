import datetime as dt,json,os,tempfile,unittest
from pathlib import Path
from tools.privacy.gallery_evidence import capture_simulator_gallery,verify_saved_gallery

class SavedGalleryEvidenceTest(unittest.TestCase):
    def fixture(self,root):
        run=root/'run';(run/'outputs').mkdir(parents=True);(run/'outputs/movie.mp4').write_bytes(b'current video bytes')
        dcim=root/'simulator/DCIM';dcim.mkdir(parents=True);now=dt.datetime.now(dt.timezone.utc);stamp=now.isoformat()
        (run/'results.json').write_text(json.dumps({'started_at_utc':stamp,'finished_at_utc':stamp,'cases':[{'case':'movie','saveToGallery':True,'result':'output_created','gallery_save_completed':True}]}))
        file=dcim/'IMG_0040.MP4';file.write_bytes(b'current video bytes');os.utime(file,(now.timestamp(),now.timestamp()))
        return run,dcim,file,now
    def test_binds_current_saved_copy_and_rejects_modified_gallery_bytes(self):
        with tempfile.TemporaryDirectory() as folder:
            run,dcim,file,now=self.fixture(Path(folder));old=dcim/'old.mp4';old.write_bytes(file.read_bytes());os.utime(old,(now.timestamp()-100,now.timestamp()-100))
            capture_simulator_gallery(run,dcim);self.assertEqual(verify_saved_gallery(run)[0]['status'],'passed_saved_original_bytes')
            (run/'gallery'/file.name).write_bytes(b'corrupt saved video')
            with self.assertRaises(ValueError):verify_saved_gallery(run)
    def test_duplicate_current_imports_remain_ambiguous(self):
        with tempfile.TemporaryDirectory() as folder:
            run,dcim,file,now=self.fixture(Path(folder));(dcim/'duplicate.mp4').write_bytes(file.read_bytes())
            with self.assertRaisesRegex(ValueError,'found 2'):capture_simulator_gallery(run,dcim)
    def test_app_owned_output_uri_alone_is_not_gallery_proof(self):
        with tempfile.TemporaryDirectory() as folder:
            run,dcim,file,now=self.fixture(Path(folder))
            with self.assertRaisesRegex(ValueError,'Missing independently'):verify_saved_gallery(run)
