package com.danceanon.native.diagnostics

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danceanon.native.inference.FloatRect
import com.danceanon.native.inference.NativeMask
import com.danceanon.native.inference.PersonDetection
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CrossDeviceTrackingDiagnosticsPerformanceInstrumentedTest {
    @Test
    fun cpuFullOnlyPreservesReferenceOutputsAndAvoidsLegacyAdaptiveShadowWork() {
        val frames = buildFrames(frameCount = 36, personCount = 6)
        val assignedIds = (0 until 6).toList()
        val protectedIds = assignedIds.take(5).toSet()

        fun run(enableAdaptive: Boolean): List<Any> {
            val diagnostics = CrossDeviceTrackingDiagnostics(
                jobId = "bench",
                fullBodyPersonIds = emptySet(),
                faceOnlyPersonIds = protectedIds,
                identityProtectedTrackIds = protectedIds,
                enableAdaptiveShadowMatrix = enableAdaptive
            )
            val signature = ArrayList<Any>(frames.size * 2)
            frames.forEachIndexed { index, detections ->
                val ptsUs = index * 16_677L
                val tracked = assertNotNull(
                    diagnostics.recordFrame(
                        ptsUs = ptsUs,
                        shouldInfer = true,
                        productionDetections = detections,
                        productionTracked = null,
                        cpuMt4Detections = detections,
                        initialAssignedIds = if (index == 0) assignedIds else null,
                        allowProductionFallbackForCpuFull = false
                    )
                )
                signature.add(tracked.sortedBy { it.id }.map { person ->
                    listOf(
                        person.id,
                        (person.bbox.left * 16f).roundToInt(),
                        (person.bbox.top * 16f).roundToInt(),
                        (person.bbox.right * 16f).roundToInt(),
                        (person.bbox.bottom * 16f).roundToInt(),
                        person.state.name,
                        person.observedThisFrame
                    )
                })
                signature.add(diagnostics.getCpuFullTemporalFacePrivacyClassEvidence()
                    .sortedBy { it.detectionIndex }
                    .map { evidence ->
                        listOf(
                            evidence.detectionIndex,
                            evidence.selectionClass.name,
                            evidence.residualTrackIds.sorted(),
                            evidence.conservativeUnknown
                        )
                    })
            }
            assertEquals(
                if (enableAdaptive) frames.size * 5L else 0L,
                diagnostics.getAdaptiveShadowTrackerSteps(),
                "adaptive shadow step count"
            )
            return signature
        }

        val historicalSignature = run(enableAdaptive = true)
        val cpuFullOnlySignature = run(enableAdaptive = false)
        assertEquals(historicalSignature, cpuFullOnlySignature)

        repeat(2) {
            run(enableAdaptive = true)
            run(enableAdaptive = false)
        }
        val historicalMs = medianMs(7) { run(enableAdaptive = true) }
        val cpuFullOnlyMs = medianMs(7) { run(enableAdaptive = false) }
        val improvementPct = (historicalMs - cpuFullOnlyMs) / historicalMs * 100.0
        Log.i(
            TAG,
            "shadow_matrix_reference_ms=$historicalMs cpu_full_only_ms=$cpuFullOnlyMs " +
                "improvement_pct=$improvementPct"
        )
        assertTrue(improvementPct >= 10.0, "Expected >=10% diagnostic tracking gain, got $improvementPct%")
    }

    private fun buildFrames(frameCount: Int, personCount: Int): List<List<PersonDetection>> =
        List(frameCount) { frame ->
            List(personCount) { index ->
                val left = 24f + index * 96f + frame * (1.5f + index * 0.08f)
                val top = 70f + (index % 2) * 14f + ((frame + index) % 5 - 2) * 0.6f
                val right = left + 74f + (index % 3) * 3f
                val bottom = top + 168f + (index % 2) * 8f
                val bbox = FloatRect(left, top, right, bottom)
                PersonDetection(
                    bbox = bbox,
                    confidence = 0.96f - index * 0.025f,
                    mask = rectMask(bbox),
                    footY = bottom
                )
            }
        }

    private fun rectMask(bbox: FloatRect): NativeMask {
        val size = 64
        val buffer = ByteBuffer.allocateDirect(size * size)
        repeat(size * size) { buffer.put(0.toByte()) }
        val left = (bbox.left / 10f).roundToInt().coerceIn(0, size - 1)
        val top = (bbox.top / 10f).roundToInt().coerceIn(0, size - 1)
        val right = (bbox.right / 10f).roundToInt().coerceIn(left + 1, size)
        val bottom = (bbox.bottom / 10f).roundToInt().coerceIn(top + 1, size)
        for (y in top until bottom) {
            for (x in left until right) {
                buffer.put(y * size + x, 255.toByte())
            }
        }
        buffer.rewind()
        return NativeMask(size, size, buffer, 640, 640)
    }

    private inline fun medianMs(trials: Int, block: () -> Unit): Double {
        val values = DoubleArray(trials)
        repeat(trials) { index ->
            val start = System.nanoTime()
            block()
            values[index] = (System.nanoTime() - start) / 1_000_000.0
        }
        values.sort()
        return values[values.size / 2]
    }

    private companion object {
        const val TAG = "CrossDeviceTrackingBench"
    }
}
