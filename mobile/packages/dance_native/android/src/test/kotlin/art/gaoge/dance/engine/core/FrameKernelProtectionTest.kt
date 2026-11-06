package art.gaoge.dance.engine.core

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

class FrameKernelProtectionTest {
    @Test
    fun bodyOnlyExportRequiresFaceProtectionThroughTheRealJniBridge() {
        assertTrue(FrameKernel.needsFaceProtection(body = true, face = false))
        assertTrue(FrameKernel.needsFaceProtection(body = false, face = true))
        assertFalse(FrameKernel.needsFaceProtection(body = false, face = false))
        assertEquals(setOf(-3, 2, 5, 9), FrameKernel.faceProtectionIds(setOf(-3, 9, 2), setOf(2, 5)))
        assertEquals(emptySet<Int>(), FrameKernel.faceProtectionIds(emptySet(), emptySet()))
    }
}
