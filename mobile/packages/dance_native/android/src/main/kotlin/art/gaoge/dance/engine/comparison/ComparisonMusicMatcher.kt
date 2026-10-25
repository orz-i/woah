package art.gaoge.dance.engine.comparison

import kotlin.math.*

internal data class MusicCandidate(val offsetUs: Long,val score: Double,
    val consistentWindows: Long,val supportedSeconds: Double,val residualMs: Double)
internal data class MusicAlignmentResult(val status: String,val message: String,val candidates: List<MusicCandidate>)

/** A constant MUSIC offset only. Motion, frame rates and playback speed are not matching inputs. */
internal object ComparisonMusicMatcher {
    private data class Landmark(val hash: Int,val timeUs: Long)
    private fun landmarks(f: MusicFeatures): List<Landmark> {
        val result=ArrayList<Landmark>()
        for (i in f.frames.indices step 4) {
            val row=f.frames[i]
            for (anchor in row.peaks.take(3)) {
                var fanout=0
                for (distance in intArrayOf(8,16,28,44,64)) {
                    val other=f.frames.getOrNull(i+distance) ?: continue
                    val target=other.peaks.firstOrNull() ?: continue
                    result.add(Landmark(((anchor/2) shl 16) or ((target/2) shl 8) or (distance/2),row.timeUs))
                    if (++fanout==4) break
                }
            }
        }
        return result
    }
    private fun check(current: ()->Boolean) { if (!current()) throw ComparisonAlignmentCancelled() }
    fun match(teacher: MusicFeatures,learner: MusicFeatures,current: ()->Boolean={true}): MusicAlignmentResult {
        if (teacher.frames.size<200 || learner.frames.size<200)
            return MusicAlignmentResult("no_match","共同音乐太短或声音不足，请手动校准。",emptyList())
        val t=landmarks(teacher);val l=landmarks(learner)
        if (t.map { it.hash }.distinct().size<12 || l.map { it.hash }.distinct().size<12)
            return MusicAlignmentResult("no_match","声音缺少可辨认的变化，请手动校准。",emptyList())
        val index=HashMap<Int,MutableList<Long>>()
        t.forEach { row -> val bucket=index.getOrPut(row.hash) { ArrayList() };if (bucket.size<80) bucket.add(row.timeUs) }
        val histogram=HashMap<Long,Int>()
        l.forEachIndexed { i,row ->
            if (i%256==0) check(current)
            val a=row.hash ushr 16;val b=(row.hash ushr 8) and 255;val dt=row.hash and 255
            for (da in -1..1) for (db in -1..1) for (d in -1..1) {
                if (a+da !in 0..255 || b+db !in 0..255 || dt+d !in 0..255) continue
                val key=((a+da) shl 16) or ((b+db) shl 8) or (dt+d)
                index[key]?.forEach { time ->
                    val lag=round((row.timeUs-time)/20_000.0).toLong()*20_000
                    histogram[lag]=(histogram[lag] ?: 0)+1
                }
            }
        }
        val offsets=ArrayList<Long>()
        for ((offset,votes) in histogram.entries.sortedByDescending { it.value }) {
            if (votes<6) break
            if (offsets.none { abs(it-offset)<180_000 }) offsets.add(offset)
            if (offsets.size>=8) break
        }
        val candidates=offsets.mapNotNull { offset ->
            check(current)
            var bestOffset=offset;var best=-1.0
            for (delta in -120_000L..120_000L step 10_000L) {
                val value=score(teacher,learner,offset+delta)
                if (value>best) { best=value;bestOffset=offset+delta }
            }
            val start=max(teacher.startUs,learner.startUs-bestOffset)
            val end=min(teacher.endUs,learner.endUs-bestOffset)
            if (end-start<2_000_000 || best<.35) return@mapNotNull null
            val window=(end-start)/3
            val estimates=ArrayList<Long>()
            for (i in 0..2) {
                var localOffset=bestOffset;var localScore=-1.0
                for (delta in -100_000L..100_000L step 10_000L) {
                    val value=score(teacher,learner,bestOffset+delta,start+i*window,start+(i+1)*window)
                    if (value>localScore) { localScore=value;localOffset=bestOffset+delta }
                }
                if (localScore>=.45) estimates.add(localOffset)
            }
            val residual=if (estimates.isEmpty()) 1e6 else estimates.maxOf { abs(it-bestOffset)/1000.0 }
            MusicCandidate(bestOffset,best,estimates.count { abs(it-bestOffset)<=30_000 }.toLong(),
                (end-start)/1e6,residual)
        }.sortedByDescending { it.score }.fold(ArrayList<MusicCandidate>()) { selected,item ->
            if (selected.none { abs(it.offsetUs-item.offsetUs)<150_000 }) selected.add(item)
            selected
        }.take(3)
        val best=candidates.firstOrNull()
            ?: return MusicAlignmentResult("no_match","没有找到可靠的共同音乐，可以手动校准。",emptyList())
        if (best.score<.64) return MusicAlignmentResult("no_match","共同音乐证据不足，请试听候选或手动校准。",candidates)
        if (best.residualMs>60)
            return MusicAlignmentResult("inconsistent","不同片段的音乐位置不一致，可能存在变速或剪辑，请手动选择对比位置。",candidates)
        if (best.consistentWindows<3)
            return MusicAlignmentResult("ambiguous","部分乐段可以对齐，请试听候选或手动校准。",candidates)
        if (candidates.drop(1).any { it.score>=best.score-.08 && it.score>.60 })
            return MusicAlignmentResult("ambiguous","找到多个相似乐段，请试听并选择对应位置。",candidates)
        return MusicAlignmentResult("matched","已按共同音乐对齐，可随时手动校准。",candidates)
    }
    private fun score(t: MusicFeatures,l: MusicFeatures,offset: Long,
        from: Long=max(t.startUs,l.startUs-offset),until: Long=min(t.endUs,l.endUs-offset)): Double {
        if (until-from<500_000) return -1.0
        val first=ceil((from-t.startUs)/10_000.0).toInt().coerceAtLeast(0)
        val last=min(t.frames.size,ceil((until-t.startUs)/10_000.0).toInt())
        var value=0.0;var count=0
        // Score at 20ms intervals; full precision is retained in source-time offsets.
        for (i in first until last step 2) {
            val j=round((t.frames[i].timeUs+offset-l.startUs)/10_000.0).toInt()
            if (j !in l.frames.indices) continue
            val a=t.frames[i];val b=l.frames[j]
            var spectral=0.0;var onset=0.0
            for (band in 0..15) { spectral+=a.spectrum[band]*b.spectrum[band];onset+=a.onset[band]*b.onset[band] }
            value+=.75*spectral+.25*onset;count++
        }
        return if (count>=25) (value/count).coerceIn(0.0,1.0) else -1.0
    }
}
