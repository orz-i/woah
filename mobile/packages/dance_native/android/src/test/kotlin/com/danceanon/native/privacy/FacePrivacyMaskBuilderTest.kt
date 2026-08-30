package com.danceanon.native.privacy

import com.danceanon.native.face.FaceHeadRoiPlan
import com.danceanon.native.face.FaceObservation
import com.danceanon.native.face.FaceRoiCandidateSelection
import com.danceanon.native.geometry.ModelCoordinateMapper
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FacePrivacyMaskBuilderTest {
    @Test
    fun `selected roi face maps to expanded detected privacy ellipse`() {
        val plan = FaceHeadRoiPlan(
            sourceRect = FloatRect(100f, 200f, 500f, 600f),
            anchorX = 0.5f,
            anchorY = 0.5f,
            outputSize = 256
        )
        val selection = FaceRoiCandidateSelection(
            faceIndex = 0,
            face = FaceObservation(FloatRect(96f, 80f, 160f, 144f), 0.9f),
            anchorDistanceRatio = 0.05f
        )

        val region = assertNotNull(
            FacePrivacyRegionResolver.resolve(
                personBbox = FloatRect(120f, 190f, 480f, 900f),
                roiPlan = plan,
                selectedFace = selection
            )
        )

        assertEquals(FacePrivacyRegionSource.DETECTED_FACE, region.source)
        val rawFaceWidth = (160f - 96f) / 256f * 400f
        val rawFaceHeight = (144f - 80f) / 256f * 400f
        assertTrue(region.radiusX * 2f > rawFaceWidth)
        assertTrue(region.radiusY * 2f > rawFaceHeight)
        assertTrue(
            region.radiusX * 2f <= rawFaceWidth * 1.35f,
            "detected FACE_ONLY width should stay close to the actual face box"
        )
        assertTrue(
            region.radiusY * 2f <= rawFaceHeight * 1.50f,
            "detected FACE_ONLY height should stay close to the actual face box"
        )
        assertTrue(region.centerY < 200f + ((80f + 144f) * 0.5f / 256f) * 400f)
    }

    @Test
    fun `detected facial keypoints move privacy center away from pose-skewed bbox center`() {
        val person = FloatRect(100f, 50f, 500f, 750f)
        val roi = FaceHeadRoiPlan(
            sourceRect = FloatRect(0f, 0f, 400f, 400f),
            anchorX = 0.52f,
            anchorY = 0.44f,
            outputSize = 256
        )
        val observation = FaceObservation(
            bbox = FloatRect(120f, 80f, 200f, 150f),
            confidence = 0.9f,
            keypoints = listOf(
                com.danceanon.native.face.FacePoint(126f, 100f),
                com.danceanon.native.face.FacePoint(136f, 100f),
                com.danceanon.native.face.FacePoint(130f, 112f),
                com.danceanon.native.face.FacePoint(132f, 124f),
                com.danceanon.native.face.FacePoint(155f, 108f),
                com.danceanon.native.face.FacePoint(190f, 108f)
            )
        )
        val selection = assertNotNull(
            com.danceanon.native.face.FaceRoiCandidateSelector.select(
                faces = listOf(observation),
                roiWidth = 256,
                roiHeight = 256,
                anchorX = roi.anchorX,
                anchorY = roi.anchorY
            )
        )
        val region = assertNotNull(FacePrivacyRegionResolver.resolve(person, roi, selection))
        val bboxCenterSourceX = ((120f + 200f) * 0.5f / 256f) * 400f
        assertTrue(
            region.centerX < bboxCenterSourceX - 10f,
            "central facial features should pull sticker center away from the pose-skewed bbox center"
        )
    }

    @Test
    fun `detector miss or ambiguity falls back to nonzero yolo head region`() {
        val person = FloatRect(300f, 100f, 500f, 900f)
        val region = assertNotNull(
            FacePrivacyRegionResolver.resolve(
                personBbox = person,
                roiPlan = null,
                selectedFace = null
            )
        )

        assertEquals(FacePrivacyRegionSource.YOLO_HEAD_FALLBACK, region.source)
        assertEquals(person.centerX, region.centerX)
        assertTrue(region.centerY > person.top && region.centerY < person.centerY)
        assertTrue(region.radiusX > 0f && region.radiusY > 0f)
        assertTrue(region.radiusX * 2f <= person.width * 0.75f)
        assertTrue(region.radiusY * 2f <= person.width * 0.85f)
    }

    @Test
    fun `face ellipse rasterizes in visual top letterboxed proto coordinates`() {
        val mapper = ModelCoordinateMapper(srcWidth = 1000, srcHeight = 500, protoSize = 160)
        val region = FacePrivacyEllipse(
            centerX = 500f,
            centerY = 100f,
            radiusX = 80f,
            radiusY = 60f,
            source = FacePrivacyRegionSource.DETECTED_FACE
        )
        val mask = assertNotNull(FacePrivacyMaskBuilder.build(listOf(region), mapper))

        assertEquals(160, mask.width)
        assertEquals(160, mask.height)
        val centerX = mapper.sourceToProtoX(500f).roundToInt().coerceIn(0, 159)
        val centerY = mapper.sourceToProtoY(100f).roundToInt().coerceIn(0, 159)
        assertEquals(255, mask.byteAt(centerX, centerY))
        assertEquals(0, mask.byteAt(centerX, 120))
        // For a wide 2:1 frame, content begins at proto y=40. A head near the
        // visual top must therefore live near that top content edge, not bottom.
        assertTrue(centerY in 40..80, "Unexpected visual-top proto y=$centerY")
    }

    @Test
    fun `multiple face regions rasterize as union`() {
        val mapper = ModelCoordinateMapper(srcWidth = 1000, srcHeight = 1000, protoSize = 160)
        val regions = listOf(
            FacePrivacyEllipse(250f, 250f, 100f, 120f, FacePrivacyRegionSource.DETECTED_FACE),
            FacePrivacyEllipse(750f, 700f, 90f, 110f, FacePrivacyRegionSource.YOLO_HEAD_FALLBACK)
        )
        val mask = assertNotNull(FacePrivacyMaskBuilder.build(regions, mapper))

        assertEquals(255, mask.byteAt(protoX(mapper, 250f), protoY(mapper, 250f)))
        assertEquals(255, mask.byteAt(protoX(mapper, 750f), protoY(mapper, 700f)))
        assertEquals(0, mask.byteAt(protoX(mapper, 500f), protoY(mapper, 500f)))
    }

    @Test
    fun `frame edge ellipse clips safely while preserving privacy pixels`() {
        val mapper = ModelCoordinateMapper(srcWidth = 640, srcHeight = 640, protoSize = 160)
        val region = FacePrivacyEllipse(
            centerX = 8f,
            centerY = 12f,
            radiusX = 80f,
            radiusY = 90f,
            source = FacePrivacyRegionSource.YOLO_HEAD_FALLBACK
        )
        val mask = assertNotNull(FacePrivacyMaskBuilder.build(listOf(region), mapper))

        assertTrue(PrivacyOcclusionResolver.countMaskPixels(mask) > 0)
        assertEquals(255, mask.byteAt(1, 1))
    }

    @Test
    fun `compatible face mask unions with existing body privacy mask`() {
        val mapper = ModelCoordinateMapper(srcWidth = 640, srcHeight = 640, protoSize = 160)
        val face = assertNotNull(
            FacePrivacyMaskBuilder.build(
                listOf(FacePrivacyEllipse(100f, 100f, 50f, 60f, FacePrivacyRegionSource.DETECTED_FACE)),
                mapper
            )
        )
        val bodyBuffer = ByteBuffer.allocateDirect(160 * 160)
        bodyBuffer.put(150 * 160 + 150, 255.toByte())
        val body = NativeMask(
            width = 160,
            height = 160,
            buffer = bodyBuffer,
            originalWidth = 640,
            originalHeight = 640,
            mapper = mapper
        )

        val union = assertNotNull(FacePrivacyMaskBuilder.unionCompatible(body, face))
        assertEquals(255, union.byteAt(protoX(mapper, 100f), protoY(mapper, 100f)))
        assertEquals(255, union.byteAt(150, 150))
    }

    @Test
    fun `incompatible privacy mask union fails closed`() {
        val mapper = ModelCoordinateMapper(srcWidth = 640, srcHeight = 640, protoSize = 160)
        val face = assertNotNull(
            FacePrivacyMaskBuilder.build(
                listOf(FacePrivacyEllipse(100f, 100f, 50f, 60f, FacePrivacyRegionSource.DETECTED_FACE)),
                mapper
            )
        )
        val incompatible = NativeMask(
            width = 128,
            height = 128,
            buffer = ByteBuffer.allocateDirect(128 * 128),
            originalWidth = 640,
            originalHeight = 640,
            mapper = mapper
        )

        assertFailsWith<IllegalArgumentException> {
            FacePrivacyMaskBuilder.unionCompatible(incompatible, face)
        }
    }

    private fun NativeMask.byteAt(x: Int, y: Int): Int {
        val safeX = x.coerceIn(0, width - 1)
        val safeY = y.coerceIn(0, height - 1)
        return buffer.get(safeY * width + safeX).toInt() and 0xFF
    }

    private fun protoX(mapper: ModelCoordinateMapper, sourceX: Float): Int =
        mapper.sourceToProtoX(sourceX).roundToInt().coerceIn(0, mapper.protoSize - 1)

    private fun protoY(mapper: ModelCoordinateMapper, sourceY: Float): Int =
        mapper.sourceToProtoY(sourceY).roundToInt().coerceIn(0, mapper.protoSize - 1)
}
