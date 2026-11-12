package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.core.FrameKernel

internal data class MusicCandidate(val offsetUs:Long,val score:Double,val consistentWindows:Long,val supportedSeconds:Double,val residualMs:Double,val timeScale:Double=1.0)
internal data class MusicAlignmentResult(val status:String,val message:String,val candidates:List<MusicCandidate>)

/** Transport and product messages only. Features, ranking and decisions are Rust. */
internal object ComparisonMusicMatcher {
    fun match(teacher:MusicFeatures,learner:MusicFeatures,current:()->Boolean={true}):MusicAlignmentResult {
        val data=FrameKernel.musicAlignNative(teacher.payload,learner.payload,current)
        val status=when(data[0].toInt()) {0->"matched";1->"no_match";2->"inconsistent";3->"ambiguous";4->throw ComparisonAlignmentCancelled();else->throw IllegalArgumentException("Invalid shared music features")}
        val candidates=(0 until data[1].toInt()).map {i->
            val k=2+i*6
            MusicCandidate(data[k].toLong(),data[k+2],data[k+3].toLong(),data[k+4],data[k+5],data[k+1])
        }
        val message=when(status) {
            "matched"->"已按共同音乐的速率和位置对齐，可随时手动校准。"
            "ambiguous"->"找到多个相似乐段，已保留最佳位置，可手动校准。"
            "inconsistent"->"未找到稳定的音乐对应关系，请用前后两组音乐点手动校准。"
            else->"没有找到可靠的共同音乐，可以手动校准。"
        }
        return MusicAlignmentResult(status,message,candidates)
    }
}
