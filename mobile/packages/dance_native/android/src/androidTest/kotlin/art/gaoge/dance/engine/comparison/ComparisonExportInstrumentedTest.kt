package art.gaoge.dance.engine.comparison

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test

@RunWith(AndroidJUnit4::class)
class ComparisonExportInstrumentedTest {
    @Test fun sourcePtsAffinePicturesAndSilentAudioExportAtOneTimes() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("comparisonExportFixtures")=="true")
        val context=ApplicationProvider.getApplicationContext<Context>()
        val root=File(context.getExternalFilesDir(null),"comparison-export-fixtures")
        ComparisonExportDeviceProbe.run(context,root,root)
    }
}
