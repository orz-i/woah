package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.face.FaceHeadRoiPlan
import art.gaoge.dance.engine.face.FaceObservation
import art.gaoge.dance.engine.face.FaceRoiCandidateSelector
import art.gaoge.dance.engine.face.coreRecords
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import java.nio.ByteBuffer
import kotlin.test.*

/** Synthetic rectangles only; original QA observations remain local. */
class CurrentFaceOwnershipNativeTest {
    private val person=FloatRect(0f,0f,640f,640f)
    private val plan=FaceHeadRoiPlan(FloatRect(160f,0f,480f,320f),.5f,.5f,320)
    private val faces=listOf(FaceObservation(FloatRect(160f,120f,260f,220f),.9f))
    private fun mask(left:Int,right:Int):NativeMask {
        val pixels=ByteBuffer.allocateDirect(160*160)
        for(y in 4 until 80)for(x in left until right)pixels.put(y*160+x,255.toByte())
        return NativeMask(160,160,pixels,640,640,mapper=ModelCoordinateMapper(640,640))
    }
    @Test fun overlappingBodyBoxesCannotBorrowTheNeighborFace() {
        assertEquals(faces,FaceRoiCandidateSelector.ownedObservations(faces,plan,person))
        assertTrue(FaceRoiCandidateSelector.ownedObservations(faces,plan,person,mask(4,70)).isEmpty())
        assertEquals(faces,FaceRoiCandidateSelector.ownedObservations(faces,plan,
            FloatRect(240f,0f,600f,640f),mask(76,110)))
    }
    @Test fun malformedCurrentMaskCannotProduceAcceptedIndices() {
        assertFailsWith<IllegalArgumentException> {
            FrameKernel.currentFaceOwnedNative(faces.coreRecords(),floatArrayOf(160f,0f,480f,320f),320f,
                floatArrayOf(0f,0f,640f,640f),byteArrayOf(1,2,3),160,160,
                floatArrayOf(1f,0f,0f,640f,640f,640f,160f))
        }
    }
}
