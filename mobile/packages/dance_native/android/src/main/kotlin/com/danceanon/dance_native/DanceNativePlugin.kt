package com.danceanon.dance_native

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.danceanon.native.bridge.DanceNativeApi
import com.danceanon.native.bridge.DanceNativeApiImpl
import com.danceanon.native.bridge.DanceProcessingEvents
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

/** DanceNativePlugin */
class DanceNativePlugin :
    FlutterPlugin,
    MethodCallHandler {
    private lateinit var channel: MethodChannel
    private var context: Context? = null
    private var apiImpl: DanceNativeApiImpl? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        val appCtx = flutterPluginBinding.applicationContext
        context = appCtx
        com.danceanon.native.diagnostics.NativeDiagnostics.initialize(appCtx)

        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "dance_native")
        channel.setMethodCallHandler(this)

        val eventEmitter = DanceProcessingEvents(flutterPluginBinding.binaryMessenger)
        val impl = DanceNativeApiImpl(appCtx, eventEmitter)
        apiImpl = impl
        DanceNativeApi.setUp(flutterPluginBinding.binaryMessenger, impl)
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
        try {
            apiImpl?.close()
        } catch (_: Throwable) {}
        apiImpl = null
        context = null
    }
}
