package art.gaoge.dance.engine.pipeline

import android.content.Context
import android.graphics.Bitmap
import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.PersonDetection
import art.gaoge.dance.engine.inference.YoloLiteRtSegmenter
import art.gaoge.dance.engine.media.SourceFrameReader
import art.gaoge.dance.engine.storage.AnalysisMetadata
import art.gaoge.dance.engine.tracking.TrackManager
import art.gaoge.dance.engine.tracking.TrackedPerson

/** Decoder/inference transport for the shared source-time replay plan. */
internal class PreviewIdentityReplay:AutoCloseable {
    private var policyKey:String?=null
    private var analysisId:String?=null
    private var tracker:TrackManager?=null
    private var lastUs:Long?=null
    private var lastPersons=emptyList<TrackedPerson>()

    @Synchronized fun resolve(context:Context,cacheId:String,key:String,uri:String,rotation:Int,
        metadata:AnalysisMetadata?,scope:Int,hero:Int?,body:Set<Int>,face:Set<Int>,timestampUs:Long,stepUs:Long,
        frame:Bitmap,detections:List<PersonDetection>,segmenter:YoloLiteRtSegmenter,checkCancelled:()->Unit):List<TrackedPerson> {
        val same=policyKey==key
        val plan=FrameKernel.replayPlan(scope,hero!=null,body.isNotEmpty()||face.isNotEmpty(),
            metadata?.anchorTimestampUs,if(same)lastUs else null,timestampUs,stepUs)
        require(plan.required)
        try {
            if(!same||plan.reset||tracker==null) {
                close();policyKey=key;analysisId=cacheId
                val roots=body+face+listOfNotNull(hero)+if(face.isEmpty())emptySet() else metadata?.persons.orEmpty().filter{it.confidence>=0.60}.map{it.id}.toSet()
                tracker=TrackManager().also{
                    it.setIdentityProtectedTrackIds(roots);it.setPrivacySelectedTrackIds(body)
                    it.setFacePrivacySelectedTrackIds(face-body);it.setPrivacyOffscreenDormancyEnabled(body.isNotEmpty()&&face.isNotEmpty())
                }
            }
            if(plan.count==0)return lastPersons
            SourceFrameReader.Session(context,uri,rotation).use { source->
                for(index in 0 until plan.count) {
                    checkCancelled();val at=plan.time(index)
                    val bitmap=if(at==timestampUs)frame else source.read(at)
                    try {
                        val observations=if(at==timestampUs)detections else segmenter.segmentBitmapSync(bitmap,at).persons
                        lastPersons=if(lastUs==null) {
                            val initialized=tracker!!.initializeWithAssignedIds(observations,
                                assignedIds(observations,metadata,bitmap.width,bitmap.height))
                            val required=body+face+listOfNotNull(hero)
                            check(initialized.map{it.id}.toSet().containsAll(required)){"Selected identity is missing at its observed analysis anchor"}
                            tracker!!.reserveNextIdentity((metadata?.persons.orEmpty().maxOfOrNull{it.id}?:-1).toLong()+1L)
                            initialized
                        } else tracker!!.update(observations,at)
                        lastUs=at
                    } finally {if(bitmap!==frame&&!bitmap.isRecycled)bitmap.recycle()}
                }
            }
            return lastPersons
        } catch(error:Throwable) {close();throw error}
    }
    @Synchronized fun clearForAnalysis(cacheId:String){if(analysisId==cacheId)close()}
    @Synchronized override fun close(){tracker?.close();tracker=null;policyKey=null;analysisId=null;lastUs=null;lastPersons=emptyList()}

    companion object {
        fun assignedIds(detections:List<PersonDetection>,metadata:AnalysisMetadata?,width:Int,height:Int):List<Int> {
            val cached=metadata?.persons.orEmpty()
            if(cached.isEmpty())return detections.indices.toList()
            val boxes=cached.map{FloatRect((it.bbox.left*width).toFloat(),(it.bbox.top*height).toFloat(),(it.bbox.right*width).toFloat(),(it.bbox.bottom*height).toFloat())}
            val ids=IntArray(detections.size){-1};val used=mutableSetOf<Int>()
            for((row,column) in FrameKernel.matchRects(boxes,detections.map{it.bbox},0.70f)){ids[column]=cached[row].id;used.add(ids[column])}
            var next=(cached.maxOfOrNull{it.id}?:-1)+1
            for(i in ids.indices)if(ids[i]==-1){while(used.contains(next))next++;ids[i]=next;used.add(next);next++}
            return ids.toList()
        }
    }
}
