package art.gaoge.dance.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import art.gaoge.dance.engine.bridge.DanceNativeApi
import art.gaoge.dance.engine.bridge.DanceNativeApiImpl
import art.gaoge.dance.engine.bridge.DanceProcessingEvents
import art.gaoge.dance.engine.export.ExportCoordinator
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import art.gaoge.dance.engine.bridge.comparison.DanceComparisonApi
import art.gaoge.dance.engine.comparison.ComparisonNativeApi
import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** DanceNativePlugin */
class DanceNativePlugin :
    FlutterPlugin,
    MethodCallHandler, ActivityAware {
    private var comparisonApi: ComparisonNativeApi?=null
    private var currentActivity: Activity?=null
    private val comparisonLifecycle=object: Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(activity: Activity) { if (activity===currentActivity) comparisonApi?.background() }
        override fun onActivityResumed(activity: Activity) { if (activity===currentActivity) comparisonApi?.foreground() }
        override fun onActivityCreated(activity: Activity,state: Bundle?)=Unit
        override fun onActivityStarted(activity: Activity)=Unit
        override fun onActivityStopped(activity: Activity)=Unit
        override fun onActivitySaveInstanceState(activity: Activity,state: Bundle)=Unit
        override fun onActivityDestroyed(activity: Activity)=Unit
    }
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        currentActivity=binding.activity
        binding.activity.application.registerActivityLifecycleCallbacks(comparisonLifecycle)
        comparisonApi?.attach(binding)
    }
    override fun onDetachedFromActivityForConfigChanges()=onDetachedFromActivity()
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding)=onAttachedToActivity(binding)
    override fun onDetachedFromActivity() {
        currentActivity?.application?.unregisterActivityLifecycleCallbacks(comparisonLifecycle)
        comparisonApi?.detach();currentActivity=null
    }
    private lateinit var channel: MethodChannel
    private var context: Context? = null
    private var apiImpl: DanceNativeApiImpl? = null
    private var mainHandler: Handler? = null
    private var thumbnailExecutor: ExecutorService? = null
    private var trimCacheOwner: Pair<art.gaoge.dance.engine.storage.RegenerableCacheRegistry, art.gaoge.dance.engine.storage.RegenerableCacheRegistry.Owner>? = null
    private var gallerySaveWorker: art.gaoge.dance.engine.media.GallerySaveWorker? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        val appCtx = flutterPluginBinding.applicationContext
        comparisonApi=ComparisonNativeApi(appCtx,flutterPluginBinding.textureRegistry)
        DanceComparisonApi.setUp(flutterPluginBinding.binaryMessenger,comparisonApi)
        context = appCtx
        val replyHandler = Handler(Looper.getMainLooper())
        mainHandler = replyHandler
        gallerySaveWorker?.close()
        gallerySaveWorker = art.gaoge.dance.engine.media.GallerySaveWorker(
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "WoahGallerySave").apply { isDaemon = true }
            },
            postReply = { callback -> replyHandler.post { callback() } },
            save = { sourcePath, control ->
                art.gaoge.dance.engine.media.GallerySaveTransaction(
                    art.gaoge.dance.engine.media.MediaStoreVideoSaveBackend(appCtx.contentResolver)
                ).save({ FileInputStream(File(sourcePath)) }, control)
            }
        )
        thumbnailExecutor?.shutdown()
        trimCacheOwner?.let { (registry, owner) -> registry.releaseOwner(owner) }
        trimCacheOwner = ExportCoordinator.getInstance(appCtx).cacheRegistry to art.gaoge.dance.engine.storage.RegenerableCacheRegistry.Owner()
        thumbnailExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DanceTrimThumbnail").apply { isDaemon = true }
        }
        if (art.gaoge.dance.engine.diagnostics.DiagnosticsBuild.ENABLED) {
            art.gaoge.dance.engine.diagnostics.NativeDiagnostics.initialize(appCtx)
        }

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
        timestampsMs: List<Number>,
        batchId: String
    ): List<String> {
        val retriever = MediaMetadataRetriever()
        try {
            if (videoUri.startsWith("content://")) {
                retriever.setDataSource(ctx, Uri.parse(videoUri))
            } else {
                retriever.setDataSource(videoUri.removePrefix("file://"))
            }

            val dir = File(ctx.cacheDir.canonicalFile, "trim_thumbnails")
            check(dir.canonicalFile == dir) { "INVALID_CACHE_PATH" }
            check(dir.isDirectory || dir.mkdirs()) { "CACHE_DIRECTORY_FAILED" }
            return timestampsMs.mapIndexedNotNull { index, value ->
                if (Thread.currentThread().isInterrupted) return@mapIndexedNotNull null

                val timestampUs = value.toLong().coerceAtLeast(0L) * 1000L
                val bitmap = getTrimThumbnailFrame(retriever, timestampUs)
                    ?: return@mapIndexedNotNull null
                try {
                    val out = File(dir, "${batchId}_${index}.jpg")
                    check(out.canonicalFile == out) { "INVALID_CACHE_PATH" }
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
        if (DiagnosticsChannelBridge.handle(call, result, context)) return
        when (call.method) {
            "getPlatformVersion" -> {
                result.success("Android ${Build.VERSION.RELEASE}")
            }
            "persistStickerAsset" -> {
                val bytes = call.argument<ByteArray>("bytes")
                val ctx = context
                if (bytes == null || bytes.isEmpty() || ctx == null) {
                    result.error("INVALID_ARGS", "bytes or context is unavailable", null)
                    return
                }
                try {
                    val dir = File(ctx.filesDir, "stickers").apply { mkdirs() }
                    val file = File(dir, "sticker_${System.currentTimeMillis()}.png")
                    file.outputStream().use { it.write(bytes) }
                    result.success(file.absolutePath)
                } catch (e: Exception) {
                    android.util.Log.e("DanceNativePlugin", "Failed to persist sticker: ${e.message}", e)
                    result.error("STICKER_PERSIST_FAILED", e.message ?: "Failed to persist sticker", null)
                }
            }
            "saveVideoToGallery" -> {
                val filePath = call.argument<String>("filePath")
                val ctx = context
                if (filePath == null || ctx == null) {
                    result.error("INVALID_ARGS", "filePath or context is null", null)
                    return
                }
                val worker = gallerySaveWorker
                if (worker == null) {
                    result.error("PLUGIN_DETACHED", "Gallery save worker is unavailable", null)
                    return
                }
                worker.submit(filePath) { outcome ->
                    try {
                        outcome.fold(
                            onSuccess = { result.success(it) },
                            onFailure = { error ->
                                val code = (error as? art.gaoge.dance.engine.media.GallerySaveFailure)?.code
                                    ?: "SAVE_FAILED"
                                result.error(code, "Gallery save did not complete", null)
                            }
                        )
                    } catch (_: Throwable) {
                        // An engine may disappear after commit; do not undo a successful save.
                        android.util.Log.w("DanceNativePlugin", "Gallery save reply is unavailable")
                    }
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
                val cacheOwner = trimCacheOwner
                if (executor == null || replyHandler == null || cacheOwner == null) {
                    result.error("PLUGIN_DETACHED", "Thumbnail worker is unavailable", null)
                    return
                }
                val (registry, owner) = cacheOwner
                val lease = registry.beginTrim(owner)
                try {
                    executor.execute {
                        var delivered = false
                        try {
                            registry.acquire(lease.id).close()
                            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                            val paths = createTrimThumbnails(ctx, videoUri, timestampsMs, lease.id)
                            delivered = replyHandler.post { result.success(paths) }
                        } catch (e: Exception) {
                            android.util.Log.e("DanceNativePlugin", "Failed to create trim thumbnails: ${e.message}", e)
                            replyHandler.post {
                                result.error("THUMBNAIL_FAILED", e.message ?: "Failed to create trim thumbnails", null)
                            }
                        } finally {
                            if (!delivered) registry.releaseProject(owner, lease.id)
                            lease.close()
                        }
                    }
                } catch (e: RejectedExecutionException) {
                    registry.releaseProject(owner, lease.id)
                    lease.close()
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
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        onDetachedFromActivity()
        DanceComparisonApi.setUp(binding.binaryMessenger,null)
        comparisonApi?.dispose();comparisonApi=null
        channel.setMethodCallHandler(null)
        DanceNativeApi.setUp(binding.binaryMessenger, null)
        gallerySaveWorker?.close()
        gallerySaveWorker = null
        trimCacheOwner?.let { (registry, owner) -> registry.releaseOwner(owner) }
        trimCacheOwner = null
        thumbnailExecutor?.shutdown()
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
