package com.danceanon.native.face

import com.danceanon.native.inference.FloatRect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FaceHeadRoiPlannerTest {
    @Test
    fun `upper roi stays square and centers normal target head`() {
        val plan = assertNotNull(
            FaceHeadRoiPlanner.plan(
                personBbox = FloatRect(760f, 200f, 960f, 800f),
                frameWidth = 1920,
                frameHeight = 1080
            )
        )

        assertEquals(plan.sourceRect.width, plan.sourceRect.height)
        assertNear(0.5f, plan.anchorX)
        assertNear(0.5f, plan.anchorY)
        assertEquals(256, plan.outputSize)
    }

    @Test
    fun `roi shifts inside frame while preserving edge anchor`() {
        val plan = assertNotNull(
            FaceHeadRoiPlanner.plan(
                personBbox = FloatRect(0f, 10f, 120f, 610f),
                frameWidth = 1080,
                frameHeight = 1920
            )
        )

        assertEquals(0f, plan.sourceRect.left)
        assertTrue(plan.sourceRect.top >= 0f)
        assertEquals(plan.sourceRect.width, plan.sourceRect.height)
        assertTrue(plan.anchorX < 0.5f, "edge target should retain its shifted anchor")
    }

    @Test
    fun `invalid person box is rejected`() {
        val plan = FaceHeadRoiPlanner.plan(
            personBbox = FloatRect(10f, 10f, 10f, 20f),
            frameWidth = 100,
            frameHeight = 100
        )
        assertEquals(null, plan)
    }

    private fun assertNear(expected: Float, actual: Float, epsilon: Float = 1e-4f) {
        assertTrue(abs(expected - actual) <= epsilon, "expected=$expected actual=$actual")
    }
}
