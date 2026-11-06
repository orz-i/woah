package art.gaoge.dance.engine.media

import android.media.MediaFormat
import art.gaoge.dance.engine.core.FrameKernel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FrameKernelPixelsParityTest {
    @Test
    fun yuvSamplingMatchesFrozenNativeColorAndFixedPointReference() {
        val bytes = listOf(ByteArray(51) { (it * 17 + 3).toByte() },
            ByteArray(31) { (it * 11 + 17).toByte() }, ByteArray(31) { (it * 23 + 71).toByte() })
        val planes = bytes.map { ByteBuffer.allocateDirect(it.size).put(it).apply { rewind() } }
        val snapshots = bytes.mapIndexed { i, b -> ReferenceCanonicalYuvToRgba.PlaneSnapshot(b, b.size, if (i == 0) 11 else 12, if (i == 0) 1 else 2) }
        val digest = MessageDigest.getInstance("SHA-256")
        for (crop in listOf(false, true)) for (rotation in listOf(0, 90, 180, 270)) {
            val left = if (crop) 1 else 0; val top = if (crop) 1 else 0
            val right = if (crop) 6 else 7; val bottom = 5
            val swap = rotation == 90 || rotation == 270
            val dw = if (swap) bottom - top else right - left
            val dh = if (swap) right - left else bottom - top
            val scale = min(16f / dw, 16f / dh)
            val w = dw * scale; val h = dh * scale; val pl = (16 - w) / 2; val pt = (16 - h) / 2
            fun axis(v: Double, size: Int): ReferenceCanonicalYuvToRgba.AxisSamples {
                val c = v.coerceIn(0.0, size - 1.0); val a = floor(c).toInt()
                return ReferenceCanonicalYuvToRgba.AxisSamples(intArrayOf(a), intArrayOf(min(a + 1, size - 1)), intArrayOf(((c - a) * 256).roundToInt()))
            }
            for (matrix in 0..2) for (full in 0..1) for (flip in 0..1) {
                val output = ByteBuffer.allocateDirect(1024).order(ByteOrder.LITTLE_ENDIAN)
                val meta = intArrayOf(7, 5, left, top, right, bottom, dw, dh, rotation, 16, 16, flip, matrix, full, 114, 11, 1, 12, 2, 12, 2)
                FrameKernel.yuv420(planes[0], planes[1], planes[2], meta, output)
                for (y in 0..15) for (x in 0..15) {
                    val index = ((if (flip == 1) 15 - y else y) * 16 + x) * 4
                    val expected = if (x + 0.5 < pl || x + 0.5 >= (pl + w).toDouble() || y + 0.5 < pt || y + 0.5 >= (pt + h).toDouble()) {
                        0xff727272.toInt()
                    } else {
                        val dx = (x + 0.5 - pl) / w * (if (swap) bottom - top else right - left) - 0.5
                        val dy = (y + 0.5 - pt) / h * (if (swap) right - left else bottom - top) - 0.5
                        val (rx, ry) = ReferenceCanonicalYuvToRgba.inverseRotate(dx, dy, right - left, bottom - top, rotation)
                        val sx = (rx + left).coerceIn(left.toDouble(), right - 1.0)
                        val sy = (ry + top).coerceIn(top.toDouble(), bottom - 1.0)
                        val yy = ReferenceCanonicalYuvToRgba.sampleSnapshot(snapshots[0], axis(sx, 7), 0, axis(sy, 5), 0)
                        val u = ReferenceCanonicalYuvToRgba.sampleSnapshot(snapshots[1], axis(sx * 0.5, 4), 0, axis(sy * 0.5, 3), 0)
                        val v = ReferenceCanonicalYuvToRgba.sampleSnapshot(snapshots[2], axis(sx * 0.5, 4), 0, axis(sy * 0.5, 3), 0)
                        val standard = listOf(MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_STANDARD_BT709, MediaFormat.COLOR_STANDARD_BT2020)[matrix]
                        val rgb = ReferenceCanonicalYuvToRgba.yuvToRgb(yy, u, v, standard, if (full == 1) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED)
                        ReferenceCanonicalYuvToRgba.rgbaLittleEndianInt(rgb)
                    }
                    assertEquals(expected, output.getInt(index), "crop=$crop rotation=$rotation matrix=$matrix full=$full flip=$flip x=$x y=$y")
                }
                val result = ByteArray(1024); output.duplicate().apply { position(0) }.get(result); digest.update(result)
            }
        }
        assertEquals("3fb3f8996f0cf401e8b9d707d51798c90774c5880d143dc3d558ea48c1783d51",
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    @Test
    fun rgbaOrientationAndTensorLayoutPreserveEveryFloat32Bit() {
        val input = ByteArray(1024) { (it * 37 + 11).toByte() }
        val rgba = ByteBuffer.allocateDirect(1024).put(input).apply { rewind() }
        for (fy in listOf(false, true)) for (fx in listOf(false, true)) for (interleaved in listOf(false, true)) {
            for ((scale, bias) in listOf((1f / 255f) to 0f, (1f / 127.5f) to -1f)) {
                val output = FloatArray(768)
                FrameKernel.normalizePixels(rgba, 16, 16, 64, output, flipRows = fy, flipColumns = fx,
                    interleaved = interleaved, scale = scale, bias = bias)
                for (y in 0..15) for (x in 0..15) for (c in 0..2) {
                    val src = ((if (fy) 15 - y else y) * 16 + if (fx) 15 - x else x) * 4 + c
                    val expected = (input[src].toInt() and 255) * scale + bias
                    val index = if (interleaved) (y * 16 + x) * 3 + c else c * 256 + y * 16 + x
                    assertEquals(expected.toRawBits(), output[index].toRawBits())
                }
            }
        }
        val untouched = floatArrayOf(42f)
        assertFailsWith<IllegalArgumentException> { FrameKernel.normalizePixels(rgba, 16, 16, 64, untouched) }
        assertEquals(42f, untouched[0])
    }
}
