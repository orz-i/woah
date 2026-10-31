package art.gaoge.dance.engine.media

import android.content.ContentResolver
import android.content.ContentValues
import androidx.core.net.toUri
import android.os.Build
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class MediaStoreVideoSaveBackend(private val resolver: ContentResolver) : GallerySaveBackend {
    override fun insert(): String {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Woah_${UUID.randomUUID()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Woah")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        return resolver.insert(collection, values)?.toString()
            ?: throw IOException("MediaStore insert failed")
    }

    override fun openOutput(uri: String): OutputStream =
        resolver.openOutputStream(uri.toUri()) ?: throw IOException("MediaStore output unavailable")

    override fun publish(uri: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            if (resolver.update(uri.toUri(), values, null, null) != 1) {
                throw IOException("MediaStore publish failed")
            }
        }
        // API 24–28 has no pending flag; do not request new storage permissions here.
        // The inserted media row is the destination. Never scan the app's source file.
    }

    override fun rollback(uri: String) {
        if (resolver.delete(uri.toUri(), null, null) != 1) {
            throw IOException("MediaStore rollback was not confirmed")
        }
    }
}
