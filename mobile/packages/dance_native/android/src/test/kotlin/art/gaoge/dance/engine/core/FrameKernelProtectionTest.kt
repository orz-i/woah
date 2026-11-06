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

    @Test fun sceneWideTargetsFollowCurrentPeopleButIndividualIdsRemainStrict() {
        val roots=setOf(0,1); val current=setOf(8,9)
        assertEquals(FrameKernel.SceneTargets(current,emptySet()),FrameKernel.sceneTargets(roots,roots,roots,current,null))
        assertEquals(FrameKernel.SceneTargets(emptySet(),current),FrameKernel.sceneTargets(roots,emptySet(),roots,current,null))
        assertEquals(FrameKernel.SceneTargets(setOf(0),emptySet()),FrameKernel.sceneTargets(roots,setOf(0),emptySet(),current,null))
        assertEquals(FrameKernel.SceneTargets(setOf(0),emptySet()),FrameKernel.sceneTargets(setOf(0),setOf(0),emptySet(),emptySet(),null))
        assertEquals(FrameKernel.SceneTargets(emptySet(),setOf(-2,-3)),FrameKernel.sceneTargets(setOf(0,1,2),emptySet(),setOf(0,2),setOf(-2,1,-3),1))
    }
}
