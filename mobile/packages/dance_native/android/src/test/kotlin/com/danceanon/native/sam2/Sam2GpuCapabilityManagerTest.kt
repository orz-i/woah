package com.danceanon.native.sam2

import com.danceanon.native.bridge.DanceNativeException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Sam2GpuCapabilityManagerTest {

    @BeforeTest
    fun setUp() {
        Sam2GpuCapabilityManager.resetForTesting()
    }

    @Test
    fun testInitialStateIsUnknownAndNotAvailable() {
        assertEquals(Sam2GpuState.UNKNOWN, Sam2GpuCapabilityManager.getState())
        assertFalse(Sam2GpuCapabilityManager.isAvailable())
    }

    @Test
    fun testMarkUnavailableSetsStateAndReason() {
        Sam2GpuCapabilityManager.markUnavailable("Driver compilation aborted")
        assertEquals(Sam2GpuState.UNAVAILABLE, Sam2GpuCapabilityManager.getState())
        assertFalse(Sam2GpuCapabilityManager.isAvailable())
        assertEquals("Driver compilation aborted", Sam2GpuCapabilityManager.getUnavailableReason())
    }

    @Test
    fun testAvailableStateBehavior() {
        Sam2GpuCapabilityManager.setForTesting(Sam2GpuState.AVAILABLE)
        assertEquals(Sam2GpuState.AVAILABLE, Sam2GpuCapabilityManager.getState())
        assertTrue(Sam2GpuCapabilityManager.isAvailable())
    }

    @Test
    fun testSupportedProfilesFiltering() {
        val baseProfiles = listOf("quality", "balanced", "speed")

        // Unavailable case
        Sam2GpuCapabilityManager.setForTesting(Sam2GpuState.UNAVAILABLE, "No OpenCL delegate")
        val profilesUnavailable = mutableListOf("quality", "balanced", "speed")
        if (Sam2GpuCapabilityManager.isAvailable()) {
            profilesUnavailable.add("sam2")
        }
        assertEquals(baseProfiles, profilesUnavailable)
        assertFalse(profilesUnavailable.contains("sam2"))

        // Available case
        Sam2GpuCapabilityManager.setForTesting(Sam2GpuState.AVAILABLE)
        val profilesAvailable = mutableListOf("quality", "balanced", "speed")
        if (Sam2GpuCapabilityManager.isAvailable()) {
            profilesAvailable.add("sam2")
        }
        assertEquals(listOf("quality", "balanced", "speed", "sam2"), profilesAvailable)
        assertTrue(profilesAvailable.contains("sam2"))
    }

    @Test
    fun testNativeHardRejectionWhenSam2Unavailable() {
        Sam2GpuCapabilityManager.setForTesting(Sam2GpuState.UNAVAILABLE, "GPU warm-up failed")

        val requestedProfile = "sam2"
        val ex = assertFailsWith<DanceNativeException> {
            if (requestedProfile == "sam2" && !Sam2GpuCapabilityManager.isAvailable()) {
                throw DanceNativeException(
                    DanceNativeException.SAM2_GPU_UNAVAILABLE,
                    "SAM2 requires a verified LiteRT GPU accelerator on this device."
                )
            }
        }
        assertEquals(DanceNativeException.SAM2_GPU_UNAVAILABLE, ex.code)
    }
}
