package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.diagnostics.NativeDiagnostics
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.inference.PersonDetection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class TrackingConfig(
    val maxMissedFrames: Int = 15,
    val postOcclusionGraceFrames: Int = 10,
    val minMatchScore: Float = 0.20f,
    val bboxIouWeight: Float = art.gaoge.dance.engine.core.FrameKernel.associationDefaults.bounds,
    val maskIouWeight: Float = art.gaoge.dance.engine.core.FrameKernel.associationDefaults.mask,
    val motionWeight: Float = art.gaoge.dance.engine.core.FrameKernel.associationDefaults.motion,
    val directionWeight: Float = art.gaoge.dance.engine.core.FrameKernel.associationDefaults.direction,
    val occlusionOverlapRatio: Float = 0.30f,
    val occlusionMaxDurationFrames: Int = 90,
    val associationAmbiguityMargin: Float = 0.05f,
    val kalmanGatingThreshold: Float = art.gaoge.dance.engine.core.FrameKernel.associationDefaults.gating,
    val minSceneMotionInliers: Int = 2,
    val sceneMotionTolerance: Float = 25.0f,
    val maxGlobalShift: Float = 300.0f,
    val enableSceneMotionCompensation: Boolean = false,
    val foregroundFootYMarginRatio: Float = 0.05f,
    val privacyOccluderErosionRadius: Int = 1
)

enum class OcclusionGroupState {
    ACTIVE_OVERLAP,
    REACQUIRING
}

data class OcclusionGroup(
    val trackIds: MutableSet<Int>,
    val startedAtUs: Long,
    var lastOverlapTimestampUs: Long = startedAtUs,
    var state: OcclusionGroupState = OcclusionGroupState.ACTIVE_OVERLAP,
    var reacquireFrames: Int = 0
)

enum class PrivacySelectionClass {
    SELECTED,
    UNSELECTED
}

/**
 * Fresh YOLO evidence whose privacy class is known even though its exact track
 * identity is intentionally left unresolved. This is only emitted for balanced
 * occlusion groups where one privacy class has been fully committed already and
 * every remaining track belongs to the other class.
 */
data class FreshPrivacyClassEvidence(
    val selectionClass: PrivacySelectionClass,
    val detectionIndex: Int,
    val detection: PersonDetection,
    val residualTrackIds: Set<Int>,
    val conservativeUnknown: Boolean = false
)

/**
 * Current-frame YOLO geometry that is strong enough to move a protected
 * FACE_ONLY render anchor, but intentionally not strong enough to commit track
 * identity. This is emitted only for reciprocal-best protected candidates where
 * absolute bbox/mask evidence passes and identity commitment was deferred by an
 * ambiguity margin. Consumers must treat it as motion-only evidence.
 */
data class ProtectedTrackMotionEvidence(
    val trackId: Int,
    val detectionIndex: Int,
    val detection: PersonDetection,
    val assignedScore: Float,
    val bboxIou: Float,
    val maskIou: Float,
    val timestampUs: Long
)

/**
 * Internal tracking representation enforcing strict separation between:
 * 1. Canonical observed segmentation (lastObservedMask, lastObservedBbox)
 * 2. Predicted motion state (currentPredictedBbox)
 * 3. Rendered privacy fallback mask (currentRenderMask)
 * 4. Separate counters for lostFrames, occludedFrames, and reacquireFrames.
 * 5. Current-frame fresh observation metadata (observedThisFrame, lastObservedFootY, currentObservedFootY).
 */
class InternalTrack(
    val id: Int,
    var lastObservedBbox: FloatRect,
    var lastObservedMask: NativeMask?,
    var currentPredictedBbox: FloatRect = lastObservedBbox,
    var currentRenderMask: NativeMask? = lastObservedMask,
    var confidence: Float,
    val kalman: KalmanFilter = KalmanFilter(),
    var state: TrackState = TrackState.ACTIVE,
    var lostFrames: Int = 0,
    var occludedFrames: Int = 0,
    var reacquireFrames: Int = 0,
    var framesSinceLastObservation: Int = 0,
    val occludedByTrackIds: MutableSet<Int> = mutableSetOf(),
    var occlusionMotionBbox: FloatRect? = null,
    var age: Int = 1,
    var observedThisFrame: Boolean = false,
    var observedOnPreviousFrame: Boolean = false,
    var hasFreshObservedMotion: Boolean = false,
    var freshObservedMotionDx: Float = 0f,
    var freshObservedMotionDy: Float = 0f,
    var lastObservedFootY: Float = lastObservedBbox.bottom,
    var currentObservedFootY: Float? = null,
    var lastReliableObservedMotionDx: Float = 0f,
    var lastReliableObservedMotionDy: Float = 0f,
    var lastReliableObservedMotionTimestampUs: Long = Long.MIN_VALUE,
    var offscreenDormant: Boolean = false
) {
    init {
        kalman.init(lastObservedBbox)
    }

    var missedFrames: Int
        get() = when (state) {
            TrackState.OCCLUDED -> occludedFrames
            TrackState.REACQUIRING -> reacquireFrames
            else -> lostFrames
        }
        set(value) {
            lostFrames = value
        }

    var bbox: FloatRect
        get() = currentPredictedBbox
        set(value) {
            currentPredictedBbox = value
        }

    var mask: NativeMask?
        get() = currentRenderMask
        set(value) {
            currentRenderMask = value
        }

    fun toTrackedPerson(): TrackedPerson {
        return TrackedPerson(
            id = id,
            bbox = currentPredictedBbox,
            mask = currentRenderMask,
            confidence = confidence,
            missedFrames = missedFrames,
            framesSinceLastObservation = framesSinceLastObservation,
            age = age,
            state = state,
            occludedByTrackIds = occludedByTrackIds.toSet(),
            observedThisFrame = observedThisFrame,
            footY = currentObservedFootY ?: lastObservedFootY
        )
    }

}

/** Native DTO transport for the shared Rust tracking session. */
class TrackManager(val config:TrackingConfig=TrackingConfig(),private val diagnosticsEnabled:Boolean=true):PersonTracker,AutoCloseable {
    private var session:FrameKernel.TrackingSession?=null
    private var protectedIds=setOf<Int>()
    private var bodyIds=setOf<Int>()
    private var faceIds=setOf<Int>()
    private var dormancy=false
    private var bodyEvidence=emptyList<FreshPrivacyClassEvidence>()
    private var faceEvidence=emptyList<FreshPrivacyClassEvidence>()
    private var motionEvidence=emptyList<ProtectedTrackMotionEvidence>()
    private var suppressed=emptySet<Int>()
    private var hard=emptyMap<Int,PrivacySelectionClass>()
    fun setProtectedTrackIds(ids:Set<Int>){protectedIds=ids.toSet();bodyIds=ids.toSet();faceIds=emptySet()}
    fun setIdentityProtectedTrackIds(ids:Set<Int>){protectedIds=ids.toSet()}
    fun setPrivacySelectedTrackIds(ids:Set<Int>){bodyIds=ids.toSet()}
    fun setFacePrivacySelectedTrackIds(ids:Set<Int>){faceIds=ids.toSet()}
    fun setPrivacyOffscreenDormancyEnabled(enabled:Boolean){dormancy=enabled}
    fun getFreshPrivacyClassEvidence()=bodyEvidence
    fun getFreshFacePrivacyClassEvidence()=faceEvidence
    fun getFreshProtectedTrackMotionEvidence()=motionEvidence
    fun getPrivacySuppressedSelectedTrackIds()=suppressed
    fun getHardPrivacyClassByDetectionIndex()=hard
    fun getMaxMissedFrames()=config.maxMissedFrames
    private fun owned():FrameKernel.TrackingSession {
        return session?:FrameKernel.TrackingSession(floatArrayOf(config.minMatchScore,config.bboxIouWeight,
            config.maskIouWeight,config.motionWeight,config.directionWeight,config.kalmanGatingThreshold,
            config.occlusionOverlapRatio,config.associationAmbiguityMargin,config.sceneMotionTolerance,
            config.maxGlobalShift,config.foregroundFootYMarginRatio),intArrayOf(config.maxMissedFrames,
            config.postOcclusionGraceFrames,config.occlusionMaxDurationFrames,config.minSceneMotionInliers,
            if(config.enableSceneMotionCompensation)1 else 0,config.privacyOccluderErosionRadius)).also{session=it}
    }
    override fun initialize(detections:List<PersonDetection>)=initializeWithAssignedIds(detections,emptyList())
    fun initializeWithAssignedIds(detections:List<PersonDetection>,assignedIds:List<Int>)=advance(0,0L,detections,assignedIds)
    override fun update(detections:List<PersonDetection>,timestampUs:Long)=advance(1,timestampUs,detections)
    override fun predict(timestampUs:Long)=advance(2,timestampUs,emptyList())
    fun predictWithoutObservation(timestampUs:Long)=advance(3,timestampUs,emptyList())
    @Synchronized private fun advance(action:Int,at:Long,detections:List<PersonDetection>,assigned:List<Int> = emptyList()):List<TrackedPerson> {
        val owner=owned()
        owner.select(protectedIds.map{it.toLong()}.toLongArray(),bodyIds.map{it.toLong()}.toLongArray(),faceIds.map{it.toLong()}.toLongArray(),dormancy)
        val values=FloatArray(detections.size*15);val offsets=LongArray(detections.size){-1L};val pool=ByteArrayOutputStream()
        detections.forEachIndexed{index,d->
            val mask=d.mask;val mapper=mask?.let{it.mapper?:ModelCoordinateMapper(max(1,it.originalWidth),max(1,it.originalHeight),640,it.width)}
            val row=floatArrayOf(d.bbox.left,d.bbox.top,d.bbox.right,d.bbox.bottom,d.confidence,d.footY,
                mask?.width?.toFloat()?:0f,mask?.height?.toFloat()?:0f,mapper?.scale?:1f,mapper?.padLeft?:0f,mapper?.padTop?:0f,
                mapper?.srcWidth?.toFloat()?:1f,mapper?.srcHeight?.toFloat()?:1f,mapper?.modelInputSize?.toFloat()?:1f,mapper?.protoSize?.toFloat()?:1f)
            row.copyInto(values,index*15)
            if(mask!=null){offsets[index]=pool.size().toLong();val bytes=ByteArray(Math.multiplyExact(mask.width,mask.height));mask.buffer.duplicate().apply{rewind();get(bytes)};pool.write(bytes)}
        }
        val data=ByteBuffer.wrap(owner.step(action,at,values,offsets,pool.toByteArray(),assigned.map{it.toLong()}.toLongArray())).order(ByteOrder.LITTLE_ENDIAN)
        check(data.int==1){"Unsupported tracking snapshot"}
        fun ids():Set<Int> = (0 until data.int).map{Math.toIntExact(data.long)}.toSet()
        fun rect()=FloatRect(data.float,data.float,data.float,data.float)
        fun mask():NativeMask? {
            if(data.int==0)return null
            val width=data.int;val height=data.int;val scale=data.float;val left=data.float;val top=data.float
            val srcWidth=data.int;val srcHeight=data.int;val input=data.int;val proto=data.int;val count=data.int
            check(count==Math.multiplyExact(width,height));val bytes=ByteArray(count);data.get(bytes)
            val mapper=ModelCoordinateMapper(srcWidth,srcHeight,input,proto)
            check(mapper.scale==scale&&mapper.padLeft==left&&mapper.padTop==top)
            return NativeMask(width,height,ByteBuffer.allocateDirect(count).apply{put(bytes);rewind()},srcWidth,srcHeight,mapper)
        }
        val persons=(0 until data.int).map{
            val id=Math.toIntExact(data.long);val state=TrackState.entries[data.int];val missed=data.int;val observedAge=data.int;val age=data.int;val observed=data.int!=0
            val bounds=rect();val confidence=data.float;val foot=data.float;val occluders=ids();val mask=mask()
            rect();data.int // Canonical anchor and dormancy are retained by Rust.
            TrackedPerson(id,bounds,mask,confidence,missed,observedAge,age,state,occluders,observed,foot)
        }
        fun classes():List<FreshPrivacyClassEvidence> = (0 until data.int).map{
            val selection=PrivacySelectionClass.entries[data.int];val index=data.int;val residual=ids();val unknown=data.int!=0
            FreshPrivacyClassEvidence(selection,index,detections[index],residual,unknown)
        }
        bodyEvidence=classes();faceEvidence=classes();suppressed=ids()
        hard=(0 until data.int).associate{data.int to PrivacySelectionClass.entries[data.int]}
        motionEvidence=(0 until data.int).map{
            val id=Math.toIntExact(data.long);val index=data.int;val score=data.float;val box=data.float;val mask=data.float;val time=data.long
            ProtectedTrackMotionEvidence(id,index,detections[index],score,box,mask,time)
        }
        check(!data.hasRemaining());return persons
    }
    @Synchronized override fun reset(){session?.close();session=null;protectedIds=emptySet();bodyIds=emptySet();faceIds=emptySet();dormancy=false;bodyEvidence=emptyList();faceEvidence=emptyList();motionEvidence=emptyList();suppressed=emptySet();hard=emptyMap()}
    override fun close()=reset()

    companion object {
        private const val ASSOCIATION_MASK_IOU_SAMPLE_STRIDE = 4
        private const val OCCLUSION_GROUP_MOTION_BLEND = 0.50f
        private const val PROTECTED_GROUP_ACTIVE_MIN_BBOX_IOU = 0.35f
        private const val PROTECTED_GROUP_ACTIVE_MIN_MASK_IOU = 0.20f
        private const val PROTECTED_GROUP_REACQUIRE_MIN_BBOX_IOU = 0.45f
        private const val PROTECTED_GROUP_REACQUIRE_MIN_MASK_IOU = 0.25f
        private const val PROTECTED_RECOVERY_MIN_BBOX_IOU = 0.50f
        private const val PROTECTED_RECOVERY_MIN_MASK_IOU = 0.45f
        private const val PROTECTED_MOTION_MIN_BBOX_IOU = 0.20f
        private const val PROTECTED_MOTION_MIN_MASK_IOU = 0.08f
        private const val PROTECTED_UNOBSERVED_MAX_CENTER_TRAVEL_RATIO = 0.30f
        private const val PROTECTED_UNOBSERVED_MIN_SCALE = 0.82f
        private const val PROTECTED_UNOBSERVED_MAX_SCALE = 1.18f
        private const val OFFSCREEN_EXIT_EDGE_RATIO = 0.06f
        private const val OFFSCREEN_EXIT_MIN_STEP_RATIO = 0.03f
        private const val MIXED_FULL_BODY_MAX_RENDER_MISS_FRAMES = 3
        private const val GROUP_RESERVATION_MIN_EDGE_PENETRATION_PX = 1.0f
        private const val GROUP_RESERVATION_MIN_EDGE_PENETRATION_RATIO = 0.01f
        private const val OCCLUSION_OVERLAP_EDGE_TELEMETRY_MARGIN = 0.10f

        internal fun isStrictFaceOnlyReservationRescueEligible(
            wouldStrictGlobalCommit: Boolean,
            faceOnlyIdentityProtected: Boolean,
            reservationOwnerGroupCount: Int,
            directOwnerSupport: Boolean = true,
            bboxIoU: Float = 0f,
            maskIoU: Float = 0f
        ): Boolean = art.gaoge.dance.engine.core.FrameKernel.reservationRelease(
            wouldStrictGlobalCommit, faceOnlyIdentityProtected, reservationOwnerGroupCount,
            directOwnerSupport, bboxIoU, maskIoU)

        internal fun isProtectedLostAnchorOcclusionSupported(
            trackState: TrackState,
            identityProtected: Boolean,
            predictionAnchorIoU: Float,
            anchorFreshOverlapRatio: Float,
            overlapThreshold: Float,
            occluderReliable: Boolean = true
        ): Boolean =
            identityProtected &&
                trackState == TrackState.LOST &&
                occluderReliable &&
                predictionAnchorIoU >= overlapThreshold &&
                anchorFreshOverlapRatio >= overlapThreshold

        internal fun shouldIgnoreGloballyUncertainOccluderForLostProtected(
            trackState: TrackState,
            identityProtected: Boolean,
            occluderGloballyUncertain: Boolean
        ): Boolean =
            identityProtected &&
                trackState == TrackState.LOST &&
                occluderGloballyUncertain

        internal fun isProtectedFaceOnlyTwoTrackMaskOwnershipConflict(
            groupTrackCount: Int,
            trackState: TrackState,
            identityProtected: Boolean,
            privacySelected: Boolean,
            bestScore: Float,
            minMatchScore: Float,
            protectedIdentityEvidenceOk: Boolean,
            protectedMaskIoU: Float,
            winnerIdentityProtected: Boolean,
            winnerMaskIoU: Float?
        ): Boolean =
            groupTrackCount == 2 &&
                (trackState == TrackState.OCCLUDED || trackState == TrackState.REACQUIRING) &&
                identityProtected &&
                !privacySelected &&
                bestScore >= minMatchScore &&
                protectedIdentityEvidenceOk &&
                protectedMaskIoU > 0f &&
                !winnerIdentityProtected &&
                winnerMaskIoU != null &&
                protectedMaskIoU >= winnerMaskIoU

        fun boundPredictionAroundAnchor(
            anchor: FloatRect,
            predicted: FloatRect,
            maxCenterTravelRatio: Float = PROTECTED_UNOBSERVED_MAX_CENTER_TRAVEL_RATIO,
            minScale: Float = PROTECTED_UNOBSERVED_MIN_SCALE,
            maxScale: Float = PROTECTED_UNOBSERVED_MAX_SCALE
        ): FloatRect {
            val anchorWidth = anchor.width.coerceAtLeast(1f)
            val anchorHeight = anchor.height.coerceAtLeast(1f)
            val refDim = max(anchorWidth, anchorHeight)
            val maxTravel = refDim * maxCenterTravelRatio.coerceAtLeast(0f)
            val dx = predicted.centerX - anchor.centerX
            val dy = predicted.centerY - anchor.centerY
            val distance = sqrt(dx * dx + dy * dy)
            val travelScale = if (distance > maxTravel && distance > 1e-4f) {
                maxTravel / distance
            } else {
                1f
            }
            val centerX = anchor.centerX + dx * travelScale
            val centerY = anchor.centerY + dy * travelScale
            val safeMinScale = minScale.coerceAtLeast(0.1f)
            val safeMaxScale = max(maxScale, safeMinScale)
            val width = predicted.width.coerceIn(anchorWidth * safeMinScale, anchorWidth * safeMaxScale)
            val height = predicted.height.coerceIn(anchorHeight * safeMinScale, anchorHeight * safeMaxScale)
            return FloatRect(
                left = centerX - width * 0.5f,
                top = centerY - height * 0.5f,
                right = centerX + width * 0.5f,
                bottom = centerY + height * 0.5f
            )
        }

        fun medianOf(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) {
                sorted[mid]
            } else {
                (sorted[mid - 1] + sorted[mid]) * 0.5f
            }
        }

        fun blendBboxes(own: FloatRect, group: FloatRect, groupWeight: Float): FloatRect {
            val w = groupWeight.coerceIn(0f, 1f)
            val ownWeight = 1f - w
            return FloatRect(
                left = own.left * ownWeight + group.left * w,
                top = own.top * ownWeight + group.top * w,
                right = own.right * ownWeight + group.right * w,
                bottom = own.bottom * ownWeight + group.bottom * w
            )
        }
        const val LOST_WARP_MAX_FRAMES = 3
        const val LOST_MARGIN_TIER1_RATIO = 0.15f // 15% margin for frames 4..10
        const val LOST_MARGIN_TIER2_RATIO = 0.25f // 25% margin for frames > 10

        fun isProtectedGroupIdentityEvidenceSufficient(
            state: TrackState,
            bboxIoU: Float,
            maskIoU: Float
        ): Boolean {
            return when (state) {
                // Once a protected identity has timed out to LOST, group/global
                // association must not become an easier recovery path than the
                // dedicated LOST recovery stage. A real mixed-video failure had
                // FULL_BODY id=4 commit from LOST at bboxIoU=0.458/maskIoU=0.20,
                // which passed the former ACTIVE fallback threshold despite
                // being too weak for ordinary protected recovery.
                TrackState.LOST ->
                    bboxIoU >= PROTECTED_RECOVERY_MIN_BBOX_IOU ||
                        maskIoU >= PROTECTED_RECOVERY_MIN_MASK_IOU
                TrackState.OCCLUDED, TrackState.REACQUIRING ->
                    bboxIoU >= PROTECTED_GROUP_REACQUIRE_MIN_BBOX_IOU ||
                        maskIoU >= PROTECTED_GROUP_REACQUIRE_MIN_MASK_IOU
                else ->
                    bboxIoU >= PROTECTED_GROUP_ACTIVE_MIN_BBOX_IOU ||
                        maskIoU >= PROTECTED_GROUP_ACTIVE_MIN_MASK_IOU
            }
        }

        fun isProtectedMotionEvidenceSufficient(
            bboxIoU: Float,
            maskIoU: Float
        ): Boolean =
            bboxIoU >= PROTECTED_MOTION_MIN_BBOX_IOU ||
                maskIoU >= PROTECTED_MOTION_MIN_MASK_IOU

        fun computeBBoxIntersectionArea(boxA: FloatRect, boxB: FloatRect): Float {
            val interX1 = max(boxA.left, boxB.left)
            val interY1 = max(boxA.top, boxB.top)
            val interX2 = min(boxA.right, boxB.right)
            val interY2 = min(boxA.bottom, boxB.bottom)

            val interW = max(0f, interX2 - interX1)
            val interH = max(0f, interY2 - interY1)
            return interW * interH
        }

        private fun hasMeaningfulGroupReservationOverlap(boxA: FloatRect, boxB: FloatRect): Boolean {
            val interW = min(boxA.right, boxB.right) - max(boxA.left, boxB.left)
            val interH = min(boxA.bottom, boxB.bottom) - max(boxA.top, boxB.top)
            if (interW <= 0f || interH <= 0f) return false

            // Group reservation exists to keep real fragments / duplicate detections
            // inside an occlusion group from leaking into Global Hungarian. A mere
            // edge graze must not claim ownership: the same 1080p frame diverged
            // across devices when one bbox edge differed by sub-pixel amounts and
            // the old `intersectionArea > 0` boolean flipped. Require a tiny but
            // meaningful penetration on both axes while leaving all identity,
            // motion, and occlusion-group commit gates unchanged.
            val minWidth = min(boxA.width, boxB.width).coerceAtLeast(1f)
            val minHeight = min(boxA.height, boxB.height).coerceAtLeast(1f)
            val requiredW = max(
                GROUP_RESERVATION_MIN_EDGE_PENETRATION_PX,
                minWidth * GROUP_RESERVATION_MIN_EDGE_PENETRATION_RATIO
            )
            val requiredH = max(
                GROUP_RESERVATION_MIN_EDGE_PENETRATION_PX,
                minHeight * GROUP_RESERVATION_MIN_EDGE_PENETRATION_RATIO
            )
            return interW >= requiredW && interH >= requiredH
        }

        fun computeBBoxIoU(boxA: FloatRect, boxB: FloatRect): Float {
            val interX1 = max(boxA.left, boxB.left)
            val interY1 = max(boxA.top, boxB.top)
            val interX2 = min(boxA.right, boxB.right)
            val interY2 = min(boxA.bottom, boxB.bottom)

            val interW = max(0f, interX2 - interX1)
            val interH = max(0f, interY2 - interY1)
            val interArea = interW * interH

            val areaA = boxA.width * boxA.height
            val areaB = boxB.width * boxB.height
            val unionArea = areaA + areaB - interArea

            return if (unionArea <= 0f) 0f else interArea / unionArea
        }

        fun computeMaskIoU(maskA: NativeMask?, maskB: NativeMask?, sampleStride: Int = 1): Float {
            if (maskA == null || maskB == null) return 0f
            if (maskA.width != maskB.width || maskA.height != maskB.height) return 0f

            val stride = sampleStride.coerceAtLeast(1)
            val width = maskA.width
            val height = maskA.height
            val bufA = maskA.buffer
            val bufB = maskB.buffer
            bufA.rewind()
            bufB.rewind()

            var intersection = 0
            var union = 0
            for (y in 0 until height step stride) {
                val row = y * width
                for (x in 0 until width step stride) {
                    val i = row + x
                    val a = (bufA.get(i).toInt() and 0xFF) > 128
                    val b = (bufB.get(i).toInt() and 0xFF) > 128
                    if (a && b) intersection++
                    if (a || b) union++
                }
            }

            return if (union == 0) 1.0f else intersection.toFloat() / union.toFloat()
        }

        internal data class PreparedWarpedMaskSamples(
            val width: Int,
            val height: Int,
            val sampleStride: Int,
            val foreground: ByteArray
        )

        internal fun prepareWarpedMaskSamples(
            sourceMask: NativeMask,
            prevBbox: FloatRect,
            predBbox: FloatRect,
            sampleStride: Int = ASSOCIATION_MASK_IOU_SAMPLE_STRIDE
        ): PreparedWarpedMaskSamples {
            val w = sourceMask.width
            val h = sourceMask.height
            val stride = sampleStride.coerceAtLeast(1)
            val sourceBuf = sourceMask.buffer

            val prevW = max(1f, prevBbox.width)
            val prevH = max(1f, prevBbox.height)
            val predW = max(1f, predBbox.width)
            val predH = max(1f, predBbox.height)
            val scaleX = predW / prevW
            val scaleY = predH / prevH

            val mapper = sourceMask.mapper ?: art.gaoge.dance.engine.geometry.ModelCoordinateMapper(
                srcWidth = max(1, sourceMask.originalWidth),
                srcHeight = max(1, sourceMask.originalHeight),
                modelInputSize = 640,
                protoSize = w
            )
            val prevCenterX = mapper.sourceToProtoX(prevBbox.centerX)
            val prevCenterY = mapper.sourceToProtoY(prevBbox.centerY)
            val predCenterX = mapper.sourceToProtoX(predBbox.centerX)
            val predCenterY = mapper.sourceToProtoY(predBbox.centerY)

            val sampleColumns = (w + stride - 1) / stride
            val sampleRows = (h + stride - 1) / stride
            val foreground = ByteArray(sampleColumns * sampleRows)
            var sampleIndex = 0

            var y = 0
            while (y < h) {
                val floatY = (y - predCenterY) / scaleY + prevCenterY
                val y0 = kotlin.math.floor(floatY).toInt()
                val y1 = y0 + 1
                val wy1 = (floatY - y0).coerceIn(0f, 1f)
                val wy0 = 1f - wy1

                var x = 0
                while (x < w) {
                    val floatX = (x - predCenterX) / scaleX + prevCenterX
                    val x0 = kotlin.math.floor(floatX).toInt()
                    val x1 = x0 + 1
                    val wx1 = (floatX - x0).coerceIn(0f, 1f)
                    val wx0 = 1f - wx1

                    fun sample(ix: Int, iy: Int): Int {
                        return if (ix in 0 until w && iy in 0 until h) {
                            sourceBuf.get(iy * w + ix).toInt() and 0xFF
                        } else {
                            0
                        }
                    }

                    val v00 = sample(x0, y0)
                    val v01 = sample(x1, y0)
                    val v10 = sample(x0, y1)
                    val v11 = sample(x1, y1)
                    val warped = (v00 * wx0 + v01 * wx1) * wy0 + (v10 * wx0 + v11 * wx1) * wy1
                    foreground[sampleIndex++] = if (warped > 128f) 1 else 0
                    x += stride
                }
                y += stride
            }

            return PreparedWarpedMaskSamples(
                width = w,
                height = h,
                sampleStride = stride,
                foreground = foreground
            )
        }

        internal fun computePreparedWarpedMaskIoU(
            prepared: PreparedWarpedMaskSamples,
            candidateMask: NativeMask?
        ): Float {
            if (candidateMask == null) return 0f
            if (prepared.width != candidateMask.width || prepared.height != candidateMask.height) return 0f

            val candidateBuf = candidateMask.buffer
            val w = prepared.width
            val h = prepared.height
            val stride = prepared.sampleStride
            var intersection = 0
            var union = 0
            var sampleIndex = 0

            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val a = prepared.foreground[sampleIndex++].toInt() != 0
                    val b = (candidateBuf.get(y * w + x).toInt() and 0xFF) > 128
                    if (a && b) intersection++
                    if (a || b) union++
                    x += stride
                }
                y += stride
            }

            return if (union == 0) 1.0f else intersection.toFloat() / union.toFloat()
        }

        fun computeWarpedMaskIoU(
            sourceMask: NativeMask?,
            prevBbox: FloatRect,
            predBbox: FloatRect,
            candidateMask: NativeMask?,
            sampleStride: Int = ASSOCIATION_MASK_IOU_SAMPLE_STRIDE
        ): Float {
            if (sourceMask == null || candidateMask == null) return 0f
            if (sourceMask.width != candidateMask.width || sourceMask.height != candidateMask.height) return 0f

            val w = sourceMask.width
            val h = sourceMask.height
            val stride = sampleStride.coerceAtLeast(1)
            val sourceBuf = sourceMask.buffer
            val candidateBuf = candidateMask.buffer

            val prevW = max(1f, prevBbox.width)
            val prevH = max(1f, prevBbox.height)
            val predW = max(1f, predBbox.width)
            val predH = max(1f, predBbox.height)
            val scaleX = predW / prevW
            val scaleY = predH / prevH

            val mapper = sourceMask.mapper ?: art.gaoge.dance.engine.geometry.ModelCoordinateMapper(
                srcWidth = max(1, sourceMask.originalWidth),
                srcHeight = max(1, sourceMask.originalHeight),
                modelInputSize = 640,
                protoSize = w
            )
            val prevCenterX = mapper.sourceToProtoX(prevBbox.centerX)
            val prevCenterY = mapper.sourceToProtoY(prevBbox.centerY)
            val predCenterX = mapper.sourceToProtoX(predBbox.centerX)
            val predCenterY = mapper.sourceToProtoY(predBbox.centerY)

            var intersection = 0
            var union = 0
            var y = 0
            while (y < h) {
                val floatY = (y - predCenterY) / scaleY + prevCenterY
                val y0 = kotlin.math.floor(floatY).toInt()
                val y1 = y0 + 1
                val wy1 = (floatY - y0).coerceIn(0f, 1f)
                val wy0 = 1f - wy1

                var x = 0
                while (x < w) {
                    val floatX = (x - predCenterX) / scaleX + prevCenterX
                    val x0 = kotlin.math.floor(floatX).toInt()
                    val x1 = x0 + 1
                    val wx1 = (floatX - x0).coerceIn(0f, 1f)
                    val wx0 = 1f - wx1

                    fun sample(ix: Int, iy: Int): Int {
                        return if (ix in 0 until w && iy in 0 until h) {
                            sourceBuf.get(iy * w + ix).toInt() and 0xFF
                        } else {
                            0
                        }
                    }

                    val v00 = sample(x0, y0)
                    val v01 = sample(x1, y0)
                    val v10 = sample(x0, y1)
                    val v11 = sample(x1, y1)
                    val warped = (v00 * wx0 + v01 * wx1) * wy0 + (v10 * wx0 + v11 * wx1) * wy1
                    val a = warped > 128f
                    val b = (candidateBuf.get(y * w + x).toInt() and 0xFF) > 128
                    if (a && b) intersection++
                    if (a || b) union++
                    x += stride
                }
                y += stride
            }

            return if (union == 0) 1.0f else intersection.toFloat() / union.toFloat()
        }

        fun updateLostMask(
            canonicalMask: NativeMask,
            observedBbox: FloatRect,
            predBbox: FloatRect,
            missedFrames: Int
        ): NativeMask {
            return warpMask(
                sourceMask = canonicalMask,
                prevBbox = observedBbox,
                predBbox = predBbox,
                missedFrames = missedFrames
            )
        }

        fun generateConservativeFallbackMask(
            sourceMask: NativeMask,
            predBbox: FloatRect,
            missedFrames: Int
        ): NativeMask {
            val w = sourceMask.width
            val h = sourceMask.height
            val mapper = sourceMask.mapper ?: art.gaoge.dance.engine.geometry.ModelCoordinateMapper(
                srcWidth = max(1, sourceMask.originalWidth),
                srcHeight = max(1, sourceMask.originalHeight),
                modelInputSize = 640,
                protoSize = w
            )

            val marginRatio = if (missedFrames <= 10) LOST_MARGIN_TIER1_RATIO else LOST_MARGIN_TIER2_RATIO
            val marginX = predBbox.width * marginRatio
            val marginY = predBbox.height * marginRatio

            val expandedBox = FloatRect(
                left = predBbox.left - marginX,
                top = predBbox.top - marginY,
                right = predBbox.right + marginX,
                bottom = predBbox.bottom + marginY
            )

            val pX1 = mapper.sourceToProtoX(expandedBox.left).roundToInt().coerceIn(0, w)
            val pY1 = mapper.sourceToProtoY(expandedBox.top).roundToInt().coerceIn(0, h)
            val pX2 = mapper.sourceToProtoX(expandedBox.right).roundToInt().coerceIn(0, w)
            val pY2 = mapper.sourceToProtoY(expandedBox.bottom).roundToInt().coerceIn(0, h)

            val minX = min(pX1, pX2)
            val maxX = max(pX1, pX2)
            val minY = min(pY1, pY2)
            val maxY = max(pY1, pY2)

            val dstBuf = ByteBuffer.allocateDirect(w * h)
            val tempArr = ByteArray(w * h)

            for (y in minY until maxY) {
                val rowOffset = y * w
                for (x in minX until maxX) {
                    tempArr[rowOffset + x] = 255.toByte()
                }
            }

            dstBuf.put(tempArr)
            dstBuf.rewind()

            return NativeMask(
                width = w,
                height = h,
                buffer = dstBuf,
                originalWidth = sourceMask.originalWidth,
                originalHeight = sourceMask.originalHeight,
                mapper = mapper
            )
        }

        fun warpMask(
            sourceMask: NativeMask,
            prevBbox: FloatRect,
            predBbox: FloatRect,
            missedFrames: Int
        ): NativeMask {
            val w = sourceMask.width
            val h = sourceMask.height
            val srcBuf = sourceMask.buffer
            srcBuf.rewind()

            val dstBuf = ByteBuffer.allocateDirect(w * h)

            val prevW = max(1f, prevBbox.width)
            val prevH = max(1f, prevBbox.height)
            val predW = max(1f, predBbox.width)
            val predH = max(1f, predBbox.height)

            val scaleX = predW / prevW
            val scaleY = predH / prevH

            val mapper = sourceMask.mapper ?: art.gaoge.dance.engine.geometry.ModelCoordinateMapper(
                srcWidth = max(1, sourceMask.originalWidth),
                srcHeight = max(1, sourceMask.originalHeight),
                modelInputSize = 640,
                protoSize = w
            )

            val prevNormCenterX = mapper.sourceToProtoX(prevBbox.centerX)
            val prevNormCenterY = mapper.sourceToProtoY(prevBbox.centerY)
            val predNormCenterX = mapper.sourceToProtoX(predBbox.centerX)
            val predNormCenterY = mapper.sourceToProtoY(predBbox.centerY)

            val dilation = if (missedFrames > 10) 2 else if (missedFrames > 0) 1 else 0

            val tempArr = ByteArray(w * h)

            // The inverse X mapping is identical for every output row. The old
            // scalar loop recomputed division/floor/bilinear weights w*h times;
            // prepare the exact same Float expressions once per output column.
            // This is only a geometry cache: interpolation order, rounding,
            // thresholding/dilation and mask bytes stay unchanged.
            val sourceX0 = IntArray(w)
            val sourceX1 = IntArray(w)
            val sourceWx0 = FloatArray(w)
            val sourceWx1 = FloatArray(w)
            for (x in 0 until w) {
                val floatX = (x - predNormCenterX) / scaleX + prevNormCenterX
                val x0 = kotlin.math.floor(floatX).toInt()
                val wx1 = (floatX - x0).coerceIn(0f, 1f)
                sourceX0[x] = x0
                sourceX1[x] = x0 + 1
                sourceWx1[x] = wx1
                sourceWx0[x] = 1f - wx1
            }

            for (y in 0 until h) {
                val floatY = (y - predNormCenterY) / scaleY + prevNormCenterY
                val y0 = kotlin.math.floor(floatY).toInt()
                val y1 = y0 + 1
                val wy1 = (floatY - y0).coerceIn(0f, 1f)
                val wy0 = 1f - wy1

                for (x in 0 until w) {
                    val x0 = sourceX0[x]
                    val x1 = sourceX1[x]
                    val wx0 = sourceWx0[x]
                    val wx1 = sourceWx1[x]

                    val v00 = if (x0 in 0 until w && y0 in 0 until h) (srcBuf.get(y0 * w + x0).toInt() and 0xFF) else 0
                    val v01 = if (x1 in 0 until w && y0 in 0 until h) (srcBuf.get(y0 * w + x1).toInt() and 0xFF) else 0
                    val v10 = if (x0 in 0 until w && y1 in 0 until h) (srcBuf.get(y1 * w + x0).toInt() and 0xFF) else 0
                    val v11 = if (x1 in 0 until w && y1 in 0 until h) (srcBuf.get(y1 * w + x1).toInt() and 0xFF) else 0

                    val interp = (v00 * wx0 + v01 * wx1) * wy0 + (v10 * wx0 + v11 * wx1) * wy1
                    tempArr[y * w + x] = interp.roundToInt().coerceIn(0, 255).toByte()
                }
            }

            // Apply slight organic dilation for LOST frames to prevent under-anonymization without rigid boxes
            if (dilation > 0) {
                val rad = dilation
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        var maxVal: Byte = tempArr[y * w + x]
                        for (dy in -rad..rad) {
                            for (dx in -rad..rad) {
                                val ny = y + dy
                                val nx = x + dx
                                if (nx in 0 until w && ny in 0 until h) {
                                    val b = tempArr[ny * w + nx]
                                    if ((b.toInt() and 0xFF) > (maxVal.toInt() and 0xFF)) {
                                        maxVal = b
                                    }
                                }
                            }
                        }
                        dstBuf.put(maxVal)
                    }
                }
            } else {
                dstBuf.put(tempArr)
            }

            dstBuf.rewind()
            return NativeMask(
                width = w,
                height = h,
                buffer = dstBuf,
                originalWidth = sourceMask.originalWidth,
                originalHeight = sourceMask.originalHeight,
                mapper = mapper
            )
        }
    }
}
