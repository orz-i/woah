package art.gaoge.dance.engine.diagnostics

import java.io.File

/** Release no-op for process-local logcat capture. */
object ProcessLogCapture {
    fun isCaptureAvailable(): Boolean = false
    fun getCaptureError(): String? = null
    fun start(outputFile: File) = Unit
    fun stop() = Unit
}
