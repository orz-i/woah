package art.gaoge.dance.engine

import android.content.Context
import android.util.Log
import art.gaoge.dance.engine.diagnostics.DiagnosticBundleExporter
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Debug-only MethodChannel surface for diagnostic bundle operations. */
internal object DiagnosticsChannelBridge {
    fun handle(
        call: MethodCall,
        result: MethodChannel.Result,
        context: Context?
    ): Boolean {
        when (call.method) {
            "copyPrivacyQaGalleryOriginal" -> {
                val ctx = context
                if (ctx == null || !ctx.packageName.endsWith(".privacyqa")) {
                    result.error("QA_ONLY", "Requires the isolated QA package", null)
                    return true
                }
                val raw = call.argument<String>("uri")
                val uri = raw?.let(android.net.Uri::parse)
                if (uri?.scheme != "content" || uri.authority != "media") {
                    result.error("INVALID_ARGS", "Expected an owned MediaStore video", null)
                    return true
                }
                Thread {
                    try {
                        ctx.contentResolver.query(uri, arrayOf("owner_package_name"), null, null, null).use { cursor ->
                            check(cursor != null && cursor.moveToFirst() && cursor.getString(0) == ctx.packageName) {
                                "Gallery verification only reads this QA package's saved media"
                            }
                        }
                        val directory = java.io.File(ctx.getExternalFilesDir(null), "qa_gallery").apply { mkdirs() }
                        val target = java.io.File(directory, "${java.util.UUID.randomUUID()}.mp4")
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        java.security.DigestInputStream(requireNotNull(ctx.contentResolver.openInputStream(uri)), digest).use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                        result.success(mapOf("path" to target.absolutePath, "bytes" to target.length(),
                            "sha256" to digest.digest().joinToString("") { "%02x".format(it) }))
                    } catch (error: Exception) {
                        result.error("QA_GALLERY_READ_FAILED", error.javaClass.simpleName, null)
                    }
                }.apply { isDaemon = true; start() }
                return true
            }
            "createDiagnosticBundle" -> {
                val ctx = context
                if (ctx == null) {
                    result.error("NO_CONTEXT", "Plugin context is null", null)
                    return true
                }
                try {
                    result.success(DiagnosticBundleExporter.createBundle(ctx))
                } catch (e: Exception) {
                    Log.e("DanceNativePlugin", "BUNDLE_FAILED")
                    result.error("BUNDLE_FAILED", "Diagnostic bundle creation failed", null)
                }
                return true
            }

            "shareDiagnosticBundle" -> {
                val ctx = context
                if (ctx == null) {
                    result.error("NO_CONTEXT", "Plugin context is null", null)
                    return true
                }
                val filePath = call.argument<String>("filePath")
                val publicUri = call.argument<String>("publicUri")
                try {
                    result.success(DiagnosticBundleExporter.shareBundle(ctx, filePath, publicUri))
                } catch (e: Exception) {
                    Log.e("DanceNativePlugin", "SHARE_FAILED")
                    result.error("SHARE_FAILED", "Diagnostic bundle sharing failed", null)
                }
                return true
            }

            "clearDiagnosticLogs" -> {
                context?.let(DiagnosticBundleExporter::clearLogs)
                result.success(null)
                return true
            }

            else -> return false
        }
    }
}
