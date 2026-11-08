package art.gaoge.dance.engine.diagnostics

import android.content.Context
import art.gaoge.dance.engine.BuildConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Local QA transport, never an algorithm cache. Only explicitly approved detector
 * observations may be replayed; tracking, privacy policy and rendering run afresh.
 */
internal class QaObservationReplay internal constructor(
    private val directory: File,
    private val contract: String
) {
    fun key(kind: String, dimensions: String, input: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("qa-observation-v1|$contract|$kind|$dimensions|".toByteArray())
        val bytes = ByteBuffer.allocate(input.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        input.forEach { bytes.putInt(it.toRawBits()) }
        digest.update(bytes.array())
        return digest.digest().hex()
    }

    fun read(key: String): ByteArray? = synchronized(lock) {
        try {
            val approval = File(directory, "$key.approved")
            if (File(directory, "$key.conflict").exists()) return@synchronized null
            if (!approval.isFile) return@synchronized null
            val expected = approval.readText().trim()
            check(expected.matches(hashPattern))
            val payload = File(directory, "$key.bin")
            check(payload.length() in 1..MAX_PAYLOAD)
            val bytes = payload.readBytes()
            check(sha(bytes) == expected)
            bytes
        } catch (_: Exception) {
            event(key, "rejected", "")
            null
        }
    }

    fun hit(key: String, bytes: ByteArray) = event(key, "replay", sha(bytes))
    fun reject(key: String) = event(key, "decode_rejected", "")

    fun record(key: String, bytes: ByteArray) = synchronized(lock) {
        try {
            require(bytes.size.toLong() in 1..MAX_PAYLOAD)
            directory.mkdirs()
            val path = File(directory, "$key.bin")
            val digest = sha(bytes)
            if (path.exists() && sha(path.readBytes()) != digest) {
                // A nondeterministic result must not silently replace approved data.
                File(directory, "$key.conflict").writeText(digest)
                File(directory, "$key.approved").delete()
                event(key, "conflict", digest)
                return@synchronized
            }
            if (!path.exists()) {
                val temporary = File.createTempFile("observation-", ".tmp", directory)
                temporary.writeBytes(bytes)
                check(temporary.renameTo(path))
            }
            event(key, "inference", digest)
        } catch (_: Exception) {
            event(key, "record_failed", "")
        }
    }

    private fun event(key: String, operation: String, payload: String) = synchronized(lock) {
        runCatching {
            directory.mkdirs()
            File(directory, "events.tsv").appendText(
                "${System.currentTimeMillis()}\t${android.os.Process.myPid()}\t$key\t$operation\t$payload\n"
            )
        }
        Unit
    }

    companion object {
        private val lock = Any()
        private val hashPattern = Regex("[0-9a-f]{64}")
        private const val MAX_PAYLOAD = 32L * 1024 * 1024
        private var initialized = false
        private var instance: QaObservationReplay? = null

        fun open(context: Context): QaObservationReplay? = synchronized(lock) {
            if (initialized) return@synchronized instance
            initialized = true
            if (!BuildConfig.DEBUG || !context.packageName.endsWith(".privacyqa")) return@synchronized null
            instance = runCatching {
                val control = File(context.getExternalFilesDir(null), "qa_observation_replay.txt")
                if (!control.isFile) return@runCatching null
                val lines = control.readLines()
                require(lines.size == 3 && lines[0] == "qa-observation-v1")
                require(lines[1].matches(hashPattern) && lines[2].matches(hashPattern))
                val actual = File(context.applicationInfo.sourceDir).inputStream().use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(1024 * 1024)
                    while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    digest.digest().hex()
                }
                require(actual == lines[1]) { "QA_OBSERVATION_ARTIFACT_MISMATCH" }
                QaObservationReplay(File(context.getExternalFilesDir(null), "qa-observations/${lines[2]}"), lines[2])
            }.getOrNull()
            instance
        }

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    }
}

