package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.inference.PersonDetection
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Frozen Android behavior, consumed independently by Rust's migration tests. */
class TrackingStateReferenceCorpusTest {
    private fun mask(seed:Int): NativeMask {
        val data=ByteArray(256){i->((i*17+(i/16)*29+seed*37) and 255).toByte()}
        return NativeMask(16,16,ByteBuffer.allocateDirect(256).apply{put(data);rewind()},1920,1080,ModelCoordinateMapper(1920,1080,640,16))
    }
    private fun DataOutputStream.rect(r:FloatRect){writeFloat(r.left);writeFloat(r.top);writeFloat(r.right);writeFloat(r.bottom)}
    private fun DataOutputStream.mask(m:NativeMask?){if(m==null){writeInt(-1);return};writeInt(m.width*m.height);repeat(m.width*m.height){writeByte(m.buffer.get(it).toInt())}}
    @Test fun canonicalMasksAndPredictionCadenceRemainStable(){
        val output=ByteArrayOutputStream();val out=DataOutputStream(output)
        out.writeInt(0x464B5431);out.writeInt(32)
        repeat(32){i->
            val previous=FloatRect(420f,150f,980f,940f)
            val x=(i-16)*13.25f;val y=(i%7-3)*9.75f
            val predicted=FloatRect(420f+x,150f+y,980f+x+(i%5-2)*11.5f,940f+y+(i%3-1)*17.25f)
            val missed=i%14;val stride=1+i%5
            out.writeInt(i);out.rect(previous);out.rect(predicted);out.writeInt(missed);out.writeInt(stride)
            out.mask(CanonicalTrackManager.warpMask(mask(i),previous,predicted,missed))
            out.writeFloat(CanonicalTrackManager.computeWarpedMaskIoU(mask(i),previous,predicted,mask(i+3),stride))
            out.mask(CanonicalTrackManager.generateConservativeFallbackMask(mask(i),predicted,missed))
        }
        out.writeInt(4)
        repeat(4){mode->
            val t=CanonicalTrackManager(TrackingConfig(maxMissedFrames=3,postOcclusionGraceFrames=2),diagnosticsEnabled=false)
            if(mode==1)t.setIdentityProtectedTrackIds(setOf(5))
            if(mode==2){t.setProtectedTrackIds(setOf(5));t.setPrivacyOffscreenDormancyEnabled(true)}
            t.initializeWithAssignedIds(listOf(PersonDetection(FloatRect(420f,150f,980f,940f),0.9f,mask(0),940f),PersonDetection(FloatRect(1080f,120f,1690f,1010f),0.8f,mask(1),1010f)),listOf(5,2))
            out.writeInt(mode);out.writeInt(24)
            repeat(24){n->
                val miss=mode!=3 && n%3!=0;out.writeBoolean(miss);out.writeLong(n*40000L)
                val result=if(miss)t.predict(n*40000L)else t.predictWithoutObservation(n*40000L)
                out.writeInt(result.size)
                result.forEach{r->out.writeInt(r.id);out.writeInt(r.state.ordinal);out.writeInt(r.missedFrames);out.writeInt(r.framesSinceLastObservation);out.writeInt(r.age);out.writeBoolean(r.observedThisFrame);out.rect(r.bbox);out.writeFloat(r.confidence);out.writeFloat(r.footY!!);out.mask(r.mask)}
            }
        }
        out.close()
        val target=File(System.getenv("FRAME_KERNEL_TRACKING_REFERENCE") ?: "../../../../crates/frame-kernel/testdata/tracking-state-reference-v1.bin")
        if(System.getenv("FRAME_KERNEL_GENERATE_REFERENCE")=="1"){target.parentFile.mkdirs();target.writeBytes(output.toByteArray())}
        assertContentEquals(target.readBytes(),output.toByteArray())
    }
}
