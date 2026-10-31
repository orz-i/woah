package art.gaoge.dance.engine.comparison

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

internal fun persistGrantedUriPermissions(resolver: ContentResolver, uri: Uri, flags: Int) {
    // Persist only read/write access actually returned by the picker. Other
    // Intent flags must not reach the API or broaden the original grant.
    val read = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
    val write = flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
    when {
        read && write -> resolver.takePersistableUriPermission(uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        read -> resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        write -> resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}
