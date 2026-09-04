package com.danceanon.native.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.danceanon.native.bridge.AnalyzeRequestDto
import com.danceanon.native.bridge.AnalyzeResultDto
import com.danceanon.native.bridge.DetectedPersonDto
import com.danceanon.native.diagnostics.NativeDiagnostics
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.YoloLiteRtSegmenter
import com.danceanon.native.litert.LiteRtAccelerator
import com.danceanon.native.media.CanonicalYuvInferenceDecoder
import com.danceanon.native.media.VideoProbe
import com.danceanon.native.storage.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt

class AnalyzePipeline(
    private val context: Context,
    private val cacheManager: CacheManager
) {

    suspend fun analyze(request: AnalyzeRequestDto): AnalyzeResultDto = withContext(Dispatchers.Default) {
        val videoInfo = VideoProbe.probe(context, request.videoUri)
        val cacheId = "analysis_${System.currentTimeMillis()}"
        cacheManager.saveVideoUri(cacheId, request.videoUri)

        val trimStartUs = request.trimStartMs.coerceAtLeast(0L) * 1000L
        val mapper = ModelCoordinateMapper(
            srcWidth = videoInfo.displayWidth.toInt().coerceAtLeast(1),
            srcHeight = videoInfo.displayHeight.toInt().coerceAtLeast(1),
            modelInputSize = 640,
            protoSize = 160
        )

        // Selection IDs are the identity roots for the whole project. Keep this one-shot path
        // independent from device GPU/OES behavior: canonical YUV input + strict CPU YOLO.
        var canonicalDecoder: CanonicalYuvInferenceDecoder? = null
        var canonicalFrame: CanonicalYuvInferenceDecoder.DecodedRgbaFrame? = null
        var canonicalFallbackReason: String? = null
        try {
            canonicalDecoder = CanonicalYuvInferenceDecoder(
                context = context,
                sourceUri = request.videoUri,
                rotationDegrees = videoInfo.rotation.toInt(),
                modelInputSize = mapper.modelInputSize,
                startUs = trimStartUs
            ).also { it.prepare() }
            canonicalFrame = canonicalDecoder.decodeRgbaAtOrAfterPts(trimStartUs, mapper)
        } catch (t: Throwable) {
            canonicalFallbackReason = "${t.javaClass.simpleName}:${t.message ?: "unknown"}"
            try { canonicalDecoder?.close() } catch (_: Throwable) {}
            canonicalDecoder = null
            canonicalFrame = null
            NativeDiagnostics.event(
                level = "WARN",
                component = "AnalyzePipeline",
                event = "ANALYZE_CANONICAL_INPUT_FALLBACK",
                fields = mapOf(
                    "analysis_cache_id" to cacheId,
                    "trim_start_us" to trimStartUs,
                    "reason" to canonicalFallbackReason
                )
            )
        }
        val analysisPtsUs = canonicalFrame?.ptsUs ?: trimStartUs
        val canonicalInputSha256 = canonicalFrame?.rgbaBuffer?.let(::sha256)

        // 1. Extract first frame Bitmap
        val retriever = MediaMetadataRetriever()
        var rawBitmap: Bitmap? = null
        try {
            if (request.videoUri.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(request.videoUri))
            } else {
                retriever.setDataSource(request.videoUri.removePrefix("file://"))
            }
            rawBitmap = retriever.getFrameAtTime(analysisPtsUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(analysisPtsUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (e: Exception) {
            try { canonicalDecoder?.close() } catch (_: Throwable) {}
            android.util.Log.e("AnalyzePipeline", "Failed to retrieve first frame", e)
            throw com.danceanon.native.bridge.DanceNativeException(
                com.danceanon.native.bridge.DanceNativeException.VIDEO_OPEN_FAILED,
                "Failed to extract first frame from video: ${request.videoUri} (${e.message})",
                e
            )
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        val frameBitmap = rawBitmap ?: run {
            try { canonicalDecoder?.close() } catch (_: Throwable) {}
            throw com.danceanon.native.bridge.DanceNativeException(
                com.danceanon.native.bridge.DanceNativeException.VIDEO_OPEN_FAILED,
                "Could not decode first video frame from: ${request.videoUri}"
            )
        }

        // Safety: Ensure bitmap is a software bitmap (ARGB_8888) to allow CPU pixel access and cropping
        val softwareBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && frameBitmap.config == Bitmap.Config.HARDWARE) {
            frameBitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                if (frameBitmap != it) frameBitmap.recycle()
            }
        } else {
            frameBitmap
        }

        // 2. Rotate to align visual coordinate system if needed
        val rotation = videoInfo.rotation.toInt()
        val visualBitmap = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(softwareBitmap, 0, 0, softwareBitmap.width, softwareBitmap.height, matrix, true)
            if (softwareBitmap != rotated) {
                softwareBitmap.recycle()
            }
            rotated
        } else {
            softwareBitmap
        }


        val detectionSpaceW = if (canonicalFrame != null) mapper.srcWidth.toFloat() else visualBitmap.width.toFloat()
        val detectionSpaceH = if (canonicalFrame != null) mapper.srcHeight.toFloat() else visualBitmap.height.toFloat()
        val bitmapSampleSha256 = bitmapGridSha256(visualBitmap)

        // 3. Run selection YOLO on strict CPU. The canonical path is preferred; a software
        // bitmap fallback remains functional but is explicitly surfaced in diagnostics.
        val selectionSegmenter = YoloLiteRtSegmenter(
            context = context,
            requestedAccelerator = LiteRtAccelerator.CPU,
            cpuNumThreads = 4
        )
        var selectionEffectiveAccelerator = LiteRtAccelerator.CPU.name
        val segFrame = try {
            selectionSegmenter.initialize()
            selectionEffectiveAccelerator = selectionSegmenter.effectiveAccelerator.name
            canonicalFrame?.let { frame ->
                selectionSegmenter.segmentGlReadbackRgbaSync(
                    rgbaBuffer = frame.rgbaBuffer,
                    mapper = mapper,
                    timestampUs = analysisPtsUs
                )
            } ?: selectionSegmenter.segmentBitmap(
                visualBitmap,
                analysisPtsUs
            )
        } finally {
            try { selectionSegmenter.close() } catch (_: Throwable) {}
            try { canonicalDecoder?.close() } catch (_: Throwable) {}
        }
        val safePersons = com.danceanon.native.privacy.PrivacySegmentationProcessor.DEFAULT.applyPrivacySafety(segFrame.persons)

        NativeDiagnostics.event(
            level = "INFO",
            component = "AnalyzePipeline",
            event = "ANALYZE_SELECTION_SIGNATURE",
            fields = mapOf(
                "analysis_cache_id" to cacheId,
                "requested_trim_start_us" to trimStartUs,
                "analysis_pts_us" to analysisPtsUs,
                "input_path" to if (canonicalFrame != null) "CANONICAL_YUV_CPU" else "BITMAP_CPU_FALLBACK",
                "canonical_rgba_sha256" to canonicalInputSha256,
                "bitmap_grid_sha256" to bitmapSampleSha256,
                "canonical_fallback_reason" to canonicalFallbackReason,
                "canonical_codec_name" to canonicalDecoder?.runtimeInfo?.codecName,
                "canonical_color_standard" to canonicalDecoder?.runtimeInfo?.colorStandard,
                "canonical_color_range" to canonicalDecoder?.runtimeInfo?.colorRange,
                "yolo_requested_accelerator" to LiteRtAccelerator.CPU.name,
                "yolo_effective_accelerator" to selectionEffectiveAccelerator,
                "cpu_num_threads" to 4,
                "detection_count" to safePersons.size,
                "diagnostic_candidate_ids_ge_0_60" to safePersons.mapIndexedNotNull { index, person ->
                    index.takeIf { person.confidence >= 0.60f }
                },
                "detections" to safePersons.mapIndexed { index, person ->
                    mapOf(
                        "index" to index,
                        "confidence_q1e4" to (person.confidence * 10_000f).roundToInt(),
                        "bbox_q0_0625px" to listOf(
                            (person.bbox.left * 16f).roundToInt(),
                            (person.bbox.top * 16f).roundToInt(),
                            (person.bbox.right * 16f).roundToInt(),
                            (person.bbox.bottom * 16f).roundToInt()
                        )
                    )
                }
            )
        )

        // 4. Build DetectedPersonDto list with thumbnails and save metadata
        val detectedPersons = mutableListOf<DetectedPersonDto>()
        val cachedPersons = mutableListOf<com.danceanon.native.storage.CachedPerson>()

        for ((index, person) in safePersons.withIndex()) {
            val thumbnailBbox = if (canonicalFrame != null) {
                scaleBbox(
                    bbox = person.bbox,
                    scaleX = visualBitmap.width.toFloat() / detectionSpaceW,
                    scaleY = visualBitmap.height.toFloat() / detectionSpaceH
                )
            } else {
                person.bbox
            }
            val thumbPath = cacheManager.savePersonThumbnail(
                cacheId = cacheId,
                personId = index,
                frameBitmap = visualBitmap,
                bbox = thumbnailBbox
            )


            val normX1 = (person.bbox.left / detectionSpaceW).toDouble().coerceIn(0.0, 1.0)
            val normY1 = (person.bbox.top / detectionSpaceH).toDouble().coerceIn(0.0, 1.0)
            val normX2 = (person.bbox.right / detectionSpaceW).toDouble().coerceIn(0.0, 1.0)
            val normY2 = (person.bbox.bottom / detectionSpaceH).toDouble().coerceIn(0.0, 1.0)

            detectedPersons.add(
                DetectedPersonDto(
                    id = index.toLong(),
                    x1 = normX1,
                    y1 = normY1,
                    x2 = normX2,
                    y2 = normY2,
                    thumbnailPath = thumbPath,
                    confidence = person.confidence.toDouble()
                )
            )

            cachedPersons.add(
                com.danceanon.native.storage.CachedPerson(
                    id = index,
                    bbox = com.danceanon.native.storage.CachedBBox(
                        left = normX1,
                        top = normY1,
                        right = normX2,
                        bottom = normY2
                    ),
                    confidence = person.confidence.toDouble()
                )
            )
        }

        // Save analysis.json metadata for export ID binding
        cacheManager.saveAnalysisMetadata(
            cacheId = cacheId,
            metadata = com.danceanon.native.storage.AnalysisMetadata(
                schemaVersion = 1,
                sourceUri = request.videoUri,
                persons = cachedPersons
            )
        )

        if (visualBitmap != softwareBitmap && !visualBitmap.isRecycled) {
            visualBitmap.recycle()
        }
        if (softwareBitmap != rawBitmap && !softwareBitmap.isRecycled) {
            softwareBitmap.recycle()
        }
        if (!rawBitmap.isRecycled) {
            rawBitmap.recycle()
        }


        AnalyzeResultDto(
            analysisCacheId = cacheId,
            videoInfo = videoInfo,
            persons = detectedPersons
        )
    }

    private fun scaleBbox(bbox: FloatRect, scaleX: Float, scaleY: Float): FloatRect = FloatRect(
        left = bbox.left * scaleX,
        top = bbox.top * scaleY,
        right = bbox.right * scaleX,
        bottom = bbox.bottom * scaleY
    )

    private fun sha256(buffer: ByteBuffer): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val duplicate = buffer.duplicate().apply { rewind() }
        val chunk = ByteArray(4096)
        while (duplicate.hasRemaining()) {
            val count = minOf(chunk.size, duplicate.remaining())
            duplicate.get(chunk, 0, count)
            digest.update(chunk, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Compact direct-pixel fingerprint of the visual thumbnail frame; inference uses canonical RGBA. */
    private fun bitmapGridSha256(bitmap: Bitmap, gridSize: Int = 64): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        for (gy in 0 until gridSize) {
            val y = (((gy + 0.5) * height) / gridSize).toInt().coerceIn(0, height - 1)
            for (gx in 0 until gridSize) {
                val x = (((gx + 0.5) * width) / gridSize).toInt().coerceIn(0, width - 1)
                val argb = bitmap.getPixel(x, y)
                digest.update((argb ushr 24).toByte())
                digest.update((argb ushr 16).toByte())
                digest.update((argb ushr 8).toByte())
                digest.update(argb.toByte())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
