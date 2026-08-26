package com.danceanon.native.render

object RenderCoordinateConvention {

    fun glYToVisualY(glY: Float): Float {
        return 1f - glY
    }

    fun bitmapTextureMatrix(): FloatArray {
        return floatArrayOf(
            1f,  0f, 0f, 0f,
            0f, -1f, 0f, 0f,
            0f,  0f, 1f, 0f,
            0f,  1f, 0f, 1f
        )
    }
}
