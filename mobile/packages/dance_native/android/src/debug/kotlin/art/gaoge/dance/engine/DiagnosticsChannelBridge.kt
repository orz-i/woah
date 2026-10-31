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
