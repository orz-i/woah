package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.diagnostics.NativeDiagnostics
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.PersonDetection
import art.gaoge.dance.engine.tracking.FreshPrivacyClassEvidence
import art.gaoge.dance.engine.tracking.PrivacySelectionClass
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Native values/lifetime only. The immutable-root class algorithm executes in Rust. */
class PrivacyClassTemporalTracker(
    private val minClassScore:Float=0.42f,
    private val minSingleClassScore:Float=0.65f,
    private val minClassMargin:Float=0.12f,
    private val maxPrototypeMisses:Int=4,
    private val reuseFrameSimilarityCache:Boolean=true,
    private val reuseFrameWarpedMaskSupportCache:Boolean=true,
    private val countSimilarityEvaluations:Boolean=false
):AutoCloseable {
    private var session:FrameKernel.ClassSession?=null
    internal var lastSimilarityEvaluationCount:Int=0
        private set
    private fun owned()=session?:FrameKernel.ClassSession(floatArrayOf(minClassScore,minSingleClassScore,minClassMargin),
        maxPrototypeMisses,(if(reuseFrameSimilarityCache)1 else 0) or (if(reuseFrameWarpedMaskSupportCache)2 else 0) or
        (if(countSimilarityEvaluations)4 else 0)).also{session=it}
    @Synchronized fun reset(){session?.reset();lastSimilarityEvaluationCount=0}
    @Synchronized override fun close(){session?.close();session=null;lastSimilarityEvaluationCount=0}
    @Synchronized fun update(detections:List<PersonDetection>,hardClassByDetectionIndex:Map<Int,PrivacySelectionClass>,ptsUs:Long):List<FreshPrivacyClassEvidence> {
        val values=FloatArray(detections.size*15);val offsets=LongArray(detections.size){-1L};val pool=ByteArrayOutputStream()
        detections.forEachIndexed{index,d->
            val mask=d.mask;val mapper=mask?.let{it.mapper?:ModelCoordinateMapper(max(1,it.originalWidth),max(1,it.originalHeight),640,it.width)}
            floatArrayOf(d.bbox.left,d.bbox.top,d.bbox.right,d.bbox.bottom,d.confidence,d.footY,
                mask?.width?.toFloat()?:0f,mask?.height?.toFloat()?:0f,mapper?.scale?:1f,mapper?.padLeft?:0f,mapper?.padTop?:0f,
                mapper?.srcWidth?.toFloat()?:1f,mapper?.srcHeight?.toFloat()?:1f,mapper?.modelInputSize?.toFloat()?:1f,mapper?.protoSize?.toFloat()?:1f).copyInto(values,index*15)
            if(mask!=null){offsets[index]=pool.size().toLong();val bytes=ByteArray(Math.multiplyExact(mask.width,mask.height));mask.buffer.duplicate().apply{rewind();get(bytes)};pool.write(bytes)}
        }
        val roots=hardClassByDetectionIndex.toSortedMap().flatMap{listOf(it.key.toLong(),it.value.ordinal.toLong())}.toLongArray()
        val output=owned().update(values,offsets,pool.toByteArray(),roots)
        lastSimilarityEvaluationCount=output[0]
        val result=(1 until output.size step 3).map {i->
            val index=output[i]
            FreshPrivacyClassEvidence(PrivacySelectionClass.entries[output[i+1]],index,detections[index],emptySet(),output[i+2]!=0)
        }
        NativeDiagnostics.eventLazy(level="INFO",component="PrivacyClassTemporalTracker",event="PRIVACY_CLASS_TEMPORAL_SUMMARY",
            fields={mapOf("pts_us" to ptsUs,"unknown" to result.count{it.conservativeUnknown},"similarity_evaluations" to lastSimilarityEvaluationCount)})
        return result
    }
}
