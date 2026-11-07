package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.core.FrameKernel
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.*

class CurrentHeadNativeTest {
    @Test fun actualClippedFaceUsesTheCompleteSharedFallback() {
        val bytes=File("../../../../crates/frame-kernel/testdata/face-edge-ios-185-v1.bin").readBytes()
        assertEquals("15158a59608780bc1a8eb0660ff60da19a3d3b1cc7bb7995b4d309cf3e45e2b7",
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
        DataInputStream(ByteArrayInputStream(bytes)).use{r->
            assertEquals(0x464b4531,r.readInt())
            val width=r.readInt();val height=r.readInt();val input=r.readInt();val proto=r.readInt()
            val transform=floatArrayOf(r.readFloat(),r.readFloat(),r.readFloat(),width.toFloat(),height.toFloat(),input.toFloat(),proto.toFloat())
            assertEquals(7_400_000L,r.readLong());assertEquals(-2_185_002L,r.readLong())
            val person=FloatArray(4){r.readFloat()};val count=r.readInt();val pixels=ByteArray(count);var offset=0
            repeat(r.readInt()){val n=r.readInt();val value=r.readByte();pixels.fill(value,offset,offset+n);offset+=n}
            assertEquals(count,offset);val face=FloatArray(4){r.readFloat()};assertEquals(-1,r.read())
            val bbox=assertNotNull(FrameKernel.headFallbackNative(person))
            val completed=assertNotNull(FrameKernel.currentHeadRegionNative(pixels,proto,proto,transform,person,null))
            assertEquals(5,completed.size);assertEquals(1f,completed[4])
            fun covered(region:FloatArray):Float {
                var n=0
                for(y in 0..30)for(x in 0..30){
                    val sx=face[0]+(face[2]-face[0])*(.15f+.70f*x/30)
                    val sy=face[1]+(face[3]-face[1])*(.15f+.70f*y/30)
                    val dx=(sx-region[0])/region[2];val dy=(sy-region[1])/region[3]
                    if(dx*dx+dy*dy<=1f)n++
                }
                return n/961f
            }
            assertEquals(0f,covered(bbox));assertTrue(covered(completed)>=.90f)
            for(absent in listOf<ByteArray?>(null,byteArrayOf(),ByteArray(count))){
                val value=assertNotNull(FrameKernel.currentHeadRegionNative(absent,proto,proto,transform,person,null))
                assertEquals(0f,value[4]);repeat(4){assertEquals(bbox[it].toRawBits(),value[it].toRawBits())}
            }
            assertNull(FrameKernel.currentHeadRegionNative(byteArrayOf(1,2,3),proto,proto,transform,person,null))
        }
    }
}
