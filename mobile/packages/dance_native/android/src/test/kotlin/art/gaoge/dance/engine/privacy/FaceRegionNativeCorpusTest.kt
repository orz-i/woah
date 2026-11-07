package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.face.*
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.*
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.*

/** Verifies the unused adapter against frozen native results and existing defaults. */
class FaceRegionNativeCorpusTest {
    private fun DataInputStream.rect()=FloatRect(readFloat(),readFloat(),readFloat(),readFloat())
    private fun DataInputStream.region():List<Int>? {
        if(!readBoolean())return null
        val out=List(4){readFloat().toRawBits()};readInt();return out
    }
    private fun bits(r:FacePrivacyEllipse?)=r?.let{listOf(it.centerX.toRawBits(),it.centerY.toRawBits(),it.radiusX.toRawBits(),it.radiusY.toRawBits())}
    @Test fun everyNativeRegionAndHeadMatchesAllFrozenBits(){
        val bytes=File("../../../../crates/frame-kernel/testdata/face-region-native-v1.bin").readBytes()
        assertEquals("e0e75f439552d6e7599c73f1020dd1d1d7f5323db0db26a4628bf10035b96298",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
        DataInputStream(ByteArrayInputStream(bytes)).use{r->
            assertEquals(0x464B4731,r.readInt());assertEquals(512,r.readInt())
            repeat(512){i->
                val fw=r.readInt();val fh=r.readInt();val person=r.rect();val plan=FaceHeadRoiPlan(r.rect(),.5f,.5f,r.readInt())
                val face=FaceObservation(r.rect(),r.readFloat(),List(r.readInt()){FacePoint(r.readFloat(),r.readFloat())})
                val selected=FaceRoiCandidateSelection(0,face,.01f)
                val detected=r.region();assertEquals(detected,bits(FacePrivacyRegionResolver.resolve(person,plan,selected)),"old detected$i")
                assertEquals(detected,bits(FrameKernelFaceGeometry.resolve(person,plan,selected)),"shared detected$i")
                val fallback=r.region();assertEquals(fallback,bits(FacePrivacyRegionResolver.resolve(person,null,null)),"old fallback$i")
                assertEquals(fallback,bits(FrameKernelFaceGeometry.resolve(person,null,null)),"shared fallback$i")
                val mapper=ModelCoordinateMapper(fw,fh,640,160)
                assertEquals(r.readFloat().toRawBits(),mapper.scale.toRawBits());assertEquals(r.readFloat().toRawBits(),mapper.padLeft.toRawBits());assertEquals(r.readFloat().toRawBits(),mapper.padTop.toRawBits())
                val sx=r.readFloat();val sy=r.readFloat();val rx=r.readFloat();val ry=r.readFloat()
                val pixels=ByteArray(25600);var offset=0
                repeat(r.readInt()){val n=r.readInt();val value=r.readByte();pixels.fill(value,offset,offset+n);offset+=n};assertEquals(pixels.size,offset)
                val mask=NativeMask(160,160,ByteBuffer.allocateDirect(pixels.size).apply{put(pixels);rewind()},fw,fh,mapper)
                val expected=if(r.readBoolean())listOf(r.readFloat().toRawBits(),r.readFloat().toRawBits()) else null
                val old=BodyMaskFaceHeadEstimator.estimate(mask,person,sx,sy,rx,ry)
                val shared=FrameKernelFaceGeometry.estimate(mask,person,sx,sy,rx,ry)
                assertEquals(expected,old?.let{listOf(it.x.toRawBits(),it.y.toRawBits())},"old head$i")
                assertEquals(expected,shared?.let{listOf(it.x.toRawBits(),it.y.toRawBits())},"shared head$i")
                val trusted=r.region();val seed=FacePrivacyTrustedGeometry(sx,sy,rx,ry,person)
                assertEquals(trusted,bits(FaceTrustedMaskFallback.resolve(mask,person,seed,1.10f)),"old trusted$i")
                assertEquals(trusted,bits(FrameKernelFaceGeometry.trusted(mask,person,seed,1.10f)),"shared trusted$i")
            }
            assertEquals(-1,r.read())
        }
    }
}
