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
