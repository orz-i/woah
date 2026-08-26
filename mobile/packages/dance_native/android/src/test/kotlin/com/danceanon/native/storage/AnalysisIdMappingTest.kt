package com.danceanon.native.storage

import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import com.danceanon.native.tracking.HungarianSolver
import com.danceanon.native.tracking.TrackManager
import java.nio.ByteBuffer
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisIdMappingTest {

    private fun createMask(): NativeMask {
        val buf = ByteBuffer.allocateDirect(160 * 160)
        return NativeMask(160, 160, buf, 640, 640)
    }

    @Test
    fun testReorderedExportDetectionsMapToOriginalAnalysisIds() {
        val targetWidth = 1920
        val targetHeight = 1080

        // 1. Cached Persons from Analysis phase: A=ID 0, B=ID 1, C=ID 2
        val cachedPersons = listOf(
            CachedPerson(
                id = 0,
                bbox = CachedBBox(left = 0.10, top = 0.20, right = 0.25, bottom = 0.90), // Person A (left)
                confidence = 0.95
            ),
            CachedPerson(
                id = 1,
                bbox = CachedBBox(left = 0.40, top = 0.20, right = 0.55, bottom = 0.90), // Person B (center)
                confidence = 0.92
            ),
            CachedPerson(
                id = 2,
                bbox = CachedBBox(left = 0.70, top = 0.20, right = 0.85, bottom = 0.90), // Person C (right)
                confidence = 0.94
            )
        )

        // 2. Export first frame detections arriving in perturbed order: [B, A, C]
        val detB = PersonDetection(
            bbox = FloatRect(0.40f * targetWidth, 0.20f * targetHeight, 0.55f * targetWidth, 0.90f * targetHeight),
            confidence = 0.91f,
            mask = createMask()
        )
        val detA = PersonDetection(
            bbox = FloatRect(0.10f * targetWidth, 0.20f * targetHeight, 0.25f * targetWidth, 0.90f * targetHeight),
            confidence = 0.96f,
            mask = createMask()
        )
        val detC = PersonDetection(
            bbox = FloatRect(0.70f * targetWidth, 0.20f * targetHeight, 0.85f * targetWidth, 0.90f * targetHeight),
            confidence = 0.93f,
            mask = createMask()
        )

        val detections = listOf(detB, detA, detC)

        // 3. Perform Hungarian Matching as done in ExportPipeline
        val costMatrix = Array(cachedPersons.size) { r ->
            val cPerson = cachedPersons[r]
            val cLeft = (cPerson.bbox.left * targetWidth).toFloat()
            val cTop = (cPerson.bbox.top * targetHeight).toFloat()
            val cRight = (cPerson.bbox.right * targetWidth).toFloat()
            val cBottom = (cPerson.bbox.bottom * targetHeight).toFloat()
            val cBox = FloatRect(cLeft, cTop, cRight, cBottom)

            FloatArray(detections.size) { c ->
                val dBox = detections[c].bbox
                val interX1 = maxOf(cBox.left, dBox.left)
                val interY1 = maxOf(cBox.top, dBox.top)
                val interX2 = minOf(cBox.right, dBox.right)
                val interY2 = minOf(cBox.bottom, dBox.bottom)
                val interW = maxOf(0f, interX2 - interX1)
                val interH = maxOf(0f, interY2 - interY1)
                val interArea = interW * interH
                val unionArea = cBox.width * cBox.height + dBox.width * dBox.height - interArea
                val iou = if (unionArea <= 0f) 0f else interArea / unionArea

                val dx = (cBox.centerX - dBox.centerX) / targetWidth.toFloat()
                val dy = (cBox.centerY - dBox.centerY) / targetHeight.toFloat()
                val dist = sqrt(dx * dx + dy * dy).coerceIn(0f, 1f)
                (0.6f * (1.0f - iou) + 0.4f * dist).coerceIn(0f, 1f)
            }
        }

        val matchResult = HungarianSolver.match(costMatrix, maxCostThreshold = 0.70f)
        val assignedIds = IntArray(detections.size) { -1 }
        for (m in matchResult.matches) {
            assignedIds[m.second] = cachedPersons[m.first].id
        }

        // 4. Verification:
        // Index 0 (detB) must get ID 1
        // Index 1 (detA) must get ID 0
        // Index 2 (detC) must get ID 2
        assertEquals(1, assignedIds[0], "Detection B must map to original cached ID 1")
        assertEquals(0, assignedIds[1], "Detection A must map to original cached ID 0")
        assertEquals(2, assignedIds[2], "Detection C must map to original cached ID 2")

        // 5. TrackManager initialization
        val tracker = TrackManager()
        val tracked = tracker.initializeWithAssignedIds(detections, assignedIds.toList())

        assertEquals(1, tracked[0].id)
        assertEquals(0, tracked[1].id)
        assertEquals(2, tracked[2].id)
    }
}
