package art.gaoge.dance.engine.pipeline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import art.gaoge.dance.engine.bridge.AnalyzeRequestDto
import art.gaoge.dance.engine.bridge.EffectConfigDto
import art.gaoge.dance.engine.bridge.ExportRequestDto
import art.gaoge.dance.engine.bridge.FollowConfigDto
import art.gaoge.dance.engine.bridge.JobStatusDto
import art.gaoge.dance.engine.inference.YoloLiteRtSegmenter
import art.gaoge.dance.engine.media.VideoProbe
import art.gaoge.dance.engine.storage.CacheManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in real-media QA. Inputs/outputs stay in this test package's external files directory. */
@RunWith(AndroidJUnit4::class)
class NetworkPrivacyWorkflowInstrumentedTest {
    @Test fun productionApiReleasesResourcesAfterCompletionAndCancellation(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Network media QA is opt-in", args.containsKey("lifecycle"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.startActivitySync(android.content.Intent(
            context, art.gaoge.dance.engine.privacy.FaceOnlyStressHostActivity::class.java
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val root = File(context.getExternalFilesDir(null), "network_privacy_qa").apply { mkdirs() }
        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.initialize(context)
        val finished = AtomicBoolean(false)
        Thread {
            while (!finished.get()) {
                Thread.sleep(20_000)
                File(root, "lifecycle-threads.txt").writeText(Thread.getAllStackTraces().entries.joinToString("\n\n") {
                    (thread, stack) -> "${thread.name}: ${thread.state}\n" + stack.joinToString("\n")
                })
            }
        }.apply { isDaemon = true; start() }
        val api = art.gaoge.dance.engine.bridge.DanceNativeApiImpl(context)
        val coordinator = art.gaoge.dance.engine.export.ExportCoordinator.getInstance(context)
        val stages = JSONArray()
        val summary = JSONObject().put("stages", stages)
        var stage = "analysis"
        try {
            val input = File(root, "duo-dance-6940536.mp4")
            val analysis = api.analyzeVideo(AnalyzeRequestDto(input.absolutePath, "quality", 0))
            val ids = analysis.persons.map { it.id }
            val effects = EffectConfigDto(
                fillMode = "solid", fillColorArgb = 0xFFFF0000L,
                borderColorArgb = 0xFFFFFFFFL, opacity = 1.0, borderWidth = 0.0,
                blurStrength = 18.0, skinWhiten = 0.0, legStretchEnabled = false,
                legStretch = 0.0, legZoneTop = 0.55, legZoneBottom = 0.95,
                faceStickerEnabled = false, stickerAssetId = null, stickerScale = 1.0
            )
            val follow = FollowConfigDto(false, null, 1.0, 0.1)
            val preview = art.gaoge.dance.engine.bridge.PreviewRequestDto(
                analysisCacheId = analysis.analysisCacheId, timestampMs = 0,
                selectedPersonIds = emptyList(), faceOnlyPersonIds = ids, effects = effects, follow = follow
            )
            repeat(3) { index ->
                stage = "preview_$index"
                api.getPreviewFrame(preview)
                stages.put(stage)
                stage = "export_$index"
                val request = ExportRequestDto(
                    sourceUri = input.absolutePath, analysisCacheId = analysis.analysisCacheId,
                    outputFilePath = File(root, "lifecycle_$index.mp4").absolutePath,
                    selectedPersonIds = emptyList(), faceOnlyPersonIds = ids,
                    effects = effects, follow = follow, targetWidth = 960, targetHeight = 540,
                    targetFps = analysis.videoInfo.fps, videoBitrate = 3_000_000,
                    processingProfile = "quality", enableLivePreview = true,
                    trimStartMs = 0, trimEndMs = if (index == 1) 6000 else 800
                )
                val jobId = api.startExport(request)
                kotlinx.coroutines.withTimeout(120_000) {
                    if (index == 1) {
                        while (api.getJobStatus(jobId).currentFrame < 3) kotlinx.coroutines.delay(50)
                        api.cancelJob(jobId)
                    }
                    while (api.getJobStatus(jobId).state !in listOf("completed", "failed", "cancelled")) {
                        kotlinx.coroutines.delay(50)
                    }
                    assertEquals(if (index == 1) "cancelled" else "completed", api.getJobStatus(jobId).state)
                    // Terminal UI notification must not be mistaken for resource cleanup.
                    while (coordinator.resourceGate.ownsExport(jobId)) kotlinx.coroutines.delay(50)
                }
                stages.put(stage)
            }
            stage = "final_preview"
            api.getPreviewFrame(preview)
            stages.put(stage)
            summary.put("state", "passed")
        } catch (error: Throwable) {
            summary.put("state", "failed").put("stage", stage).put("exception", error.stackTraceToString())
            throw error
        } finally {
            finished.set(true)
            File(root, "lifecycle.json").writeText(summary.toString(2))
            api.close()
            host.runOnUiThread { host.finish() }
        }
    }

    @Test fun downloadedClipPreservesProductionPipelineArtifacts() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Network media QA is opt-in; provide -e case NAME and a downloaded fixture", args.containsKey("case"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.getExternalFilesDir(null), "network_privacy_qa").apply { mkdirs() }
        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.initialize(context)
        val input = File(root, args.getString("input", "solo-hd-7570258.mp4"))
        assertTrue(input.isFile, "Push a public fixture to ${input.absolutePath} before running")
        val name = args.getString("case", "face_sticker")!!
        require(name.matches(Regex("[a-z0-9_]+")))
        val faceOnly = args.getString("faceOnly", "true").toBoolean()
        val fill = args.getString("fill", "sticker")!!
        val start = args.getString("startMs", "0")!!.toLong()
        val end = args.getString("endMs", "1600")!!.toLong()
        val summary = JSONObject().put("case", name).put("input", input.name)
            .put("face_only", faceOnly).put("fill", fill).put("trim_start_ms", start).put("trim_end_ms", end)
        val output = File(root, "$name.mp4")
        output.delete()
        val segmenter = YoloLiteRtSegmenter(
            context,
            requestedAccelerator = if (args.getString("profile", "quality") == "quality")
                art.gaoge.dance.engine.litert.LiteRtAccelerator.CPU
            else art.gaoge.dance.engine.litert.LiteRtAccelerator.GPU,
            cpuNumThreads = 4
        )
        try {
            val info = VideoProbe.probe(context, input.absolutePath)
            val analysis = AnalyzePipeline(context, CacheManager(context)).analyze(
                AnalyzeRequestDto(input.absolutePath, "quality", start)
            )
            val ids = analysis.persons.map { it.id }
            summary.put("detected_ids", JSONArray(ids))
            assertTrue(ids.isNotEmpty(), "Real-media fixture must contain detected people")
            val hero = args.getString("hero")?.toLong()
            val selected = (if (args.getString("select", "all") == "first") ids.take(1) else ids)
                .filter { it != hero }
            summary.put("selected_ids", JSONArray(selected)).put("hero", hero)
            var terminal: JobStatusDto? = null
            ExportPipeline(context, segmenter).execute(
                jobId = "network_qa_$name",
                sourceUri = input.absolutePath,
                request = ExportRequestDto(
                    sourceUri = input.absolutePath,
                    analysisCacheId = analysis.analysisCacheId,
                    outputFilePath = output.absolutePath,
                    selectedPersonIds = if (faceOnly) emptyList() else selected,
                    faceOnlyPersonIds = if (faceOnly) selected else emptyList(),
                    effects = EffectConfigDto(
                        fillMode = fill, fillColorArgb = 0xFFFF0000L,
                        borderColorArgb = 0xFFFFFFFFL, opacity = 1.0, borderWidth = 0.0,
                        blurStrength = 18.0, skinWhiten = 0.0, legStretchEnabled = false,
                        legStretch = 0.0, legZoneTop = 0.55, legZoneBottom = 0.95,
                        faceStickerEnabled = fill == "sticker", stickerAssetId = "builtin:sunglasses", stickerScale = 1.0
                    ),
                    follow = FollowConfigDto(
                        enabled = hero != null, targetPersonId = hero, zoom = 1.0, smoothFactor = 0.1,
                        outputAspectRatio = if (hero == null) null else
                            args.getString("width", "540")!!.toDouble() / args.getString("height", "1024")!!.toDouble()
                    ),
                    targetWidth = args.getString("width", "540")!!.toLong(),
                    targetHeight = args.getString("height", "1024")!!.toLong(),
                    targetFps = info.fps, videoBitrate = 4_000_000L,
                    processingProfile = args.getString("profile", "quality")!!,
                    enableLivePreview = false, trimStartMs = start, trimEndMs = end
                ),
                isCancelled = AtomicBoolean(false),
                onStatusChange = { terminal = it }
            )
            summary.put("state", terminal?.state).put("error_code", terminal?.errorCode)
                .put("frames", terminal?.currentFrame).put("output_bytes", output.length())
            assertEquals("completed", terminal?.state, "Production export must complete: ${terminal?.errorCode}")
            assertTrue(output.length() > 0, "Export output missing")
            val expectedPts = videoPresentationTimes(input).filter { it >= start * 1000 && it < end * 1000 }
            val actualPts = videoPresentationTimes(output)
            summary.put("expected_frames", expectedPts.size).put("actual_frames", actualPts.size)
                .put("last_output_pts_us", actualPts.lastOrNull())
            assertEquals(expectedPts.size, actualPts.size, "Trim must preserve every source picture within [start, end)")
            assertTrue(actualPts.zipWithNext().all { (a, b) -> b > a }, "Output PTS must increase strictly")
        } catch (error: Throwable) {
            summary.put("exception", error.javaClass.simpleName).put("message", error.message)
            throw error
        } finally {
            File(root, "$name.json").writeText(summary.toString(2))
            segmenter.close()
        }
    }

    private fun videoPresentationTimes(file: File): List<Long> {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            return buildList {
                while (extractor.sampleTime >= 0) {
                    add(extractor.sampleTime)
                    if (!extractor.advance()) break
                }
            }
        } finally { extractor.release() }
    }
}
