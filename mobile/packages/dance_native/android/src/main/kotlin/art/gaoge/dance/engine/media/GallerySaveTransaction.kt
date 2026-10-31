package art.gaoge.dance.engine.media

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

class GallerySaveFailure(val code: String, cause: Throwable? = null) :
    RuntimeException(code, cause)

/** Detach cancels only before the publish commit point, without waiting for provider I/O. */
class GallerySaveControl {
    private enum class State { COPYING, CANCELLED, COMMITTING }
    private val state = AtomicReference(State.COPYING)

    fun cancel(): Boolean = state.compareAndSet(State.COPYING, State.CANCELLED)
    fun checkCancelled() {
        if (state.get() == State.CANCELLED) throw CancellationException("Gallery save cancelled")
    }
    fun beginCommit() {
        if (!state.compareAndSet(State.COPYING, State.COMMITTING)) {
            throw CancellationException("Gallery save cancelled before publish")
        }
    }
}

/** Only the newly inserted URI belongs to this transaction. Never receives a delete source path. */
interface GallerySaveBackend {
    fun insert(): String
    fun openOutput(uri: String): OutputStream
    fun publish(uri: String)
    fun rollback(uri: String)
}

class GallerySaveTransaction(private val backend: GallerySaveBackend) {
    fun save(openSource: () -> InputStream, control: GallerySaveControl): String {
        var ownedUri: String? = null
        var committed = false
        var copiedBytes = 0L
        try {
            control.checkCancelled()
            openSource().use { input ->
                control.checkCancelled()
                val uri = backend.insert()
                ownedUri = uri
                control.checkCancelled()
                backend.openOutput(uri).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        control.checkCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        control.checkCancelled()
                        if (count > 0) {
                            output.write(buffer, 0, count)
                            copiedBytes += count
                        }
                    }
                    output.flush()
                }
            }
            // Input AND output close succeeded before publishing. After this CAS detach
            // cannot turn a successful user-requested save into deletion of a public asset.
            if (copiedBytes == 0L) throw IOException("Empty gallery source")
            control.beginCommit()
            val uri = checkNotNull(ownedUri)
            backend.publish(uri)
            committed = true
            return uri
        } catch (error: Throwable) {
            val uri = ownedUri
            if (uri != null && !committed) {
                try {
                    backend.rollback(uri)
                } catch (rollbackError: Throwable) {
                    throw GallerySaveFailure("SAVE_ROLLBACK_FAILED", error).also {
                        it.addSuppressed(rollbackError)
                    }
                }
            }
            if (error is CancellationException) throw GallerySaveFailure("SAVE_CANCELLED", error)
            if (error is GallerySaveFailure) throw error
            throw GallerySaveFailure("SAVE_FAILED", error)
        }
    }
}
