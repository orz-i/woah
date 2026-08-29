package com.danceanon.native.privacy

import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.NativeMask
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Rasterizes source-space face privacy ellipses into the same letterboxed proto
 * coordinate system used by YOLO segmentation masks.
 *
 * Output is deliberately binary (0/255). Privacy padding belongs in the source
 * ellipse geometry, not a soft alpha edge that could under-mask a face.
 */
object FacePrivacyMaskBuilder {
    fun build(
        regions: List<FacePrivacyEllipse>,
        mapper: ModelCoordinateMapper,
        maskWidth: Int = mapper.protoSize,
        maskHeight: Int = mapper.protoSize
    ): NativeMask? {
        if (regions.isEmpty() || maskWidth <= 1 || maskHeight <= 1) return null

        val pixels = ByteArray(maskWidth * maskHeight)
        var wroteAny = false

        for (region in regions) {
            if (region.radiusX <= 0.5f || region.radiusY <= 0.5f) continue

            val centerModelX = mapper.sourceToModelX(region.centerX)
            val centerModelY = mapper.sourceToModelY(region.centerY)
            val centerX = centerModelX / mapper.modelInputSize.toFloat() * maskWidth
            val centerY = centerModelY / mapper.modelInputSize.toFloat() * maskHeight
            val radiusX = region.radiusX * mapper.scale / mapper.modelInputSize.toFloat() * maskWidth
            val radiusY = region.radiusY * mapper.scale / mapper.modelInputSize.toFloat() * maskHeight
            if (radiusX <= 0.25f || radiusY <= 0.25f) continue

            val minX = floor(centerX - radiusX).toInt().coerceIn(0, maskWidth - 1)
            val maxX = ceil(centerX + radiusX).toInt().coerceIn(0, maskWidth - 1)
            val minY = floor(centerY - radiusY).toInt().coerceIn(0, maskHeight - 1)
            val maxY = ceil(centerY + radiusY).toInt().coerceIn(0, maskHeight - 1)

            for (y in minY..maxY) {
                val dy = ((y + 0.5f) - centerY) / radiusY
                val dy2 = dy * dy
                if (dy2 > 1f) continue
                val row = y * maskWidth
                for (x in minX..maxX) {
                    val dx = ((x + 0.5f) - centerX) / radiusX
                    if (dx * dx + dy2 <= 1f) {
                        pixels[row + x] = 255.toByte()
                        wroteAny = true
                    }
                }
            }
        }

        if (!wroteAny) return null
        val buffer = ByteBuffer.allocateDirect(pixels.size).order(ByteOrder.nativeOrder())
        buffer.put(pixels)
        buffer.rewind()
        return NativeMask(
            width = maskWidth,
            height = maskHeight,
            buffer = buffer,
            originalWidth = mapper.srcWidth,
            originalHeight = mapper.srcHeight,
            mapper = mapper,
            samplingRect = null
        )
    }

    /**
     * Unions a face privacy mask with an existing compositor privacy mask only
     * when both masks share the exact texture/sampling contract.
     */
    fun unionCompatible(base: NativeMask?, face: NativeMask?): NativeMask? {
        if (base == null) return face
        if (face == null) return base
        require(base.width == face.width && base.height == face.height) {
            "Cannot union privacy masks with different texture sizes: " +
                "${base.width}x${base.height} vs ${face.width}x${face.height}"
        }
        require(base.originalWidth == face.originalWidth && base.originalHeight == face.originalHeight) {
            "Cannot union privacy masks from different source frames"
        }
        require(base.samplingRect == face.samplingRect) {
            "Cannot union privacy masks with different sampling rects"
        }
        return PrivacyOcclusionResolver.mergeMasks(listOf(base, face))
    }
}
