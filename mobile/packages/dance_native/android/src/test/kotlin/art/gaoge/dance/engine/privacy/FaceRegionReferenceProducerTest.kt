package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.face.*
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.*
import java.io.*
import java.nio.ByteBuffer
import kotlin.math.*
import kotlin.test.Test

/** Captures still-native geometry before replacing production consumers. */
class FaceRegionReferenceProducerTest {
    private fun DataOutputStream.rect(r:FloatRect){writeFloat(r.left);writeFloat(r.top);writeFloat(r.right);writeFloat(r.bottom)}
    private fun DataOutputStream.region(r:FacePrivacyEllipse?){writeBoolean(r!=null);if(r!=null){writeFloat(r.centerX);writeFloat(r.centerY);writeFloat(r.radiusX);writeFloat(r.radiusY);writeInt(r.source.ordinal)}}
    @Test fun captureIndependentFaceAndMaskGeometry(){
        val target=System.getenv("WOAH_FACE_REGION_REFERENCE")?:return
        DataOutputStream(BufferedOutputStream(FileOutputStream(target))).use {o->
            o.writeInt(0x464B4731);o.writeInt(512)
            repeat(512){i->
                val (fw,fh)=listOf(640 to 640,1920 to 1080,2160 to 4096,1080 to 1920)[i%4]
                val body=FloatRect(fw*(if(i%11==0)0f else .1f+(i%5)*.07f),fh*(if(i%13==0)0f else .08f),fw*.88f,fh*.92f)
                val plan=FaceHeadRoiPlanner.plan(body,fw,fh)!!
                val left=40f+i%37;val top=45f+i%29;val w=36f+i%61;val h=42f+i%43
                val points=if(i%3==0)emptyList() else listOf(FacePoint(left+w*.3f,top+h*.3f),FacePoint(left+w*.7f,top+h*.3f),
                    FacePoint(left+w*.55f,top+h*.52f),FacePoint(left+w*.52f,top+h*.73f),FacePoint(left,top+h*.4f),FacePoint(left+w,top+h*.4f))
                val face=FaceObservation(FloatRect(left,top,left+w,top+h),.85f,points)
                o.writeInt(fw);o.writeInt(fh);o.rect(body);o.rect(plan.sourceRect);o.writeInt(plan.outputSize)
                o.rect(face.bbox);o.writeFloat(face.confidence);o.writeInt(points.size);points.forEach{o.writeFloat(it.x);o.writeFloat(it.y)}
                o.region(CanonicalFacePrivacyRegionResolver.resolve(body,plan,FaceRoiCandidateSelection(0,face,.01f)))
                o.region(CanonicalFacePrivacyRegionResolver.resolve(body,null,null))
                val size=160;val mapper=ModelCoordinateMapper(fw,fh,640,size)
                val seedX=body.centerX+(i%7-3)*2.3f;val seedY=body.top+body.height*.14f+(i%9-4)*1.7f
                val rx=max(body.width*.12f,24f);val ry=max(body.height*.045f,24f)
                for(v in floatArrayOf(mapper.scale,mapper.padLeft,mapper.padTop,seedX,seedY,rx,ry))o.writeFloat(v)
                val cx=mapper.sourceToProtoX(seedX);val cy=mapper.sourceToProtoY(seedY)
                val halfX=max(abs(mapper.sourceToProtoX(seedX+rx*.55f)-cx),1f)
                val halfY=max(abs(mapper.sourceToProtoY(seedY+ry*.6f)-cy),1f)
                val bytes=ByteArray(size*size){n->
                    val x=n%size+.5f;val y=n/size+.5f
                    val head=abs(x-cx)<=halfX&&abs(y-cy)<=halfY
                    val shoulder=y>=cy+halfY+2&&y<cy+halfY+8&&abs(x-cx)<halfX*3.5f
                    val arm=abs(x-cx-15f)<5&&y>=cy-10&&y<cy+20
                    val on=when(i%5){0->head;1->head||shoulder;2->head||arm;3->shoulder;else->head&&floor(y)==floor(cy)}
                    if(on)(if(n%7==0)96 else 255).toByte() else (if(n%97==0)95 else 0).toByte()
                }
                val mask=NativeMask(size,size,ByteBuffer.allocateDirect(bytes.size).apply{put(bytes);rewind()},fw,fh,mapper)
                val runs=mutableListOf<Pair<Int,Byte>>();var start=0
                while(start<bytes.size){var end=start+1;while(end<bytes.size&&bytes[end]==bytes[start])end++;runs.add((end-start) to bytes[start]);start=end}
                o.writeInt(runs.size);runs.forEach{(n,v)->o.writeInt(n);o.writeByte(v.toInt())}
                val center=CanonicalBodyMaskFaceHeadEstimator.estimate(mask,body,seedX,seedY,rx,ry)
                o.writeBoolean(center!=null);if(center!=null){o.writeFloat(center.x);o.writeFloat(center.y)}
                val trusted=FacePrivacyTrustedGeometry(seedX,seedY,rx,ry,body)
                o.region(CanonicalFaceTrustedMaskFallback.resolve(mask,body,trusted,1.10f))
            }
        }
    }
}
