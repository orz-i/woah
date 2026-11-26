package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.bridge.comparison.ComparisonPictureDto

internal data class ComparisonQuad(val scaleX: Float,val scaleY: Float,val shiftX: Float,val shiftY: Float)

/** Screen-space pan within the zoomed pane; independent of rotation and mirroring. */
internal data class ComparisonPicture(val zoom: Double=1.0,val panX: Double=0.0,val panY: Double=0.0) {
    init {
        require(zoom.isFinite() && zoom in 1.0..4.0)
        require(panX.isFinite() && panX in -1.0..1.0 && panY.isFinite() && panY in -1.0..1.0)
    }
    fun quad(pane: ComparisonViewport,fit: ComparisonViewport): ComparisonQuad {
        val v=art.gaoge.dance.engine.core.FrameKernel.quadNative(
            intArrayOf(pane.x,pane.top,pane.width,pane.height),intArrayOf(fit.x,fit.top,fit.width,fit.height),zoom,panX,panY)
        return ComparisonQuad(v[0],v[1],v[2],v[3])
    }
    companion object {
        fun fromDto(value: ComparisonPictureDto?) = value?.let { ComparisonPicture(it.zoom,it.panX,it.panY) } ?: ComparisonPicture()
    }
}

/** M0 renderer contract. Alpha is top-down in display-oriented source coordinates,
 * not in model letterbox space. Placement is fixed from the selection frame. */
internal data class ComparisonCutoutPlacement(
    val anchor: art.gaoge.dance.engine.inference.FloatRect,
    val x: Double, val y: Double, val subjectHeight: Double,
    val mirror: Boolean = false, val opacity: Float = 1f
) {
    init { require(opacity.isFinite() && opacity in 0f..1f) }
    fun quad(width: Int, height: Int, aspect: Double): ComparisonQuad {
        val v = art.gaoge.dance.engine.core.FrameKernel.cutoutQuadNative(width,height,aspect,
            floatArrayOf(anchor.left,anchor.top,anchor.right,anchor.bottom),x,y,subjectHeight,mirror)
        return ComparisonQuad(v[0],v[1],v[2],v[3])
    }
}
internal class ComparisonCutoutLayer(
    val sourcePtsUs: Long, val width: Int, val height: Int, alpha: ByteArray,
    val placement: ComparisonCutoutPlacement
) {
    init { require(sourcePtsUs >= 0 && width in 1..4096 && height in 1..4096 && alpha.size == width*height) }
    private val pixels = alpha.copyOf()
    fun uploadTo(buffer: java.nio.ByteBuffer) {
        buffer.clear(); buffer.put(pixels); buffer.flip()
    }
}
/** Missing or mismatched source PTS always hides that layer; no raw-video fallback. */
internal data class ComparisonCutoutFrame(
    val reference: ComparisonCutoutLayer?, val target: ComparisonCutoutLayer?
) {
    fun layer(referenceRole: Boolean, ptsUs: Long): ComparisonCutoutLayer? =
        (if(referenceRole) reference else target)?.takeIf { it.sourcePtsUs == ptsUs }
}
