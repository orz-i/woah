package art.gaoge.dance.engine.clarity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Promotion probe for a locally provisioned crop-clarity model.
 *
 * Normal CI has no optional model and skips this test. When a verified model is
 * staged into the instrumentation APK, this gate proves that LiteRT can compile
 * the graph with the GPU-targeted runtime and execute the exact 192 -> 384 contract. Full GPU residency is a separate profiler promotion gate.
 */
@RunWith(AndroidJUnit4::class)
class CropClarityGpuInstrumentedTest {
    @Test
    fun optionalModelCompilesAndRunsOnStrictGpu() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "crop-clarity prototype not provisioned",
            LiteRtCropClarityRestorer.assetPresent(context)
        )

        val restorer = LiteRtCropClarityRestorer(context)
        try {
            restorer.initialize()
            assertTrue(restorer.initialized)

            val input = FloatArray(CropClarityModelContract.INPUT_FLOAT_COUNT) { index ->
                (index % 251) / 250.0f
            }
            val output = restorer.restoreTileRgb(input)
            assertEquals(CropClarityModelContract.OUTPUT_FLOAT_COUNT, output.size)
            assertTrue(output.all { it.isFinite() }, "neural crop-clarity output must remain finite")
        } finally {
            restorer.close()
        }
    }
}
