package com.danceanon.native.tracking

data class MatchResult(
    val matches: List<Pair<Int, Int>>,
    val unmatchedTracks: List<Int>,
    val unmatchedDetections: List<Int>
)

object HungarianSolver {

    /**
     * Solves bipartite matching minimizing cost with a maximum acceptable threshold.
     */
    fun match(
        costMatrix: Array<FloatArray>,
        maxCostThreshold: Float = 0.50f
    ): MatchResult {
        val numRows = costMatrix.size
        if (numRows == 0) {
            return MatchResult(emptyList(), emptyList(), emptyList())
        }
        val numCols = costMatrix[0].size
        if (numCols == 0) {
            return MatchResult(emptyList(), (0 until numRows).toList(), emptyList())
        }

        // Greedy priority queue matching with thresholding
        val flatCosts = mutableListOf<Triple<Int, Int, Float>>()
        for (r in 0 until numRows) {
            for (c in 0 until numCols) {
                if (costMatrix[r][c] <= maxCostThreshold) {
                    flatCosts.add(Triple(r, c, costMatrix[r][c]))
                }
            }
        }
        flatCosts.sortBy { it.third }

        val matchedRows = BooleanArray(numRows)
        val matchedCols = BooleanArray(numCols)
        val matches = mutableListOf<Pair<Int, Int>>()

        for (item in flatCosts) {
            val r = item.first
            val c = item.second
            if (!matchedRows[r] && !matchedCols[c]) {
                matchedRows[r] = true
                matchedCols[c] = true
                matches.add(Pair(r, c))
            }
        }

        val unmatchedTracks = mutableListOf<Int>()
        for (r in 0 until numRows) {
            if (!matchedRows[r]) unmatchedTracks.add(r)
        }

        val unmatchedDetections = mutableListOf<Int>()
        for (c in 0 until numCols) {
            if (!matchedCols[c]) unmatchedDetections.add(c)
        }

        return MatchResult(matches, unmatchedTracks, unmatchedDetections)
    }
}
