package com.danceanon.native.privacy

import android.content.Context
import android.util.Log
import com.danceanon.native.face.FaceHeadRoiPlanner
import com.danceanon.native.face.FaceLocator
import com.danceanon.native.face.FaceLocatorProvider
import com.danceanon.native.face.FaceRoiCandidateSelector
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.render.FaceRoiRenderer
import com.danceanon.native.render.FaceStickerPlacement
import com.danceanon.native.render.InferenceFbo
import com.danceanon.native.render.SourceTextureType
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import kotlin.math.sqrt

data class FaceOnlyPrivacyFrameResult(
    val resolvedPrivacy: ResolvedCompositorMasks?,
    val readyForRender: Boolean,
    val unresolvedTrackIds: Set<Int>,
    val detectedTrackIds: Set<Int>,
    val predictedTrackIds: Set<Int>,
    val fallbackTrackIds: Set<Int>,
    val escalatedFullBodyTrackIds: Set<Int>,
    val faceInferenceMs: Double,
    val detectorCallCount: Int,
    val detectorObservationCount: Int,
    val detectorZeroObservationCallCount: Int,
    val detectorRejectedCallCount: Int,
    val detectorCalledTrackIds: Set<Int>,
    val detectorRejectedTrackIds: Set<Int>,
    val roiReadbackMs: Double,
    val maskBuildMs: Double,
    val privacyResolveMs: Double,
    val stickerPlacements: List<FaceStickerPlacement>
)

/**
 * Resolves FACE_ONLY privacy independently from the full-body fresh-class path.
 * Identity remains owned by YOLO/TrackManager. Detector/ROI failures reduce
 * localization precision only: privacy falls back to the YOLO-owned head region.
 */
class FaceOnlyPrivacyFrameProcessor(
    private val locator: FaceLocator,
    private val mapper: ModelCoordinateMapper,
    private val roiRenderer: FaceRoiRenderer = FaceRoiRenderer(),
    private val roiFbo: InferenceFbo = InferenceFbo(FACE_ROI_SIZE),
    private val temporalStabilizer: FacePrivacyTemporalStabilizer = FacePrivacyTemporalStabilizer(),
    private val detectorIntervalUs: Long = DEFAULT_DETECTOR_INTERVAL_US,
    private val maxDetectorCallsPerFrame: Int = DEFAULT_MAX_DETECTOR_CALLS_PER_FRAME
) : AutoCloseable {

    private data class CachedFaceGeometry(
        val centerX: Float,
        val centerY: Float,
        val radiusX: Float,
        val radiusY: Float,
        val trustedPersonBbox: FloatRect,
        val lastTrustedPtsUs: Long
    ) {
        fun project(personBbox: FloatRect, ageUs: Long): FacePrivacyEllipse? {
            if (personBbox.width <= 1f || personBbox.height <= 1f) return null

            // Follow short-term body translation, but do not let an occlusion/
            // merge-expanded person bbox blow the trusted face size up.  Face
            // scale cannot legitimately double within the <=150 ms cache window.
            val widthRatio = (personBbox.width / trustedPersonBbox.width.coerceAtLeast(1f))
                .coerceAtLeast(0.1f)
            val heightRatio = (personBbox.height / trustedPersonBbox.height.coerceAtLeast(1f))
                .coerceAtLeast(0.1f)
            val bboxScale = sqrt(widthRatio * heightRatio)
                .coerceIn(MIN_PREDICTED_FACE_SCALE, MAX_PREDICTED_FACE_SCALE)
            val ageExpansion = (
                1f + (ageUs.coerceIn(0L, MAX_PREDICTED_FACE_AGE_US).toFloat() /
                    MAX_PREDICTED_FACE_AGE_US.toFloat()) * MAX_PREDICTED_AGE_EXPANSION
                )

            val centerDx = personBbox.centerX - trustedPersonBbox.centerX
            // Vertical head motion is more stable against pose/bbox-height
            // changes when anchored to the top edge instead of bbox center.
            val centerDy = personBbox.top - trustedPersonBbox.top
            return FacePrivacyEllipse(
                centerX = centerX + centerDx,
                centerY = centerY + centerDy,
                radiusX = (radiusX * bboxScale * ageExpansion).coerceAtLeast(1f),
                radiusY = (radiusY * bboxScale * ageExpansion).coerceAtLeast(1f),
                source = FacePrivacyRegionSource.PREDICTED_FACE
            )
        }

        companion object {
            fun from(
                region: FacePrivacyEllipse,
                personBbox: FloatRect,
                ptsUs: Long
            ): CachedFaceGeometry? {
                if (personBbox.width <= 1f || personBbox.height <= 1f) return null
                return CachedFaceGeometry(
                    centerX = region.centerX,
                    centerY = region.centerY,
                    radiusX = region.radiusX,
                    radiusY = region.radiusY,
                    trustedPersonBbox = personBbox,
                    lastTrustedPtsUs = ptsUs
                )
            }
        }
    }

    private val cachedFaceByTrackId = mutableMapOf<Int, CachedFaceGeometry>()
    private val lastDetectorAttemptPtsUsByTrackId = mutableMapOf<Int, Long>()

    fun resolveFrame(
        frameTexture: Int,
        texMatrix: FloatArray,
        textureType: SourceTextureType,
        persons: List<TrackedPerson>,
        faceOnlyTrackIds: Set<Int>,
        fullBodyTrackIds: Set<Int> = emptySet(),
        ptsUs: Long
    ): FaceOnlyPrivacyFrameResult {
        if (faceOnlyTrackIds.isEmpty()) {
            return FaceOnlyPrivacyFrameResult(
                resolvedPrivacy = null,
                readyForRender = true,
                unresolvedTrackIds = emptySet(),
                detectedTrackIds = emptySet(),
                predictedTrackIds = emptySet(),
                fallbackTrackIds = emptySet(),
                escalatedFullBodyTrackIds = emptySet(),
                faceInferenceMs = 0.0,
                detectorCallCount = 0,
                detectorObservationCount = 0,
                detectorZeroObservationCallCount = 0,
                detectorRejectedCallCount = 0,
                detectorCalledTrackIds = emptySet(),
                detectorRejectedTrackIds = emptySet(),
                roiReadbackMs = 0.0,
                maskBuildMs = 0.0,
                privacyResolveMs = 0.0,
                stickerPlacements = emptyList()
            )
        }

        val personsById = persons.associateBy { it.id }
        val missingTrackIds = faceOnlyTrackIds.filterTo(linkedSetOf()) { id ->
            val person = personsById[id]
            person == null || person.state == TrackState.REMOVED
        }
        val activeFaceOnlyTrackIds = faceOnlyTrackIds.filterTo(linkedSetOf()) { id ->
            val person = personsById[id]
            person != null && person.state != TrackState.REMOVED
        }
        cachedFaceByTrackId.keys.retainAll(activeFaceOnlyTrackIds)
        lastDetectorAttemptPtsUsByTrackId.keys.retainAll(activeFaceOnlyTrackIds)
        temporalStabilizer.retainTracks(activeFaceOnlyTrackIds)

        val detectorCandidates = activeFaceOnlyTrackIds.asSequence()
            .filter { trackId ->
                val person = personsById[trackId] ?: return@filter false
                // Detector observations are positional evidence only.  During
                // REACQUIRING/OCCLUDED/unobserved states the person bbox is not a
                // trustworthy identity anchor; running the detector there caused
                // hundreds of neighboring-face rejections in the real 5-person
                // clip and occasionally pulled privacy toward another dancer.
                if (!person.observedThisFrame || person.state != TrackState.ACTIVE) return@filter false
                val lastAttemptPtsUs = lastDetectorAttemptPtsUsByTrackId[trackId]
                lastAttemptPtsUs == null ||
                    ptsUs < lastAttemptPtsUs ||
                    ptsUs - lastAttemptPtsUs >= detectorIntervalUs
            }
            .sortedWith(
                compareBy<Int> { if (lastDetectorAttemptPtsUsByTrackId.containsKey(it)) 1 else 0 }
                    .thenBy { if (cachedFaceByTrackId.containsKey(it)) 1 else 0 }
                    .thenBy { lastDetectorAttemptPtsUsByTrackId[it] ?: Long.MIN_VALUE }
                    .thenBy { it }
            )
            .toList()
        val unattemptedCount = detectorCandidates.count { !lastDetectorAttemptPtsUsByTrackId.containsKey(it) }
        val detectorBudget = if (unattemptedCount > 0) {
            // Offline export/preview can afford a one-time acquisition burst.
            // This prevents right-side/later IDs from rendering several startup
            // frames with the generic YOLO-head fallback while waiting for a
            // round-robin 2-call budget.
            maxOf(
                maxDetectorCallsPerFrame.coerceAtLeast(1),
                minOf(unattemptedCount, INITIAL_ACQUISITION_MAX_CALLS)
            )
        } else {
            maxDetectorCallsPerFrame.coerceAtLeast(1)
        }
        val dueDetectorTrackIds = detectorCandidates.take(detectorBudget).toSet()

        val faceMasks = linkedMapOf<Int, NativeMask>()
        val detected = linkedSetOf<Int>()
        val predicted = linkedSetOf<Int>()
        val fallback = linkedSetOf<Int>()
        var inferenceMs = 0.0
        var detectorCallCount = 0
        var detectorObservationCount = 0
        var detectorZeroObservationCallCount = 0
        var detectorRejectedCallCount = 0
        val detectorCalledTrackIds = linkedSetOf<Int>()
        val detectorRejectedTrackIds = linkedSetOf<Int>()
        var roiReadbackMs = 0.0
        var maskBuildMs = 0.0
        val stickerPlacements = mutableListOf<FaceStickerPlacement>()

        for (trackId in faceOnlyTrackIds.sorted()) {
            val person = personsById[trackId] ?: continue
            if (person.state == TrackState.REMOVED) continue

            val plan = FaceHeadRoiPlanner.plan(
                personBbox = person.bbox,
                frameWidth = mapper.srcWidth,
                frameHeight = mapper.srcHeight
            )

            var region: FacePrivacyEllipse? = null
            if (dueDetectorTrackIds.contains(trackId) && plan != null) {
                lastDetectorAttemptPtsUsByTrackId[trackId] = ptsUs
                val cachedBeforeAttempt = cachedFaceByTrackId[trackId]
                try {
                    val roiStartNs = System.nanoTime()
                    roiRenderer.renderToFbo(
                        textureId = frameTexture,
                        texMatrix = texMatrix,
                        sourceRect = plan.sourceRect,
                        sourceWidth = mapper.srcWidth,
                        sourceHeight = mapper.srcHeight,
                        fbo = roiFbo,
                        textureType = textureType
                    )
                    val rgba = roiFbo.readRgbaPixels()
                    roiReadbackMs += (System.nanoTime() - roiStartNs) / 1_000_000.0
                    val locatorResult = locator.detectRgbaTopDown(
                        rgba = rgba,
                        width = FACE_ROI_SIZE,
                        height = FACE_ROI_SIZE
                    )
                    detectorCallCount++
                    detectorCalledTrackIds += trackId
                    inferenceMs += locatorResult.inferenceMs
                    detectorObservationCount += locatorResult.observations.size
                    if (locatorResult.observations.isEmpty()) {
                        detectorZeroObservationCallCount++
                    }
                    val selectedFace = FaceRoiCandidateSelector.select(
                        faces = locatorResult.observations,
                        roiWidth = FACE_ROI_SIZE,
                        roiHeight = FACE_ROI_SIZE,
                        anchorX = plan.anchorX,
                        anchorY = plan.anchorY
                    )
                    if (locatorResult.observations.isNotEmpty() && selectedFace == null) {
                        detectorRejectedCallCount++
                        detectorRejectedTrackIds += trackId
                    }
                    region = if (selectedFace != null) {
                        FacePrivacyRegionResolver.resolve(
                            personBbox = person.bbox,
                            roiPlan = plan,
                            selectedFace = selectedFace
                        )
                    } else {
                        val cacheAgeUs = cachedBeforeAttempt?.let { ptsUs - it.lastTrustedPtsUs }
                        if (
                            cachedBeforeAttempt != null && cacheAgeUs != null &&
                            cacheAgeUs in 0L..MAX_PREDICTED_FACE_AGE_US
                        ) {
                            cachedBeforeAttempt.project(person.bbox, cacheAgeUs)
                        } else {
                            FacePrivacyRegionResolver.resolve(
                                personBbox = person.bbox,
                                roiPlan = null,
                                selectedFace = null
                            )
                        }
                    }
                    if (selectedFace != null && region?.source == FacePrivacyRegionSource.DETECTED_FACE) {
                        CachedFaceGeometry.from(region, person.bbox, ptsUs)?.let { cached ->
                            cachedFaceByTrackId[trackId] = cached
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Face ROI detection failed for track=$trackId; using head fallback", t)
                    val cacheAgeUs = cachedBeforeAttempt?.let { ptsUs - it.lastTrustedPtsUs }
                    region = if (
                        cachedBeforeAttempt != null && cacheAgeUs != null &&
                        cacheAgeUs in 0L..MAX_PREDICTED_FACE_AGE_US
                    ) {
                        cachedBeforeAttempt.project(person.bbox, cacheAgeUs)
                    } else {
                        FacePrivacyRegionResolver.resolve(
                            personBbox = person.bbox,
                            roiPlan = null,
                            selectedFace = null
                        )
                    }
                }
            }

            if (region == null) {
                val cached = cachedFaceByTrackId[trackId]
                val cacheAgeUs = cached?.let { ptsUs - it.lastTrustedPtsUs }
                val cacheUsable = cached != null &&
                    cacheAgeUs != null &&
                    cacheAgeUs >= 0L &&
                    cacheAgeUs <= MAX_PREDICTED_FACE_AGE_US &&
                    person.framesSinceLastObservation <= MAX_PREDICTED_OBSERVATION_AGE_FRAMES &&
                    person.state != TrackState.LOST
                region = if (cacheUsable) {
                    cached.project(person.bbox, cacheAgeUs)
                } else {
                    FacePrivacyRegionResolver.resolve(
                        personBbox = person.bbox,
                        roiPlan = null,
                        selectedFace = null
                    )
                }
            }
            if (region != null) {
                region = temporalStabilizer.stabilize(
                    trackId = trackId,
                    rawRegion = region,
                    personBbox = person.bbox,
                    ptsUs = ptsUs
                )
                val maskStartNs = System.nanoTime()
                val builtMask = FacePrivacyMaskBuilder.build(listOf(region), mapper)
                maskBuildMs += (System.nanoTime() - maskStartNs) / 1_000_000.0
                builtMask?.let { mask ->
                    faceMasks[trackId] = mask
                }
                when (region.source) {
                    FacePrivacyRegionSource.DETECTED_FACE -> detected += trackId
                    FacePrivacyRegionSource.PREDICTED_FACE -> predicted += trackId
                    FacePrivacyRegionSource.YOLO_HEAD_FALLBACK -> fallback += trackId
                }
                FaceStickerPlacement.from(
                    trackId = trackId,
                    region = region,
                    sourceWidth = mapper.srcWidth,
                    sourceHeight = mapper.srcHeight
                )?.let(stickerPlacements::add)
            }
        }

        // FULL_BODY targets are owned by the primary compositor path. They must
        // not become "unselected foreground" inside this secondary FACE_ONLY
        // resolver or they could carve a selected face during overlap. Preserve
        // identity/geometry here but remove only their secondary-pass mask.
        val secondaryPersons = persons.map { person ->
            if (fullBodyTrackIds.contains(person.id)) person.copy(mask = null) else person
        }
        val privacyResolveStartNs = System.nanoTime()
        val adaptation = PersonPrivacyPolicyAdapter.adapt(
            persons = secondaryPersons,
            modeByTrackId = faceOnlyTrackIds.associateWith { PersonPrivacyMode.FACE_ONLY },
            faceMaskByTrackId = faceMasks
        )
        val unresolved = linkedSetOf<Int>().apply {
            addAll(missingTrackIds)
            addAll(adaptation.unresolvedSelectedTrackIds)
        }
        val resolved = if (adaptation.selectedPersonIds.isEmpty()) {
            null
        } else {
            PrivacyOcclusionResolver.resolveMasks(
                persons = adaptation.persons,
                selectedPersonIds = adaptation.selectedPersonIds,
                ptsUs = ptsUs,
                expectedSelectedCount = adaptation.selectedPersonIds.size
            )
        }
        val privacyResolveMs = (System.nanoTime() - privacyResolveStartNs) / 1_000_000.0

        if (privacyResolveMs >= SLOW_STAGE_LOG_THRESHOLD_MS) {
            val hasSecondaryOccluderEvidence = adaptation.persons.any { person ->
                !adaptation.selectedPersonIds.contains(person.id) && person.mask != null
            }
            Log.w(
                TAG,
                "slow_privacy_resolve pts_us=$ptsUs ms=$privacyResolveMs " +
                    "detector_calls=$detectorCallCount roi_ms=$roiReadbackMs detector_ms=$inferenceMs " +
                    "mask_ms=$maskBuildMs detected=${detected.sorted()} predicted=${predicted.sorted()} " +
                    "fallback=${fallback.sorted()} escalated=${adaptation.escalatedFullBodyTrackIds.sorted()} " +
                    "selected=${adaptation.selectedPersonIds.sorted()} unresolved=${unresolved.sorted()} " +
                    "secondary_occluder=$hasSecondaryOccluderEvidence"
            )
        }

        return FaceOnlyPrivacyFrameResult(
            resolvedPrivacy = resolved,
            readyForRender = unresolved.isEmpty() && resolved?.hasPrivacy == true,
            unresolvedTrackIds = unresolved,
            detectedTrackIds = detected,
            predictedTrackIds = predicted,
            fallbackTrackIds = fallback,
            escalatedFullBodyTrackIds = adaptation.escalatedFullBodyTrackIds,
            faceInferenceMs = inferenceMs,
            detectorCallCount = detectorCallCount,
            detectorObservationCount = detectorObservationCount,
            detectorZeroObservationCallCount = detectorZeroObservationCallCount,
            detectorRejectedCallCount = detectorRejectedCallCount,
            detectorCalledTrackIds = detectorCalledTrackIds,
            detectorRejectedTrackIds = detectorRejectedTrackIds,
            roiReadbackMs = roiReadbackMs,
            maskBuildMs = maskBuildMs,
            privacyResolveMs = privacyResolveMs,
            stickerPlacements = stickerPlacements
        )
    }

    override fun close() {
        try {
            locator.close()
        } finally {
            try {
                roiFbo.close()
            } finally {
                roiRenderer.close()
            }
        }
    }

    companion object {
        private const val TAG = "FaceOnlyPrivacy"
        const val FACE_ROI_SIZE = 256
        const val DEFAULT_DETECTOR_INTERVAL_US = 66_000L
        const val DEFAULT_MAX_DETECTOR_CALLS_PER_FRAME = 2
        private const val INITIAL_ACQUISITION_MAX_CALLS = 8
        private const val MAX_PREDICTED_FACE_AGE_US = 150_000L
        private const val MAX_PREDICTED_OBSERVATION_AGE_FRAMES = 6
        private const val MIN_PREDICTED_FACE_SCALE = 0.88f
        private const val MAX_PREDICTED_FACE_SCALE = 1.12f
        private const val MAX_PREDICTED_AGE_EXPANSION = 0.10f
        private const val SLOW_STAGE_LOG_THRESHOLD_MS = 20.0

        fun create(context: Context, mapper: ModelCoordinateMapper): FaceOnlyPrivacyFrameProcessor {
            val locator = requireNotNull(FaceLocatorProvider.createOrNull(context, enabled = true)) {
                "Face locator was not available after explicit enable"
            }
            return FaceOnlyPrivacyFrameProcessor(locator = locator, mapper = mapper)
        }
    }
}
