package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.inference.PersonDetection
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Generated against the still-canonical native observation update, not Rust. */
class TrackingUpdateReferenceCorpusTest {
 private fun mask(seed:Int):NativeMask = NativeMask(16,16,ByteBuffer.allocateDirect(256).apply{
  put(ByteArray(256){i->((i*17+i/16*29+seed*37) and 255).toByte()});rewind()
 },1920,1080,ModelCoordinateMapper(1920,1080,640,16))
 private fun DataOutputStream.rect(r:FloatRect){writeFloat(r.left);writeFloat(r.top);writeFloat(r.right);writeFloat(r.bottom)}
 private fun detection(x:Float,y:Float,seed:Int)=PersonDetection(FloatRect(x,y,x+240f,y+620f),0.9f,mask(seed),y+620f)
 private fun DataOutputStream.input(list:List<Pair<PersonDetection,Int>>){writeInt(list.size);for((d,seed) in list){rect(d.bbox);writeFloat(d.confidence);writeFloat(d.footY?:d.bbox.bottom);writeInt(seed)}}
 private fun DataOutputStream.ids(values:Collection<Int>){writeInt(values.size);for(id in values.sorted())writeInt(id)}
 private fun DataOutputStream.mask(value:NativeMask?){if(value==null){writeInt(-1);return};writeInt(value.width*value.height);repeat(value.width*value.height){writeByte(value.buffer.get(it).toInt())}}
 private fun DataOutputStream.classes(values:List<FreshPrivacyClassEvidence>){writeInt(values.size);for(e in values){writeInt(e.selectionClass.ordinal);writeInt(e.detectionIndex);ids(e.residualTrackIds);writeBoolean(e.conservativeUnknown)}}
 @Test fun fullObservationUpdateMatchesFrozenNativeTransitions(){
  val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes);out.writeInt(0x464B5531);out.writeInt(12)
  repeat(12){mode->
   val config=TrackingConfig(maxMissedFrames=3+mode%3,postOcclusionGraceFrames=3,enableSceneMotionCompensation=mode>=6)
   val tracker=CanonicalTrackManager(config,diagnosticsEnabled=false)
   val protected=if(mode%3==0)setOf(9,2)else if(mode%3==1)setOf(9)else emptySet()
   val selected=if(mode%3==0)setOf(9)else emptySet()
   val face=protected-selected
   tracker.setIdentityProtectedTrackIds(protected);tracker.setPrivacySelectedTrackIds(selected);tracker.setFacePrivacySelectedTrackIds(face);tracker.setPrivacyOffscreenDormancyEnabled(mode%2==0)
   out.writeInt(mode);out.ids(protected);out.ids(selected);out.ids(face)
   val initial=listOf(detection(180f,150f,0) to 0,detection(640f,160f,1) to 1,detection(1200f,140f,2) to 2)
   out.input(initial);val identities=listOf(9,2,7);out.writeInt(identities.size);identities.forEach{out.writeInt(it)}
   tracker.initializeWithAssignedIds(initial.map{it.first},identities)
   out.writeInt(80)
   repeat(80){frame->
    val at=(frame+1)*40000L;val command=if(frame%13==0)1 else if(frame%11==3)2 else 0
    out.writeInt(command);out.writeLong(at)
    val observations=mutableListOf<Pair<PersonDetection,Int>>()
    if(frame !in 42..50){
     repeat(if(frame>=30)4 else 3){person->
      if((frame%9==4&&person==0)||(frame in 61..69&&person==0)||(frame in 18..30&&person==1))return@repeat
      val step=if(frame<=38)frame else 76-frame
      val x=when(person){0->180f+step*22f;1->640f-step*15f;2->1200f+(frame%7-3)*4f;else->1600f-frame*8f}
      val d=if(frame in 18..30&&person==0)PersonDetection(FloatRect(x,150f,x+480f,790f),0.9f,mask(0),790f) else detection(x,150f+(person%3)*10f,person)
      observations.add(d to person)
     }
    }
    if(frame%2==1)observations.reverse()
    out.input(observations)
    val result=when(command){1->tracker.predict(at);2->tracker.predictWithoutObservation(at);else->tracker.update(observations.map{it.first},at)}
    out.writeInt(result.size)
    for(r in result){out.writeInt(r.id);out.writeInt(r.state.ordinal);out.writeInt(r.missedFrames);out.writeInt(r.framesSinceLastObservation);out.writeInt(r.age);out.writeBoolean(r.observedThisFrame);out.rect(r.bbox);out.writeFloat(r.confidence);out.writeFloat(r.footY!!);out.ids(r.occludedByTrackIds);out.mask(r.mask)}
    out.classes(tracker.getFreshPrivacyClassEvidence());out.classes(tracker.getFreshFacePrivacyClassEvidence());out.ids(tracker.getPrivacySuppressedSelectedTrackIds())
    val hard=tracker.getHardPrivacyClassByDetectionIndex();out.writeInt(hard.size);for((key,value) in hard.toSortedMap()){out.writeInt(key);out.writeInt(value.ordinal)}
    val motion=tracker.getFreshProtectedTrackMotionEvidence();out.writeInt(motion.size);for(e in motion){out.writeInt(e.trackId);out.writeInt(e.detectionIndex);out.writeFloat(e.assignedScore);out.writeFloat(e.bboxIou);out.writeFloat(e.maskIou);out.writeLong(e.timestampUs)}
   }
  }
  out.close();val target=File(System.getenv("FRAME_KERNEL_UPDATE_REFERENCE")?:"../../../../crates/frame-kernel/testdata/tracking-update-reference-v1.bin")
  if(System.getenv("FRAME_KERNEL_GENERATE_REFERENCE")=="1"){target.parentFile.mkdirs();target.writeBytes(bytes.toByteArray())}
  assertContentEquals(target.readBytes(),bytes.toByteArray())
 }
}
