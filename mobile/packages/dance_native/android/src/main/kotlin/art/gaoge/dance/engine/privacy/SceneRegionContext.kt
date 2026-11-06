package art.gaoge.dance.engine.privacy

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.YoloLiteRtSegmenter
import art.gaoge.dance.engine.media.SourceFrameReader
import art.gaoge.dance.engine.render.RenderCoordinateConvention
import art.gaoge.dance.engine.render.SourceTextureType
import art.gaoge.dance.engine.tracking.TrackedPerson
import art.gaoge.dance.engine.tracking.TrackState

/** Decode/inference transport. Context grants neither identity nor exemptions. */
internal object SceneRegionContext {
    fun seed(context:Context,segmenter:YoloLiteRtSegmenter,processor:FaceOnlyPrivacyFrameProcessor,
        sourceUri:String,timestampUs:Long,rotation:Int,width:Int,height:Int) {
        val probe=FrameKernel.regionContextProbeUs(timestampUs)?:return
        require(width > 0 && height > 0)
        val decoded=SourceFrameReader.read(context,sourceUri,probe,rotation)
        val bitmap=try {
            if(decoded.width==width && decoded.height==height) decoded
            else Bitmap.createScaledBitmap(decoded,width,height,true)
        } catch(error:Throwable) { decoded.recycle();throw error }
        if(bitmap!==decoded) decoded.recycle()
        val textures=IntArray(1)
        try {
            val detections=segmenter.segmentBitmapSync(bitmap,probe).persons
            val persons=detections.mapIndexed { index,d->TrackedPerson(id=-4_000_000-index,
                bbox=d.bbox,mask=d.mask,confidence=d.confidence,state=TrackState.ACTIVE,observedThisFrame=true,footY=d.footY) }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glGenTextures(1,textures,0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
            processor.resolveFrame(frameTexture=textures[0],texMatrix=RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType=SourceTextureType.TEXTURE_2D,persons=persons,faceOnlyTrackIds=persons.map{it.id}.toSet(),
                ptsUs=probe,conservativeSceneCoverage=true)
        }finally{if(textures[0]!=0)GLES20.glDeleteTextures(1,textures,0);bitmap.recycle()}
    }
}
