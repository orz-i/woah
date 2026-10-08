package art.gaoge.dance.engine.clarity

import android.content.Context
import android.graphics.Bitmap
import art.gaoge.dance.engine.BuildConfig
import art.gaoge.dance.engine.diagnostics.NativeDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Explicit opt-in, debug-only, bounded capture of the real *protected* export output.
 * The marker is consumed once; PNGs are kept only in a private, separate ZIP, never
 * attached to the normal (automatically shared) diagnostic bundle. No raw source,
 * mask or unprotected intermediate is saved. Run via `adb shell run-as ...` only.
 *
 * The two variants come from the same decoded frame/tracks/privacy composition
 * and the very same crop matrix. Only crop-clarity strength differs.
 */
class CropClarityAbCapture private constructor(
    private val jobId: String,
    private val nominalFrames: Int,
    private val scale: Double,
    private val width: Int,
    private val height: Int,
    private val file: File
) : AutoCloseable {
    private val partialFile = File(file.parentFile, "${file.name}.partial")
    private val zip = ZipOutputStream(FileOutputStream(partialFile))
    private val sampler = CropClaritySceneSampler(nominalFrames)
    private val targets = linkedSetOf<Int>()
    private val samples = JSONArray()
    private var closed = false
    private var broken = false

    fun choose(frameNumber: Int, signals: CropClaritySceneSampler.Signals): CropClaritySceneSampler.Choice? {
        if (closed || broken) return null
        val choice = sampler.consider(frameNumber, signals) ?: return null
        targets += frameNumber
        NativeDiagnostics.event(
            level = "INFO", component = "CropClarityAbCapture", event = "CROP_CLARITY_SCENE_SELECTED",
            fields = mapOf("job_id" to jobId, "frame" to frameNumber, "kind" to choice.kind,
                "burst_index" to choice.burstIndex, "contrast" to choice.contrast,
                "motion" to choice.motion, "overlap" to choice.overlap)
        )
        return choice
    }

    /** Takes ownership of both bitmaps, recycling them regardless of write outcome. */
    fun capture(
        choice: CropClaritySceneSampler.Choice,
        ptsUs: Long,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        baseline: Bitmap,
        enhanced: Bitmap
    ) {
        val frameNumber = choice.frame
        try {
            check(!closed && !broken && targets.contains(frameNumber)) {
                "Frame $frameNumber is not a scheduled quality-gate capture"
            }
            check(baseline.width == width && baseline.height == height)
            check(enhanced.width == width && enhanced.height == height)
            val id = "frame_${frameNumber.toString().padStart(6, '0')}"
            val baselineName = "${id}_off.png"
            val enhancedName = "${id}_on.png"
            zip.putNextEntry(ZipEntry(baselineName))
            check(baseline.compress(Bitmap.CompressFormat.PNG, 100, zip))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(enhancedName))
            check(enhanced.compress(Bitmap.CompressFormat.PNG, 100, zip))
            zip.closeEntry()

            samples.put(JSONObject().apply {
                put("frame", frameNumber)
                put("pts_us", ptsUs)
                put("baseline", baselineName)
                put("enhanced", enhancedName)
                put("crop", JSONArray(listOf(cropLeft, cropTop, cropRight, cropBottom)))
                put("scene_kind", choice.kind)
                put("burst_index", choice.burstIndex ?: JSONObject.NULL)
                put("luma_mean", choice.lumaMean.toDouble())
                put("contrast", choice.contrast)
                put("protected_overlap", choice.overlap.toDouble())
                put("protagonist_motion", choice.motion.toDouble())
                put("protected_count", choice.protectedCount)
                put("same_decoded_frame", true)
                put("same_privacy_state", true)
                put("same_crop_matrix", true)
            })
            NativeDiagnostics.event(
                level = "INFO", component = "CropClarityAbCapture", event = "CROP_CLARITY_AB_PAIR_CAPTURED",
                fields = mapOf("job_id" to jobId, "frame" to frameNumber, "pts_us" to ptsUs,
                    "pairs" to samples.length())
            )
        } catch (error: Throwable) {
            broken = true
            NativeDiagnostics.event(
                level = "WARN", component = "CropClarityAbCapture", event = "CROP_CLARITY_AB_CAPTURE_FAILED",
                fields = mapOf("job_id" to jobId, "frame" to frameNumber,
                    "error" to error.javaClass.simpleName)
            )
        } finally {
            baseline.recycle()
            enhanced.recycle()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (!broken && samples.length() > 0) {
                val manifest = JSONObject().apply {
                    put("schema", 2)
                    put("job_id", jobId)
                    put("privacy_composited", true)
                    put("capture_mode", "same_frame_same_crop_two_pass_scene_driven")
                    put("source_material_included", false)
                    put("scale", scale)
                    put("strength_off", 0.0)
                    put("strength_on", CropClarityQualityGate.strength(scale).toDouble())
                    put("output_width", width)
                    put("output_height", height)
                    put("nominal_frames", nominalFrames)
                    put("selection_policy", "online_scene_heuristics_v2")
                    put("max_pairs", CropClaritySceneSampler.MAX_PAIRS)
                    put("expected_frames", JSONArray(targets.sorted()))
                    put("scene_counts", JSONObject(sampler.counts))
                    put("screening", JSONObject(sampler.screeningSummary))
                    put("samples", samples)
                }
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        } catch (_: Throwable) {
            broken = true
        } finally {
            try { zip.close() } catch (_: Throwable) { broken = true }
        }
        if (broken || samples.length() == 0 || !partialFile.renameTo(file)) {
            partialFile.delete()
            file.delete()
            NativeDiagnostics.event(
                level = "WARN", component = "CropClarityAbCapture", event = "CROP_CLARITY_AB_NO_ARTIFACT",
                fields = mapOf("job_id" to jobId, "broken" to broken,
                    "chosen_frames" to targets.size, "screening" to sampler.screeningSummary)
            )
        } else {
            val capturedFrames = (0 until samples.length()).mapNotNull { index ->
                samples.optJSONObject(index)?.optInt("frame")
            }.toSet()
            val missingFrames = CropClarityQualityGate.missingFrames(targets, capturedFrames)
            NativeDiagnostics.event(
                level = if (missingFrames.isEmpty()) "INFO" else "WARN",
                component = "CropClarityAbCapture",
                event = "CROP_CLARITY_AB_READY",
                fields = mapOf(
                    "job_id" to jobId,
                    "file_name" to file.name,
                    "pair_count" to samples.length(),
                    "expected_pair_count" to targets.size,
                    "expected_frames" to targets.sorted(),
                    "missing_frames" to missingFrames,
                    "complete" to missingFrames.isEmpty(),
                    "scene_counts" to sampler.counts,
                    "screening" to sampler.screeningSummary,
                    "temporal_pairs" to sampler.counts.getOrDefault("temporal_burst", 0),
                    "debug_opt_in" to true
                )
            )
        }
    }

    companion object {
        /** Final check on the already-protected output; no raw/source pixels leave the renderer. */
        fun hasVisibleProtectedContent(bitmap: Bitmap): Boolean {
            if (bitmap.width <= 0 || bitmap.height <= 0) return false
            val stepX = (bitmap.width / 16).coerceAtLeast(1)
            val stepY = (bitmap.height / 16).coerceAtLeast(1)
            var sum = 0L
            var count = 0
            var bright = 0
            var maximum = 0
            for (y in stepY / 2 until bitmap.height step stepY) {
                for (x in stepX / 2 until bitmap.width step stepX) {
                    val rgb = bitmap.getPixel(x, y)
                    val lum = (54 * android.graphics.Color.red(rgb) +
                        183 * android.graphics.Color.green(rgb) +
                        19 * android.graphics.Color.blue(rgb) + 128) shr 8
                    sum += lum
                    if (lum >= 32) bright++
                    maximum = maxOf(maximum, lum)
                    count++
                }
            }
            return count > 0 && sum >= count * 12L &&
                bright * 40 >= count && maximum >= 48
        }

        fun beginIfRequested(
            context: Context,
            jobId: String,
            nominalFrames: Int,
            requestedScale: Double,
            postCrop: Boolean,
            hasPrivacyTargets: Boolean,
            outputWidth: Int,
            outputHeight: Int
        ): CropClarityAbCapture? {
            // Consume only in debug exports that actually need clarity. Release has
            // no I/O or allocation even if a stale marker exists on the device.
            if (!CropClarityQualityGate.eligible(BuildConfig.DEBUG, postCrop, hasPrivacyTargets, requestedScale)) return null
            val marker = File(context.cacheDir, CropClarityQualityGate.ENABLE_MARKER)
            if (!marker.isFile || !marker.delete()) return null
            return try {
                val dir = File(context.cacheDir, "crop_clarity_ab").apply { mkdirs() }
                // Bounded retention: private media from previous QA runs should
                // not silently accumulate without limit in app cache.
                dir.listFiles()?.filter { it.isFile && it.name.startsWith("crop_clarity_ab_") &&
                    it.name.endsWith(".zip") }?.sortedByDescending { it.lastModified() }
                    ?.drop(2)?.forEach { it.delete() }
                val safeJobId = jobId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)
                CropClarityAbCapture(
                    jobId = safeJobId,
                    nominalFrames = nominalFrames,
                    scale = CropClarityQualityGate.safeScale(requestedScale),
                    width = outputWidth,
                    height = outputHeight,
                    file = File(dir, "crop_clarity_ab_${safeJobId}.zip")
                )
            } catch (error: Throwable) {
                NativeDiagnostics.event(
                    level = "WARN", component = "CropClarityAbCapture", event = "CROP_CLARITY_AB_INIT_FAILED",
                    fields = mapOf("error" to error.javaClass.simpleName)
                )
                null
            }
        }
    }
}
