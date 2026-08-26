package com.danceanon.native.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.danceanon.native.bridge.AnalyzeRequestDto
import com.danceanon.native.bridge.AnalyzeResultDto
import com.danceanon.native.bridge.DetectedPersonDto
import com.danceanon.native.inference.YoloOnnxSegmenter
import com.danceanon.native.media.VideoProbe
import com.danceanon.native.storage.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AnalyzePipeline(
    private val context: Context,
    private val segmenter: YoloOnnxSegmenter,
    private val cacheManager: CacheManager
) {

    suspend fun analyze(request: AnalyzeRequestDto): AnalyzeResultDto = withContext(Dispatchers.Default) {
        val videoInfo = VideoProbe.probe(context, request.videoUri)
        val cacheId = "analysis_${System.currentTimeMillis()}"
        cacheManager.saveVideoUri(cacheId, request.videoUri)

        // 1. Extract first frame Bitmap
        val retriever = MediaMetadataRetriever()
        var rawBitmap: Bitmap? = null
        try {
            if (request.videoUri.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(request.videoUri))
            } else {
                retriever.setDataSource(request.videoUri.removePrefix("file://"))
            }
            rawBitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.frameAtTime
        } catch (e: Exception) {
            android.util.Log.e("AnalyzePipeline", "Failed to retrieve first frame", e)
            throw com.danceanon.native.bridge.DanceNativeException(
                com.danceanon.native.bridge.DanceNativeException.VIDEO_OPEN_FAILED,
                "Failed to extract first frame from video: ${request.videoUri} (${e.message})",
                e
            )
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }

        val frameBitmap = rawBitmap ?: throw com.danceanon.native.bridge.DanceNativeException(
            com.danceanon.native.bridge.DanceNativeException.VIDEO_OPEN_FAILED,
            "Could not decode first video frame from: ${request.videoUri}"
        )

        // 2. Rotate to align visual coordinate system if needed
        val rotation = videoInfo.rotation.toInt()
        val visualBitmap = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(frameBitmap, 0, 0, frameBitmap.width, frameBitmap.height, matrix, true)
            if (frameBitmap != rawBitmap) {
                frameBitmap.recycle()
            }
            rotated
        } else {
            frameBitmap
        }

        val visualW = visualBitmap.width.toFloat()
        val visualH = visualBitmap.height.toFloat()

        // 3. Initialize & Run YOLO Segmentation
        segmenter.initialize()
        val segFrame = segmenter.segmentBitmap(visualBitmap, 0)
        val safePersons = com.danceanon.native.privacy.PrivacySegmentationProcessor.DEFAULT.applyPrivacySafety(segFrame.persons)

        // 4. Build DetectedPersonDto list with thumbnails and save metadata
        val detectedPersons = mutableListOf<DetectedPersonDto>()
        val cachedPersons = mutableListOf<com.danceanon.native.storage.CachedPerson>()

        for ((index, person) in safePersons.withIndex()) {
            val thumbPath = cacheManager.savePersonThumbnail(
                cacheId = cacheId,
                personId = index,
                frameBitmap = visualBitmap,
                bbox = person.bbox
            )


            val normX1 = (person.bbox.left / visualW).toDouble().coerceIn(0.0, 1.0)
            val normY1 = (person.bbox.top / visualH).toDouble().coerceIn(0.0, 1.0)
            val normX2 = (person.bbox.right / visualW).toDouble().coerceIn(0.0, 1.0)
            val normY2 = (person.bbox.bottom / visualH).toDouble().coerceIn(0.0, 1.0)

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

        if (visualBitmap != rawBitmap) {
            visualBitmap.recycle()
        }
        rawBitmap.recycle()

        AnalyzeResultDto(
            analysisCacheId = cacheId,
            videoInfo = videoInfo,
            persons = detectedPersons
        )
    }
}
