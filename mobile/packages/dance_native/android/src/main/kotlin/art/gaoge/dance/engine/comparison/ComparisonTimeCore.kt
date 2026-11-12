package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.core.FrameKernel

/** Transport only. Dart, JNI and Swift consume the same source-time contract. */
internal object ComparisonTimeCore {
    fun map(value:Long,scale:Double,offset:Long,inverse:Boolean=false):Long =
        FrameKernel.timeValueNative(if(inverse)1 else 0,value,1_000_000,scale,offset)
    fun overlap(rs:Long,re:Long,ts:Long,te:Long,scale:Double,offset:Long):LongArray? =
        FrameKernel.timeOverlapNative(rs,re,ts,te,scale,offset)
}
