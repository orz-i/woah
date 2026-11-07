package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.*
import art.gaoge.dance.engine.tracking.PrivacySelectionClass
import java.io.*
import java.nio.ByteBuffer
import kotlin.math.*
import kotlin.test.Test

/** Producer runs the frozen Kotlin algorithm, never the Rust replacement. */
class PrivacyClassReferenceProducerTest {
    private fun field(owner:Any,name:String):Any?=owner.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(owner)
    private fun detection(x:Float,width:Float,seed:Int,maskMissing:Boolean=false):PersonDetection {
        val box=FloatRect(x,80f,x+width,380f)
        val size=16;val mapper=ModelCoordinateMapper(640,640,640,size)
        val bytes=ByteArray(size*size){i->
            val sx=(i%size+.5f)*40;val sy=(i/size+.5f)*40
            if(sx>=box.left&&sx<box.right&&sy>=box.top&&sy<box.bottom) {
                if((i+seed)%11==0)127.toByte() else 255.toByte()
            } else 0
        }
        val mask=if(maskMissing)null else NativeMask(size,size,ByteBuffer.allocateDirect(bytes.size).apply{put(bytes);rewind()},640,640,mapper)
        return PersonDetection(box,.91f,mask,box.bottom)
    }
    private fun DataOutputStream.rect(r:FloatRect){writeFloat(r.left);writeFloat(r.top);writeFloat(r.right);writeFloat(r.bottom)}
    private fun DataOutputStream.mask(mask:NativeMask?){
        if(mask==null){writeInt(-1);return}
        writeInt(mask.width);writeInt(mask.height)
        val bytes=ByteArray(mask.width*mask.height);mask.buffer.duplicate().apply{rewind();get(bytes)};write(bytes)
    }
    @Test fun captureIndependentClassTransitions() {
        val path=System.getenv("WOAH_CLASS_REFERENCE")?:return
        DataOutputStream(BufferedOutputStream(FileOutputStream(path))).use {o->
            o.writeInt(0x464B4331);o.writeInt(16)
            for(cache in listOf(false,true))for(mode in 0 until 8) {
                val tracker=CanonicalPrivacyClassTemporalTracker(reuseFrameSimilarityCache=cache,
                    reuseFrameWarpedMaskSupportCache=cache,countSimilarityEvaluations=true)
                o.writeBoolean(cache);o.writeInt(mode);o.writeInt(64)
                for(frame in 0 until 64) {
                    val step=frame.toFloat();val moving=if(mode==0)0f else step*2.8f
                    var detections=listOf(detection(60f+moving,125f,frame,mode==5&&frame%3==1),
                        detection(410f-moving,130f,frame+3),detection(240f,80f,frame+7))
                    if(mode==2&&frame in 20..25)detections=listOf(detection(170f,300f,frame))
                    if(mode==3&&(frame in 7..12||frame in 36..44))detections=emptyList()
                    if(mode==4&&frame>=30)detections=detections+ detection(505f,100f,frame+11)
                    if(mode==1&&frame>35)detections=detections.reversed()
                    val hard=linkedMapOf<Int,PrivacySelectionClass>()
                    if(frame==(if(mode==7)4 else 0)||mode==6&&frame>=20) {
                        hard[1]=PrivacySelectionClass.UNSELECTED;hard[0]=PrivacySelectionClass.SELECTED;hard[2]=PrivacySelectionClass.SELECTED
                        if(mode==6&&frame>=20){hard[0]=PrivacySelectionClass.UNSELECTED;hard[1]=PrivacySelectionClass.SELECTED}
                    }
                    o.writeLong(frame*16667L);o.writeInt(detections.size)
                    for(d in detections){o.rect(d.bbox);o.writeFloat(d.confidence);o.mask(d.mask)}
                    o.writeInt(hard.size);for((index,c)in hard){o.writeInt(index);o.writeInt(c.ordinal)}
                    val result=tracker.update(detections,hard,frame*16667L)
                    o.writeInt(result.size);for(e in result){o.writeInt(e.detectionIndex);o.writeInt(e.selectionClass.ordinal);o.writeBoolean(e.conservativeUnknown)}
                    o.writeBoolean(field(tracker,"rootSeeded") as Boolean);o.writeInt(tracker.lastSimilarityEvaluationCount)
                    val prototypes=field(tracker,"prototypes") as List<*>
                    o.writeInt(prototypes.size)
                    for(raw in prototypes){val p=checkNotNull(raw);o.writeInt((field(p,"selectionClass") as PrivacySelectionClass).ordinal)
                        o.rect(field(p,"bbox") as FloatRect);o.mask(field(p,"mask") as NativeMask?)
                        o.writeFloat(field(p,"velocityX") as Float);o.writeFloat(field(p,"velocityY") as Float)
                        o.writeInt(field(p,"misses") as Int);o.writeFloat(field(p,"reliability") as Float)
                    }
                }
            }
        }
    }
}
