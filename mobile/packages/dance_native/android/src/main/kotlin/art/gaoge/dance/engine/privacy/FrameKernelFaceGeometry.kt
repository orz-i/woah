package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.face.FaceHeadRoiPlan
import art.gaoge.dance.engine.face.FacePoint
import art.gaoge.dance.engine.face.FaceRoiCandidateSelection
import art.gaoge.dance.engine.face.coreRecord
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask

/** Transport candidate. Production consumers remain unchanged until parity passes. */
internal object FrameKernelFaceGeometry {
    private fun FloatRect.values()=floatArrayOf(left,top,right,bottom)
    private fun FloatArray.region(source:FacePrivacyRegionSource)=FacePrivacyEllipse(this[0],this[1],this[2],this[3],source)
    fun resolve(person:FloatRect,plan:FaceHeadRoiPlan?,selected:FaceRoiCandidateSelection?):FacePrivacyEllipse? {
        if(person.width<=1f||person.height<=1f)return null
        if(plan!=null&&selected!=null) {
            val value=FrameKernel.faceRegionNative(selected.face.coreRecord(),plan.sourceRect.values(),plan.outputSize.toFloat(),0f)
            if(value!=null)return value.region(FacePrivacyRegionSource.DETECTED_FACE)
        }
        return FrameKernel.headFallbackNative(person.values())?.region(FacePrivacyRegionSource.YOLO_HEAD_FALLBACK)
    }
    private fun head(mask:NativeMask?,person:FloatRect,seed:FloatArray,previous:FloatRect?=null,expansion:Float=1f):FloatArray? {
        mask?:return null
        val mapper=mask.mapper?:return null
        if(mask.width<=1||mask.height<=1||mask.width>1024||mask.height>1024)return null
        val count=Math.multiplyExact(mask.width,mask.height)
        if(mask.buffer.capacity()<count||mask.buffer.limit()<count)return null
        val bytes=ByteArray(count)
        mask.buffer.duplicate().apply{position(0);limit(count);get(bytes)}
        return FrameKernel.maskHeadNative(bytes,mask.width,mask.height,
            floatArrayOf(mapper.scale,mapper.padLeft,mapper.padTop,mapper.srcWidth.toFloat(),mapper.srcHeight.toFloat(),mapper.modelInputSize.toFloat(),mapper.protoSize.toFloat()),
            person.values(),seed,previous?.values(),expansion)
    }
    fun estimate(mask:NativeMask?,person:FloatRect,cx:Float,cy:Float,rx:Float,ry:Float):FacePoint? {
        val value=head(mask,person,floatArrayOf(cx,cy,rx,ry))?:return null
        return FacePoint(value[0],value[1])
    }
    fun trusted(mask:NativeMask?,person:FloatRect,seed:FacePrivacyTrustedGeometry,expansion:Float):FacePrivacyEllipse? {
        return head(mask,person,floatArrayOf(seed.centerX,seed.centerY,seed.radiusX,seed.radiusY),seed.trustedPersonBbox,expansion)
            ?.region(FacePrivacyRegionSource.YOLO_HEAD_FALLBACK)
    }
}
