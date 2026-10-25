package art.gaoge.dance.engine.comparison

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal data class ComparisonCopiedSource(val bytes: Long, val sha256: String)

/** Local Debug evidence only. Publish a complete working copy, never a partial file. */
internal object ComparisonSelectedSourceCopy {
    fun copy(input: InputStream, destination: File,
             current: () -> Boolean): ComparisonCopiedSource = input.use { source ->
        val part = File(destination.parentFile, destination.name + ".part")
        check(!destination.exists() && !part.exists()) { "Evidence destination already exists" }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        try {
            part.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    check(current() && !Thread.currentThread().isInterrupted) { "Source copy cancelled" }
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    bytes += count
                }
            }
            check(current() && bytes > 0) { "Empty or cancelled source copy" }
            check(part.renameTo(destination)) { "Could not publish source evidence" }
            ComparisonCopiedSource(bytes, digest.digest().joinToString("") { "%02x".format(it) })
        } finally {
            if (part.exists()) part.delete()
        }
    }
}
