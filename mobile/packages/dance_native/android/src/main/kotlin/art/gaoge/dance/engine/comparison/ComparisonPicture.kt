package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.bridge.comparison.ComparisonPictureDto
import art.gaoge.dance.engine.bridge.comparison.ComparisonCanvasLayoutDto
import art.gaoge.dance.engine.bridge.comparison.ComparisonCanvasLayerDto

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
/** Immutable editable layout; cached masks and calibration anchors are not copied. */
internal data class ComparisonCanvasLayer(val x:Double,val y:Double,val height:Double,val opacity:Float,val mirror:Boolean) {
    init {
        require(x.isFinite() && y.isFinite() && height.isFinite() && opacity.isFinite() &&
            x in 0.0..1.0 && y in 0.0..1.0 && height in .05..2.0 && opacity in 0f..1f)
    }
    fun apply(anchor: ComparisonCutoutPlacement)=anchor.copy(x=x,y=y,subjectHeight=height,opacity=opacity,mirror=mirror)
    companion object {
        fun fromDto(v:ComparisonCanvasLayerDto):ComparisonCanvasLayer {
            require(v.opacity.isFinite() && v.opacity in 0.0..1.0)
            return ComparisonCanvasLayer(v.x,v.y,v.height,v.opacity.toFloat(),v.mirror)
        }
    }
}
internal data class ComparisonCanvasLayout(val reference:ComparisonCanvasLayer,val target:ComparisonCanvasLayer,
    val targetOnTop:Boolean,val background:Int) {
    init {require(background in 0..2)}
    fun layer(referenceRole:Boolean)=if(referenceRole)reference else target
    fun color():FloatArray=when(background){
        1->floatArrayOf(20f/255,33f/255,35f/255)
        2->floatArrayOf(235f/255,237f/255,235f/255)
        else->floatArrayOf(0f,0f,0f)
    }
    companion object {
        fun fromDto(v:ComparisonCanvasLayoutDto):ComparisonCanvasLayout {
            require(v.background in 0L..2L)
            return ComparisonCanvasLayout(ComparisonCanvasLayer.fromDto(v.reference),
                ComparisonCanvasLayer.fromDto(v.target),v.targetOnTop,v.background.toInt())
        }
    }
}
/** Missing or mismatched source PTS always hides that layer; no raw-video fallback. */
internal data class ComparisonCutoutFrame(
    val reference: ComparisonCutoutLayer?, val target: ComparisonCutoutLayer?,
    val layout: ComparisonCanvasLayout?=null
) {
    fun layer(referenceRole: Boolean, ptsUs: Long): ComparisonCutoutLayer? =
        (if(referenceRole) reference else target)?.takeIf { it.sourcePtsUs == ptsUs }
}
