package com.danceanon.dance_native

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import com.danceanon.native.bridge.DanceNativeApi
import com.danceanon.native.bridge.DanceNativeApiImpl
import com.danceanon.native.bridge.DanceProcessingEvents
import com.danceanon.native.export.ExportCoordinator
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** DanceNativePlugin */
class DanceNativePlugin :
    FlutterPlugin,
    MethodCallHandler {
    private lateinit var channel: MethodChannel
    private var context: Context? = null
    private var apiImpl: DanceNativeApiImpl? = null
    private var mainHandler: Handler? = null
    private var thumbnailExecutor: ExecutorService? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        val appCtx = flutterPluginBinding.applicationContext
        context = appCtx
        mainHandler = Handler(Looper.getMainLooper())
        thumbnailExecutor?.shutdownNow()
        thumbnailExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DanceTrimThumbnail").apply { isDaemon = true }
        }
        com.danceanon.native.diagnostics.NativeDiagnostics.initialize(appCtx)

        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "dance_native")
        channel.setMethodCallHandler(this)

        val eventEmitter = DanceProcessingEvents(flutterPluginBinding.binaryMessenger)
        val impl = DanceNativeApiImpl(appCtx, eventEmitter)
        apiImpl = impl
        DanceNativeApi.setUp(flutterPluginBinding.binaryMessenger, impl)
    }

    private fun createTrimThumbnails(
        ctx: Context,
        videoUri: String,
        timestampsMs: List<Number>
    ): List<String> {
        val retriever = MediaMetadataRetriever()
        try {
            if (videoUri.startsWith("content://")) {
                retriever.setDataSource(ctx, Uri.parse(videoUri))
            } else {
                retriever.setDataSource(videoUri.removePrefix("file://"))
            }

            val dir = File(ctx.cacheDir, "trim_thumbnails").apply { mkdirs() }
            val token = System.currentTimeMillis()
            return timestampsMs.mapIndexedNotNull { index, value ->
                if (Thread.currentThread().isInterrupted) return@mapIndexedNotNull null

                val timestampUs = value.toLong().coerceAtLeast(0L) * 1000L
                val bitmap = getTrimThumbnailFrame(retriever, timestampUs)
                    ?: return@mapIndexedNotNull null
                try {
                    val out = File(dir, "trim_${token}_${index}.jpg")
                    val written = out.outputStream().use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 72, stream)
                    }
                    if (written) {
                        out.absolutePath
                    } else {
                        out.delete()
                        null
                    }
                } finally {
                    try { bitmap.recycle() } catch (_: Throwable) {}
                }
            }
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
        }
    }

    private fun getTrimThumbnailFrame(
        retriever: MediaMetadataRetriever,
        timestampUs: Long
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            return retriever.getScaledFrameAtTime(
                timestampUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                TRIM_THUMBNAIL_MAX_SIZE,
                TRIM_THUMBNAIL_MAX_SIZE
            ) ?: retriever.getScaledFrameAtTime(
                timestampUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                TRIM_THUMBNAIL_MAX_SIZE,
                TRIM_THUMBNAIL_MAX_SIZE
            )
        }

        val fullSize = retriever.getFrameAtTime(
            timestampUs,
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        ) ?: retriever.getFrameAtTime(
            timestampUs,
            MediaMetadataRetriever.OPTION_CLOSEST
        ) ?: retriever.frameAtTime ?: return null
        return scaleBitmapForThumbnail(fullSize)
    }

    private fun scaleBitmapForThumbnail(bitmap: Bitmap): Bitmap {
        val maxDimension = maxOf(bitmap.width, bitmap.height)
        if (maxDimension <= TRIM_THUMBNAIL_MAX_SIZE) return bitmap

        val targetWidth = ((bitmap.width.toLong() * TRIM_THUMBNAIL_MAX_SIZE) / maxDimension)
            .toInt()
            .coerceAtLeast(1)
        val targetHeight = ((bitmap.height.toLong() * TRIM_THUMBNAIL_MAX_SIZE) / maxDimension)
            .toInt()
            .coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaled !== bitmap) {
            try { bitmap.recycle() } catch (_: Throwable) {}
        }
        return scaled
    }

    override fun onMethodCall(
        call: MethodCall,
        result: Result
    ) {
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${Build.VERSION.RELEASE}")
            }
            "saveVideoToGallery" -> {
                val filePath = call.argument<String>("filePath")
                val ctx = context
                if (filePath == null || ctx == null) {
                    result.error("INVALID_ARGS", "filePath or context is null", null)
                    return
                }
                try {
                    val uri = saveVideoToMediaStore(ctx, filePath)
                    result.success(uri)
                } catch (e: Exception) {
                    android.util.Log.e("DanceNativePlugin", "Failed to save video to gallery: ${e.message}", e)
                    result.error("SAVE_FAILED", e.message ?: "Failed to save video to gallery", null)
                }
            }
            "shareVideo" -> {
                val publicUri = call.argument<String>("publicUri")
                val ctx = context
                if (publicUri.isNullOrBlank() || ctx == null) {
                    result.error("INVALID_ARGS", "publicUri or context is null", null)
                    return
                }
                try {
                    val uri = Uri.parse(publicUri)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "video/mp4"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(shareIntent, "分享视频").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(chooser)
                    result.success(null)
                } catch (e: Exception) {
                    android.util.Log.e("DanceNativePlugin", "Failed to share video: ${e.message}", e)
                    result.error("SHARE_VIDEO_FAILED", e.message ?: "Failed to share video", null)
                }
            }
            "openVideo" -> {
                val publicUri = call.argument<String>("publicUri")
                val ctx = context
                if (publicUri.isNullOrBlank() || ctx == null) {
                    result.error("INVALID_ARGS", "publicUri or context is null", null)
                    return
                }
                try {
                    val uri = Uri.parse(publicUri)
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(viewIntent)
                    result.success(null)
                } catch (e: Exception) {
                    android.util.Log.e("DanceNativePlugin", "Failed to open video: ${e.message}", e)
                    result.error("OPEN_VIDEO_FAILED", e.message ?: "Failed to open video", null)
                }
            }
            "getVideoFrameThumbnails" -> {
                val videoUri = call.argument<String>("videoUri")
                val timestampsMs = call.argument<List<Number>>("timestampsMs")
                val ctx = context
                if (videoUri.isNullOrBlank() || timestampsMs == null || ctx == null) {
                    result.error("INVALID_ARGS", "videoUri, timestampsMs or context is null", null)
                    return
                }

                val executor = thumbnailExecutor
                val replyHandler = mainHandler
                if (executor == null || replyHandler == null) {
                    result.error("PLUGIN_DETACHED", "Thumbnail worker is unavailable", null)
                    return
                }
                try {
                    executor.execute {
                        try {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                            val paths = createTrimThumbnails(ctx, videoUri, timestampsMs)
                            replyHandler.post { result.success(paths) }
                        } catch (e: Exception) {
                            android.util.Log.e("DanceNativePlugin", "Failed to create trim thumbnails: ${e.message}", e)
                            replyHandler.post {
                                result.error("THUMBNAIL_FAILED", e.message ?: "Failed to create trim thumbnails", null)
                            }
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    result.error("PLUGIN_DETACHED", "Thumbnail worker is shutting down", null)
                }
            }
            "setExportLivePreviewEnabled" -> {
                val jobId = call.argument<String>("jobId")
                val enabled = call.argument<Boolean>("enabled")
                val ctx = context
                if (jobId.isNullOrBlank() || enabled == null || ctx == null) {
                    result.error("INVALID_ARGS", "jobId, enabled or context is null", null)
                    return
                }
                ExportCoordinator.getInstance(ctx).setLivePreviewEnabled(jobId, enabled)
                result.success(null)
            }
            "createDiagnosticBundle" -> {
                val ctx = context
                if (ctx == null) {
                    result.error("NO_CONTEXT", "Plugin context is null", null)
                    return
                }
                try {
                    val bundleInfo = com.danceanon.native.diagnostics.DiagnosticBundleExporter.createBundle(ctx)
                    result.success(bundleInfo)
                } catch (e: Exception) {
                    android.util.Log.e("DanceNativePlugin", "Failed to create diagnostic bundle: ${e.message}", e)
                    result.error("BUNDLE_FAILED", e.message ?: "Failed to create diagnostic bundle", null)
                }
            }
            "shareDiagnosticBundle" -> {
                val ctx = context
                if (ctx == null) {
                    result.error("NO_CONTEXT", "Plugin context is null", null)
                    return
                }
                val filePath = call.argument<String>("filePath")
                val publicUri = call.argument<String>("publicUri")
                try {
                    val shareResult = com.danceanon.native.diagnostics.DiagnosticBundleExporter.shareBundle(ctx, filePath, publicUri)
                    result.success(shareResult)
                } catch (e: Exception) {
                    android.util.Log.e("DanceNativePlugin", "Failed to share diagnostic bundle: ${e.message}", e)
                    result.error("SHARE_FAILED", e.message ?: "Failed to share diagnostic bundle", null)
                }
            }
            "clearDiagnosticLogs" -> {
                val ctx = context
                if (ctx != null) {
                    com.danceanon.native.diagnostics.DiagnosticBundleExporter.clearLogs(ctx)
                }
                result.success(null)
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    private fun saveVideoToMediaStore(ctx: Context, filePath: String): String {
        val file = File(filePath)
        if (!file.exists()) {
            throw FileNotFoundException("File does not exist: $filePath")
        }

        val filename = "DanceAnon_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DanceAnon")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = ctx.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collection, values) ?: throw IOException("Failed to create MediaStore record")

        resolver.openOutputStream(itemUri).use { out ->
            if (out == null) throw IOException("Failed to open output stream for $itemUri")
            FileInputStream(file).use { input ->
                input.copyTo(out)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        }

        MediaScannerConnection.scanFile(
            ctx,
            arrayOf(file.absolutePath),
            arrayOf("video/mp4"),
            null
        )

        return itemUri.toString()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        DanceNativeApi.setUp(binding.binaryMessenger, null)
        thumbnailExecutor?.shutdownNow()
        thumbnailExecutor = null
        mainHandler = null
        try {
            apiImpl?.close()
        } catch (_: Throwable) {}
        apiImpl = null
        context = null
    }

    private companion object {
        const val TRIM_THUMBNAIL_MAX_SIZE = 240
    }
}
