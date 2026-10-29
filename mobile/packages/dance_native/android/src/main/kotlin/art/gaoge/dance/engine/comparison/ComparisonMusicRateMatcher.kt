package art.gaoge.dance.engine.comparison

import kotlin.math.*

/** Pairwise, bounded affine music search; no database, pose features or tempo-grid assumption. */
internal object ComparisonMusicRateMatcher {
    private val distances=intArrayOf(8,16,28,44)
    private data class Seed(val scale: Double,val offset: Long,val pitch: Boolean,val votes: Int)
    private class Votes {
        var count=0;var x=0.0;var y=0.0;var xx=0.0;var xy=0.0
        var first=Double.POSITIVE_INFINITY;var last=Double.NEGATIVE_INFINITY
        fun add(reference: Long,target: Long) {
            val a=reference/1e6;val b=target/1e6
            count++;x+=a;y+=b;xx+=a*a;xy+=a*b;first=min(first,a);last=max(last,a)
        }
        fun seed(around: Double,pitch: Boolean): Seed? {
            val denominator=xx-x*x/count
            if (count<12 || last-first<4 || denominator<1e-6) return null
            val scale=(xy-x*y/count)/denominator
            if (scale !in .48..2.02 || abs(scale-around)>.04) return null
            val bounded=scale.coerceIn(.5,2.0)
            return Seed(bounded,((y-bounded*x)/count*1e6).roundToLong(),pitch,count)
        }
    }
    private fun check(current: ()->Boolean) { if (!current()) throw ComparisonAlignmentCancelled() }
    private fun key(a: Int,b: Int,d: Int) = (a shl 16) or (b shl 8) or d

    fun match(t: MusicFeatures,l: MusicFeatures,current: ()->Boolean): MusicAlignmentResult? {
        if (min(t.endUs-t.startUs,l.endUs-l.startUs)<6_000_000 ||
            t.frames.size<600 || l.frames.size<600) return null
        val index=HashMap<Int,MutableList<Long>>()
        for (i in t.frames.indices step 4) {
            val row=t.frames[i]
            for (p in row.peaks.take(3)) for ((d,dt) in distances.withIndex()) {
                val other=t.frames.getOrNull(i+dt)?.peaks?.firstOrNull() ?: continue
                val bucket=index.getOrPut(key(p/4,other/4,d)) { ArrayList() }
                if (bucket.size<80) bucket.add(row.timeUs)
            }
        }
        if (index.size<12) return null
        val seeds=ArrayList<Seed>()
        // A coarse scale is only a candidate generator. Regression and local music
        // refinement estimate a continuous rate, including non-preset slow versions.
        for (step in 0..60) {
            check(current)
            val scale=.5+step*.025
            for (pitch in listOf(false,true)) {
                if (pitch && step==20) continue
                val frequencyScale=if (pitch) scale else 1.0
                val histogram=HashMap<Long,Votes>()
                for (i in l.frames.indices step 8) {
                    if (i%1024==0) check(current)
                    val row=l.frames[i]
                    for (p in row.peaks.take(3)) for ((d,dt) in distances.withIndex()) {
                        val other=l.frames.getOrNull(i+(dt*scale).roundToInt())?.peaks?.firstOrNull() ?: continue
                        val a=(p*frequencyScale/4).roundToInt();val b=(other*frequencyScale/4).roundToInt()
                        for (da in -1..1) for (db in -1..1) {
                            if (a+da !in 0..255 || b+db !in 0..255) continue
                            index[key(a+da,b+db,d)]?.forEach { reference ->
                                val lag=((row.timeUs-scale*reference)/100_000).roundToLong()
                                histogram.getOrPut(lag) { Votes() }.add(reference,row.timeUs)
                            }
                        }
                    }
                }
                histogram.values.sortedByDescending { it.count }.take(4)
                    .mapNotNullTo(seeds) { it.seed(scale,pitch) }
            }
        }
        val coarse=seeds.mapNotNull { seed ->
            check(current)
            val score=score(t,l,seed,100_000)
            if (score<.35) null else seed to score
        }.sortedByDescending { it.second }.fold(ArrayList<Pair<Seed,Double>>()) { list,item ->
            if (list.none { abs(it.first.scale-item.first.scale)<.003 &&
                    abs(it.first.offset-item.first.offset)<120_000 && it.first.pitch==item.first.pitch }) list.add(item)
            list
        }.take(8)
        val results=coarse.mapNotNull { refine(t,l,it.first,current) }
            .sortedByDescending { it.score }.fold(ArrayList<MusicCandidate>()) { list,item ->
                if (list.none { abs(it.timeScale-item.timeScale)<.003 && abs(it.offsetUs-item.offsetUs)<150_000 }) list.add(item)
                list
            }.take(3)
        val best=results.firstOrNull() ?: return null
        if (best.score<.64 || best.consistentWindows<4 || best.residualMs>35 || best.supportedSeconds<6) {
            return MusicAlignmentResult("inconsistent","未找到稳定的音乐速率，请用前后两组音乐点手动校准。",results)
        }
        if (results.drop(1).any { it.score>=best.score-.08 && it.consistentWindows>=4 })
            return MusicAlignmentResult("ambiguous","找到多个相似乐段，已保留最佳位置，可手动校准。",results)
        return MusicAlignmentResult("matched","已按共同音乐的速率和位置对齐。",results)
    }

    private fun range(t: MusicFeatures,l: MusicFeatures,s: Seed): Pair<Long,Long> =
        max(t.startUs,ceil((l.startUs-s.offset)/s.scale).toLong()) to
            min(t.endUs,floor((l.endUs-s.offset)/s.scale).toLong())

    private fun score(t: MusicFeatures,l: MusicFeatures,s: Seed,stepUs: Long,
        from: Long=range(t,l,s).first,until: Long=range(t,l,s).second): Double {
        if (until-from<500_000) return -1.0
        val start=max(from,range(t,l,s).first);val end=min(until,range(t,l,s).second)
        var sum=0.0;var count=0
        var time=start
        val shift=-ln(s.scale)/ln(2.0)*8
        while (time<end) {
            val i=((time-t.startUs)/10_000.0).roundToInt()
            val j=((time*s.scale+s.offset-l.startUs)/10_000.0).roundToInt()
            if (i in t.frames.indices && j in l.frames.indices) {
                val a=t.frames[i];val b=l.frames[j]
                var spectral=0.0;var onset=0.0
                for (band in 0..15) onset+=a.onset[band]*b.onset[band]
                if (s.pitch) {
                    if (a.pitchSpectrum.size!=48 || b.pitchSpectrum.size!=48) return -1.0
                    var na=0.0;var nb=0.0
                    for (band in 0..47) {
                        val p=band+shift;val k=floor(p).toInt()
                        if (k !in 0..46) continue
                        val f=p-k;val x=a.pitchSpectrum[band].toDouble()
                        val y=b.pitchSpectrum[k]*(1-f)+b.pitchSpectrum[k+1]*f
                        spectral+=x*y;na+=x*x;nb+=y*y
                    }
                    spectral/=sqrt(na*nb).coerceAtLeast(1e-8)
                    sum+=.9*spectral+.1*onset
                } else {
                    for (band in 0..15) spectral+=a.spectrum[band]*b.spectrum[band]
                    sum+=.75*spectral+.25*onset
                }
                count++
            }
            time+=stepUs
        }
        return if (count<5) -1.0 else (sum/count).coerceIn(0.0,1.0)
    }

    private fun refine(t: MusicFeatures,l: MusicFeatures,initial: Seed,current: ()->Boolean): MusicCandidate? {
        var s=initial
        var pairs=emptyList<Pair<Double,Double>>()
        repeat(3) {
            check(current)
            val (start,end)=range(t,l,s)
            if (end-start<6_000_000) return null
            pairs=(0..4).mapNotNull { k ->
                val center=start+(end-start)*(2*k+1)/10
                val half=min(1_000_000L,(end-start)/12)
                var best=-1.0;var deltaBest=0L
                for (delta in -150_000L..150_000L step 10_000L) {
                    val v=score(t,l,s.copy(offset=s.offset+delta),40_000,center-half,center+half)
                    if (v>best) { best=v;deltaBest=delta }
                }
                if (best<.50 || abs(deltaBest)>=150_000) null
                else center/1e6 to (center*s.scale+s.offset+deltaBest)/1e6
            }
            if (pairs.size<4) return null
            val mx=pairs.map { it.first }.average();val my=pairs.map { it.second }.average()
            val denominator=pairs.sumOf { (it.first-mx).pow(2) }
            val scale=pairs.sumOf { (it.first-mx)*(it.second-my) }/denominator
            if (scale !in .495..2.005) return null
            val bounded=scale.coerceIn(.5,2.0)
            s=s.copy(scale=bounded,offset=((my-bounded*mx)*1e6).roundToLong())
        }
        var residual=pairs.maxOf { abs(it.second-(s.scale*it.first+s.offset/1e6))*1000 }
        val (start,end)=range(t,l,s)
        var verified=0L
        // These interleaved windows were NOT the local anchors used for fitting.
        // A tempo jump or a repeated-beat coincidence must also survive holdout checks.
        for (k in 1..4) {
            check(current)
            val center=start+(end-start)*k/5
            val half=min(500_000L,(end-start)/24)
            var best=-1.0;var deltaBest=0L
            for (delta in -100_000L..100_000L step 10_000L) {
                val v=score(t,l,s.copy(offset=s.offset+delta),20_000,center-half,center+half)
                if (v>best) { best=v;deltaBest=delta }
            }
            if (best>=.5 && abs(deltaBest)<=30_000) verified++
            residual=max(residual,abs(deltaBest)/1000.0)
        }
        return MusicCandidate(s.offset,score(t,l,s,20_000),verified,
            (end-start)/1e6,residual,s.scale)
    }
}
