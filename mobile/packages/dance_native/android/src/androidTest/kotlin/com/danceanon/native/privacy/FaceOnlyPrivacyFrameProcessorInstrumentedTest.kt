package com.danceanon.native.privacy

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.face.FaceLocator
import com.danceanon.native.face.FaceLocatorResult
import com.danceanon.native.face.FaceObservation
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.render.EglCore
import com.danceanon.native.render.RenderCoordinateConvention
import com.danceanon.native.render.SourceTextureType
import com.danceanon.native.tracking.ProtectedTrackMotionEvidence
import com.danceanon.native.tracking.TrackState
import com.danceanon.native.tracking.TrackedPerson
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class FaceOnlyPrivacyFrameProcessorInstrumentedTest {
    @Test
    fun unambiguousFaceProducesDetectedFacePrivacyMask() {
        withProcessor(FixedLocator(listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f)))) {
                processor, texture, mapper ->
            val target = person(7, FloatRect(220f, 40f, 420f, 350f))
            val result = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(target),
                faceOnlyTrackIds = setOf(7),
                ptsUs = 0L
            )
            assertTrue(result.readyForRender)
            assertEquals(setOf(7), result.detectedTrackIds)
            assertTrue(result.fallbackTrackIds.isEmpty())
            assertEquals(1, result.stickerPlacements.size)
            assertEquals(7, result.stickerPlacements.single().trackId)
            val mask = assertNotNull(result.resolvedPrivacy?.privacyMask)
            val stickerRect = result.stickerPlacements.single().sourceRect
            assertTrue(
                pixelAtSource(mask, mapper, stickerRect.centerX, stickerRect.centerY) > 0,
                "resolved privacy must cover the detected/sticker face center"
            )
            assertEquals(0, pixelAtSource(mask, mapper, 320f, 300f))
        }
    }

    private fun sourcePixelBitmap(centerX: Int, centerY: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(24, 24, 24))
        for (dy in -30..30) {
            for (dx in -30..30) {
                val x = centerX + dx
                val y = centerY + dy
                if (x !in 0 until FRAME_W || y !in 0 until FRAME_H) continue
                val r = (80 + (dx * 17 + dy * 7 + dx * dy * 3)).and(0xFF)
                val g = (60 + (dx * 5 - dy * 19 + dx * dx)).and(0xFF)
                val b = (40 + (dy * 13 - dx * 11 + dy * dy)).and(0xFF)
                bitmap.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return bitmap
    }

    private fun uploadSourceTexture(textureId: Int, centerX: Int, centerY: Int) {
        val bitmap = sourcePixelBitmap(centerX, centerY)
        try {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun currentPixelEvidenceKeepsDormantFaceRenderableUntilEvidenceActuallyBreaks() {
        val face = FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f)
        val locator = SequencedLocator(
            listOf(
                listOf(face),
                listOf(face),
                emptyList(),
                emptyList()
            )
        )
        withProcessor(locator) { processor, texture, mapper ->
            val observed = person(61, FloatRect(220f, 40f, 420f, 350f))
            // The synthetic locator maps this face to roughly (320,166) in the
            // 640x360 source frame. Put a real high-frequency source patch there
            // so the processor can seed its 256x256 ROI tracker from the same
            // pixels that MediaPipe sees.
            uploadSourceTexture(texture, 320, 166)

            // First acquire the real detector-owned source-space face center.
            val initial = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(observed),
                faceOnlyTrackIds = setOf(61),
                ptsUs = 0L
            )
            val initialRect = initial.stickerPlacements.single().sourceRect
            val initialSourceX = initialRect.centerX.roundToInt()
            val initialSourceY = initialRect.centerY.roundToInt()

            // A second detector hit refreshes the detector-owned ROI template.
            uploadSourceTexture(texture, initialSourceX, initialSourceY)
            val seeded = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(observed),
                faceOnlyTrackIds = setOf(61),
                ptsUs = 33_333L
            )
            assertEquals(setOf(61), seeded.detectedTrackIds)

            val lost = observed.copy(
                state = TrackState.LOST,
                observedThisFrame = false,
                framesSinceLastObservation = 30,
                mask = null
            )

            // While still inside the direct YOLO-miss window, detector failure
            // is bridged by the high-resolution source ROI and renews evidence.
            uploadSourceTexture(texture, initialSourceX + 12, initialSourceY - 7)
            val directPixel = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(lost),
                faceOnlyTrackIds = setOf(61),
                ptsUs = 100_000L
            )
            assertEquals(setOf(61), directPixel.pixelMotionTrackIds)
            assertTrue(directPixel.stickerPlacements.isNotEmpty())

            // YOLO ownership is now old enough that the legacy lifecycle would
            // become DORMANT. Current high-correlation face pixels must keep the
            // sticker renderable without committing any new TrackManager ID.
            uploadSourceTexture(texture, initialSourceX + 24, initialSourceY - 12)
            val dormantBridge = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(lost),
                faceOnlyTrackIds = setOf(61),
                ptsUs = 200_000L
            )
            assertEquals(setOf(61), dormantBridge.dormantPixelMotionBridgeTrackIds)
            assertEquals(setOf(61), dormantBridge.pixelMotionTrackIds)
            assertTrue(dormantBridge.dormantSuppressedTrackIds.isEmpty())
            assertTrue(dormantBridge.stickerPlacements.isNotEmpty())

            // No successful current pixel evidence for >150 ms invalidates the
            // tracklet; fail-closed dormancy must return immediately.
            val afterGap = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(lost),
                faceOnlyTrackIds = setOf(61),
                ptsUs = 351_001L
            )
            assertEquals(setOf(61), afterGap.dormantSuppressedTrackIds)
            assertEquals("EVIDENCE_GAP_EXPIRED", afterGap.dormantSuppressionReasonByTrackId[61])
            assertTrue(afterGap.dormantPixelMotionBridgeTrackIds.isEmpty())
            assertTrue(afterGap.stickerPlacements.isEmpty())
        }
    }

    @Test
    fun dormantProbeRejectsLargeAmbiguousBodyTranslationBeforeFaceDetection() {
        val locator = CountingLocator(
            listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f))
        )
        withProcessor(locator) { processor, texture, mapper ->
            val observed = person(53, FloatRect(220f, 40f, 420f, 350f))
            processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(observed),
                faceOnlyTrackIds = setOf(53),
                ptsUs = 0L
            )
            assertEquals(1, locator.calls)

            val lost = observed.copy(
                state = TrackState.LOST,
                observedThisFrame = false,
                framesSinceLastObservation = 60,
                mask = null
            )
            processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(lost),
                faceOnlyTrackIds = setOf(53),
                ptsUs = 900_000L
            )

            val farBbox = FloatRect(380f, 40f, 580f, 350f)
            val farMask = sourceRectMask(mapper, farBbox)
            val rejected = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(lost),
                faceOnlyTrackIds = setOf(53),
                protectedMotionEvidence = listOf(
                    ProtectedTrackMotionEvidence(
                        trackId = 53,
                        detectionIndex = 0,
                        detection = PersonDetection(
                            bbox = farBbox,
                            confidence = 0.90f,
                            mask = farMask
                        ),
                        assignedScore = 0.30f,
                        bboxIou = 0.30f,
                        maskIou = 0.10f,
                        timestampUs = 916_666L
                    )
                ),
                ptsUs = 916_666L
            )

            assertEquals(setOf(53), rejected.dormantProbeMotionRejectedTrackIds)
            assertTrue(rejected.dormantReactivationProbeTrackIds.isEmpty())
            assertTrue(rejected.dormantReactivatedTrackIds.isEmpty())
            assertEquals(setOf(53), rejected.dormantSuppressedTrackIds)
            assertTrue(rejected.stickerPlacements.isEmpty())
            assertEquals(1, locator.calls, "rejected long-distance motion must not call face detector")
        }
    }

    @Test
    fun localRefreshPersonBboxShapeChangeCannotRatchetTrustedFaceSize() {
        val locator = SequencedLocator(
            listOf(
                listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f)),
                listOf(FaceObservation(FloatRect(72f, 72f, 200f, 200f), 0.9f)),
                listOf(FaceObservation(FloatRect(72f, 72f, 200f, 200f), 0.9f))
            )
        )
        withProcessor(locator) { processor, texture, _ ->
            val original = person(32, FloatRect(220f, 40f, 420f, 350f))
            val first = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(original),
                faceOnlyTrackIds = setOf(32),
                ptsUs = 0L
            )
            val firstWidth = first.stickerPlacements.single().sourceRect.width

            val widened = original.copy(
                bbox = FloatRect(140f, 40f, 500f, 350f),
                footY = 350f
            )
            val second = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(widened),
                faceOnlyTrackIds = setOf(32),
                ptsUs = 33_333L
            )
            val secondWidth = second.stickerPlacements.single().sourceRect.width
            assertTrue(
                kotlin.math.abs(secondWidth - firstWidth) <= 0.5f,
                "person bbox shape must not scale local trusted face: first=$firstWidth second=$secondWidth"
            )

            val third = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(original),
                faceOnlyTrackIds = setOf(32),
                ptsUs = 66_666L
            )
            val thirdWidth = third.stickerPlacements.single().sourceRect.width
            assertTrue(
                kotlin.math.abs(thirdWidth - firstWidth) <= 0.5f,
                "repeated bbox shape changes must not ratchet cached face size: first=$firstWidth third=$thirdWidth"
            )
        }
    }

    @Test
    fun detectorIsThrottledAt60FpsAndTrustedFaceMovesWithCurrentPersonBox() {
        val locator = CountingLocator(
            listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f))
        )
        withProcessor(locator) { processor, texture, mapper ->
            val first = person(5, FloatRect(200f, 40f, 400f, 350f))
            val frame0 = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(first),
                faceOnlyTrackIds = setOf(5),
                ptsUs = 0L
            )
            assertEquals(1, frame0.detectorCallCount)
            assertEquals(setOf(5), frame0.detectedTrackIds)

            val moved = first.copy(
                bbox = FloatRect(220f, 40f, 420f, 350f),
                footY = 350f
            )
            val frame1 = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(moved),
                faceOnlyTrackIds = setOf(5),
                ptsUs = 16_666L
            )
            assertEquals(0, frame1.detectorCallCount)
            assertEquals(setOf(5), frame1.predictedTrackIds)
            assertEquals(1, locator.calls)
            val predictedMask = assertNotNull(frame1.resolvedPrivacy?.privacyMask)
            val detectedCenterX = frame0.stickerPlacements.single().sourceRect.centerX
            val predictedRect = frame1.stickerPlacements.single().sourceRect
            assertTrue(
                predictedRect.centerX >= detectedCenterX + 18f,
                "predicted face center must follow the current person-box translation"
            )
            assertTrue(
                pixelAtSource(predictedMask, mapper, predictedRect.centerX, predictedRect.centerY) > 0,
                "predicted privacy must cover the translated face center"
            )

            val frame2 = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(moved),
                faceOnlyTrackIds = setOf(5),
                ptsUs = 33_333L
            )
            assertEquals(1, frame2.detectorCallCount)
            assertEquals(2, locator.calls)
            assertEquals(1, frame2.stickerPlacements.size)
        }
    }

    @Test
    fun detectorMissAfterTrustedFaceKeepsShortLivedTrustedPrediction() {
        val locator = SequencedLocator(
            listOf(
                listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f)),
                emptyList()
            )
        )
        withProcessor(locator) { processor, texture, _ ->
            val target = person(12, FloatRect(220f, 40f, 420f, 350f))
            val detected = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(target),
                faceOnlyTrackIds = setOf(12),
                ptsUs = 0L
            )
            val detectedRect = detected.stickerPlacements.single().sourceRect

            val predicted = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(target),
                faceOnlyTrackIds = setOf(12),
                ptsUs = 66_666L
            )
            assertEquals(setOf(12), predicted.predictedTrackIds)
            assertTrue(predicted.fallbackTrackIds.isEmpty())
            val predictedRect = predicted.stickerPlacements.single().sourceRect
            assertTrue(
                predictedRect.width <= detectedRect.width * 1.20f,
                "detector miss must not inflate trusted face: detected=${detectedRect.width} predicted=${predictedRect.width}"
            )
            assertTrue(predictedRect.width >= detectedRect.width)
        }
    }

    @Test
    fun startupAcquisitionCoversAllFaceOnlyTracksBeforeSteadyStateBudget() {
        val locator = CountingLocator(
            listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f))
        )
        withProcessor(locator) { processor, texture, _ ->
            val persons = listOf(
                person(1, FloatRect(80f, 30f, 260f, 340f)),
                person(2, FloatRect(190f, 30f, 370f, 340f)),
                person(3, FloatRect(300f, 30f, 480f, 340f)),
                person(5, FloatRect(410f, 30f, 590f, 340f)),
                person(6, FloatRect(460f, 30f, 640f, 340f))
            )
            val ids = setOf(1, 2, 3, 5, 6)
            val first = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = persons,
                faceOnlyTrackIds = ids,
                ptsUs = 0L
            )
            assertEquals(5, first.detectorCallCount)
            assertEquals(ids, first.detectorCalledTrackIds)
            assertEquals(5, locator.calls)
            // The synthetic locator returns the same ROI-local face position for
            // every target, so edge-shifted right-side ROI anchors may reject it.
            // What matters for startup is that no ID is starved waiting for the
            // steady-state 2-call budget, and rejected bootstrap fallback stays
            // face-sized instead of head/shoulder-sized.
            first.stickerPlacements
                .filter { first.fallbackTrackIds.contains(it.trackId) }
                .forEach { placement ->
                    assertTrue(
                        placement.sourceRect.width <= 100f,
                        "bootstrap fallback too large for id=${placement.trackId}: ${placement.sourceRect.width}"
                    )
                }

            val second = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = persons,
                faceOnlyTrackIds = ids,
                ptsUs = 16_666L
            )
            assertEquals(0, second.detectorCallCount)
            assertEquals(5, locator.calls)
            assertTrue(second.predictedTrackIds.containsAll(first.detectedTrackIds))
        }
    }

    @Test
    fun reacquiringUnobservedFaceOnlyTrackUsesTightTrustedLocalDetectorRoi() {
        val locator = CountingLocator(
            listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f))
        )
        withProcessor(locator) { processor, texture, _ ->
            val observed = person(21, FloatRect(220f, 40f, 420f, 350f))
            val initial = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(observed),
                faceOnlyTrackIds = setOf(21),
                ptsUs = 0L
            )
            assertEquals(setOf(21), initial.detectedTrackIds)

            val target = observed.copy(
                bbox = FloatRect(250f, 40f, 450f, 350f),
                state = TrackState.REACQUIRING,
                observedThisFrame = false,
                framesSinceLastObservation = 4
            )
            val result = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(target),
                faceOnlyTrackIds = setOf(21),
                ptsUs = 33_333L
            )
            assertEquals(1, result.detectorCallCount)
            assertEquals(setOf(21), result.detectedTrackIds)
            assertEquals(2, locator.calls)
            assertTrue(
                result.stickerPlacements.single().sourceRect.centerX >
                    initial.stickerPlacements.single().sourceRect.centerX + 25f,
                "trusted local face refresh must follow the translated head during reacquire"
            )
        }
    }

    @Test
    fun recentLostFaceOnlyTrackKeepsTightLocalRefreshButDoesNotBecomeIndependentTracker() {
        val locator = CountingLocator(
            listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f))
        )
        withProcessor(locator) { processor, texture, _ ->
            val observed = person(27, FloatRect(220f, 40f, 420f, 350f))
            val initial = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(observed),
                faceOnlyTrackIds = setOf(27),
                ptsUs = 0L
            )
            assertEquals(setOf(27), initial.detectedTrackIds)

            val brieflyLost = observed.copy(
                bbox = FloatRect(236f, 40f, 436f, 350f),
                state = TrackState.LOST,
                observedThisFrame = false,
                framesSinceLastObservation = 8
            )
            val refreshed = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(brieflyLost),
                faceOnlyTrackIds = setOf(27),
                ptsUs = 33_333L
            )
            assertEquals(1, refreshed.detectorCallCount)
            assertEquals(setOf(27), refreshed.detectedTrackIds)
            assertEquals(2, locator.calls)

            val longLost = brieflyLost.copy(framesSinceLastObservation = 31)
            val stopped = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(longLost),
                faceOnlyTrackIds = setOf(27),
                ptsUs = 66_666L
            )
            assertEquals(0, stopped.detectorCallCount)
            assertEquals(2, locator.calls)
            assertEquals(
                setOf(27),
                stopped.fallbackTrackIds,
                "identity-local face refresh must stop after the bounded YOLO-unobserved window"
            )
        }
    }

    @Test
    fun trustedLocalFaceRefreshTracksHeadMotionInsideStablePersonBox() {
        val locator = SequencedLocator(
            listOf(
                listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f)),
                // Deliberately much larger local detector box. Local refresh is
                // allowed to move the face center, but must not feed this size
                // back into sticker/ROI scale.
                listOf(FaceObservation(FloatRect(112f, 72f, 208f, 168f), 0.9f))
            )
        )
        withProcessor(locator) { processor, texture, _ ->
            val target = person(31, FloatRect(220f, 40f, 420f, 350f))
            val first = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(target),
                faceOnlyTrackIds = setOf(31),
                ptsUs = 0L
            )
            val firstCenterX = first.stickerPlacements.single().sourceRect.centerX
            val firstWidth = first.stickerPlacements.single().sourceRect.width
            val firstHeight = first.stickerPlacements.single().sourceRect.height

            val second = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(target),
                faceOnlyTrackIds = setOf(31),
                ptsUs = 33_333L
            )
            assertEquals(1, second.detectorCallCount)
            assertEquals(setOf(31), second.detectedTrackIds)
            assertTrue(
                second.stickerPlacements.single().sourceRect.centerX > firstCenterX + 5f,
                "local detector refresh must follow head motion even when the person bbox is unchanged"
            )
            val secondRect = second.stickerPlacements.single().sourceRect
            assertTrue(
                secondRect.width <= firstWidth * 1.15f,
                "local detector size noise must not inflate sticker width: first=$firstWidth second=${secondRect.width}"
            )
            assertTrue(
                secondRect.height <= firstHeight * 1.15f,
                "local detector size noise must not inflate sticker height: first=$firstHeight second=${secondRect.height}"
            )

            val third = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(target),
                faceOnlyTrackIds = setOf(31),
                ptsUs = 49_999L
            )
            assertEquals(0, third.detectorCallCount)
            assertEquals(setOf(31), third.predictedTrackIds)
            assertTrue(
                kotlin.math.abs(third.stickerPlacements.single().sourceRect.centerX - secondRect.centerX) < 0.5f,
                "without current pixel evidence, detector history must not self-propagate the ROI away from the last trusted face"
            )
        }
    }

    @Test
    fun dormantFreshMotionOnlyOpensProbeAndDetectorMissKeepsStickerSuppressed() {
        val locator = SequencedLocator(
            listOf(
                listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f)),
                emptyList()
            )
        )
        withProcessor(locator) { processor, texture, mapper ->
            val observed = person(51, FloatRect(220f, 40f, 420f, 350f))
            val initial = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(observed),
                faceOnlyTrackIds = setOf(51),
                ptsUs = 0L
            )
            assertEquals(setOf(51), initial.detectedTrackIds)

            val lost = observed.copy(
                state = TrackState.LOST,
                observedThisFrame = false,
                framesSinceLastObservation = 60,
                mask = null
            )
            val dormant = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(lost),
                faceOnlyTrackIds = setOf(51),
                ptsUs = 900_000L
            )
            assertEquals(setOf(51), dormant.dormantSuppressedTrackIds)
            assertTrue(dormant.stickerPlacements.isEmpty())

            val motionBbox = FloatRect(160f, 20f, 480f, 360f)
            val motionMask = sourceRectMask(mapper, motionBbox)
            val probe = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(lost),
                faceOnlyTrackIds = setOf(51),
                protectedMotionEvidence = listOf(
                    ProtectedTrackMotionEvidence(
                        trackId = 51,
                        detectionIndex = 0,
                        detection = PersonDetection(
                            bbox = motionBbox,
                            confidence = 0.90f,
                            mask = motionMask
                        ),
                        assignedScore = 0.30f,
                        bboxIou = 0.30f,
                        maskIou = 0.10f,
                        timestampUs = 916_666L
                    )
                ),
                ptsUs = 916_666L
            )

            assertEquals(setOf(51), probe.dormantReactivationProbeTrackIds)
            assertTrue(probe.dormantReactivatedTrackIds.isEmpty())
            assertEquals(setOf(51), probe.dormantSuppressedTrackIds)
            assertTrue(probe.stickerPlacements.isEmpty(), "detector miss must not render dormant fallback")
        }
    }

    @Test
    fun dormantFaceDetectorHitReactivatesWithoutScalingHiddenTrustedSize() {
        val locator = SequencedLocator(
            listOf(
                listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f)),
                // Deliberately huge detector extent on reactivation. It may move
                // center, but must not overwrite the hidden trusted source size.
                listOf(FaceObservation(FloatRect(64f, 56f, 208f, 200f), 0.9f))
            )
        )
        withProcessor(locator) { processor, texture, mapper ->
            val observed = person(52, FloatRect(220f, 40f, 420f, 350f))
            val initial = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(observed),
                faceOnlyTrackIds = setOf(52),
                ptsUs = 0L
            )
            val initialWidth = initial.stickerPlacements.single().sourceRect.width

            val lost = observed.copy(
                state = TrackState.LOST,
                observedThisFrame = false,
                framesSinceLastObservation = 60,
                mask = null
            )
            val dormant = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(lost),
                faceOnlyTrackIds = setOf(52),
                ptsUs = 900_000L
            )
            assertTrue(dormant.stickerPlacements.isEmpty())

            // Symmetric body-box expansion would previously scale the hidden
            // face before it was rendered/cached again.
            val expandedBbox = FloatRect(120f, 10f, 520f, 360f)
            val expandedMask = sourceRectMask(mapper, expandedBbox)
            val reactivated = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(lost),
                faceOnlyTrackIds = setOf(52),
                protectedMotionEvidence = listOf(
                    ProtectedTrackMotionEvidence(
                        trackId = 52,
                        detectionIndex = 0,
                        detection = PersonDetection(
                            bbox = expandedBbox,
                            confidence = 0.90f,
                            mask = expandedMask
                        ),
                        assignedScore = 0.32f,
                        bboxIou = 0.30f,
                        maskIou = 0.10f,
                        timestampUs = 916_666L
                    )
                ),
                ptsUs = 916_666L
            )

            assertEquals(setOf(52), reactivated.dormantReactivationProbeTrackIds)
            assertEquals(setOf(52), reactivated.dormantReactivatedTrackIds)
            assertEquals(setOf(52), reactivated.detectedTrackIds)
            val reactivatedWidth = reactivated.stickerPlacements.single().sourceRect.width
            assertTrue(
                kotlin.math.abs(reactivatedWidth - initialWidth) <= 0.5f,
                "dormant reactivation must preserve trusted face size: initial=$initialWidth reactivated=$reactivatedWidth"
            )
        }
    }

    @Test
    fun currentBodyMaskGuidesThrottledFaceFrameInsideStablePersonBox() {
        val locator = CountingLocator(
            listOf(FaceObservation(FloatRect(104f, 96f, 152f, 144f), 0.9f))
        )
        withProcessor(locator) { processor, texture, mapper ->
            val bbox = FloatRect(220f, 40f, 420f, 350f)
            val initialPerson = person(41, bbox)
            val initial = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(initialPerson),
                faceOnlyTrackIds = setOf(41),
                ptsUs = 0L
            )
            val initialCenterX = initial.stickerPlacements.single().sourceRect.centerX

            val articulated = initialPerson.copy(
                // Same person bbox, but current accurate body segmentation has
                // the head silhouette shifted to the right relative to torso.
                mask = sourceRectsMask(
                    mapper,
                    FloatRect(350f, 80f, 395f, 145f),
                    FloatRect(265f, 140f, 375f, 345f)
                )
            )
            val guided = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(articulated),
                faceOnlyTrackIds = setOf(41),
                ptsUs = 16_666L
            )
            assertEquals(0, guided.detectorCallCount)
            assertEquals(setOf(41), guided.bodyMaskGuidedTrackIds)
            assertTrue(
                guided.stickerPlacements.single().sourceRect.centerX > initialCenterX + 3f,
                "accurate current body mask should move FACE_ONLY fallback with articulated head pixels"
            )
        }
    }

    @Test
    fun fullBodyTargetCannotActAsSecondaryOccluderAgainstFaceOnlyTarget() {
        withProcessor(FixedLocator(emptyList())) { processor, texture, mapper ->
            val faceOnly = person(1, FloatRect(180f, 30f, 360f, 340f)).copy(
                mask = sourceRectMask(mapper, FloatRect(180f, 30f, 360f, 340f))
            )
            val fullBody = person(2, FloatRect(250f, 20f, 500f, 350f)).copy(
                mask = sourceRectMask(mapper, FloatRect(250f, 20f, 500f, 350f))
            )
            val result = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(faceOnly, fullBody),
                faceOnlyTrackIds = setOf(1),
                fullBodyTrackIds = setOf(2),
                ptsUs = 0L
            )
            assertTrue(result.readyForRender)
            assertEquals(setOf(1), result.fallbackTrackIds)
            val mask = assertNotNull(result.resolvedPrivacy?.privacyMask)
            val headX = faceOnly.bbox.centerX
            val headY = faceOnly.bbox.top + faceOnly.bbox.height * 0.14f
            assertTrue(
                pixelAtSource(mask, mapper, headX, headY) > 0,
                "FULL_BODY primary target must not carve secondary FACE_ONLY fallback"
            )
        }
    }

    @Test
    fun ambiguousFaceCandidatesFallBackToYoloHeadPrivacy() {
        withProcessor(
            FixedLocator(
                listOf(
                    FaceObservation(FloatRect(105f, 105f, 137f, 137f), 0.7f),
                    FaceObservation(FloatRect(119f, 111f, 151f, 143f), 0.99f)
                )
            )
        ) { processor, texture, mapper ->
            val target = person(9, FloatRect(220f, 40f, 420f, 350f))
            val result = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = listOf(target),
                faceOnlyTrackIds = setOf(9),
                ptsUs = 0L
            )
            assertTrue(result.readyForRender)
            assertEquals(setOf(9), result.fallbackTrackIds)
            assertTrue(result.detectedTrackIds.isEmpty())
            val mask = assertNotNull(result.resolvedPrivacy?.privacyMask)
            assertTrue(pixelAtSource(mask, mapper, target.bbox.centerX, target.bbox.top + target.bbox.height * 0.14f) > 0)
            assertEquals(0, pixelAtSource(mask, mapper, target.bbox.centerX, target.bbox.bottom - 20f))
        }
    }

    @Test
    fun missingRequestedTrackFailsClosedInsteadOfRenderingTransparent() {
        withProcessor(FixedLocator(emptyList())) { processor, texture, _ ->
            val result = processor.resolveFrame(
                frameTexture = texture,
                texMatrix = RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = SourceTextureType.TEXTURE_2D,
                persons = emptyList(),
                faceOnlyTrackIds = setOf(99),
                ptsUs = 0L
            )
            assertFalse(result.readyForRender)
            assertEquals(setOf(99), result.unresolvedTrackIds)
        }
    }

    private fun withProcessor(
        locator: FaceLocator,
        block: (FaceOnlyPrivacyFrameProcessor, Int, ModelCoordinateMapper) -> Unit
    ) {
        val egl = EglCore()
        val surface = egl.createOffscreenSurface(FRAME_W, FRAME_H)
        egl.makeCurrent(surface)
        val bitmap = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        var texture = 0
        val mapper = ModelCoordinateMapper(FRAME_W, FRAME_H, 640, 160)
        val processor = FaceOnlyPrivacyFrameProcessor(locator = locator, mapper = mapper)
        try {
            texture = create2dTexture(bitmap)
            block(processor, texture, mapper)
        } finally {
            processor.close()
            if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            bitmap.recycle()
            egl.releaseSurface(surface)
            egl.close()
        }
    }

    private fun person(id: Int, bbox: FloatRect) = TrackedPerson(
        id = id,
        bbox = bbox,
        mask = null,
        confidence = 0.95f,
        state = TrackState.ACTIVE,
        observedThisFrame = true,
        footY = bbox.bottom
    )

    private fun create2dTexture(bitmap: Bitmap): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return textureId
    }

    private fun pixelAtSource(
        mask: com.danceanon.native.inference.NativeMask,
        mapper: ModelCoordinateMapper,
        x: Float,
        y: Float
    ): Int {
        val px = mapper.sourceToProtoX(x).roundToInt().coerceIn(0, mask.width - 1)
        val py = mapper.sourceToProtoY(y).roundToInt().coerceIn(0, mask.height - 1)
        return mask.buffer.get(py * mask.width + px).toInt() and 0xFF
    }

    private fun sourceRectMask(
        mapper: ModelCoordinateMapper,
        rect: FloatRect
    ): com.danceanon.native.inference.NativeMask = sourceRectsMask(mapper, rect)

    private fun sourceRectsMask(
        mapper: ModelCoordinateMapper,
        vararg rects: FloatRect
    ): com.danceanon.native.inference.NativeMask {
        val width = mapper.protoSize
        val height = mapper.protoSize
        val buffer = ByteBuffer.allocateDirect(width * height)
        val protoRects = rects.map { rect ->
            intArrayOf(
                mapper.sourceToProtoX(rect.left).roundToInt().coerceIn(0, width - 1),
                mapper.sourceToProtoY(rect.top).roundToInt().coerceIn(0, height - 1),
                mapper.sourceToProtoX(rect.right).roundToInt().coerceIn(0, width - 1),
                mapper.sourceToProtoY(rect.bottom).roundToInt().coerceIn(0, height - 1)
            )
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val inside = protoRects.any { rect -> x in rect[0]..rect[2] && y in rect[1]..rect[3] }
                buffer.put(if (inside) 255.toByte() else 0)
            }
        }
        buffer.rewind()
        return com.danceanon.native.inference.NativeMask(
            width = width,
            height = height,
            buffer = buffer,
            originalWidth = mapper.srcWidth,
            originalHeight = mapper.srcHeight,
            mapper = mapper
        )
    }

    private class FixedLocator(private val observations: List<FaceObservation>) : FaceLocator {
        override fun detectRgbaTopDown(rgba: ByteBuffer, width: Int, height: Int): FaceLocatorResult =
            FaceLocatorResult(observations = observations, inferenceMs = 1.0)

        override fun close() = Unit
    }

    private class SequencedLocator(private val sequence: List<List<FaceObservation>>) : FaceLocator {
        private var index = 0

        override fun detectRgbaTopDown(rgba: ByteBuffer, width: Int, height: Int): FaceLocatorResult {
            val observations = sequence[index.coerceAtMost(sequence.lastIndex)]
            index++
            return FaceLocatorResult(observations = observations, inferenceMs = 1.0)
        }

        override fun close() = Unit
    }

    private class CountingLocator(private val observations: List<FaceObservation>) : FaceLocator {
        var calls: Int = 0
            private set

        override fun detectRgbaTopDown(rgba: ByteBuffer, width: Int, height: Int): FaceLocatorResult {
            calls++
            return FaceLocatorResult(observations = observations, inferenceMs = 1.0)
        }

        override fun close() = Unit
    }

    companion object {
        private const val FRAME_W = 640
        private const val FRAME_H = 360
    }
}
