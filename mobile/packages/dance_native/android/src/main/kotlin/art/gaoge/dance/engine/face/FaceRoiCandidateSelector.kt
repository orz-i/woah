package art.gaoge.dance.engine.face

import art.gaoge.dance.engine.inference.FloatRect

data class FaceRoiCandidateSelection(
    val faceIndex: Int,
    val face: FaceObservation,
    val anchorDistanceRatio: Float
)

/**
 * Selects the face owned by a YOLO person inside that person's source ROI.
 *
 * A wide upper-head crop can intentionally contain a neighboring face during
 * crossings. Identity is therefore never taken from the face detector itself:
 * the accepted face must stay close to the target person's planned head anchor.
 */
object FaceRoiCandidateSelector {
    fun ownedObservations(faces: List<FaceObservation>,plan: FaceHeadRoiPlan,personBbox: FloatRect,
        currentMask: art.gaoge.dance.engine.inference.NativeMask? = null): List<FaceObservation> {
        val r=plan.sourceRect
        var pixels:ByteArray?=null
        var transform:FloatArray?=null
        if(currentMask!=null) {
            val mapper=currentMask.mapper ?: return emptyList()
            if(currentMask.width<=1||currentMask.height<=1||currentMask.width>1024||currentMask.height>1024)return emptyList()
            val count=Math.multiplyExact(currentMask.width,currentMask.height)
            if(currentMask.buffer.limit()<count||currentMask.buffer.capacity()<count)return emptyList()
            val currentPixels=ByteArray(count)
            currentMask.buffer.duplicate().apply{position(0);limit(count);get(currentPixels)}
            pixels=currentPixels
            transform=floatArrayOf(mapper.scale,mapper.padLeft,mapper.padTop,mapper.srcWidth.toFloat(),mapper.srcHeight.toFloat(),mapper.modelInputSize.toFloat(),mapper.protoSize.toFloat())
        }
        return art.gaoge.dance.engine.core.FrameKernel.currentFaceOwnedNative(faces.coreRecords(),
            floatArrayOf(r.left,r.top,r.right,r.bottom),plan.outputSize.toFloat(),
            floatArrayOf(personBbox.left,personBbox.top,personBbox.right,personBbox.bottom),
            pixels,currentMask?.width ?: 0,currentMask?.height ?: 0,transform).map { faces[it] }
    }
    fun select(faces: List<FaceObservation>,roiWidth: Int,roiHeight: Int,anchorX: Float,anchorY: Float,
        maxAnchorDistanceRatio: Float=0.22f,minAnchorSeparationRatio: Float=0.04f): FaceRoiCandidateSelection? {
        if(faces.isEmpty() || roiWidth<=1 || roiHeight<=1) return null
        val value=art.gaoge.dance.engine.core.FrameKernel.faceSelectNative(faces.coreRecords(),
            floatArrayOf(0f,0f,roiWidth.toFloat(),roiHeight.toFloat()),anchorX,anchorY,
            maxAnchorDistanceRatio,minAnchorSeparationRatio) ?: return null
        val index=value[0].toInt()
        return FaceRoiCandidateSelection(index,faces[index],value[1])
    }
}
