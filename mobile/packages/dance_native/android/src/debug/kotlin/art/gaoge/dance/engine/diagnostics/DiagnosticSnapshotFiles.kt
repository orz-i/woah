package art.gaoge.dance.engine.diagnostics

import android.annotation.SuppressLint
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

internal object DiagnosticSnapshotFiles {
    @SuppressLint("NewApi")
    fun isRegularFile(file: File): Boolean {
        if (!file.isFile || file.canonicalFile != file.absoluteFile) return false
        return try {
            // Canonical File paths do not resolve Windows symbolic links.
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (_: LinkageError) {
            // java.nio.file starts at Android 26. Android 24/25 canonical paths
            // already resolve links, so the containment check above applies.
            true
        }
    }
}
