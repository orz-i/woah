package com.danceanon.native.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HungarianSolverTest {

    @Test
    fun testExactDiagonalMatching() {
        val costMatrix = arrayOf(
            floatArrayOf(0.1f, 0.9f, 0.9f),
            floatArrayOf(0.9f, 0.2f, 0.9f),
            floatArrayOf(0.9f, 0.9f, 0.15f)
        )

        val result = HungarianSolver.match(costMatrix, maxCostThreshold = 0.5f)
        assertEquals(3, result.matches.size)
        assertEquals(listOf(Pair(0, 0), Pair(2, 2), Pair(1, 1)), result.matches)
        assertTrue(result.unmatchedTracks.isEmpty())
        assertTrue(result.unmatchedDetections.isEmpty())
    }

    @Test
    fun testRejectsHighCostEntriesAboveThreshold() {
        val costMatrix = arrayOf(
            floatArrayOf(0.1f, 0.8f),
            floatArrayOf(0.9f, 0.85f)
        )

        val result = HungarianSolver.match(costMatrix, maxCostThreshold = 0.5f)
        assertEquals(1, result.matches.size)
        assertEquals(Pair(0, 0), result.matches[0])
        assertEquals(listOf(1), result.unmatchedTracks)
        assertEquals(listOf(1), result.unmatchedDetections)
    }

    @Test
    fun testRectangularMoreTracksThanDetections() {
        val costMatrix = arrayOf(
            floatArrayOf(0.8f),
            floatArrayOf(0.1f),
            floatArrayOf(0.9f)
        )

        val result = HungarianSolver.match(costMatrix, maxCostThreshold = 0.5f)
        assertEquals(1, result.matches.size)
        assertEquals(Pair(1, 0), result.matches[0])
        assertEquals(setOf(0, 2), result.unmatchedTracks.toSet())
        assertTrue(result.unmatchedDetections.isEmpty())
    }

    @Test
    fun testEmptyMatrix() {
        val empty = HungarianSolver.match(emptyArray())
        assertTrue(empty.matches.isEmpty())
        assertTrue(empty.unmatchedTracks.isEmpty())
        assertTrue(empty.unmatchedDetections.isEmpty())
    }
}
