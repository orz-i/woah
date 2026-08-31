package com.danceanon.native.privacy

import android.content.Context
import android.util.Log
import com.danceanon.native.face.FaceHeadRoiPlanner
import com.danceanon.native.face.FaceHeadRoiPlan
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
    val bodyMaskGuidedTrackIds: Set<Int>,
    val positionClampedTrackIds: Set<Int>,
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
            // Use the lower-body/foot edge as the vertical whole-person motion
            // anchor. Real-video YOLO boxes can change their top edge by nearly
            // 100 px in one 60 fps frame when upper-body coverage changes while
            // the feet remain stable. Treating that top-edge change as body
            // translation moves the face ROI with a detector-box shape change.
            // Bottom-edge motion is substantially more stable for this purpose;
            // articulated head motion is supplied by the face detector itself.
            val centerDy = personBbox.bottom - trustedPersonBbox.bottom
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

    private fun planCachedFaceLocalRoi(
        cached: CachedFaceGeometry,
        person: TrackedPerson,
        ptsUs: Long
    ): FaceHeadRoiPlan? {
        val ageUs = ptsUs - cached.lastTrustedPtsUs
        if (ageUs !in 0L..MAX_LOCAL_FACE_REFRESH_AGE_US) return null
        val projected = cached.project(person.bbox, ageUs) ?: return null
        val frameWidth = mapper.srcWidth.toFloat()
        val frameHeight = mapper.srcHeight.toFloat()
        if (frameWidth <= 1f || frameHeight <= 1f) return null

        val faceDiameter = maxOf(projected.radiusX, projected.radiusY) * 2f
        val requestedSide = maxOf(LOCAL_FACE_ROI_MIN_SIDE_PX, faceDiameter * LOCAL_FACE_ROI_DIAMETER_FACTOR)
        val side = minOf(requestedSide, minOf(frameWidth, frameHeight)).coerceAtLeast(2f)
        val maxLeft = (frameWidth - side).coerceAtLeast(0f)
        val maxTop = (frameHeight - side).coerceAtLeast(0f)
        val left = (projected.centerX - side * 0.5f).coerceIn(0f, maxLeft)
        val top = (projected.centerY - side * 0.5f).coerceIn(0f, maxTop)
        val rect = FloatRect(left, top, left + side, top + side)
        return FaceHeadRoiPlan(
            sourceRect = rect,
            anchorX = ((projected.centerX - rect.left) / side).coerceIn(0f, 1f),
            anchorY = ((projected.centerY - rect.top) / side).coerceIn(0f, 1f),
            outputSize = FACE_ROI_SIZE
        )
    }

    private fun refineWithCurrentBodyMask(
        person: TrackedPerson,
        region: FacePrivacyEllipse
    ): FacePrivacyEllipse? {
        if (!person.observedThisFrame || person.mask == null) return null
        val estimate = BodyMaskFaceHeadEstimator.estimate(
            mask = person.mask,
            personBbox = person.bbox,
            seedCenterX = region.centerX,
            seedCenterY = region.centerY,
            seedRadiusX = region.radiusX,
            seedRadiusY = region.radiusY
        ) ?: return null
        return region.copy(centerX = estimate.x, centerY = estimate.y)
    }

    private data class FallbackGeometry(
        val region: FacePrivacyEllipse,
        val bodyMaskGuided: Boolean
    )

    private fun resolveFallbackGeometry(
        person: TrackedPerson,
        cached: CachedFaceGeometry?,
        ptsUs: Long
    ): FallbackGeometry? {
        val cacheAgeUs = cached?.let { ptsUs - it.lastTrustedPtsUs }
        val base = if (
            cached != null && cacheAgeUs != null &&
            cacheAgeUs in 0L..MAX_PREDICTED_FACE_AGE_US &&
            person.framesSinceLastObservation <= MAX_LOCAL_FACE_UNOBSERVED_FRAMES
        ) {
            cached.project(person.bbox, cacheAgeUs)
        } else {
            FacePrivacyRegionResolver.resolve(
                personBbox = person.bbox,
                roiPlan = null,
                selectedFace = null
            )
        } ?: return null
        val refined = refineWithCurrentBodyMask(person, base)
        return FallbackGeometry(
            region = refined ?: base,
            bodyMaskGuided = refined != null
        )
    }

    private fun preserveTrustedSizeForLocalDetection(
        detected: FacePrivacyEllipse,
        cached: CachedFaceGeometry,
        personBbox: FloatRect
    ): FacePrivacyEllipse {
        if (detected.source != FacePrivacyRegionSource.DETECTED_FACE) return detected
        val projectedReference = cached.project(personBbox, ageUs = 0L) ?: return detected
        // Once identity-local face tracking is established, the small ROI exists
        // to refresh *position*. Letting the detector's local-box extent redefine
        // ROI scale creates a feedback loop (ROI -> larger local face box -> larger
        // ROI) and was visible as ~1.40x sticker-height pumping on device. Preserve
        // the previously trusted source-space size, adjusted only by the bounded
        // person-scale projection, while accepting the detector's new center.
        return detected.copy(
            radiusX = projectedReference.radiusX,
            radiusY = projectedReference.radiusY
        )
    }

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
                bodyMaskGuidedTrackIds = emptySet(),
                positionClampedTrackIds = emptySet(),
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

        val detectorPlanByTrackId = linkedMapOf<Int, FaceHeadRoiPlan>()
        val localDetectorTrackIds = linkedSetOf<Int>()
        activeFaceOnlyTrackIds.sorted().forEach { trackId ->
            val person = personsById[trackId] ?: return@forEach
            if (person.state == TrackState.REMOVED) return@forEach
            val cached = cachedFaceByTrackId[trackId]
            val canUseIdentityLocalRoi =
                person.observedThisFrame ||
                    person.framesSinceLastObservation <= MAX_LOCAL_FACE_UNOBSERVED_FRAMES
            val localPlan = if (canUseIdentityLocalRoi) {
                cached?.let { planCachedFaceLocalRoi(it, person, ptsUs) }
            } else {
                null
            }
            val plan = localPlan ?: if (person.observedThisFrame && person.state == TrackState.ACTIVE) {
                FaceHeadRoiPlanner.plan(
                    personBbox = person.bbox,
                    frameWidth = mapper.srcWidth,
                    frameHeight = mapper.srcHeight
                )
            } else {
                null
            }
            if (plan != null) {
                detectorPlanByTrackId[trackId] = plan
                if (localPlan != null) localDetectorTrackIds += trackId
            }
        }

        val detectorCandidates = detectorPlanByTrackId.keys.asSequence()
            .filter { trackId ->
                val lastAttemptPtsUs = lastDetectorAttemptPtsUsByTrackId[trackId]
                lastAttemptPtsUs == null ||
                    ptsUs < lastAttemptPtsUs ||
                    ptsUs - lastAttemptPtsUs >= detectorIntervalUs
            }
            .sortedWith(
                compareBy<Int> {
                    val person = personsById[it]
                    if (localDetectorTrackIds.contains(it) && person?.observedThisFrame == false) 0 else 1
                }
                    .thenBy { if (lastDetectorAttemptPtsUsByTrackId.containsKey(it)) 1 else 0 }
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
        val bodyMaskGuidedTrackIds = linkedSetOf<Int>()
        val positionClampedTrackIds = linkedSetOf<Int>()
        var roiReadbackMs = 0.0
        var maskBuildMs = 0.0
        val stickerPlacements = mutableListOf<FaceStickerPlacement>()

        for (trackId in faceOnlyTrackIds.sorted()) {
            val person = personsById[trackId] ?: continue
            if (person.state == TrackState.REMOVED) continue

            val plan = detectorPlanByTrackId[trackId]

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
                        val detectedRegion = FacePrivacyRegionResolver.resolve(
                            personBbox = person.bbox,
                            roiPlan = plan,
                            selectedFace = selectedFace
                        )
                        if (
                            detectedRegion != null &&
                            localDetectorTrackIds.contains(trackId) &&
                            cachedBeforeAttempt != null
                        ) {
                            preserveTrustedSizeForLocalDetection(
                                detected = detectedRegion,
                                cached = cachedBeforeAttempt,
                                personBbox = person.bbox
                            )
                        } else {
                            detectedRegion
                        }
                    } else {
                        resolveFallbackGeometry(person, cachedBeforeAttempt, ptsUs)?.also { fallbackGeometry ->
                            if (fallbackGeometry.bodyMaskGuided) bodyMaskGuidedTrackIds += trackId
                        }?.region
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Face ROI detection failed for track=$trackId; using head fallback", t)
                    region = resolveFallbackGeometry(person, cachedBeforeAttempt, ptsUs)?.also { fallbackGeometry ->
                        if (fallbackGeometry.bodyMaskGuided) bodyMaskGuidedTrackIds += trackId
                    }?.region
                }
            }

            if (region == null) {
                val cached = cachedFaceByTrackId[trackId]
                region = resolveFallbackGeometry(person, cached, ptsUs)?.also { fallbackGeometry ->
                    if (fallbackGeometry.bodyMaskGuided) bodyMaskGuidedTrackIds += trackId
                }?.region
            }
            if (region != null) {
                val rawCenterX = region.centerX
                val rawCenterY = region.centerY
                region = temporalStabilizer.stabilize(
                    trackId = trackId,
                    rawRegion = region,
                    personBbox = person.bbox,
                    personObservedThisFrame = person.observedThisFrame,
                    ptsUs = ptsUs
                )
                if (
                    kotlin.math.abs(region.centerX - rawCenterX) > POSITION_CLAMP_DIAGNOSTIC_EPSILON_PX ||
                    kotlin.math.abs(region.centerY - rawCenterY) > POSITION_CLAMP_DIAGNOSTIC_EPSILON_PX
                ) {
                    positionClampedTrackIds += trackId
                }
                if (region.source == FacePrivacyRegionSource.DETECTED_FACE) {
                    // Commit only the post-gate center. A detector observation can
                    // be close enough to the broad/local ROI anchor to pass
                    // ownership yet still be a non-physical one-frame jump. If
                    // the raw center were cached before the temporal position
                    // gate, the next detector ROI would immediately move to that
                    // bad location and defeat the visual clamp on the following
                    // frame. The stabilized detected center becomes the trusted
                    // ROI seed instead.
                    CachedFaceGeometry.from(
                        region = region,
                        personBbox = person.bbox,
                        ptsUs = ptsUs
                    )?.let { cached ->
                        cachedFaceByTrackId[trackId] = cached
                    }
                }
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
            bodyMaskGuidedTrackIds = bodyMaskGuidedTrackIds,
            positionClampedTrackIds = positionClampedTrackIds,
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
        const val DEFAULT_DETECTOR_INTERVAL_US = 33_000L
        const val DEFAULT_MAX_DETECTOR_CALLS_PER_FRAME = 2
        private const val INITIAL_ACQUISITION_MAX_CALLS = 8
        private const val MAX_PREDICTED_FACE_AGE_US = 150_000L
        private const val MAX_LOCAL_FACE_REFRESH_AGE_US = 500_000L
        private const val MAX_LOCAL_FACE_UNOBSERVED_FRAMES = 30
        private const val MIN_PREDICTED_FACE_SCALE = 0.88f
        private const val MAX_PREDICTED_FACE_SCALE = 1.12f
        private const val MAX_PREDICTED_AGE_EXPANSION = 0.10f
        private const val LOCAL_FACE_ROI_MIN_SIDE_PX = 72f
        private const val LOCAL_FACE_ROI_DIAMETER_FACTOR = 2.8f
        private const val POSITION_CLAMP_DIAGNOSTIC_EPSILON_PX = 0.25f
        private const val SLOW_STAGE_LOG_THRESHOLD_MS = 20.0

        fun create(context: Context, mapper: ModelCoordinateMapper): FaceOnlyPrivacyFrameProcessor {
            val locator = requireNotNull(FaceLocatorProvider.createOrNull(context, enabled = true)) {
                "Face locator was not available after explicit enable"
            }
            return FaceOnlyPrivacyFrameProcessor(locator = locator, mapper = mapper)
        }
    }
}
