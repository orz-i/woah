package art.gaoge.dance.engine.diagnostics

import android.os.Process
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures process-local native logs from logcat for LiteRT GPU compiler diagnostics.
 * Enforces:
 * 1. Strictly process-local (filters own PID only; no READ_LOGS permission required).
 * 2. Capped at 2 MiB max log size.
 * 3. Best-effort execution: never throws or crashes application.
 */
object ProcessLogCapture {
    private const val TAG = "ProcessLogCapture"
    private const val MAX_LOG_BYTES = 2L * 1024L * 1024L // 2 MiB

    private var captureProcess: java.lang.Process? = null
    private var readerThread: Thread? = null
    private val isCapturing = AtomicBoolean(false)
    private var isAvailable = false
    private var captureError: String? = null

    fun isCaptureAvailable(): Boolean = isAvailable
    fun getCaptureError(): String? = captureError

    fun start(outputFile: File) {
        if (isCapturing.getAndSet(true)) return

        val myPid = Process.myPid()
        try {
            outputFile.parentFile?.mkdirs()
            val pb = try {
                ProcessBuilder("logcat", "--pid=$myPid", "-v", "threadtime")
            } catch (_: Throwable) {
                ProcessBuilder("logcat", "-v", "threadtime")
            }
            pb.redirectErrorStream(true)
            val proc = pb.start()
            captureProcess = proc
            isAvailable = true
            captureError = null

            readerThread = Thread({
                var totalBytes = 0L
                try {
                    proc.inputStream.use { input ->
                        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                            FileOutputStream(outputFile, false).use { fos ->
                                while (isCapturing.get()) {
                                    val line = reader.readLine() ?: break
                                    // Raw process lines can contain SDK paths and nested exception text.
                                    val lineWithSep = "stage=diagnostics; code=NATIVE_LOG_REDACTED\n"
                                    val b = lineWithSep.toByteArray(Charsets.UTF_8)
                                    if (totalBytes + b.size > MAX_LOG_BYTES) break
                                    fos.write(b)
                                    totalBytes += b.size
                                }
                                fos.flush()
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "PROCESS_LOG_READER_STOPPED")
                }
            }, "NativeLogCaptureReader").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            isAvailable = false
            captureError = NativeDiagnosticPrivacy.reason(t, "diagnostics")
            isCapturing.set(false)
            Log.w(TAG, "PROCESS_LOG_START_FAILED")
        }
    }

    fun stop() {
        if (!isCapturing.getAndSet(false)) return
        try {
            captureProcess?.destroy()
        } catch (_: Throwable) {}
        try {
            readerThread?.join(500L)
        } catch (_: Throwable) {}
        captureProcess = null
        readerThread = null
    }
}
