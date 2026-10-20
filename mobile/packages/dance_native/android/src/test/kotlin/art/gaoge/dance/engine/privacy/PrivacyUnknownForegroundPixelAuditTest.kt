package art.gaoge.dance.engine.privacy

import art.gaoge.dance.engine.geometry.ModelCoordinateMapper
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.NativeMask
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyUnknownForegroundPixelAuditTest {
    private val mapper = ModelCoordinateMapper(
        srcWidth = 80, srcHeight = 80, modelInputSize = 80, protoSize = 8
    )

    private fun mask(value: (Int, Int) -> Int): NativeMask {
        val buffer = ByteBuffer.allocateDirect(8 * 8)
        for (y in 0 until 8) {
            for (x in 0 until 8) buffer.put(value(x, y).toByte())
        }
        buffer.rewind()
        return NativeMask(
            width = 8, height = 8, buffer = buffer,
            originalWidth = 80, originalHeight = 80, mapper = mapper
        )
    }

    private fun fixture(): PrivacyUnknownForegroundPixelAudit.Probe {
        val selected = mask { _, _ -> 160 }
        val current = mask { x, _ -> if (x < 4) 255 else 60 }
        val owner = mask { x, _ -> if (x < 2) 255 else 0 }
        val currentCore = mask { x, _ -> if (x == 0) 255 else 0 }
        val temporal = mask { x, _ -> if (x == 1) 255 else 0 }
        val effective = mask { x, _ -> if (x < 2) 0 else 160 }
        val anotherSelected = mask { x, y ->
            if (x < 2 && y < 2) 255 else 0
        }
        val merged = mask { x, y ->
            if (x >= 2) 160 else if (y < 2) 255 else 0
        }
        return PrivacyUnknownForegroundPixelAudit.Probe(
            targetBbox = FloatRect(0f, 0f, 80f, 80f),
            selectedRaw = selected,
            foregroundFresh = current,
            foregroundObserved = current,
            ownership = owner,
            erodedFreshCore = currentCore,
            approvedCarve = currentCore,
            temporalCarve = temporal,
            effectiveSelected = effective,
            mergedPrivacy = merged,
            competingSelected = listOf(anotherSelected),
            strongVetoSelected = listOf(anotherSelected)
        )
    }

    @Test
    fun fixedCoarseBinsDistinguishForegroundEvidenceAndCompetingSelectedCoverage() {
        val result = PrivacyUnknownForegroundPixelAudit.summarize(fixture())
        assertEquals(true, result["valid"])
        assertEquals(listOf(4, 4), result["grid_shape"])
        assertEquals("ROW_MAJOR_TOP_TO_BOTTOM_SOURCE", result["grid_order"])
        @Suppress("UNCHECKED_CAST")
        val totals = result["total_by_signal"] as Map<String, Int>
        assertEquals(64, totals["selected_raw"])
        assertEquals(32, totals["fresh_foreground"])
        assertEquals(32, totals["selected_and_fresh_foreground"])
        assertEquals(32, totals["selected_and_observed_foreground"])
        assertEquals(32, totals["selected_with_weak_foreground"])
        // Current raw mask probability advantage exists only on the left.
        assertEquals(32, totals["selected_with_raw_foreground_advantage"])
        assertEquals(16, totals["selected_and_probability_ownership"])
        assertEquals(8, totals["selected_and_fresh_eroded_core"])
        assertEquals(8, totals["selected_and_approved_carve"])
        assertEquals(8, totals["selected_and_temporal_carve"])
        assertEquals(48, totals["selected_retained_effective"])
        assertEquals(16, totals["selected_retained_and_fresh_foreground"])
        assertEquals(32, totals["selected_retained_with_weak_foreground"])
        assertEquals(4, totals["competing_selected"])
        assertEquals(4, totals["competing_selected_and_fresh_foreground"])
        assertEquals(52, totals["final_merged"])
        assertEquals(20, totals["final_merged_and_fresh_foreground"])
        @Suppress("UNCHECKED_CAST")
        val strong = result["strong_threshold_totals"] as Map<String, Int>
        assertEquals(32, strong["selected_and_fresh_ge224"])
        assertEquals(32, strong["selected_and_observed_ge224"])
        assertEquals(32, strong["selected_and_both_ge192"])
        assertEquals(32, strong["selected_and_both_ge224"])
        assertEquals(4, strong["selected_and_both_ge224_vetoed_other"])
        assertEquals(28, strong["selected_and_both_ge224_without_other"])
        assertEquals(1, result["strong_veto_selected_mask_count"])
        @Suppress("UNCHECKED_CAST")
        val grid = result["grid_by_signal"] as Map<String, List<Int>>
        assertEquals(listOf(4, 4, 0, 0), grid.getValue("fresh_foreground").take(4))
        assertEquals(listOf(4, 4, 4, 4), grid.getValue("selected_raw").take(4))
        assertEquals(listOf(4, 0, 0, 0), grid.getValue("competing_selected").take(4))
        assertEquals(listOf(4, 0, 4, 4), grid.getValue("final_merged").take(4))
        assertEquals(
            "AGGREGATE_PROTO_MASK_COUNTS_ONLY_NO_RGB_NO_RAW_MASK",
            result["evidence_scope"]
        )
    }

    @Test
    fun reportsPotentialInstanceMaskBleedOutsideTheDetectionBox() {
        val input = fixture().copy(
            targetBbox = FloatRect(20f, 20f, 60f, 60f)
        )
        val result = PrivacyUnknownForegroundPixelAudit.summarize(input)
        assertEquals(true, result["valid"])
        @Suppress("UNCHECKED_CAST")
        val inside = result["total_by_signal"] as Map<String, Int>
        assertEquals(16, inside["selected_raw"])
        assertEquals(8, inside["selected_and_fresh_foreground"])
        @Suppress("UNCHECKED_CAST")
        val outside = result["outside_selected_bbox_totals"] as Map<String, Int>
        assertEquals(48, outside["selected_raw"])
        assertEquals(24, outside["selected_and_fresh_foreground"])
        assertEquals(32, outside["selected_retained_effective"])
        assertEquals(8, outside["selected_and_approved_carve"])
        assertEquals(8, outside["selected_and_temporal_carve"])
    }

    @Test
    fun strictForegroundEvidenceCountsNeverInferStrongPixelsFromWeakObservation() {
        val weakObserved = mask { _, _ -> 100 }
        val result = PrivacyUnknownForegroundPixelAudit.summarize(
            fixture().copy(foregroundObserved = weakObserved)
        )
        assertEquals(true, result["valid"])
        @Suppress("UNCHECKED_CAST")
        val strong = result["strong_threshold_totals"] as Map<String, Int>
        assertEquals(32, strong["selected_and_fresh_ge224"])
        assertEquals(0, strong["selected_and_observed_ge224"])
        assertEquals(0, strong["selected_and_both_ge224"])
        assertEquals(0, strong["selected_and_both_ge224_without_other"])
    }

    @Test
    fun mismatchedMaskCoordinatesRejectAuditWithoutApprovingAnyCarve() {
        val fixture = fixture()
        val wrongWidth = mask { _, _ -> 255 }.copy(originalWidth = 90)
        val result = PrivacyUnknownForegroundPixelAudit.summarize(
            fixture.copy(foregroundObserved = wrongWidth)
        )
        assertEquals(false, result["valid"])
        assertEquals("MASK_COORDINATE_MISMATCH", result["reason"])
        assertFalse(result.containsKey("grid_by_signal"))
    }

    @Test
    fun samplingNeverMutatesMaskBytesOrExportsRawMasks() {
        val input = fixture()
        input.selectedRaw.buffer.position(3)
        val before = input.selectedRaw.buffer.get(0)
        val result = PrivacyUnknownForegroundPixelAudit.summarize(input)
        assertEquals(3, input.selectedRaw.buffer.position())
        assertEquals(before, input.selectedRaw.buffer.get(0))
        assertTrue(result.keys.none { it.contains("raw_bytes") || it.contains("png") })
        val bins = result["grid_by_signal"]
        assertFalse(bins is NativeMask)
        assertFalse(bins is ByteBuffer)
    }
}
