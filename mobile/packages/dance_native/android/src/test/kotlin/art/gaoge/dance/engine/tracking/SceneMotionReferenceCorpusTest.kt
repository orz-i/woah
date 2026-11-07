package art.gaoge.dance.engine.tracking
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.PersonDetection
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Frozen canonical Android scene consensus, generated explicitly during migration. */
class SceneMotionReferenceCorpusTest {
    private var random=0x1d2e3f4L
    private fun next(n:Int):Int { random=(random*1664525+1013904223) and 0xffffffffL;return ((random ushr 8)%n).toInt() }
    private fun DataOutputStream.rect(b:FloatRect){writeFloat(b.left);writeFloat(b.top);writeFloat(b.right);writeFloat(b.bottom)}
    @Test fun orderedResidualConsensusMatchesTheFrozenReference() {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
        out.writeInt(0x464B4D31);out.writeInt(512)
        repeat(512){sequence->
            val config=TrackingConfig(minSceneMotionInliers=1+next(3),maxGlobalShift=listOf(30f,100f,300f)[next(3)],sceneMotionTolerance=listOf(5f,10f,25f,50f)[next(4)])
            val tracks=(0 until sequence%7).map{i->
                val x=next(8000)/4f;val y=next(4000)/4f;val w=10+next(800)/4f;val h=20+next(1600)/4f
                InternalTrack(id=i*3+1,lastObservedBbox=FloatRect(x,y,x+w,y+h),lastObservedMask=null,confidence=.9f)
            }
            val dx=(next(401)-200)/4f;val dy=(next(401)-200)/4f
            val detections=tracks.filterIndexed{i,_->(i+sequence)%5!=0}.mapIndexed{i,t->
                val box=t.currentPredictedBbox;val noise=if(sequence%9==0) -dx*2 else (next(9)-4)/4f
                val h=if(sequence%11==0)box.height*.4f else box.height
                PersonDetection(FloatRect(box.left+dx+noise,box.top+dy,box.right+dx+noise,box.top+dy+h),.8f,null,box.top+dy+h)
            }.toMutableList()
            if(sequence%4==0)detections.add(PersonDetection(FloatRect(20f,20f,180f,400f),.7f,null,400f))
            out.writeInt(config.minSceneMotionInliers);out.writeFloat(config.maxGlobalShift);out.writeFloat(config.sceneMotionTolerance)
            out.writeInt(tracks.size);tracks.forEach{out.writeInt(it.id);out.rect(it.currentPredictedBbox)}
            out.writeInt(detections.size);detections.forEach{out.rect(it.bbox)}
            val result=SceneMotionEstimator.estimateSceneMotion(tracks,detections,config)
            out.writeFloat(result.dx);out.writeFloat(result.dy);out.writeInt(result.inlierCount);out.writeFloat(result.confidence)
        }
        out.close()
        val target=File(System.getenv("FRAME_KERNEL_SCENE_MOTION_REFERENCE") ?: "../../../../crates/frame-kernel/testdata/scene-motion-reference-v1.bin")
        if(System.getenv("FRAME_KERNEL_GENERATE_REFERENCE")=="1"){target.parentFile.mkdirs();target.writeBytes(bytes.toByteArray())}
        assertContentEquals(target.readBytes(),bytes.toByteArray())
    }
}
