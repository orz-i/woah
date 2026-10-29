package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.bridge.comparison.ComparisonPictureDto
import kotlin.math.abs

internal data class ComparisonQuad(val scaleX: Float,val scaleY: Float,val shiftX: Float,val shiftY: Float)

/** Screen-space pan within the zoomed pane; independent of rotation and mirroring. */
internal data class ComparisonPicture(val zoom: Double=1.0,val panX: Double=0.0,val panY: Double=0.0) {
    init {
        require(zoom.isFinite() && zoom in 1.0..4.0)
        require(panX.isFinite() && panX in -1.0..1.0 && panY.isFinite() && panY in -1.0..1.0)
    }
    fun quad(pane: ComparisonViewport,fit: ComparisonViewport): ComparisonQuad {
        val centerX=(2.0*(fit.x-pane.x+fit.width/2.0)/pane.width-1)*zoom
        val centerY=(1-2.0*(fit.top-pane.top+fit.height/2.0)/pane.height)*zoom
        val travelX=if (zoom==1.0) 0.0 else abs(fit.width.toDouble()/pane.width*zoom-1)
        val travelY=if (zoom==1.0) 0.0 else abs(fit.height.toDouble()/pane.height*zoom-1)
        return ComparisonQuad((fit.width.toDouble()/pane.width*zoom).toFloat(),
            (fit.height.toDouble()/pane.height*zoom).toFloat(),
            (if (zoom==1.0) centerX else (centerX+panX*travelX).coerceIn(-travelX,travelX)).toFloat(),
            (if (zoom==1.0) centerY else (centerY-panY*travelY).coerceIn(-travelY,travelY)).toFloat())
    }
    companion object {
        fun fromDto(value: ComparisonPictureDto?) = value?.let { ComparisonPicture(it.zoom,it.panX,it.panY) } ?: ComparisonPicture()
    }
}
