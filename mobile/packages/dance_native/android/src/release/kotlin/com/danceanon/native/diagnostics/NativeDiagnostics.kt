package com.danceanon.native.diagnostics

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Release diagnostics backend.
 *
 * This object intentionally preserves the debug API surface so production code does not branch
 * on diagnostics. All persistence, event queues, artifact capture and snapshot work is compiled
 * out by the release source set.
 */
object NativeDiagnostics {
    fun initialize(context: Context) = Unit

    fun recordPipelineLifecycle(
        stage: String,
        jobId: String? = null,
        fields: Map<String, Any?> = emptyMap()
    ) = Unit

    fun getCurrentSessionId(): String = "release"
    fun getDiagnosticsDir(): File? = null
    fun getCurrentLogFile(): File? = null
    fun writeArtifactAsync(fileName: String, bytes: ByteArray) = Unit

    fun event(
        level: String,
        component: String,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
        throwable: Throwable? = null
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    inline fun eventLazy(
        level: String,
        component: String,
        event: String,
        throwable: Throwable? = null,
        fields: () -> Map<String, Any?>
    ) = Unit

    fun breadcrumb(
        component: String,
        stage: String,
        fields: Map<String, Any?> = emptyMap()
    ) = Unit

    fun createConsistentSnapshot(stagingDir: File, timeoutMs: Long = 5000L): List<File>? = null
    fun flushCriticalNow(timeoutMs: Long = 1000L) = Unit
    fun recordCapabilities(caps: Map<String, Any?>) = Unit
    fun recordPipelineSummary(jobId: String, summary: Map<String, Any?>) = Unit
    fun clearOldDiagnostics() = Unit

    // These helpers are also used for user-facing error normalization and therefore remain
    // functional even though diagnostics storage itself is absent.
    fun rootCause(t: Throwable): Throwable {
        var current = t
        val seen = mutableSetOf<Throwable>()
        while (current.cause != null && current.cause !== current && seen.add(current)) {
            current = current.cause ?: break
        }
        return current
    }

    fun buildCauseChain(t: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = t
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            parts += "${current.javaClass.simpleName}: ${current.message ?: ""}"
            current = current.cause
        }
        return parts.joinToString(" <- ")
    }

    fun generateDeviceJson(context: Context): JSONObject = JSONObject()
}
