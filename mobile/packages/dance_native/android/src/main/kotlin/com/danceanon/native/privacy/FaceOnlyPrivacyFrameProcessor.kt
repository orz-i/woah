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
import com.danceanon.native.tracking.ProtectedTrackMotionEvidence
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
    val bodyCompensatedTrackIds: Set<Int>,
    val freshBodyMotionTrackIds: Set<Int>,
    val dormantSuppressedTrackIds: Set<Int>,
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

            val personTranslation = PersonBboxMotionEstimator.estimate(
                previous = trustedPersonBbox,
                current = personBbox
            )
            return FacePrivacyEllipse(
                centerX = centerX + personTranslation.dx,
                centerY = centerY + personTranslation.dy,
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
    private val lastObservedPtsUsByTrackId = mutableMapOf<Int, Long>()

    private fun planCachedFaceLocalRoi(
        cached: CachedFaceGeometry,
        person: TrackedPerson,
        ptsUs: Long,
        allowFreshBodyMotion: Boolean = false
    ): FaceHeadRoiPlan? {
        val ageUs = ptsUs - cached.lastTrustedPtsUs
        if (ageUs < 0L) return null
        if (ageUs > MAX_LOCAL_FACE_REFRESH_AGE_US && !allowFreshBodyMotion) return null
        val projected = cached.project(
            person.bbox,
            ageUs.coerceAtMost(MAX_LOCAL_FACE_REFRESH_AGE_US)
        ) ?: return null
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
        region: FacePrivacyEllipse,
        allowUnobservedMask: Boolean = false
    ): FacePrivacyEllipse? {
        if ((!person.observedThisFrame && !allowUnobservedMask) || person.mask == null) return null
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
        ptsUs: Long,
        renderMode: FaceOnlyRenderMode,
        hasFreshBodyMotion: Boolean = false
    ): FallbackGeometry? {
        val cacheAgeUs = cached?.let { ptsUs - it.lastTrustedPtsUs }
        val maxCacheAgeUs = if (
            renderMode == FaceOnlyRenderMode.BODY_MASK_COMPENSATED &&
            hasFreshBodyMotion
        ) {
            Long.MAX_VALUE
        } else if (renderMode == FaceOnlyRenderMode.BODY_MASK_COMPENSATED) {
            FaceOnlyDormancyPolicy.MAX_BODY_COMPENSATION_AGE_US
        } else {
            MAX_PREDICTED_FACE_AGE_US
        }
        val base = if (
            cached != null && cacheAgeUs != null &&
            cacheAgeUs in 0L..maxCacheAgeUs &&
            (renderMode == FaceOnlyRenderMode.BODY_MASK_COMPENSATED ||
                person.framesSinceLastObservation <= MAX_LOCAL_FACE_UNOBSERVED_FRAMES)
        ) {
            cached.project(
                person.bbox,
                cacheAgeUs.coerceAtMost(FaceOnlyDormancyPolicy.MAX_BODY_COMPENSATION_AGE_US)
            )
        } else {
            FacePrivacyRegionResolver.resolve(
                personBbox = person.bbox,
                roiPlan = null,
                selectedFace = null
            )
        } ?: return null
        val allowUnobservedBodyMask = renderMode == FaceOnlyRenderMode.BODY_MASK_COMPENSATED
        val refined = refineWithCurrentBodyMask(
            person = person,
            region = base,
            allowUnobservedMask = allowUnobservedBodyMask
        )
        val compensated = if (
            renderMode == FaceOnlyRenderMode.BODY_MASK_COMPENSATED &&
            cacheAgeUs != null
        ) {
            val compensationProgress = (
                (cacheAgeUs - FaceOnlyDormancyPolicy.MAX_DIRECT_UNOBSERVED_AGE_US).coerceAtLeast(0L).toFloat() /
                    (FaceOnlyDormancyPolicy.MAX_BODY_COMPENSATION_AGE_US -
                        FaceOnlyDormancyPolicy.MAX_DIRECT_UNOBSERVED_AGE_US).toFloat()
                ).coerceIn(0f, 1f)
            val expansion = 1f + compensationProgress * BODY_COMPENSATION_MAX_SIZE_EXPANSION
            (refined ?: base).copy(
                radiusX = (refined ?: base).radiusX * expansion,
                radiusY = (refined ?: base).radiusY * expansion
            )
        } else {
            refined ?: base
        }
        return FallbackGeometry(
            region = compensated,
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
        protectedMotionEvidence: List<ProtectedTrackMotionEvidence> = emptyList(),
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
                bodyCompensatedTrackIds = emptySet(),
                freshBodyMotionTrackIds = emptySet(),
                dormantSuppressedTrackIds = emptySet(),
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
        val freshMotionEvidenceByTrackId = protectedMotionEvidence.asSequence()
            .filter { evidence ->
                evidence.timestampUs == ptsUs &&
                    activeFaceOnlyTrackIds.contains(evidence.trackId)
            }
            .associateBy { it.trackId }
        val freshBodyMotionTrackIds = freshMotionEvidenceByTrackId.keys.toCollection(linkedSetOf())
        val geometryPersonByTrackId = activeFaceOnlyTrackIds.associateWith { trackId ->
            val person = personsById.getValue(trackId)
            val evidence = if (!person.observedThisFrame) freshMotionEvidenceByTrackId[trackId] else null
            if (evidence?.detection?.mask != null) {
                person.copy(
                    bbox = evidence.detection.bbox,
                    mask = evidence.detection.mask,
                    confidence = evidence.detection.confidence,
                    observedThisFrame = false,
                    footY = evidence.detection.footY
                )
            } else {
                person
            }
        }
        lastObservedPtsUsByTrackId.keys.retainAll(activeFaceOnlyTrackIds)
        activeFaceOnlyTrackIds.forEach { trackId ->
            val person = personsById[trackId] ?: return@forEach
            if (person.observedThisFrame) {
                lastObservedPtsUsByTrackId[trackId] = ptsUs
            }
        }
        val renderModeByTrackId = activeFaceOnlyTrackIds.associateWith { trackId ->
            val person = personsById[trackId] ?: return@associateWith FaceOnlyRenderMode.DORMANT
            val geometryPerson = geometryPersonByTrackId.getValue(trackId)
            val cachedFace = cachedFaceByTrackId[trackId]
            val cachedFaceAgeUs = cachedFace?.let { ptsUs - it.lastTrustedPtsUs }
            val hasFreshBodyMotion = freshMotionEvidenceByTrackId.containsKey(trackId)
            val hasUsableTrustedFace = if (hasFreshBodyMotion) {
                cachedFace != null
            } else {
                cachedFace != null && cachedFaceAgeUs != null &&
                    cachedFaceAgeUs in 0L..FaceOnlyDormancyPolicy.MAX_BODY_COMPENSATION_AGE_US
            }
            FaceOnlyDormancyPolicy.resolveMode(
                observedThisFrame = person.observedThisFrame,
                lastObservedPtsUs = lastObservedPtsUsByTrackId[trackId],
                ptsUs = ptsUs,
                hasTrustedFace = hasUsableTrustedFace,
                hasBodyMask = geometryPerson.mask != null,
                hasFreshBodyMotionEvidence = hasFreshBodyMotion
            )
        }
        val renderableFaceOnlyTrackIds = renderModeByTrackId
            .filterValues { it != FaceOnlyRenderMode.DORMANT }
            .keys
            .toCollection(linkedSetOf())
        val bodyCompensatedTrackIds = renderModeByTrackId
            .filterValues { it == FaceOnlyRenderMode.BODY_MASK_COMPENSATED }
            .keys
            .toCollection(linkedSetOf())
        val dormantSuppressedTrackIds = activeFaceOnlyTrackIds
            .filterTo(linkedSetOf()) { !renderableFaceOnlyTrackIds.contains(it) }

        // Once YOLO has been absent beyond the short privacy bridge, retain only
        // TrackManager identity. Drop the face-local state so a seconds-old face
        // anchor cannot resume drifting or seed the next detector ROI. Reacquire
        // starts from the fresh same-ID person observation instead.
        cachedFaceByTrackId.keys.retainAll(renderableFaceOnlyTrackIds)
        lastDetectorAttemptPtsUsByTrackId.keys.retainAll(renderableFaceOnlyTrackIds)
        temporalStabilizer.retainTracks(renderableFaceOnlyTrackIds)

        val detectorPlanByTrackId = linkedMapOf<Int, FaceHeadRoiPlan>()
        val localDetectorTrackIds = linkedSetOf<Int>()
        renderableFaceOnlyTrackIds.sorted().forEach { trackId ->
            val trackedPerson = personsById[trackId] ?: return@forEach
            val person = geometryPersonByTrackId[trackId] ?: trackedPerson
            if (person.state == TrackState.REMOVED) return@forEach
            val cached = cachedFaceByTrackId[trackId]
            val renderMode = renderModeByTrackId[trackId] ?: FaceOnlyRenderMode.DORMANT
            val hasFreshBodyMotion = freshMotionEvidenceByTrackId.containsKey(trackId)
            val canUseIdentityLocalRoi = renderMode != FaceOnlyRenderMode.DORMANT && cached != null
            val localPlan = if (canUseIdentityLocalRoi) {
                planCachedFaceLocalRoi(
                    cached = cached,
                    person = person,
                    ptsUs = ptsUs,
                    allowFreshBodyMotion = hasFreshBodyMotion
                )
            } else {
                null
            }
            val plan = localPlan ?: if (trackedPerson.observedThisFrame && trackedPerson.state == TrackState.ACTIVE) {
                FaceHeadRoiPlanner.plan(
                    personBbox = trackedPerson.bbox,
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

        for (trackId in renderableFaceOnlyTrackIds.sorted()) {
            val trackedPerson = personsById[trackId] ?: continue
            val person = geometryPersonByTrackId[trackId] ?: trackedPerson
            if (person.state == TrackState.REMOVED) continue
            val renderMode = renderModeByTrackId[trackId] ?: FaceOnlyRenderMode.DORMANT
            val hasFreshBodyMotion = freshMotionEvidenceByTrackId.containsKey(trackId)

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
                        resolveFallbackGeometry(
                            person,
                            cachedBeforeAttempt,
                            ptsUs,
                            renderMode,
                            hasFreshBodyMotion
                        )?.also { fallbackGeometry ->
                            if (fallbackGeometry.bodyMaskGuided) bodyMaskGuidedTrackIds += trackId
                        }?.region
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Face ROI detection failed for track=$trackId; using head fallback", t)
                    region = resolveFallbackGeometry(
                        person,
                        cachedBeforeAttempt,
                        ptsUs,
                        renderMode,
                        hasFreshBodyMotion
                    )?.also { fallbackGeometry ->
                        if (fallbackGeometry.bodyMaskGuided) bodyMaskGuidedTrackIds += trackId
                    }?.region
                }
            }

            if (region == null) {
                val cached = cachedFaceByTrackId[trackId]
                region = resolveFallbackGeometry(
                    person,
                    cached,
                    ptsUs,
                    renderMode,
                    hasFreshBodyMotion
                )?.also { fallbackGeometry ->
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
            if (
                fullBodyTrackIds.contains(person.id) ||
                dormantSuppressedTrackIds.contains(person.id)
            ) {
                person.copy(mask = null)
            } else {
                person
            }
        }
        val privacyResolveStartNs = System.nanoTime()
        val adaptation = PersonPrivacyPolicyAdapter.adapt(
            persons = secondaryPersons,
            modeByTrackId = renderableFaceOnlyTrackIds.associateWith { PersonPrivacyMode.FACE_ONLY },
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
                    "body_compensated=${bodyCompensatedTrackIds.sorted()} " +
                    "dormant=${dormantSuppressedTrackIds.sorted()} " +
                    "selected=${adaptation.selectedPersonIds.sorted()} unresolved=${unresolved.sorted()} " +
                    "secondary_occluder=$hasSecondaryOccluderEvidence"
            )
        }

        return FaceOnlyPrivacyFrameResult(
            resolvedPrivacy = resolved,
            readyForRender = unresolved.isEmpty() &&
                (adaptation.selectedPersonIds.isEmpty() || resolved?.hasPrivacy == true),
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
            bodyCompensatedTrackIds = bodyCompensatedTrackIds,
            freshBodyMotionTrackIds = freshBodyMotionTrackIds.intersect(bodyCompensatedTrackIds),
            dormantSuppressedTrackIds = dormantSuppressedTrackIds,
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
        private const val MAX_LOCAL_FACE_REFRESH_AGE_US = FaceOnlyDormancyPolicy.MAX_BODY_COMPENSATION_AGE_US
        private const val MAX_LOCAL_FACE_UNOBSERVED_FRAMES = 30
        private const val MIN_PREDICTED_FACE_SCALE = 0.88f
        private const val MAX_PREDICTED_FACE_SCALE = 1.12f
        private const val MAX_PREDICTED_AGE_EXPANSION = 0.10f
        private const val LOCAL_FACE_ROI_MIN_SIDE_PX = 72f
        private const val LOCAL_FACE_ROI_DIAMETER_FACTOR = 2.8f
        private const val BODY_COMPENSATION_MAX_SIZE_EXPANSION = 0.18f
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
