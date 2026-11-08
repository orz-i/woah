package art.gaoge.dance.engine.diagnostics

import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.inference.PersonDetection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Lossless bounded local DTO transport. Fresh buffers are returned on every read. */
internal object QaObservationCodec {
    fun encode(persons: List<PersonDetection>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1); out.writeInt(persons.size)
            persons.forEach { person ->
                out.rect(person.bbox); out.writeFloat(person.confidence); out.writeFloat(person.footY)
                val mask = person.mask
                out.writeBoolean(mask != null)
                if (mask != null) {
                    out.writeInt(mask.width); out.writeInt(mask.height)
                    out.writeInt(mask.originalWidth); out.writeInt(mask.originalHeight)
                    val mapper = mask.mapper
                    out.writeBoolean(mapper != null)
                    if (mapper != null) {
                        out.writeInt(mapper.srcWidth); out.writeInt(mapper.srcHeight)
                        out.writeInt(mapper.modelInputSize); out.writeInt(mapper.protoSize)
                    }
                    out.optionalRect(mask.roiInProto); out.optionalRect(mask.samplingRect)
                    val buffer = mask.buffer.duplicate(); buffer.position(0)
                    require(buffer.remaining() >= mask.width * mask.height)
                    val pixels = ByteArray(mask.width * mask.height); buffer.get(pixels)
                    out.write(pixels)
                }
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): List<PersonDetection> = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        require(input.readInt() == 1)
        val count = input.readInt(); require(count in 0..256)
        val persons = List(count) {
            val bbox = input.rect()
            val confidence = input.finite(); require(confidence in 0f..1f)
            val footY = input.finite()
            val mask = if (!input.readBoolean()) null else {
                val width = input.dimension(); val height = input.dimension()
                require(width.toLong() * height <= 1_048_576)
                val originalWidth = input.dimension(); val originalHeight = input.dimension()
                val mapper = if (!input.readBoolean()) null else ModelCoordinateMapper(
                    input.dimension(), input.dimension(), input.dimension(), input.dimension())
                val roi = input.optionalRect(); val sampling = input.optionalRect()
                val pixels = ByteArray(width * height); input.readFully(pixels)
                NativeMask(width, height, ByteBuffer.allocateDirect(pixels.size).apply { put(pixels); flip() },
                    originalWidth, originalHeight, mapper, roi, sampling)
            }
            PersonDetection(bbox, confidence, mask, footY)
        }
        require(input.available() == 0)
        persons
    }

    fun floats(values: FloatArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out -> out.writeInt(values.size); values.forEach { out.writeFloat(it) } }
        return bytes.toByteArray()
    }

    fun floats(bytes: ByteArray): FloatArray = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val count = input.readInt(); require(count in 0..(2304 * 17) && count % 17 == 0)
        val result = FloatArray(count) { input.finite() }
        require(input.available() == 0)
        for (i in result.indices step 17) {
            require(result[i+2] > result[i] && result[i+3] > result[i+1] && result[i+4] in 0f..1f)
        }
        result
    }

    private fun DataInputStream.dimension(): Int = readInt().also { require(it in 1..65535) }
    private fun DataInputStream.finite(): Float = readFloat().also { require(it.isFinite()) }
    private fun DataOutputStream.rect(rect: FloatRect) {
        writeFloat(rect.left); writeFloat(rect.top); writeFloat(rect.right); writeFloat(rect.bottom)
    }
    private fun DataInputStream.rect(): FloatRect = FloatRect(finite(),finite(),finite(),finite()).also {
        require(it.right >= it.left && it.bottom >= it.top)
    }
    private fun DataOutputStream.optionalRect(rect: FloatRect?) { writeBoolean(rect != null); if (rect != null) rect(rect) }
    private fun DataInputStream.optionalRect(): FloatRect? = if (readBoolean()) rect() else null
}

