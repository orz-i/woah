package art.gaoge.dance.engine.comparison

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import kotlin.test.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions

class ComparisonUriPermissionsTest {
    @Test
    fun `persist only access returned by the picker`() {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for (granted in listOf(read, write, read or write)) {
            val resolver = mock(ContentResolver::class.java)
            val uri = mock(Uri::class.java)
            persistGrantedUriPermissions(resolver, uri,
                granted or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            verify(resolver).takePersistableUriPermission(uri, granted)
            verifyNoMoreInteractions(resolver)
        }
    }

    @Test
    fun `no read or write grant does not request persistent access`() {
        val resolver = mock(ContentResolver::class.java)
        persistGrantedUriPermissions(resolver, mock(Uri::class.java),
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        verifyNoInteractions(resolver)
    }
}
