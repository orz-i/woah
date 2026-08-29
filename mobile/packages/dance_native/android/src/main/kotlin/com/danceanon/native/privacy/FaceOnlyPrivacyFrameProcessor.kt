package com.danceanon.native.privacy

import android.content.Context
import android.util.Log
import com.danceanon.native.face.FaceHeadRoiPlanner
import com.danceanon.native.face.FaceLocator
import com.danceanon.native.face.FaceLocatorProvider
import com.danceanon.native.face.FaceRoiCandidateSelector
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.render.FaceRoiRenderer
import com.danceanon.native.render.InferenceFbo
import com.danceanon.native.render.SourceTextureType
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson

data class FaceOnlyPrivacyFrameResult(
    val resolvedPrivacy: ResolvedCompositorMasks?,
    val readyForRender: Boolean,
    val unresolvedTrackIds: Set<Int>,
    val detectedTrackIds: Set<Int>,
    val fallbackTrackIds: Set<Int>,
    val escalatedFullBodyTrackIds: Set<Int>,
    val faceInferenceMs: Double
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
    private val roiFbo: InferenceFbo = InferenceFbo(FACE_ROI_SIZE)
) : AutoCloseable {

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
                fallbackTrackIds = emptySet(),
                escalatedFullBodyTrackIds = emptySet(),
                faceInferenceMs = 0.0
            )
        }

        val personsById = persons.associateBy { it.id }
        val missingTrackIds = faceOnlyTrackIds.filterTo(linkedSetOf()) { id ->
            val person = personsById[id]
            person == null || person.state == TrackState.REMOVED
        }
        val faceMasks = linkedMapOf<Int, NativeMask>()
        val detected = linkedSetOf<Int>()
        val fallback = linkedSetOf<Int>()
        var inferenceMs = 0.0

        for (trackId in faceOnlyTrackIds.sorted()) {
            val person = personsById[trackId] ?: continue
            if (person.state == TrackState.REMOVED) continue

            val plan = FaceHeadRoiPlanner.plan(
                personBbox = person.bbox,
                frameWidth = mapper.srcWidth,
                frameHeight = mapper.srcHeight
            )

            var selectedFace: com.danceanon.native.face.FaceRoiCandidateSelection? = null
            if (plan != null) {
                try {
                    roiRenderer.renderToFbo(
                        textureId = frameTexture,
                        texMatrix = texMatrix,
                        sourceRect = plan.sourceRect,
                        sourceWidth = mapper.srcWidth,
                        sourceHeight = mapper.srcHeight,
                        fbo = roiFbo,
                        textureType = textureType
                    )
                    val locatorResult = locator.detectRgbaTopDown(
                        rgba = roiFbo.readRgbaPixels(),
                        width = FACE_ROI_SIZE,
                        height = FACE_ROI_SIZE
                    )
                    inferenceMs += locatorResult.inferenceMs
                    selectedFace = FaceRoiCandidateSelector.select(
                        faces = locatorResult.observations,
                        roiWidth = FACE_ROI_SIZE,
                        roiHeight = FACE_ROI_SIZE,
                        anchorX = plan.anchorX,
                        anchorY = plan.anchorY
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Face ROI detection failed for track=$trackId; using head fallback", t)
                }
            }

            val region = FacePrivacyRegionResolver.resolve(
                personBbox = person.bbox,
                roiPlan = plan,
                selectedFace = selectedFace
            )
            if (region != null) {
                FacePrivacyMaskBuilder.build(listOf(region), mapper)?.let { mask ->
                    faceMasks[trackId] = mask
                }
                when (region.source) {
                    FacePrivacyRegionSource.DETECTED_FACE -> detected += trackId
                    FacePrivacyRegionSource.YOLO_HEAD_FALLBACK -> fallback += trackId
                }
            }
        }

        // FULL_BODY targets are owned by the primary compositor path. They must
        // not become "unselected foreground" inside this secondary FACE_ONLY
        // resolver or they could carve a selected face during overlap. Preserve
        // identity/geometry here but remove only their secondary-pass mask.
        val secondaryPersons = persons.map { person ->
            if (fullBodyTrackIds.contains(person.id)) person.copy(mask = null) else person
        }
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

        return FaceOnlyPrivacyFrameResult(
            resolvedPrivacy = resolved,
            readyForRender = unresolved.isEmpty() && resolved?.hasPrivacy == true,
            unresolvedTrackIds = unresolved,
            detectedTrackIds = detected,
            fallbackTrackIds = fallback,
            escalatedFullBodyTrackIds = adaptation.escalatedFullBodyTrackIds,
            faceInferenceMs = inferenceMs
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

        fun create(context: Context, mapper: ModelCoordinateMapper): FaceOnlyPrivacyFrameProcessor {
            val locator = requireNotNull(FaceLocatorProvider.createOrNull(context, enabled = true)) {
                "Face locator was not available after explicit enable"
            }
            return FaceOnlyPrivacyFrameProcessor(locator = locator, mapper = mapper)
        }
    }
}
