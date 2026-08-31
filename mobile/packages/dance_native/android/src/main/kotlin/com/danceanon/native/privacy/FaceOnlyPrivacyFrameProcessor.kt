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
import java.nio.ByteBuffer
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
    val recentBodyMotionBridgeTrackIds: Set<Int>,
    val dormantReactivationProbeTrackIds: Set<Int>,
    val dormantProbeMotionRejectedTrackIds: Set<Int>,
    val dormantReactivatedTrackIds: Set<Int>,
    val dormantExactReacquiredTrackIds: Set<Int>,
    val dormantSuppressedTrackIds: Set<Int>,
    val dormantPixelMotionBridgeTrackIds: Set<Int>,
    val pixelMotionTrackIds: Set<Int>,
    val pixelMotionRejectedTrackIds: Set<Int>,
    val pixelMotionRejectReasonByTrackId: Map<Int, String>,
    val dormantSuppressionReasonByTrackId: Map<Int, String>,
    val occlusionHoldTrackIds: Set<Int>,
    val occlusionReacquireDetectorTrackIds: Set<Int>,
    val pixelMotionMs: Double,
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

    private val pixelMotionTracker = FacePixelMotionTracker()

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
    private data class RecentBodyMotionGeometry(
        val bbox: FloatRect,
        val confidence: Float,
        val footY: Float,
        val ptsUs: Long
    )
    private val recentBodyMotionByTrackId = mutableMapOf<Int, RecentBodyMotionGeometry>()
    private val dormantFaceOnlyTrackIds = mutableSetOf<Int>()
    private data class OcclusionHoldGeometry(
        val region: FacePrivacyEllipse,
        val personBbox: FloatRect,
        val ptsUs: Long
    )
    private val occlusionHoldByTrackId = mutableMapOf<Int, OcclusionHoldGeometry>()

    private fun resolveOcclusionHold(
        trackId: Int,
        person: TrackedPerson,
        ptsUs: Long
    ): FacePrivacyEllipse? {
        val trusted = occlusionHoldByTrackId[trackId] ?: return null
        return FaceOcclusionBridgePolicy.projectHold(
            trustedRegion = trusted.region,
            trustedPersonBbox = trusted.personBbox,
            currentPersonBbox = person.bbox,
            personObservedThisFrame = person.observedThisFrame,
            ageUs = ptsUs - trusted.ptsUs
        )
    }

    private fun planOcclusionReacquireRoi(trackId: Int, ptsUs: Long): FaceHeadRoiPlan? {
        val projected = pixelMotionTracker.currentRoiRegion(trackId, ptsUs)
            ?: occlusionHoldByTrackId[trackId]?.region
            ?: return null
        return planLocalRoiAround(
            projected = projected,
            diameterFactor = OCCLUSION_REACQUIRE_ROI_DIAMETER_FACTOR
        )
    }

    private fun projectDormantAnchorForProbe(
        cached: CachedFaceGeometry,
        personBbox: FloatRect
    ): FacePrivacyEllipse? {
        if (personBbox.width <= 1f || personBbox.height <= 1f) return null
        val personTranslation = PersonBboxMotionEstimator.estimate(
            previous = cached.trustedPersonBbox,
            current = personBbox
        )
        return FacePrivacyEllipse(
            centerX = cached.centerX + personTranslation.dx,
            centerY = cached.centerY + personTranslation.dy,
            radiusX = cached.radiusX.coerceAtLeast(1f),
            radiusY = cached.radiusY.coerceAtLeast(1f),
            source = FacePrivacyRegionSource.PREDICTED_FACE
        )
    }

    private fun planDormantReactivationLocalRoi(
        cached: CachedFaceGeometry,
        person: TrackedPerson
    ): FaceHeadRoiPlan? {
        val projected = projectDormantAnchorForProbe(cached, person.bbox) ?: return null
        return planLocalRoiAround(projected)
    }

    private fun planLocalRoiAround(
        projected: FacePrivacyEllipse,
        diameterFactor: Float = LOCAL_FACE_ROI_DIAMETER_FACTOR
    ): FaceHeadRoiPlan? {
        val frameWidth = mapper.srcWidth.toFloat()
        val frameHeight = mapper.srcHeight.toFloat()
        if (frameWidth <= 1f || frameHeight <= 1f) return null

        val faceDiameter = maxOf(projected.radiusX, projected.radiusY) * 2f
        val requestedSide = maxOf(LOCAL_FACE_ROI_MIN_SIDE_PX, faceDiameter * diameterFactor)
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
        return planLocalRoiAround(projected)
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
        cached: CachedFaceGeometry
    ): FacePrivacyEllipse {
        if (detected.source != FacePrivacyRegionSource.DETECTED_FACE) return detected
        // Once identity-local face tracking is established, the small ROI exists
        // to refresh *position*. Letting the detector's local-box extent redefine
        // ROI scale creates a feedback loop (ROI -> larger local face box -> larger
        // ROI) and was visible as sticker-height pumping on device. Real-video
        // telemetry also shows person bbox width can change 2-3x from segmentation
        // shape/occlusion without equivalent face scale. Do not feed that bbox
        // shape change into the trusted face radius; preserve exact source-space
        // size and accept only the detector's current center.
        return detected.copy(
            radiusX = cached.radiusX.coerceAtLeast(1f),
            radiusY = cached.radiusY.coerceAtLeast(1f)
        )
    }

    private fun preserveHiddenTrustedSizeForDormantReactivation(
        detected: FacePrivacyEllipse,
        cached: CachedFaceGeometry
    ): FacePrivacyEllipse {
        // A hidden anchor can be seconds old. It is safe only as a source-space
        // size reference and a seed for the current identity-local detector ROI.
        // Do not scale it from a stale person bbox, do not apply cache-age growth,
        // and do not let body-compensation expansion become a new trusted size.
        return FaceOnlyDormantReactivationPolicy.preserveTrustedSize(
            detected = detected,
            trustedRadiusX = cached.radiusX,
            trustedRadiusY = cached.radiusY
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
                recentBodyMotionBridgeTrackIds = emptySet(),
                dormantReactivationProbeTrackIds = emptySet(),
                dormantProbeMotionRejectedTrackIds = emptySet(),
                dormantReactivatedTrackIds = emptySet(),
                dormantExactReacquiredTrackIds = emptySet(),
                dormantSuppressedTrackIds = emptySet(),
                dormantPixelMotionBridgeTrackIds = emptySet(),
                pixelMotionTrackIds = emptySet(),
                pixelMotionRejectedTrackIds = emptySet(),
                pixelMotionRejectReasonByTrackId = emptyMap(),
                dormantSuppressionReasonByTrackId = emptyMap(),
                occlusionHoldTrackIds = emptySet(),
                occlusionReacquireDetectorTrackIds = emptySet(),
                pixelMotionMs = 0.0,
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
        // Pixel state belongs to the selected TrackManager identity, not to the
        // current render mode. Keep it through FACE_ONLY dormancy so current
        // pixels can prove that the same local face is still present. A real
        // evidence gap expires the state inside FacePixelMotionTracker.
        pixelMotionTracker.retainTracks(activeFaceOnlyTrackIds)
        val freshMotionEvidenceByTrackId = protectedMotionEvidence.asSequence()
            .filter { evidence ->
                evidence.timestampUs == ptsUs &&
                    activeFaceOnlyTrackIds.contains(evidence.trackId)
            }
            .associateBy { it.trackId }
        val freshBodyMotionTrackIds = freshMotionEvidenceByTrackId.keys.toCollection(linkedSetOf())
        recentBodyMotionByTrackId.keys.retainAll(activeFaceOnlyTrackIds)
        freshMotionEvidenceByTrackId.forEach { (trackId, evidence) ->
            recentBodyMotionByTrackId[trackId] = RecentBodyMotionGeometry(
                bbox = evidence.detection.bbox,
                confidence = evidence.detection.confidence,
                footY = evidence.detection.footY,
                ptsUs = ptsUs
            )
        }
        val recentBodyMotionBridgeTrackIds = linkedSetOf<Int>()
        val geometryPersonByTrackId = activeFaceOnlyTrackIds.associateWith { trackId ->
            val person = personsById.getValue(trackId)
            val evidence = if (!person.observedThisFrame) freshMotionEvidenceByTrackId[trackId] else null
            val recent = if (!person.observedThisFrame && evidence == null) {
                recentBodyMotionByTrackId[trackId]?.takeIf { cached ->
                    val ageUs = ptsUs - cached.ptsUs
                    ageUs in 0L..FaceOnlyDormancyPolicy.MAX_DIRECT_UNOBSERVED_AGE_US
                }
            } else {
                null
            }
            if (evidence?.detection?.mask != null) {
                person.copy(
                    bbox = evidence.detection.bbox,
                    mask = evidence.detection.mask,
                    confidence = evidence.detection.confidence,
                    observedThisFrame = false,
                    footY = evidence.detection.footY
                )
            } else if (recent != null) {
                recentBodyMotionBridgeTrackIds += trackId
                person.copy(
                    bbox = recent.bbox,
                    mask = null,
                    confidence = recent.confidence,
                    observedThisFrame = false,
                    footY = recent.footY
                )
            } else {
                person
            }
        }
        lastObservedPtsUsByTrackId.keys.retainAll(activeFaceOnlyTrackIds)
        val dormantBeforeFrame = dormantFaceOnlyTrackIds.toSet()
        val dormantExactReacquiredTrackIds = linkedSetOf<Int>()
        activeFaceOnlyTrackIds.forEach { trackId ->
            val person = personsById[trackId] ?: return@forEach
            if (person.observedThisFrame) {
                lastObservedPtsUsByTrackId[trackId] = ptsUs
                // Exact YOLO ownership supersedes any ambiguity-only body bbox.
                // Do not let a pre-reacquisition bridge geometry leak into a
                // later miss after identity has already been re-established.
                recentBodyMotionByTrackId.remove(trackId)
                if (dormantBeforeFrame.contains(trackId)) {
                    // Exact YOLO ownership is stronger than the hidden dormant
                    // anchor. Drop that stale local ROI/size seed and reacquire
                    // from the current exact person bbox instead.
                    cachedFaceByTrackId.remove(trackId)
                    lastDetectorAttemptPtsUsByTrackId.remove(trackId)
                    pixelMotionTracker.remove(trackId)
                    occlusionHoldByTrackId.remove(trackId)
                    dormantExactReacquiredTrackIds += trackId
                }
            }
        }
        val hasFreshOrRecentBodyMotionByTrackId = activeFaceOnlyTrackIds.associateWith { trackId ->
            freshMotionEvidenceByTrackId.containsKey(trackId) ||
                recentBodyMotionBridgeTrackIds.contains(trackId)
        }
        val renderModeByTrackId = activeFaceOnlyTrackIds.associateWith { trackId ->
            val person = personsById[trackId] ?: return@associateWith FaceOnlyRenderMode.DORMANT
            val geometryPerson = geometryPersonByTrackId.getValue(trackId)
            val cachedFace = cachedFaceByTrackId[trackId]
            val cachedFaceAgeUs = cachedFace?.let { ptsUs - it.lastTrustedPtsUs }
            val wasDormant = dormantBeforeFrame.contains(trackId)
            // Once a face has entered dormancy, body motion may open only a
            // detector probe. It must not directly make the sticker renderable.
            val hasFreshBodyMotion = !wasDormant && hasFreshOrRecentBodyMotionByTrackId.getValue(trackId)
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
                hasBodyMask = geometryPerson.mask != null && !wasDormant,
                hasFreshBodyMotionEvidence = hasFreshBodyMotion
            )
        }
        val roiPixelStateStatusByTrackId = activeFaceOnlyTrackIds.associateWith { trackId ->
            pixelMotionTracker.roiStateStatus(trackId, ptsUs)
        }
        val roiPixelMotionCandidateTrackIds = roiPixelStateStatusByTrackId
            .filterValues { it == FacePixelMotionTracker.RoiStateStatus.USABLE }
            .keys
            .toCollection(linkedSetOf())
        val baseRenderableFaceOnlyTrackIds = renderModeByTrackId
            .filterValues { it != FaceOnlyRenderMode.DORMANT }
            .keys
            .toCollection(linkedSetOf())
        val bodyCompensatedTrackIds = renderModeByTrackId
            .filterValues { it == FaceOnlyRenderMode.BODY_MASK_COMPENSATED }
            .keys
            .toCollection(linkedSetOf())
        val dormantReactivationProbeCandidates = activeFaceOnlyTrackIds
            .filterTo(linkedSetOf()) { trackId ->
                FaceOnlyDormantReactivationPolicy.shouldProbe(
                    wasDormant = dormantBeforeFrame.contains(trackId),
                    observedThisFrame = personsById[trackId]?.observedThisFrame == true,
                    hasFreshBodyMotion = freshMotionEvidenceByTrackId.containsKey(trackId),
                    hasTrustedFace = cachedFaceByTrackId.containsKey(trackId)
                )
            }
        val dormantProbeMotionRejectedTrackIds = dormantReactivationProbeCandidates
            .filterTo(linkedSetOf()) { trackId ->
                val cached = cachedFaceByTrackId[trackId] ?: return@filterTo true
                val person = geometryPersonByTrackId[trackId] ?: return@filterTo true
                val translation = PersonBboxMotionEstimator.estimate(
                    previous = cached.trustedPersonBbox,
                    current = person.bbox
                )
                !FaceOnlyDormantReactivationPolicy.isProbeTranslationSafe(
                    dx = translation.dx,
                    dy = translation.dy,
                    trustedRadiusX = cached.radiusX,
                    trustedRadiusY = cached.radiusY
                )
            }
        val dormantReactivationProbeTrackIds = dormantReactivationProbeCandidates
            .filterTo(linkedSetOf()) { !dormantProbeMotionRejectedTrackIds.contains(it) }
        val processingFaceOnlyTrackIds = linkedSetOf<Int>().apply {
            addAll(baseRenderableFaceOnlyTrackIds)
            addAll(dormantReactivationProbeTrackIds)
            // A detector-seeded 256x256 ROI tracklet may prove current face
            // pixels even after the body lifecycle provisionally says DORMANT.
            // Inclusion here only permits a current-pixel probe; it does not
            // make the track renderable until matchRoi succeeds below.
            addAll(roiPixelMotionCandidateTrackIds)
        }

        // Dormancy stops rendering and temporal extrapolation, but it must not
        // destroy the last *detected* face anchor. Fresh current-frame body
        // motion evidence may later arrive while strict identity commit remains
        // ambiguous; keeping this hidden anchor lets that fresh evidence resume
        // FACE_ONLY without rendering stale geometry in the intervening frames.
        // The anchor is removed when the protected FACE_ONLY identity leaves the
        // active selection, or when exact YOLO ownership returns and current-body
        // face acquisition can safely replace the dormant seed.
        cachedFaceByTrackId.keys.retainAll(activeFaceOnlyTrackIds)
        lastDetectorAttemptPtsUsByTrackId.keys.retainAll(processingFaceOnlyTrackIds)
        temporalStabilizer.retainTracks(baseRenderableFaceOnlyTrackIds)
        pixelMotionTracker.retainRoiTracks(activeFaceOnlyTrackIds)
        occlusionHoldByTrackId.keys.retainAll(activeFaceOnlyTrackIds)

        val detectorPlanByTrackId = linkedMapOf<Int, FaceHeadRoiPlan>()
        val localDetectorTrackIds = linkedSetOf<Int>()
        processingFaceOnlyTrackIds.sorted().forEach { trackId ->
            val trackedPerson = personsById[trackId] ?: return@forEach
            val person = geometryPersonByTrackId[trackId] ?: trackedPerson
            if (person.state == TrackState.REMOVED) return@forEach
            val cached = cachedFaceByTrackId[trackId]
            val renderMode = renderModeByTrackId[trackId] ?: FaceOnlyRenderMode.DORMANT
            val isDormantProbe = dormantReactivationProbeTrackIds.contains(trackId)
            val hasFreshBodyMotion = hasFreshOrRecentBodyMotionByTrackId[trackId] == true
            val roiTrackRegion = pixelMotionTracker.currentRoiRegion(trackId, ptsUs)
            val localPlan = if (roiTrackRegion != null) {
                // Always center the next high-resolution crop on the last
                // current-pixel face center. The tracker itself has a separate
                // detector-seed hard age and per-frame anti-drift gates.
                planLocalRoiAround(roiTrackRegion)
            } else if (isDormantProbe && cached != null) {
                planDormantReactivationLocalRoi(
                    cached = cached,
                    person = person
                )
            } else if (renderMode != FaceOnlyRenderMode.DORMANT && cached != null) {
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
        val dormantReactivatedTrackIds = linkedSetOf<Int>()
        val dormantPixelMotionBridgeTrackIds = linkedSetOf<Int>()
        val dormantPixelMotionRejectedTrackIds = linkedSetOf<Int>()
        val pixelMotionTrackIds = linkedSetOf<Int>()
        val pixelMotionRejectedTrackIds = linkedSetOf<Int>()
        val pixelMotionRejectReasonByTrackId = linkedMapOf<Int, String>()
        val occlusionHoldTrackIds = linkedSetOf<Int>()
        val occlusionReacquireDetectorTrackIds = linkedSetOf<Int>()
        var extraOcclusionDetectorCalls = 0
        var pixelMotionMs = 0.0
        var roiReadbackMs = 0.0
        var maskBuildMs = 0.0
        val stickerPlacements = mutableListOf<FaceStickerPlacement>()

        for (trackId in processingFaceOnlyTrackIds.sorted()) {
            val trackedPerson = personsById[trackId] ?: continue
            val person = geometryPersonByTrackId[trackId] ?: trackedPerson
            if (person.state == TrackState.REMOVED) continue
            val renderMode = renderModeByTrackId[trackId] ?: FaceOnlyRenderMode.DORMANT
            val isDormantProbe = dormantReactivationProbeTrackIds.contains(trackId)
            val hasFreshBodyMotion = hasFreshOrRecentBodyMotionByTrackId[trackId] == true

            val plan = detectorPlanByTrackId[trackId]

            var region: FacePrivacyEllipse? = null
            var trustedCurrentPixelCenter = false
            var roiRgba: ByteBuffer? = null
            var detectorSeedRgba: ByteBuffer? = null
            var detectorSeedPlan: FaceHeadRoiPlan? = null
            var pixelRejectReason: FacePixelMotionTracker.RoiRejectReason? = null
            val hasRoiPixelState = pixelMotionTracker.hasUsableRoiState(trackId, ptsUs)
            if (plan != null && (hasRoiPixelState || dueDetectorTrackIds.contains(trackId))) {
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
                roiRgba = roiFbo.readRgbaPixels()
                roiReadbackMs += (System.nanoTime() - roiStartNs) / 1_000_000.0

                if (hasRoiPixelState) {
                    val pixelMotionStartNs = System.nanoTime()
                    val pixelOutcome = try {
                        pixelMotionTracker.matchRoiDetailed(
                            trackId = trackId,
                            rgbaTopDown = requireNotNull(roiRgba),
                            roiPlan = plan,
                            personBbox = person.bbox,
                            personObservedThisFrame = trackedPerson.observedThisFrame,
                            ptsUs = ptsUs
                        )
                    } finally {
                        pixelMotionMs += (System.nanoTime() - pixelMotionStartNs) / 1_000_000.0
                    }
                    val pixelMatch = pixelOutcome.match
                    if (pixelMatch != null) {
                        region = pixelMatch.region
                        trustedCurrentPixelCenter = true
                        pixelMotionTrackIds += trackId
                        if (renderMode == FaceOnlyRenderMode.DORMANT) {
                            dormantPixelMotionBridgeTrackIds += trackId
                        }
                    } else {
                        pixelMotionRejectedTrackIds += trackId
                        pixelRejectReason = pixelOutcome.rejectReason
                        pixelOutcome.rejectReason?.let { reason ->
                            pixelMotionRejectReasonByTrackId[trackId] = reason.name
                        }
                        if (renderMode == FaceOnlyRenderMode.DORMANT) {
                            dormantPixelMotionRejectedTrackIds += trackId
                        }
                    }
                }
            }

            val appearanceOcclusionReject =
                FaceOcclusionBridgePolicy.isAppearanceOcclusionReject(pixelRejectReason)
            val normalDetectorDue = dueDetectorTrackIds.contains(trackId)
            val shouldCallDetector = normalDetectorDue

            if (shouldCallDetector && plan != null && roiRgba != null) {
                lastDetectorAttemptPtsUsByTrackId[trackId] = ptsUs
                val cachedBeforeAttempt = cachedFaceByTrackId[trackId]
                try {
                    val locatorResult = locator.detectRgbaTopDown(
                        rgba = requireNotNull(roiRgba),
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
                    val selectedFace = if (isDormantProbe) {
                        FaceRoiCandidateSelector.select(
                            faces = locatorResult.observations,
                            roiWidth = FACE_ROI_SIZE,
                            roiHeight = FACE_ROI_SIZE,
                            anchorX = plan.anchorX,
                            anchorY = plan.anchorY,
                            maxAnchorDistanceRatio = DORMANT_REACTIVATION_MAX_ANCHOR_DISTANCE_RATIO
                        )
                    } else if (renderMode == FaceOnlyRenderMode.DORMANT && appearanceOcclusionReject) {
                        FaceRoiCandidateSelector.select(
                            faces = locatorResult.observations,
                            roiWidth = FACE_ROI_SIZE,
                            roiHeight = FACE_ROI_SIZE,
                            anchorX = plan.anchorX,
                            anchorY = plan.anchorY,
                            maxAnchorDistanceRatio = OCCLUSION_REACQUIRE_MAX_ANCHOR_DISTANCE_RATIO
                        )
                    } else {
                        FaceRoiCandidateSelector.select(
                            faces = locatorResult.observations,
                            roiWidth = FACE_ROI_SIZE,
                            roiHeight = FACE_ROI_SIZE,
                            anchorX = plan.anchorX,
                            anchorY = plan.anchorY
                        )
                    }
                    if (locatorResult.observations.isNotEmpty() && selectedFace == null) {
                        detectorRejectedCallCount++
                        detectorRejectedTrackIds += trackId
                    }
                    if (selectedFace != null) {
                        val detectedRegion = FacePrivacyRegionResolver.resolve(
                            personBbox = person.bbox,
                            roiPlan = plan,
                            selectedFace = selectedFace
                        )
                        region = if (
                            detectedRegion != null &&
                            isDormantProbe &&
                            cachedBeforeAttempt != null
                        ) {
                            preserveHiddenTrustedSizeForDormantReactivation(
                                detected = detectedRegion,
                                cached = cachedBeforeAttempt
                            )
                        } else if (
                            detectedRegion != null &&
                            localDetectorTrackIds.contains(trackId) &&
                            cachedBeforeAttempt != null
                        ) {
                            preserveTrustedSizeForLocalDetection(
                                detected = detectedRegion,
                                cached = cachedBeforeAttempt
                            )
                        } else {
                            detectedRegion
                        }
                        // A real detector hit supersedes the pixel center. The
                        // detector position gate remains active; only a verified
                        // current-pixel match bypasses that residual clamp.
                        trustedCurrentPixelCenter = false
                        detectorSeedRgba = roiRgba
                        detectorSeedPlan = plan
                        if (renderMode == FaceOnlyRenderMode.DORMANT) {
                            dormantReactivatedTrackIds += trackId
                        }
                    }
                } catch (t: Throwable) {
                    if (isDormantProbe) {
                        Log.w(TAG, "Dormant face reactivation probe failed for track=$trackId; keeping pixel result if available", t)
                    } else {
                        Log.w(TAG, "Face ROI detection failed for track=$trackId; keeping ROI pixel result before fallback", t)
                    }
                }
            }

            val needsExpandedOcclusionReacquire =
                region == null &&
                    renderMode == FaceOnlyRenderMode.DORMANT &&
                    appearanceOcclusionReject &&
                    extraOcclusionDetectorCalls < MAX_OCCLUSION_REACQUIRE_EXTRA_CALLS_PER_FRAME
            if (needsExpandedOcclusionReacquire) {
                val reacquirePlan = planOcclusionReacquireRoi(trackId, ptsUs)
                if (reacquirePlan != null) {
                    extraOcclusionDetectorCalls++
                    occlusionReacquireDetectorTrackIds += trackId
                    val roiStartNs = System.nanoTime()
                    roiRenderer.renderToFbo(
                        textureId = frameTexture,
                        texMatrix = texMatrix,
                        sourceRect = reacquirePlan.sourceRect,
                        sourceWidth = mapper.srcWidth,
                        sourceHeight = mapper.srcHeight,
                        fbo = roiFbo,
                        textureType = textureType
                    )
                    val reacquireRgba = roiFbo.readRgbaPixels()
                    roiReadbackMs += (System.nanoTime() - roiStartNs) / 1_000_000.0
                    lastDetectorAttemptPtsUsByTrackId[trackId] = ptsUs
                    try {
                        val locatorResult = locator.detectRgbaTopDown(
                            rgba = reacquireRgba,
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
                            anchorX = reacquirePlan.anchorX,
                            anchorY = reacquirePlan.anchorY,
                            maxAnchorDistanceRatio = OCCLUSION_REACQUIRE_MAX_ANCHOR_DISTANCE_RATIO
                        )
                        if (locatorResult.observations.isNotEmpty() && selectedFace == null) {
                            detectorRejectedCallCount++
                            detectorRejectedTrackIds += trackId
                        }
                        if (selectedFace != null) {
                            val detectedRegion = FacePrivacyRegionResolver.resolve(
                                personBbox = person.bbox,
                                roiPlan = reacquirePlan,
                                selectedFace = selectedFace
                            )
                            val cachedBeforeAttempt = cachedFaceByTrackId[trackId]
                            region = if (detectedRegion != null && cachedBeforeAttempt != null) {
                                preserveHiddenTrustedSizeForDormantReactivation(
                                    detected = detectedRegion,
                                    cached = cachedBeforeAttempt
                                )
                            } else {
                                detectedRegion
                            }
                            if (region != null) {
                                trustedCurrentPixelCenter = false
                                detectorSeedRgba = reacquireRgba
                                detectorSeedPlan = reacquirePlan
                                dormantReactivatedTrackIds += trackId
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Expanded occlusion face reacquire failed for track=$trackId", t)
                    }
                }
            }

            if (
                region == null &&
                renderMode == FaceOnlyRenderMode.DORMANT &&
                appearanceOcclusionReject
            ) {
                resolveOcclusionHold(trackId, person, ptsUs)?.let { held ->
                    region = held
                    occlusionHoldTrackIds += trackId
                }
            }

            if (
                region == null &&
                !isDormantProbe &&
                renderMode != FaceOnlyRenderMode.DORMANT
            ) {
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
                val trustedDetectedRadiusX = if (region.source == FacePrivacyRegionSource.DETECTED_FACE) region.radiusX else null
                val trustedDetectedRadiusY = if (region.source == FacePrivacyRegionSource.DETECTED_FACE) region.radiusY else null
                val rawCenterX = region.centerX
                val rawCenterY = region.centerY
                region = temporalStabilizer.stabilize(
                    trackId = trackId,
                    rawRegion = region,
                    personBbox = person.bbox,
                    personObservedThisFrame = person.observedThisFrame,
                    ptsUs = ptsUs,
                    trustedCurrentPixelCenter = trustedCurrentPixelCenter
                )
                if (region.source == FacePrivacyRegionSource.DETECTED_FACE || trustedCurrentPixelCenter) {
                    occlusionHoldByTrackId[trackId] = OcclusionHoldGeometry(
                        region = region,
                        personBbox = person.bbox,
                        ptsUs = ptsUs
                    )
                }
                if (
                    kotlin.math.abs(region.centerX - rawCenterX) > POSITION_CLAMP_DIAGNOSTIC_EPSILON_PX ||
                    kotlin.math.abs(region.centerY - rawCenterY) > POSITION_CLAMP_DIAGNOSTIC_EPSILON_PX
                ) {
                    positionClampedTrackIds += trackId
                }
                if (region.source == FacePrivacyRegionSource.DETECTED_FACE) {
                    if (isDormantProbe) {
                        dormantReactivatedTrackIds += trackId
                    }
                    // Commit only the post-gate center. A detector observation can
                    // be close enough to the broad/local ROI anchor to pass
                    // ownership yet still be a non-physical one-frame jump. If
                    // the raw center were cached before the temporal position
                    // gate, the next detector ROI would immediately move to that
                    // bad location and defeat the visual clamp on the following
                    // frame. The stabilized detected center becomes the trusted
                    // ROI seed instead.
                    if (person.bbox.width > 1f && person.bbox.height > 1f) {
                        cachedFaceByTrackId[trackId] = CachedFaceGeometry(
                            centerX = region.centerX,
                            centerY = region.centerY,
                            radiusX = trustedDetectedRadiusX ?: region.radiusX,
                            radiusY = trustedDetectedRadiusY ?: region.radiusY,
                            trustedPersonBbox = person.bbox,
                            lastTrustedPtsUs = ptsUs
                        )
                    }
                    val seedRgba = detectorSeedRgba ?: roiRgba
                    val seedPlan = detectorSeedPlan ?: plan
                    if (seedRgba != null && seedPlan != null) {
                        pixelMotionTracker.seedRoi(
                            trackId = trackId,
                            rgbaTopDown = requireNotNull(seedRgba),
                            roiPlan = seedPlan,
                            detected = FacePrivacyEllipse(
                                centerX = region.centerX,
                                centerY = region.centerY,
                                radiusX = trustedDetectedRadiusX ?: region.radiusX,
                                radiusY = trustedDetectedRadiusY ?: region.radiusY,
                                source = FacePrivacyRegionSource.DETECTED_FACE
                            ),
                            personBbox = person.bbox,
                            ptsUs = ptsUs
                        )
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

        val renderableFaceOnlyTrackIds = linkedSetOf<Int>().apply {
            addAll(baseRenderableFaceOnlyTrackIds)
            addAll(dormantReactivatedTrackIds)
            addAll(dormantPixelMotionBridgeTrackIds)
            addAll(occlusionHoldTrackIds)
        }
        val dormantSuppressedTrackIds = activeFaceOnlyTrackIds
            .filterTo(linkedSetOf()) { !renderableFaceOnlyTrackIds.contains(it) }
        val dormantSuppressionReasonByTrackId = dormantSuppressedTrackIds.associateWith { trackId ->
            pixelMotionRejectReasonByTrackId[trackId] ?: when (roiPixelStateStatusByTrackId[trackId]) {
                FacePixelMotionTracker.RoiStateStatus.MISSING -> "NO_PIXEL_STATE"
                FacePixelMotionTracker.RoiStateStatus.EVIDENCE_GAP_EXPIRED -> "EVIDENCE_GAP_EXPIRED"
                FacePixelMotionTracker.RoiStateStatus.DETECTOR_SEED_EXPIRED -> "DETECTOR_SEED_EXPIRED"
                FacePixelMotionTracker.RoiStateStatus.INVALID_TIME -> "INVALID_TIME"
                FacePixelMotionTracker.RoiStateStatus.USABLE -> "ROI_PLAN_UNAVAILABLE"
                null -> "NO_PIXEL_STATE"
            }
        }
        dormantFaceOnlyTrackIds.retainAll(activeFaceOnlyTrackIds)
        dormantFaceOnlyTrackIds.removeAll(renderableFaceOnlyTrackIds)
        dormantFaceOnlyTrackIds.addAll(dormantSuppressedTrackIds)

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
            freshBodyMotionTrackIds = freshBodyMotionTrackIds.intersect(
                bodyCompensatedTrackIds + dormantReactivatedTrackIds
            ),
            recentBodyMotionBridgeTrackIds = recentBodyMotionBridgeTrackIds.intersect(bodyCompensatedTrackIds),
            dormantReactivationProbeTrackIds = dormantReactivationProbeTrackIds,
            dormantProbeMotionRejectedTrackIds = dormantProbeMotionRejectedTrackIds,
            dormantReactivatedTrackIds = dormantReactivatedTrackIds,
            dormantExactReacquiredTrackIds = dormantExactReacquiredTrackIds,
            dormantSuppressedTrackIds = dormantSuppressedTrackIds,
            dormantPixelMotionBridgeTrackIds = dormantPixelMotionBridgeTrackIds,
            pixelMotionTrackIds = pixelMotionTrackIds,
            pixelMotionRejectedTrackIds = pixelMotionRejectedTrackIds,
            pixelMotionRejectReasonByTrackId = pixelMotionRejectReasonByTrackId,
            dormantSuppressionReasonByTrackId = dormantSuppressionReasonByTrackId,
            occlusionHoldTrackIds = occlusionHoldTrackIds,
            occlusionReacquireDetectorTrackIds = occlusionReacquireDetectorTrackIds,
            pixelMotionMs = pixelMotionMs,
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
        private const val OCCLUSION_REACQUIRE_ROI_DIAMETER_FACTOR = 4.2f
        private const val OCCLUSION_REACQUIRE_MAX_ANCHOR_DISTANCE_RATIO = 0.16f
        private const val DORMANT_REACTIVATION_MAX_ANCHOR_DISTANCE_RATIO = 0.10f
        private const val BODY_COMPENSATION_MAX_SIZE_EXPANSION = 0.18f
        private const val POSITION_CLAMP_DIAGNOSTIC_EPSILON_PX = 0.25f
        private const val MAX_OCCLUSION_REACQUIRE_EXTRA_CALLS_PER_FRAME = 1
        private const val SLOW_STAGE_LOG_THRESHOLD_MS = 20.0

        fun create(context: Context, mapper: ModelCoordinateMapper): FaceOnlyPrivacyFrameProcessor {
            val locator = requireNotNull(FaceLocatorProvider.createOrNull(context, enabled = true)) {
                "Face locator was not available after explicit enable"
            }
            return FaceOnlyPrivacyFrameProcessor(locator = locator, mapper = mapper)
        }
    }
}
