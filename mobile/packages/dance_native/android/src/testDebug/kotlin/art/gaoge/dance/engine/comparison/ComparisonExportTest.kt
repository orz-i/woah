package art.gaoge.dance.engine.comparison

import org.json.JSONObject
import kotlin.test.*

class ComparisonExportTest {
    @Test fun vfrLedgerAllowsCodecLatencyAndAnArbitraryAbStart() {
        val frames=ComparisonExportFrames()
        frames.submit(0);frames.submit(32_100);frames.submit(65_433)
        frames.accept(0);frames.accept(32_100)
        frames.submit(132_100);frames.accept(65_433);frames.accept(132_100)
        frames.finish();assertEquals(4L,frames.encoded)
    }
    @Test fun missingReorderedRetimedAndUnflushedFramesCannotBecomeSuccessfulFiles() {
        val dropped=ComparisonExportFrames();dropped.submit(0);dropped.submit(32_100);dropped.submit(65_433)
        dropped.accept(0)
        assertFailsWith<IllegalStateException> { dropped.accept(65_433) }
        assertFailsWith<IllegalStateException> { dropped.accept(32_101) }
        assertFailsWith<IllegalStateException> { dropped.finish() }
        assertFailsWith<IllegalStateException> { ComparisonExportFrames().finish() }
    }

    private fun spec()=ComparisonExportSpec("test_project","content://test/reference","content://test/target",
        -1_500_000,1.25,123_457,2_456_789,1080,1800,8,true,true,false,
        ComparisonPicture(1.8,-.4,.2),ComparisonPicture(2.2,.7,-.3))
    @Test fun affineNegativeOffsetUsesRealSourceOriginAndExclusiveAbEnd() {
        val overlap=ComparisonTimeMapping.overlap(SourceInterval(0,8_000_000),SourceInterval(200_000,10_000_000),-1_500_000,1.25)!!
        assertEquals(1_360_000,overlap.teacherStartUs)
        val range=spec().sourceRange(overlap)
        assertEquals(1_483_457,range.startUs);assertEquals(3_816_789,range.endUs)
        assertEquals(354_321,overlap.mapping.learnerTimeUs(range.startUs))
        assertFailsWith<IllegalArgumentException> { spec().copy(endUs=overlap.durationUs+1).sourceRange(overlap) }
    }
    @Test fun frozenSettingsRoundTripPreservesMappingAndIndependentGeometry() {
        val frozen=spec();assertEquals(frozen,ComparisonExportSpec.fromJson(frozen.toJson()))
        val job=ComparisonExportRecord("job-1",frozen,"publishing","保存到相册",.99,pendingUri="content://media/external_primary/video/media/42")
        assertEquals(job,ComparisonExportRecord.parse(job.json()))
        assertTrue(job.active);assertEquals("interrupted",job.recovered().state)
        assertEquals(frozen,job.recovered().spec)
        val completed=job.copy(state="completed",savedUri=job.pendingUri,pendingUri=null)
        assertEquals(completed,completed.recovered());assertFalse(completed.active)
    }
    @Test fun oldProjectsDefaultRateAndPicturesButPracticeSpeedNeverBecomesExportRate() {
        val p=JSONObject().put("schemaVersion",1).put("id","old_project")
            .put("teacher",JSONObject().put("uri","content://test/a"))
            .put("learner",JSONObject().put("uri","content://test/b"))
            .put("offsetUs",1490000).put("speed",.5).put("loopEnabled",true)
        val frozen=ComparisonExportSpec.fromProject(p.toString(),0,5_000_000,1080,1800,8,false)
        assertEquals(1.0,frozen.timeScale);assertEquals(1490000,frozen.offsetUs)
        assertEquals(ComparisonPicture(),frozen.teacherPicture)
        assertEquals(ComparisonPicture(),frozen.learnerPicture)
        assertFalse(frozen.toJson().has("speed"));assertFalse(frozen.toJson().has("loopEnabled"))
        assertFailsWith<IllegalArgumentException> { frozen.copy(width=1081) }
        assertFailsWith<IllegalArgumentException> { frozen.copy(timeScale=Double.NaN) }
    }
    @Test fun pcmClipUsesSampleBoundariesAndPreservesNonZeroSourcePts() {
        val slice=ComparisonExportAudioTiming.slice(980_000,2048,SourceInterval(1_000_001,1_020_001),44100)
        assertEquals(883,slice.skip);assertEquals(882,slice.frames);assertEquals(1,slice.outputFrame)
        assertEquals(112593,ComparisonExportAudioTiming.frameCount(2_345_679,48000))
        assertEquals(44100,ComparisonExportAudioTiming.frameCount(1_000_000,44100))
        val gap=ComparisonExportAudioTiming.slice(1_050_000,1024,SourceInterval(1_000_000,2_000_000),48000)
        assertEquals(2400,gap.outputFrame);assertEquals(1024,gap.frames)
        val before=ComparisonExportAudioTiming.slice(0,1024,SourceInterval(1_000_000,2_000_000),48000)
        assertEquals(0,before.frames)
    }
    @Test fun jobIdentityCannotEscapeTheOwnedOutputDirectory() {
        assertFailsWith<IllegalArgumentException> { ComparisonExportRecord("../other",spec()) }
        assertFailsWith<IllegalArgumentException> { spec().copy(projectId="../../project") }
        assertFailsWith<IllegalArgumentException> { spec().copy(startUs=-1) }
        assertFailsWith<IllegalArgumentException> { spec().copy(gap=1080) }
    }
}
