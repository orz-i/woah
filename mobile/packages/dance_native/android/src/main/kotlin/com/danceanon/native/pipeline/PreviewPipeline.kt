package com.danceanon.native.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.opengl.GLES20
import android.opengl.GLUtils
import com.danceanon.native.bridge.DanceNativeException
import com.danceanon.native.bridge.PreviewFrameDto
import com.danceanon.native.bridge.PreviewRequestDto
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.YoloLiteRtSegmenter
import com.danceanon.native.media.VideoProbe
import com.danceanon.native.render.EglCore
import com.danceanon.native.render.GlRenderer
import com.danceanon.native.storage.CacheManager
import com.danceanon.native.tracking.HungarianSolver
import com.danceanon.native.tracking.TrackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Interface specification reserved for future non-zero timestamp tracking preview.
 *
 * In future phases, track states will be snapshotted every N seconds.
 * When seeking to arbitrary timestamp T, decoding will start from the closest snapshot <= T,
 * tracking forward to T to guarantee stable person IDs.
 *
 * NOTE: For V1, timeline preview is strictly pinned to timestampMs = 0 in the UI
 * to prevent single-frame Hungarian matching identity drift.
 */
interface TrackingSnapshotCache {
    fun saveSnapshot(timestampMs: Long, tracks: List<com.danceanon.native.tracking.TrackedPerson>)
    fun getNearestSnapshot(timestampMs: Long): Pair<Long, List<com.danceanon.native.tracking.TrackedPerson>>?
    fun clear()
}

class PreviewPipeline(
    private val context: Context,
    private val segmenter: YoloLiteRtSegmenter,
    private val cacheManager: CacheManager
) {
    private val analysisCache = PreviewAnalysisCache()

    suspend fun renderPreview(request: PreviewRequestDto): PreviewFrameDto = withContext(Dispatchers.Default) {
        if (request.timestampMs != 0L) {
            android.util.Log.w(
                "PreviewPipeline",
                "Non-zero timestamp preview (${request.timestampMs}ms) in V1 may exhibit identity drift. Recommended timestampMs = 0."
            )
        }

        val startTime = System.currentTimeMillis()

        val sourceUri = cacheManager.getVideoUri(request.analysisCacheId)
            ?: throw DanceNativeException(
                DanceNativeException.CACHE_NOT_FOUND,
                "Analysis cache not found for cacheId: ${request.analysisCacheId}"
            )

        val videoInfo = VideoProbe.probe(context, sourceUri)
        val cacheKey = "${request.analysisCacheId}_${request.timestampMs}"
        val cachedEntry = analysisCache.get(cacheKey)

        val rotatedBitmap: Bitmap
        val trackedPersons: List<com.danceanon.native.tracking.TrackedPerson>

        if (cachedEntry != null && !cachedEntry.frameBitmap.isRecycled) {
            val srcBmp = cachedEntry.frameBitmap
            val cfg = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && srcBmp.config == Bitmap.Config.HARDWARE) {
                Bitmap.Config.ARGB_8888
            } else {
                srcBmp.config ?: Bitmap.Config.ARGB_8888
            }
            rotatedBitmap = srcBmp.copy(cfg, true)
            trackedPersons = cachedEntry.trackedPersons
        } else {
            // 1. Extract frame Bitmap at requested timestamp
            val retriever = MediaMetadataRetriever()
            val rawBitmap = try {
                if (sourceUri.startsWith("content://")) {
                    retriever.setDataSource(context, Uri.parse(sourceUri))
                } else {
                    retriever.setDataSource(sourceUri.removePrefix("file://"))
                }
                val timeUs = (request.timestampMs * 1000L).coerceAtLeast(0L)
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw DanceNativeException(
                        DanceNativeException.DECODE_FRAME_FAILED,
                        "Failed to extract video frame at timestamp ${request.timestampMs}ms"
                    )
            } catch (e: DanceNativeException) {
                throw e
            } catch (e: Exception) {
                throw DanceNativeException(
                    DanceNativeException.DECODE_FRAME_FAILED,
                    "Failed to extract frame: ${e.message}",
                    e
                )
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            // Convert Android Hardware Bitmap to ARGB_8888 software bitmap for OpenGL upload & inference
            val softwareBmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && rawBitmap.config == Bitmap.Config.HARDWARE) {
                rawBitmap.copy(Bitmap.Config.ARGB_8888, false).also {
                    if (rawBitmap != it) rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            // Apply rotation if needed
            val rotation = videoInfo.rotation.toInt()
            val baseRotatedBitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(softwareBmp, 0, 0, softwareBmp.width, softwareBmp.height, matrix, true)
                if (softwareBmp != rotated) softwareBmp.recycle()
                rotated
            } else {
                softwareBmp
            }

            val frameWidth = baseRotatedBitmap.width
            val frameHeight = baseRotatedBitmap.height

            // 2. Perform AI Segmentation
            segmenter.initialize()
            val segResult = segmenter.segmentBitmapSync(baseRotatedBitmap, request.timestampMs * 1000L)
            val detections = com.danceanon.native.privacy.PrivacySegmentationProcessor.DEFAULT.applyPrivacySafety(segResult.persons)


            // 3. Assign stable IDs matching analysis cache
            val metadata = cacheManager.getAnalysisMetadata(request.analysisCacheId)
            val tracker = TrackManager()
            val persons = if (metadata != null && metadata.persons.isNotEmpty() && detections.isNotEmpty()) {
                val cached = metadata.persons
                val costMatrix = Array(cached.size) { r ->
                    val cPerson = cached[r]
                    val cLeft = (cPerson.bbox.left * frameWidth).toFloat()
                    val cTop = (cPerson.bbox.top * frameHeight).toFloat()
                    val cRight = (cPerson.bbox.right * frameWidth).toFloat()
                    val cBottom = (cPerson.bbox.bottom * frameHeight).toFloat()
                    val cBox = FloatRect(cLeft, cTop, cRight, cBottom)

                    FloatArray(detections.size) { c ->
                        val dBox = detections[c].bbox
                        1.0f - TrackManager.computeBBoxIoU(cBox, dBox)
                    }
                }
                val matchResult = HungarianSolver.match(costMatrix, maxCostThreshold = 0.70f)
                val assignedIds = IntArray(detections.size) { -1 }
                val usedIds = mutableSetOf<Int>()
                for (m in matchResult.matches) {
                    val cachedId = cached[m.first].id
                    assignedIds[m.second] = cachedId
                    usedIds.add(cachedId)
                }
                var nextId = (cached.maxOfOrNull { it.id } ?: -1) + 1
                for (i in assignedIds.indices) {
                    if (assignedIds[i] == -1) {
                        while (usedIds.contains(nextId)) nextId++
                        assignedIds[i] = nextId
                        usedIds.add(nextId)
                        nextId++
                    }
                }
                tracker.initializeWithAssignedIds(detections, assignedIds.toList())
            } else {
                tracker.initialize(detections)
            }

            analysisCache.put(
                cacheKey,
                CachedPreviewAnalysis(
                    frameBitmap = baseRotatedBitmap,
                    trackedPersons = persons,
                    timestampMs = request.timestampMs
                )
            )

            rotatedBitmap = baseRotatedBitmap.copy(baseRotatedBitmap.config ?: Bitmap.Config.ARGB_8888, true)
            trackedPersons = persons
        }

        // 4. Offscreen GL Rendering with EGL Core
        val previewWidth = minOf(rotatedBitmap.width, 1280)
        val previewHeight = (previewWidth * (rotatedBitmap.height.toFloat() / rotatedBitmap.width)).toInt().coerceAtLeast(1)

        val eglCore = EglCore()
        val eglSurface = eglCore.createOffscreenSurface(previewWidth, previewHeight)
        eglCore.makeCurrent(eglSurface)

        val glRenderer = GlRenderer()
        glRenderer.initialize(previewWidth, previewHeight)

        // Upload frame texture
        val frameTextures = IntArray(1)
        GLES20.glGenTextures(1, frameTextures, 0)
        val frameTextureId = frameTextures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, rotatedBitmap, 0)
        rotatedBitmap.recycle()

        val selectedIds = request.selectedPersonIds.map { it.toInt() }.toSet()

        glRenderer.render(
            frameTexture = frameTextureId,
            texMatrix = null,
            persons = trackedPersons,
            selectedPersonIds = selectedIds,
            effects = request.effects,
            follow = request.follow,
            presentationTimeUs = request.timestampMs * 1000L,
            textureType = com.danceanon.native.render.SourceTextureType.TEXTURE_2D
        )

        val renderedBitmap = glRenderer.captureRenderedFrame()
            ?: throw DanceNativeException(DanceNativeException.RENDER_FAILED, "Failed to capture rendered preview frame")

        // Cleanup GL
        GLES20.glDeleteTextures(1, frameTextures, 0)
        glRenderer.close()
        eglCore.releaseSurface(eglSurface)
        eglCore.close()

        // 5. Save preview file with unique timestamp nonce to prevent Flutter image caching
        try {
            val oldFiles = context.cacheDir.listFiles { _, name ->
                name.startsWith("preview_${request.analysisCacheId}_") && name.endsWith(".jpg")
            }
            oldFiles?.forEach { it.delete() }
        } catch (_: Exception) {}

        val previewOutFile = File(context.cacheDir, "preview_${request.analysisCacheId}_${request.timestampMs}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(previewOutFile).use { out ->
            renderedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        renderedBitmap.recycle()


        val elapsed = (System.currentTimeMillis() - startTime).toDouble()

        PreviewFrameDto(
            thumbnailPath = previewOutFile.absolutePath,
            timestampMs = request.timestampMs,
            renderTimeMs = elapsed
        )
    }

    fun clearForAnalysis(cacheId: String) {
        analysisCache.clearForAnalysis(cacheId)
    }

    fun clear() {
        analysisCache.clear()
    }
}

