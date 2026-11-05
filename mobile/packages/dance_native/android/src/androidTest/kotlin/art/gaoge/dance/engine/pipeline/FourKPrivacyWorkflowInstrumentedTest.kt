package art.gaoge.dance.engine.pipeline

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import art.gaoge.dance.engine.bridge.AnalyzeRequestDto
import art.gaoge.dance.engine.bridge.AnalyzeResultDto
import art.gaoge.dance.engine.bridge.DanceNativeApiImpl
import art.gaoge.dance.engine.bridge.EffectConfigDto
import art.gaoge.dance.engine.bridge.ExportRequestDto
import art.gaoge.dance.engine.bridge.FollowConfigDto
import art.gaoge.dance.engine.bridge.PreviewRequestDto
import art.gaoge.dance.engine.export.ExportCoordinator
import art.gaoge.dance.engine.media.VideoProbe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Explicitly opted-in real 4K media; uses the production API/service without process resets. */
@RunWith(AndroidJUnit4::class)
class FourKPrivacyWorkflowInstrumentedTest {
    private data class Case(
        val name: String,
        val input: String,
        val faceOnly: Boolean,
        val fill: String,
        val profile: String = "quality",
        val cancel: Boolean = false,
        val startMs: Long = 0,
        val endMs: Long? = null
    )

    @Test fun fourKFullDurationExportsStayAtSourceResolutionInOneProcess(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("4K real-media matrix is opt-in", args.getString("fourK") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val host = instrumentation.startActivitySync(android.content.Intent(
            context, art.gaoge.dance.engine.privacy.FaceOnlyStressHostActivity::class.java
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val fixtureRoot = File(context.getExternalFilesDir(null), "network_privacy_qa")
        val runName = args.getString("run", "4k_20261104")!!
        require(runName.matches(Regex("[a-z0-9_]+")))
        val root = File(fixtureRoot, runName).apply { mkdirs() }
        art.gaoge.dance.engine.diagnostics.NativeDiagnostics.initialize(context)
        val api = DanceNativeApiImpl(context)
        val coordinator = ExportCoordinator.getInstance(context)
        val rows = JSONArray()
        val summary = JSONObject().put("pid", Process.myPid()).put("cases", rows)
        val analyses = mutableMapOf<String, AnalyzeResultDto>()
        val group = "group-mirror-8956295.mp4"
        val solo = "solo-portrait-7570258.mp4"
        val matrix = buildList {
            for ((label, filename) in listOf("group" to group, "solo" to solo)) {
                for (fill in listOf("solid", "mosaic", "blur", "gradient")) {
                    add(Case("${label}_4k_body_$fill", filename, false, fill))
                }
                for (fill in listOf("solid", "mosaic", "blur", "gradient", "sticker")) {
                    add(Case("${label}_4k_face_$fill", filename, true, fill))
                }
            }
            for (profile in listOf("balanced", "speed")) {
                add(Case("group_4k_body_mosaic_$profile", group, false, "mosaic", profile))
                add(Case("solo_4k_face_sticker_$profile", solo, true, "sticker", profile))
            }
            add(Case("solo_4k_cancel", solo, true, "sticker", cancel = true))
            add(Case("solo_4k_retry_sticker", solo, true, "sticker"))
            add(Case("solo_4k_nonzero_trim", solo, true, "sticker", startMs = 1000, endMs = 2600))
            if (args.getString("leakFrame") == "true") {
                clear()
                add(Case("group_4k_face_solid", group, true, "solid", startMs = args.getString("reviewStartMs", "11600")!!.toLong(),
                    endMs = args.getString("reviewStartMs", "11600")!!.toLong() + 40))
            }
        }
        val requested = args.getString("cases")?.split(',')?.filter { it.isNotEmpty() }?.toSet()
        val priority = listOf("group_4k_face_solid", "group_4k_body_solid", "solo_4k_body_solid", "solo_4k_face_sticker")
        val cases = matrix.filter { requested == null || it.name in requested }.sortedBy {
            priority.indexOf(it.name).takeIf { index -> index >= 0 } ?: priority.size
        }
        assertTrue(cases.isNotEmpty(), "No matching 4K cases")
        if (requested != null) assertEquals(requested, cases.map { it.name }.toSet())
        fun persist() = File(root, "results.json").writeText(summary.toString(2))
        try {
            val caps = api.getCapabilities()
            summary.put("capabilities", caps.toString()).put("planned_cases", JSONArray(cases.map { it.name }))
            persist()
            for (case in cases) {
                val began = SystemClock.elapsedRealtime()
                val row = JSONObject().put("case", case.name).put("input", case.input)
                    .put("face_only", case.faceOnly).put("fill", case.fill).put("profile", case.profile)
                    .put("pid", Process.myPid()).put("state", "preparing")
                rows.put(row)
                persist()
                try {
                    val input = File(fixtureRoot, case.input)
                    assertTrue(input.isFile, "Missing original fixture: ${case.input}")
                    val analysis = analyses.getOrPut(case.input) {
                        api.analyzeVideo(AnalyzeRequestDto(input.absolutePath, "quality", 0))
                    }
                    val info = analysis.videoInfo
                    val width = info.displayWidth
                    val height = info.displayHeight
                    assertTrue(minOf(width, height) >= 2160, "Fixture must have 4K source geometry")
                    val endMs = case.endMs ?: info.durationMs
                    val ids = analysis.persons.map { it.id }
                    assertTrue(ids.isNotEmpty(), "Fixture must contain detected persons")
                    val effects = EffectConfigDto(
                        fillMode = case.fill, fillColorArgb = 0xFFFF0000L, borderColorArgb = 0xFFFFFFFFL,
                        opacity = 1.0, borderWidth = 0.0, blurStrength = 18.0, skinWhiten = 0.0,
                        legStretchEnabled = false, legStretch = 0.0, legZoneTop = 0.55, legZoneBottom = 0.95,
                        faceStickerEnabled = case.fill == "sticker", stickerAssetId = "builtin:sunglasses", stickerScale = 1.0
                    )
                    val follow = FollowConfigDto(false, null, 1.0, 0.1)
                    val bodyIds = if (case.faceOnly) emptyList() else ids
                    val faceIds = if (case.faceOnly) ids else emptyList()
                    val preview = PreviewRequestDto(
                        analysisCacheId = analysis.analysisCacheId, timestampMs = case.startMs,
                        selectedPersonIds = bodyIds, faceOnlyPersonIds = faceIds, effects = effects, follow = follow
                    )
                    row.put("stage", "preview_before")
                    persist()
                    if (args.getString("leakFrame") != "true") {
                        assertTrue(File(api.getPreviewFrame(preview).thumbnailPath).isFile)
                    }
                    val output = File(root, "${case.name}.mp4")
                    assertFalse(output.exists(), "Use a fresh run directory; do not overwrite previous evidence")
                    // Match ExportPlan's source-pixel/fps-dependent production bitrate.
                    val bitrate = (width.toDouble() * height * info.fps * 0.13).roundToLong()
                        .coerceIn(4_000_000L, 80_000_000L)
                    row.put("width", width).put("height", height).put("fps", info.fps)
                        .put("requested_bitrate", bitrate).put("trim_start_ms", case.startMs)
                        .put("trim_end_ms", endMs).put("detected_ids", JSONArray(ids))
                    val jobId = api.startExport(ExportRequestDto(
                        sourceUri = input.absolutePath, analysisCacheId = analysis.analysisCacheId,
                        outputFilePath = output.absolutePath, selectedPersonIds = bodyIds, faceOnlyPersonIds = faceIds,
                        effects = effects, follow = follow, targetWidth = width, targetHeight = height,
                        targetFps = info.fps, videoBitrate = bitrate, processingProfile = case.profile,
                        enableLivePreview = true, trimStartMs = case.startMs, trimEndMs = endMs
                    ))
                    row.put("job_id", jobId).put("stage", "export")
                    var cancellationSent = false
                    withTimeout(900_000) {
                        while (true) {
                            val status = api.getJobStatus(jobId)
                            row.put("state", status.state).put("current_frame", status.currentFrame)
                                .put("total_frames", status.totalFrames).put("error_code", status.errorCode)
                                .put("error_message", status.errorMessage)
                                .put("elapsed_seconds", (SystemClock.elapsedRealtime() - began) / 1000.0)
                            persist()
                            if (status.state in listOf("completed", "failed", "cancelled")) break
                            if (case.cancel && !cancellationSent && status.currentFrame >= 25) {
                                api.cancelJob(jobId)
                                cancellationSent = true
                            }
                            delay(1000)
                        }
                        while (coordinator.resourceGate.ownsExport(jobId)) delay(50)
                    }
                    val terminal = api.getJobStatus(jobId)
                    assertEquals(if (case.cancel) "cancelled" else "completed", terminal.state,
                        "${case.name}: ${terminal.errorCode} ${terminal.errorMessage}")
                    if (case.cancel) {
                        assertTrue(cancellationSent)
                        assertEquals(null, terminal.outputUri)
                        assertFalse(output.exists(), "Cancelled 4K export must not publish a partial file")
                    } else {
                        assertTrue(output.length() > 0)
                        val actualInfo = VideoProbe.probe(context, output.absolutePath)
                        assertEquals(width, actualInfo.displayWidth, "Silent output width fallback")
                        assertEquals(height, actualInfo.displayHeight, "Silent output height fallback")
                        assertEquals(info.hasAudio, actualInfo.hasAudio, "Source audio must be preserved")
                        val expected = videoPts(input)
                            .filter { it >= case.startMs * 1000 && it < endMs * 1000 }.sorted()
                        val actual = videoPts(output)
                        row.put("expected_frames", expected.size).put("actual_frames", actual.size)
                            .put("last_pts_us", actual.lastOrNull()).put("output_bytes", output.length())
                        assertEquals(expected.size, actual.size, "Full source/trim must retain every picture")
                        assertTrue(actual.zipWithNext().all { (a, b) -> b > a })
                        assertTrue(expected.zip(actual).all { (a, b) -> abs(a - case.startMs * 1000 - b) <= 1 },
                            "Export must preserve every source presentation timestamp")
                        if (case.input == group && case.fill == "solid" && case.startMs <= 11600 && endMs > 11600) {
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(output.absolutePath)
                                val reviewPts = if (args.getString("leakFrame") == "true") 0L else 11_600_000L
                                val frame = requireNotNull(retriever.getFrameAtTime(reviewPts,
                                    android.media.MediaMetadataRetriever.OPTION_CLOSEST))
                                try {
                                    // Reviewed face core in the unchanged public fixture at frame 290.
                                    // A metadata-only pass previously missed this clearly visible mirror face.
                                    var protectedPixels = 0
                                    for (y in 1263 until 1287) for (x in 881 until 905) {
                                        val pixel = frame.getPixel(x, y)
                                        if (android.graphics.Color.red(pixel) > 170 &&
                                            android.graphics.Color.green(pixel) < 110 &&
                                            android.graphics.Color.blue(pixel) < 110) protectedPixels++
                                    }
                                    row.put("reviewed_mirror_face_protected_pixels", protectedPixels)
                                    assertTrue(protectedPixels >= 24 * 24 * 0.90,
                                        "Reviewed mirror face at frame 290 must be protected")
                                } finally { frame.recycle() }
                            } finally { retriever.release() }
                        }
                    }
                    if (!case.cancel && case.input == group && case.faceOnly && case.fill == "solid" &&
                        case.startMs <= 5800 && endMs > 5800) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(output.absolutePath)
                            val frame = requireNotNull(retriever.getFrameAtTime((5800 - case.startMs) * 1000,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST))
                            try {
                                // Frame 145: adjacent body association once borrowed a
                                // neighbor's face cache and left this visible side face clear.
                                var protectedPixels = 0
                                for (y in 670 until 694) for (x in 1360 until 1384) {
                                    val pixel = frame.getPixel(x, y)
                                    if (android.graphics.Color.red(pixel) > 170 &&
                                        android.graphics.Color.green(pixel) < 110 &&
                                        android.graphics.Color.blue(pixel) < 110) protectedPixels++
                                }
                                row.put("reviewed_side_face_protected_pixels", protectedPixels)
                                assertTrue(protectedPixels >= 24 * 24 * 0.9,
                                    "Visible side face at frame 145 must never borrow a neighbor face cache")
                            } finally { frame.recycle() }
                        } finally { retriever.release() }
                    }
                    if (!case.cancel && case.input == group && case.faceOnly && case.fill == "solid" &&
                        case.startMs <= 23200 && endMs > 23200) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(output.absolutePath)
                            val frame = requireNotNull(retriever.getFrameAtTime((23200 - case.startMs) * 1000,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST))
                            try {
                                // Frame 580: the foreground dancer's trouser leg is not a new head.
                                var falseFacePixels = 0
                                for (y in 1310 until 1334) for (x in 1390 until 1414) {
                                    val pixel = frame.getPixel(x, y)
                                    if (android.graphics.Color.red(pixel) > 170 &&
                                        android.graphics.Color.green(pixel) < 110 &&
                                        android.graphics.Color.blue(pixel) < 110) falseFacePixels++
                                }
                                row.put("reviewed_body_fragment_false_face_pixels", falseFacePixels)
                                assertTrue(falseFacePixels <= 24 * 24 * 0.1,
                                    "Lower-body fragment must not invent a face on the trouser leg")
                            } finally { frame.recycle() }
                        } finally { retriever.release() }
                    }
                    row.put("stage", "preview_after")
                    persist()
                    if (args.getString("leakFrame") != "true") {
                        assertTrue(File(api.getPreviewFrame(preview).thumbnailPath).isFile)
                    }
                    val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                    row.put("pss_kb_after", memory.totalPss).put("result", "passed")
                } catch (error: Throwable) {
                    row.put("result", "failed").put("exception", error.stackTraceToString())
                    throw error
                } finally {
                    row.put("elapsed_seconds", (SystemClock.elapsedRealtime() - began) / 1000.0)
                    persist()
                }
            }
            summary.put("result", "passed")
        } catch (error: Throwable) {
            summary.put("result", "failed")
            throw error
        } finally {
            persist()
            analyses.values.forEach { api.releaseProject(it.analysisCacheId) }
            api.close()
            host.runOnUiThread { host.finish() }
        }
    }

    private fun videoPts(file: File): List<Long> {
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            extractor.selectTrack((0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            })
            buildList {
                while (extractor.sampleTime >= 0) {
                    add(extractor.sampleTime)
                    if (!extractor.advance()) break
                }
            }
        } finally { extractor.release() }
    }
}
