package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.*
import art.gaoge.dance.engine.tracking.PrivacySelectionClass
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.*

/** Reads immutable original-Kotlin outputs; never regenerates the oracle from Rust. */
class PrivacyClassNativeCorpusTest {
    private fun DataInputStream.rect()=FloatRect(readFloat(),readFloat(),readFloat(),readFloat())
    private fun DataInputStream.mask():NativeMask? {
        val w=readInt();if(w<0)return null
        val h=readInt();val b=ByteArray(Math.multiplyExact(w,h));readFully(b)
        return NativeMask(w,h,ByteBuffer.allocateDirect(b.size).apply{put(b);rewind()},640,640,ModelCoordinateMapper(640,640,640,w))
    }
    @Test fun nativeTransportMatchesAllFrozenClassDecisionsAndEvaluationCounts(){
        val bytes=File("../../../../crates/frame-kernel/testdata/privacy-class-native-v1.bin").readBytes()
        assertEquals("d17fad6313559b4c6e3a6363ccc228901188399d1fac82bf7874ecc44eba9d92",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
        DataInputStream(ByteArrayInputStream(bytes)).use {input->
            assertEquals(0x464B4331,input.readInt());val scenarios=input.readInt();assertEquals(16,scenarios)
            repeat(scenarios){
                val cache=input.readBoolean();input.readInt();val frames=input.readInt()
                PrivacyClassTemporalTracker(reuseFrameSimilarityCache=cache,reuseFrameWarpedMaskSupportCache=cache,
                    countSimilarityEvaluations=true).use {tracker->
                    repeat(frames){
                        val pts=input.readLong()
                        val detections=List(input.readInt()){
                            val box=input.rect();val confidence=input.readFloat();PersonDetection(box,confidence,input.mask(),box.bottom)
                        }
                        val hard=linkedMapOf<Int,PrivacySelectionClass>()
                        repeat(input.readInt()){hard[input.readInt()]=PrivacySelectionClass.entries[input.readInt()]}
                        val actual=tracker.update(detections,hard,pts)
                        assertEquals(input.readInt(),actual.size)
                        actual.forEach{e->assertEquals(input.readInt(),e.detectionIndex);assertEquals(input.readInt(),e.selectionClass.ordinal);assertEquals(input.readBoolean(),e.conservativeUnknown)}
                        input.readBoolean() // Internal root/prototypes are independently checked bit-for-bit in Rust.
                        assertEquals(input.readInt(),tracker.lastSimilarityEvaluationCount)
                        repeat(input.readInt()){
                            input.readInt();input.rect();input.mask();input.readFloat();input.readFloat();input.readInt();input.readFloat()
                        }
                    }
                }
            }
            assertEquals(-1,input.read())
        }
    }
}
