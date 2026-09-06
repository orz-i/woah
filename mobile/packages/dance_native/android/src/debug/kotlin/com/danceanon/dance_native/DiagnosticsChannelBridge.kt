package com.danceanon.dance_native

import android.content.Context
import android.util.Log
import com.danceanon.native.diagnostics.DiagnosticBundleExporter
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
            "createDiagnosticBundle" -> {
                val ctx = context
                if (ctx == null) {
                    result.error("NO_CONTEXT", "Plugin context is null", null)
                    return true
                }
                try {
                    result.success(DiagnosticBundleExporter.createBundle(ctx))
                } catch (e: Exception) {
                    Log.e("DanceNativePlugin", "Failed to create diagnostic bundle: ${e.message}", e)
                    result.error("BUNDLE_FAILED", e.message ?: "Failed to create diagnostic bundle", null)
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
                    Log.e("DanceNativePlugin", "Failed to share diagnostic bundle: ${e.message}", e)
                    result.error("SHARE_FAILED", e.message ?: "Failed to share diagnostic bundle", null)
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
