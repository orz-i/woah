package art.gaoge.dance.engine.comparison

import java.util.ArrayDeque

/** A bounded submitted/output ledger: a codec must not silently omit or retime a frame. */
internal class ComparisonExportFrames {
    private val pending=ArrayDeque<Long>()
    private var last=-1L
    var submitted=0L;private set
    var encoded=0L;private set
    fun submit(ptsUs: Long) {
        require(ptsUs>=0 && ptsUs>last)
        check(pending.size<256) { "编码器输出未能跟上画面提交" }
        pending.addLast(ptsUs);last=ptsUs;submitted++
    }
    fun accept(ptsUs: Long) {
        check(pending.peekFirst()==ptsUs) { "编码器遗漏或重排了参考帧" }
        pending.removeFirst();encoded++
    }
    fun finish() {
        check(submitted>0 && pending.isEmpty() && submitted==encoded) { "编码器未输出完整参考帧" }
    }
}
