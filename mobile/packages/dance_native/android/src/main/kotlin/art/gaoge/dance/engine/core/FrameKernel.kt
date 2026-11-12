package art.gaoge.dance.engine.core

import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import java.nio.ByteBuffer

/** Transport adapter only; the production and JVM paths load the same Rust core. */
internal object FrameKernel {
    external fun musicCreateNative(origin:Long):Long
    external fun musicDestroyNative(id:Long)
    external fun musicPushNative(id:Long,values:FloatArray,mode:Int,rate:Int,channels:Int,time:Long):Int
    external fun musicFinishNative(id:Long):ByteArray
    external fun musicAlignNative(reference:ByteArray,target:ByteArray,current:()->Boolean):DoubleArray
    external fun timeValueNative(mode:Int,value:Long,ticks:Int,scale:Double,offset:Long):Long
    external fun timeOverlapNative(rs:Long,re:Long,ts:Long,te:Long,scale:Double,offset:Long):LongArray?
    external fun timeInverseNative(scale:Double,offset:Long):Long
    external fun frameIndexNative(pts:LongArray,time:Long,start:Long,end:Long,mode:Int):Int
    external fun paneNative(width:Int,height:Int,gap:Int,reference:Boolean,sideBySide:Boolean):IntArray
    external fun fitNative(pane:IntArray,aspect:Double):IntArray
    external fun quadNative(pane:IntArray,fit:IntArray,zoom:Double,panX:Double,panY:Double):FloatArray
    external fun projectFaceRegionNative(current:FloatArray,previous:FloatArray,seed:FloatArray,ageUs:Long):FloatArray?
    external fun faceRegionNative(row:FloatArray,view:FloatArray?,size:Float,yaw:Float):FloatArray?
    external fun headFallbackNative(person:FloatArray):FloatArray?
    external fun currentHeadRegionNative(pixels:ByteArray?,width:Int,height:Int,transform:FloatArray,person:FloatArray,seed:FloatArray?):FloatArray?
    external fun currentFaceRegionNative(pixels:ByteArray?,width:Int,height:Int,transform:FloatArray,person:FloatArray,region:FloatArray):FloatArray?
    external fun maskHeadNative(pixels:ByteArray,width:Int,height:Int,transform:FloatArray,person:FloatArray,seed:FloatArray,previous:FloatArray?,expansion:Float):FloatArray?

    external fun faceHeadPlanNative(person:FloatArray,width:Int,height:Int):FloatArray?
    external fun faceCenterNative(row:FloatArray):FloatArray
    external fun faceSelectNative(rows:FloatArray,view:FloatArray,ax:Float,ay:Float,maximum:Float,separation:Float):FloatArray?
    external fun faceOwnedNative(rows:FloatArray,source:FloatArray,size:Float,person:FloatArray):IntArray
    external fun currentFaceOwnedNative(rows:FloatArray,source:FloatArray,size:Float,person:FloatArray,pixels:ByteArray?,width:Int,height:Int,transform:FloatArray?):IntArray

    external fun gridFaceInputNative(rgba:ByteBuffer,width:Int,height:Int,stride:Int):FloatArray
    external fun gridFaceDecodeNative(boxes:FloatArray,scores:FloatArray,width:Int,height:Int,minimum:Float):FloatArray

    init {
        System.loadLibrary("frame_kernel_jni")
        check(abiVersion() == 1) { "Unsupported FrameKernel ABI" }
    }

    private external fun replayPlanNative(scope:Int,flags:Int,anchor:Long,last:Long,target:Long,step:Long):LongArray
    data class ReplayPlan(val required:Boolean,val reset:Boolean,val startUs:Long,val stepUs:Long,val regularCount:Int,val appendTarget:Boolean,val targetUs:Long) {
        val count:Int get()=regularCount+if(appendTarget)1 else 0
        fun time(index:Int):Long {require(index in 0 until count);return if(index<regularCount)startUs+index*stepUs else targetUs}
    }
    fun replayPlan(scope:Int,hero:Boolean,namedTargets:Boolean,anchorUs:Long?,lastUs:Long?,targetUs:Long,stepUs:Long):ReplayPlan {
        val v=replayPlanNative(scope,(if(hero)1 else 0) or (if(namedTargets)2 else 0),anchorUs?:-1L,lastUs?:-1L,targetUs,stepUs)
        return ReplayPlan(v[0]!=0L,v[1]!=0L,v[2],v[3],v[4].toInt(),v[5]!=0L,v[6])
    }

    private external fun classCreateNative(values:FloatArray,misses:Int,flags:Int):Long
    private external fun classSummaryNative(id:Long):IntArray
    private external fun classDestroyNative(id:Long)
    private external fun classResetNative(id:Long)
    private external fun classUpdateNative(id:Long,values:FloatArray,offsets:LongArray,pixels:ByteArray,roots:LongArray):IntArray
    class ClassSession(values:FloatArray,misses:Int,flags:Int):AutoCloseable {
        private var id=classCreateNative(values,misses,flags)
        @Synchronized fun update(values:FloatArray,offsets:LongArray,pixels:ByteArray,roots:LongArray):IntArray {
            check(id!=0L);return classUpdateNative(id,values,offsets,pixels,roots)
        }
        @Synchronized fun summary():IntArray {check(id!=0L);return classSummaryNative(id)}
        @Synchronized fun reset(){check(id!=0L);classResetNative(id)}
        @Synchronized override fun close(){if(id!=0L){classDestroyNative(id);id=0L}}
    }

    private external fun trackingCreateNative(scalars:FloatArray,integers:IntArray):Long
    private external fun trackingDestroyNative(id:Long)
    private external fun trackingReserveNative(id:Long,minimum:Long)
    private external fun trackingSelectionNative(id:Long,protected:LongArray,body:LongArray,face:LongArray,dormancy:Boolean)
    private external fun trackingStepNative(id:Long,action:Int,timestampUs:Long,values:FloatArray,offsets:LongArray,pixels:ByteArray,assigned:LongArray):ByteArray
    class TrackingSession(scalars:FloatArray,integers:IntArray):AutoCloseable {
        private var id=trackingCreateNative(scalars,integers)
        @Synchronized fun select(protected:LongArray,body:LongArray,face:LongArray,dormancy:Boolean) {
            check(id!=0L);trackingSelectionNative(id,protected,body,face,dormancy)
        }
        @Synchronized fun step(action:Int,timestampUs:Long,values:FloatArray,offsets:LongArray,pixels:ByteArray,assigned:LongArray):ByteArray {
            check(id!=0L);return trackingStepNative(id,action,timestampUs,values,offsets,pixels,assigned)
        }
        @Synchronized fun reserveNextIdentity(minimum:Long){check(id!=0L);trackingReserveNative(id,minimum)}
        @Synchronized override fun close() {if(id!=0L){trackingDestroyNative(id);id=0L}}
    }

    private external fun workCanReplaceNative(state: Int): Int
    private external fun workTransitionNative(state: Int, event: Int): Int
    class WorkLifecycle {
        private var state = 0
        val acceptingProgress: Boolean get() = state <= 1
        val canAdmitReplacement: Boolean get() = workCanReplaceNative(state) == 1
        fun requestCancel(): Boolean {
            state = workTransitionNative(state, 0)
            return state == 1
        }
        fun observe(outcome: String): Boolean {
            val event = when(outcome) {
                "completed" -> 1; "failed" -> 2; "cancelled" -> 3
                else -> return false
            }
            state = workTransitionNative(state, event)
            return true
        }
        fun outcomeAfterRelease(): String = terminalName(workTransitionNative(state, 4))
        fun release(): String {
            state = workTransitionNative(state, 4)
            return terminalName(state)
        }
        private fun terminalName(value: Int): String = when(value) {
            5 -> "completed"; 6 -> "failed"; 7 -> "cancelled"
            else -> error("Shared worker outcome is not terminal")
        }
    }

    private external fun regionStepNative(values: FloatArray, clock: LongArray, raw: FloatArray,
        anchor: FloatArray, pts: Long, kind: Int, flags: Int): FloatArray
    private external fun anchorTranslationNative(previous: FloatArray, current: FloatArray): FloatArray

    private external fun continueRegionNative(values: FloatArray, clock: LongArray,
        current: FloatArray, pts: Long, kind: Int): FloatArray
    private external fun regionContextProbeUsNative(pts: Long): Long
    fun regionContextProbeUs(pts: Long): Long? = regionContextProbeUsNative(pts).takeIf { it >= 0 }
    class RegionContinuation {
        private val values = FloatArray(1024 * 8)
        private val clock = LongArray(1025)
        fun reset() { clock[0] = 0 }
        fun step(raw: FloatArray, anchor: FloatRect, pts: Long, kind: Int): FloatArray =
            continueRegionNative(values, clock, raw + anchor.values(), pts, kind)
    }

    class RegionState {
        private val values = FloatArray(12)
        private val clock = LongArray(2)
        fun step(raw: FloatArray, anchor: FloatRect, pts: Long, kind: Int,
            observed: Boolean = true, trustedCenter: Boolean = false, canonical: Boolean = false,
            translationOnly: Boolean = false, coverCurrent: Boolean = false): FloatArray {
            val flags = (if (observed) 1 else 0) or (if (trustedCenter) 2 else 0) or
                (if (canonical) 4 else 0) or (if (translationOnly) 8 else 0) or (if (coverCurrent) 16 else 0)
            return regionStepNative(values, clock, raw, anchor.values(), pts, kind, flags)
        }
        fun snapshot(): FloatArray = values.copyOf()
    }
    fun anchorTranslation(previous: FloatRect, current: FloatRect): FloatArray =
        anchorTranslationNative(previous.values(), current.values())

    private external fun abiVersion(): Int
    private external fun associationDefaultsNative(): FloatArray
    private external fun associationScoreNative(values: FloatArray, historyAge: Long, flags: Int, weights: FloatArray): Float
    private external fun sceneTargetsNative(roots: LongArray, body: LongArray, face: LongArray,
        current: LongArray, hasHero: Boolean, hero: Long): LongArray
    data class SceneTargets(val body: Set<Int>, val faceOnly: Set<Int>)
    fun sceneTargets(roots: Set<Int>, body: Set<Int>, face: Set<Int>, current: Set<Int>, hero: Int?): SceneTargets {
        val targets=sceneTargetsNative(roots.ids(),body.ids(),face.ids(),current.ids(),hero!=null,hero?.toLong()?:0)
            .asList().chunked(2)
        return SceneTargets(targets.filter { it[1] and 1L != 0L }.map {it[0].toInt()}.toSet(),
            targets.filter { it[1] == 2L }.map {it[0].toInt()}.toSet())
    }
    private external fun sceneScopeNative(roots: LongArray, body: LongArray, face: LongArray, hasHero: Boolean, hero: Long): Int
    private external fun compositorTargetsNative(selected: LongArray, covered: LongArray, explicit: LongArray): LongArray
    private external fun currentHeroIndexNative(boxes: FloatArray, observed: FloatArray?): Int
    private external fun boxFilterStepNative(values: FloatArray, clock: LongArray, operation: Int, bounds: FloatArray?, pts: Long, factor: Float): FloatArray
    private external fun invertMatrix4Native(values: FloatArray): FloatArray?
    private external fun letterboxNative(width: Int, height: Int, outputWidth: Int, outputHeight: Int): FloatArray
    private external fun normalizePixelsNative(input: ByteBuffer, width: Int, height: Int, rowStride: Int,
        channels: Int, red: Int, green: Int, blue: Int, flipRows: Boolean, flipColumns: Boolean,
        interleaved: Boolean, scale: Float, bias: Float, output: FloatArray)
    private external fun resizeRgbaNative(input: ByteBuffer, width: Int, height: Int, stride: Int,
        output: ByteBuffer, outputWidth: Int, outputHeight: Int, fill: Int)
    private external fun yuv420Native(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, metadata: IntArray, output: ByteBuffer)
    private external fun matchCostsNative(costs: FloatArray, rows: Int, columns: Int, maxCost: Float): IntArray
    private external fun matchRectsNative(rows: FloatArray, columns: FloatArray, maxCost: Float): IntArray
    private external fun followResetNative(values: FloatArray, clock: LongArray)
    private external fun followStepNative(values: FloatArray, clock: LongArray, target: FloatArray?, pts: Long,
        sourceAspect: Float, outputAspect: Float, zoom: Float, smooth: Float): FloatArray
    private external fun fitEvenRatioNative(width: Int, height: Int, maxHeight: Int, ratioWidth: Int, ratioHeight: Int): IntArray?
    private external fun maskBytesNative(input: FloatArray): ByteArray
    private external fun decodeInstancesNative(head: FloatArray, proto: FloatArray, spec: IntArray, transform: FloatArray,
        thresholds: FloatArray, detections: FloatArray, masks: ByteBuffer, metrics: LongArray): Int
    private external fun needsFaceProtectionNative(body: Boolean, face: Boolean): Boolean
    private external fun faceProtectionIdsNative(body: LongArray, face: LongArray): LongArray
    private external fun detailViewsNative(width: Int, height: Int): FloatArray
    private external fun detailCompleteNative(box: FloatArray, view: FloatArray, width: Int, height: Int): Boolean
    private external fun iouNative(a: FloatArray, b: FloatArray): Float
    private external fun reprojectMaskNative(mask: ByteBuffer, output: ByteBuffer, local: FloatArray, scene: FloatArray, view: FloatArray)

    fun needsFaceProtection(body: Boolean, face: Boolean): Boolean = needsFaceProtectionNative(body, face)

    fun matchCosts(costs: FloatArray, rows: Int, columns: Int, maxCost: Float): List<Pair<Int, Int>> =
        matchCostsNative(costs, rows, columns, maxCost).asList().chunked(2).map { it[0] to it[1] }
    fun matchRects(rows: List<FloatRect>, columns: List<FloatRect>, maxCost: Float): List<Pair<Int, Int>> {
        fun flatten(rects: List<FloatRect>): FloatArray = FloatArray(rects.size * 4).also { result ->
            rects.forEachIndexed { i, r -> r.values().copyInto(result, i * 4) }
        }
        return matchRectsNative(flatten(rows), flatten(columns), maxCost).asList().chunked(2).map { it[0] to it[1] }
    }

    fun followReset(values: FloatArray, clock: LongArray) = followResetNative(values, clock)
    fun followStep(values: FloatArray, clock: LongArray, target: FloatRect?, pts: Long,
        sourceAspect: Float, outputAspect: Float, zoom: Float, smooth: Float): FloatRect {
        val result = followStepNative(values, clock, target?.values(), pts, sourceAspect, outputAspect, zoom, smooth)
        return FloatRect(result[0], result[1], result[2], result[3])
    }
    fun fitEvenRatio(width: Int, height: Int, maxHeight: Int, ratioWidth: Int, ratioHeight: Int): Pair<Int, Int>? =
        fitEvenRatioNative(width, height, maxHeight, ratioWidth, ratioHeight)?.let { it[0] to it[1] }

    fun maskBytes(input: FloatArray): ByteArray = maskBytesNative(input)

    fun decodeInstances(head: FloatArray, proto: FloatArray, spec: IntArray, transform: FloatArray,
        thresholds: FloatArray, detections: FloatArray, masks: ByteBuffer, metrics: LongArray): Int =
        decodeInstancesNative(head, proto, spec, transform, thresholds, detections, masks, metrics)

    fun faceProtectionIds(body: Set<Int>, face: Set<Int>): Set<Int> =
        faceProtectionIdsNative(body.map { it.toLong() }.toLongArray(), face.map { it.toLong() }.toLongArray())
            .map { it.toInt() }.toSet()

    fun detailViews(width: Int, height: Int): List<FloatRect> =
        detailViewsNative(width, height).asList().chunked(4).map { FloatRect(it[0], it[1], it[2], it[3]) }

    fun detailComplete(box: FloatRect, view: FloatRect, width: Int, height: Int): Boolean =
        detailCompleteNative(box.values(), view.values(), width, height)

    fun iou(a: FloatRect, b: FloatRect): Float = iouNative(a.values(), b.values())

    fun reprojectMask(mask: ByteBuffer, output: ByteBuffer, local: ModelCoordinateMapper, scene: ModelCoordinateMapper, view: FloatRect) =
        reprojectMaskNative(mask, output, local.values(), scene.values(), view.values())

    fun letterbox(width: Int, height: Int, outputWidth: Int, outputHeight: Int) = letterboxNative(width, height, outputWidth, outputHeight)
    data class AssociationWeights(val bounds: Float, val mask: Float, val motion: Float, val direction: Float, val gating: Float)
    val associationDefaults: AssociationWeights by lazy {
        val v = associationDefaultsNative()
        AssociationWeights(v[0], v[1], v[2], v[3], v[4])
    }
    private external fun reservationReleaseNative(flags: Int, owners: Int, box: Float, mask: Float): Boolean
    fun reservationRelease(strict: Boolean, durableNonBody: Boolean, owners: Int, direct: Boolean, box: Float, mask: Float): Boolean =
        reservationReleaseNative((if (strict) 1 else 0) or (if (durableNonBody) 2 else 0) or (if (direct) 4 else 0), owners, box, mask)
    fun associationScore(values: FloatArray, historyAge: Long, flags: Int, weights: FloatArray): Float =
        associationScoreNative(values, historyAge, flags, weights)
    const val SCENE_BODY = 1
    const val SCENE_FACE = 2
    const val SCENE_OTHER_FACES = 4
    fun sceneScope(roots: Set<Int>, body: Set<Int>, face: Set<Int>, hero: Int?): Int =
        sceneScopeNative(roots.ids(), body.ids(), face.ids(), hero != null, hero?.toLong() ?: 0)
    fun compositorTargets(selected: Set<Int>, covered: Set<Int>, explicit: Set<Int>): Set<Int> =
        compositorTargetsNative(selected.ids(), covered.ids(), explicit.ids()).map { it.toInt() }.toSet()
    fun currentHeroIndex(boxes: List<FloatRect>, observed: FloatRect?): Int =
        currentHeroIndexNative(boxes.flatMap { it.values().asList() }.toFloatArray(), observed?.values())
    private fun Set<Int>.ids() = map { it.toLong() }.toLongArray()
    fun boxFilterStep(values: FloatArray, clock: LongArray, operation: Int, bounds: FloatRect? = null, pts: Long = -1, factor: Float = 1f) =
        boxFilterStepNative(values, clock, operation, bounds?.values(), pts, factor)
    fun invertMatrix4(values: FloatArray) = invertMatrix4Native(values)
    fun yuv420(y: ByteBuffer, u: ByteBuffer, v: ByteBuffer, metadata: IntArray, output: ByteBuffer) = yuv420Native(y, u, v, metadata, output)

    fun normalizePixels(input: ByteBuffer, width: Int, height: Int, rowStride: Int, output: FloatArray,
        channels: Int = 4, red: Int = 0, green: Int = 1, blue: Int = 2,
        flipRows: Boolean = false, flipColumns: Boolean = false, interleaved: Boolean = false,
        scale: Float = 1f / 255f, bias: Float = 0f) =
        normalizePixelsNative(input, width, height, rowStride, channels, red, green, blue,
            flipRows, flipColumns, interleaved, scale, bias, output)

    fun resizeRgba(input: ByteBuffer, width: Int, height: Int, stride: Int, output: ByteBuffer,
        outputWidth: Int, outputHeight: Int, fill: Int = 114) =
        resizeRgbaNative(input, width, height, stride, output, outputWidth, outputHeight, fill)

    private fun FloatRect.values() = floatArrayOf(left, top, right, bottom)
    private fun ModelCoordinateMapper.values() = floatArrayOf(scale, padLeft, padTop, srcWidth.toFloat(), srcHeight.toFloat(), modelInputSize.toFloat(), protoSize.toFloat())
}
