package com.danceanon.native.geometry

import com.danceanon.native.inference.FloatRect
import kotlin.math.max
import kotlin.math.min

data class ModelCoordinateMapper(
    val srcWidth: Int,
    val srcHeight: Int,
    val modelInputSize: Int = 640,
    val protoSize: Int = 160
) {
    val scale: Float = min(
        modelInputSize.toFloat() / max(1, srcWidth),
        modelInputSize.toFloat() / max(1, srcHeight)
    )
    val scaledW: Float = srcWidth * scale
    val scaledH: Float = srcHeight * scale
    val padLeft: Float = (modelInputSize - scaledW) / 2f
    val padTop: Float = (modelInputSize - scaledH) / 2f

    fun sourceToModelX(srcX: Float): Float {
        return padLeft + srcX * scale
    }

    fun sourceToModelY(srcY: Float): Float {
        return padTop + srcY * scale
    }

    fun modelToSourceX(modelX: Float): Float {
        return ((modelX - padLeft) / scale).coerceIn(0f, srcWidth.toFloat())
    }

    fun modelToSourceY(modelY: Float): Float {
        return ((modelY - padTop) / scale).coerceIn(0f, srcHeight.toFloat())
    }

    fun sourceRectToModel(rect: FloatRect): FloatRect {
        return FloatRect(
            left = sourceToModelX(rect.left),
            top = sourceToModelY(rect.top),
            right = sourceToModelX(rect.right),
            bottom = sourceToModelY(rect.bottom)
        )
    }

    fun modelRectToSource(rect: FloatRect): FloatRect {
        return FloatRect(
            left = modelToSourceX(rect.left),
            top = modelToSourceY(rect.top),
            right = modelToSourceX(rect.right),
            bottom = modelToSourceY(rect.bottom)
        )
    }

    fun modelToProtoX(modelX: Float): Int {
        return ((modelX / modelInputSize) * protoSize).toInt().coerceIn(0, protoSize)
    }

    fun modelToProtoY(modelY: Float): Int {
        return ((modelY / modelInputSize) * protoSize).toInt().coerceIn(0, protoSize)
    }
}
