package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.camera.FollowCameraIdentityHistory
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.PersonDetection
import kotlin.test.Test
import kotlin.test.assertEquals

class TrackManagerFollowModeIndependenceTest {
    private fun person(left: Float, top: Float, right: Float, bottom: Float) =
        PersonDetection(FloatRect(left, top, right, bottom), 0.90f)

    private fun signature(tracks: List<TrackedPerson>) = tracks.sortedBy { it.id }.map { track ->
        listOf(
            track.id, track.bbox, track.state, track.observedThisFrame,
            track.framesSinceLastObservation, track.occludedByTrackIds
        )
    }

    @Test
    fun cameraIdentityObservationDoesNotModifySourceTrackingForSameDetections() {
        val landscape = TrackManager(TrackingConfig(), diagnosticsEnabled = false)
        val portraitFollow = TrackManager(TrackingConfig(), diagnosticsEnabled = false)

        // A protected bystander, a selected camera protagonist (unprotected),
        // and a distinct co-observed track use the same initial identities.
        // The real portrait crop must not feed an identity assignment back
        // into the source tracker.
        val ids = listOf(1, 2, 8)
        val first = listOf(
            person(200f, 140f, 300f, 500f),
            person(540f, 250f, 690f, 640f),
            person(725f, 250f, 835f, 570f)
        )
        landscape.setProtectedTrackIds(setOf(1))
        portraitFollow.setProtectedTrackIds(setOf(1))
        assertEquals(
            signature(landscape.initializeWithAssignedIds(first, ids)),
            signature(portraitFollow.initializeWithAssignedIds(first, ids))
        )

        val cameraOnlyIdentityHistory = FollowCameraIdentityHistory()
        val frames = listOf(
            listOf(
                person(201f, 140f, 301f, 500f),
                person(542f, 280f, 690f, 635f),
                person(724f, 252f, 837f, 571f)
            ),
            listOf(
                person(205f, 142f, 305f, 502f),
                person(541f, 328f, 691f, 620f),
                person(728f, 255f, 835f, 570f)
            ),
            listOf(
                person(206f, 143f, 306f, 505f),
                person(543f, 386f, 691f, 612f),
                person(727f, 255f, 835f, 571f)
            ),
            listOf(
                person(207f, 144f, 307f, 504f),
                person(542f, 382f, 690f, 615f),
                person(727f, 257f, 835f, 571f)
            ),
            listOf(
                person(208f, 144f, 308f, 503f),
                person(544f, 360f, 691f, 624f),
                person(729f, 257f, 836f, 570f)
            )
        )
        frames.forEachIndexed { index, detections ->
            val pts = 17_867_000L + (index + 1L) * 33_333L
            val noFollowTracks = landscape.update(detections, pts)
            val followTracks = portraitFollow.update(detections, pts)
            cameraOnlyIdentityHistory.recordSelectedObservation(
                selectedId = 2,
                selectedObserved = followTracks.any {
                    it.id == 2 && it.observedThisFrame
                },
                tracks = followTracks
            )
            assertEquals(signature(noFollowTracks), signature(followTracks))
        }
    }
}
