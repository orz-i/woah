package art.gaoge.dance.engine.geometry

import art.gaoge.dance.engine.inference.FloatRect

data class ModelCoordinateMapper(
    val srcWidth: Int,
    val srcHeight: Int,
    val modelInputSize: Int = 640,
    val protoSize: Int = 160
) {
    private val letterbox = art.gaoge.dance.engine.core.FrameKernel.letterbox(srcWidth, srcHeight, modelInputSize, modelInputSize)
    val scale: Float = letterbox[0]
    val scaledW: Float = letterbox[1]
    val scaledH: Float = letterbox[2]
    val padLeft: Float = letterbox[3]
    val padTop: Float = letterbox[4]

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

    fun modelToProtoXFloat(modelX: Float): Float {
        return (modelX / modelInputSize) * protoSize
    }

    fun modelToProtoYFloat(modelY: Float): Float {
        return (modelY / modelInputSize) * protoSize
    }

    fun sourceToProtoX(srcX: Float): Float {
        return modelToProtoXFloat(sourceToModelX(srcX))
    }

    fun sourceToProtoY(srcY: Float): Float {
        return modelToProtoYFloat(sourceToModelY(srcY))
    }
}

