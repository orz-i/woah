package art.gaoge.dance.engine.tracking

import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import art.gaoge.dance.engine.inference.PersonDetection
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapturedTrackerReplayTest {
    @Test fun independentHeroSurvivesExpandedNeighborReservation() {
        val tracker = TrackManager(diagnosticsEnabled = false)
        var frames = 0
        var unobserved = 0
        var changed = 0
        var baselineMissing = 0
        var criticalFrames = 0
        ZipInputStream(javaClass.getResourceAsStream("/group-hd-hero-190.zip")!!).use { zip ->
            while (zip.nextEntry != null) {
                val data = DataInputStream(GZIPInputStream(ByteArrayInputStream(zip.readBytes())))
                assertEquals(0x464B5452, data.readInt()); assertEquals(1, data.readInt())
                val pts = data.readLong()
                fun ids(): List<Int> = List(data.readInt()) { data.readInt() }
                val protected = ids().toSet(); val body = ids().toSet(); val face = ids().toSet()
                val assignedCount = data.readInt()
                val assigned = if (assignedCount >= 0) List(assignedCount) { data.readInt() } else null
                fun rect() = FloatRect(data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat())
                fun optionalRect() = if (data.readBoolean()) rect() else null
                val detections = List(data.readInt()) {
                    val box = rect(); val confidence = data.readFloat(); val foot = data.readFloat()
                    val mask = if (data.readBoolean()) {
                        val w = data.readInt(); val h = data.readInt()
                        val sw = data.readInt(); val sh = data.readInt()
                        val mapper = if (data.readBoolean()) ModelCoordinateMapper(sw, sh, data.readInt(), data.readInt()) else null
                        val roi = optionalRect(); val sampling = optionalRect()
                        val bytes = ByteArray(data.readInt()); data.readFully(bytes)
                        NativeMask(w, h, ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }, sw, sh, mapper, roi, sampling)
                    } else null
                    PersonDetection(box, confidence, mask, foot)
                }
                tracker.setIdentityProtectedTrackIds(protected)
                tracker.setPrivacySelectedTrackIds(body)
                tracker.setFacePrivacySelectedTrackIds(face)
                tracker.setPrivacyOffscreenDormancyEnabled(body.isNotEmpty())
                val result = when {
                    assigned != null -> tracker.initializeWithAssignedIds(detections, assigned)
                    detections.isEmpty() -> tracker.predict(pts)
                    else -> tracker.update(detections, pts)
                }
                val count = data.readInt()
                assertTrue(result.filter { it.observedThisFrame }.all { track -> detections.any { it.bbox == track.bbox } },
                    "an observed identity must be backed by a current detection at $pts")
                repeat(count) {
                    val id = data.readInt(); val bounds = rect(); val state = data.readUTF()
                    val observed = data.readBoolean(); val age = data.readInt()
                    val track = result.singleOrNull { it.id == id }
                    if (id == 6 && !observed) baselineMissing++
                    if (track == null || bounds != track.bbox || observed != track.observedThisFrame || state != track.state.name) changed++
                }
                assertEquals(-1, data.read())
                val hero = result.single { it.id == 6 }
                if (!hero.observedThisFrame) unobserved++
                // Manually reviewed blue-top subject stays separate and visible
                // at the original failure (source frames 162..168).
                if (pts in 6_480_000L..6_720_000L) {
                    val blue = detections.maxBy { art.gaoge.dance.engine.core.FrameKernel.iou(
                        it.bbox, FloatRect(1210f, 85f, 1528f, 1030f)) }
                    assertTrue(art.gaoge.dance.engine.core.FrameKernel.iou(blue.bbox,
                        FloatRect(1210f, 85f, 1528f, 1030f)) > 0.75f)
                    assertTrue(hero.observedThisFrame, "visible blue hero lost at $pts")
                    assertEquals(blue.bbox, hero.bbox, "wrong hero identity at $pts")
                    criticalFrames++
                }
                frames++
            }
        }
        assertEquals(190, frames)
        assertEquals(7, criticalFrames)
        assertEquals(22, baselineMissing, "the recorded pre-fix device run must include the known failure")
        assertEquals(0, unobserved, "all 190 captured frames contain a supported current hero observation")
        println("captured_frames=$frames changed_track_snapshots=$changed hero_missing_before=$baselineMissing hero_missing_after=$unobserved")
    }
}
