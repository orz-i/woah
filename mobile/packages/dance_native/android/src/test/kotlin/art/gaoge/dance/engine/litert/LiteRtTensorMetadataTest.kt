package art.gaoge.dance.engine.litert

import java.io.File
import kotlin.test.*

class LiteRtTensorMetadataTest {
    private fun pinnedFaceModel():ByteArray {
        val root=generateSequence(File(System.getProperty("user.dir"))){it.parentFile}
            .map{File(it,"testdata/models/face/blaze_face_full_range.tflite")}.first{it.isFile}
        return root.readBytes()
    }
    @Test fun actualPinnedModelReportsItsRealShapes() {
        val meta=LiteRtTensorMetadata.read(pinnedFaceModel())
        assertEquals(listOf(listOf(1,192,192,3)),meta.inputs)
        assertEquals(listOf(listOf(1,2304,16),listOf(1,2304,1)),meta.outputs)
    }
    @Test fun truncatedAndInvalidOffsetsRejectMetadata() {
        val original=pinnedFaceModel()
        assertFailsWith<IllegalArgumentException>{LiteRtTensorMetadata.read(original.copyOf(16))}
        val broken=original.copyOf();broken[0]=255.toByte();broken[1]=255.toByte();broken[2]=255.toByte();broken[3]=127
        assertFailsWith<IllegalArgumentException>{LiteRtTensorMetadata.read(broken)}
    }
}
