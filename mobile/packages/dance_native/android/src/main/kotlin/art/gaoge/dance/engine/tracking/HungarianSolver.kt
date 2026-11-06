package art.gaoge.dance.engine.tracking

data class MatchResult(
    val matches: List<Pair<Int, Int>>,
    val unmatchedTracks: List<Int>,
    val unmatchedDetections: List<Int>
)

/** Compatibility name; the established solver is thresholded greedy matching. */
object HungarianSolver {
    fun match(costMatrix: Array<FloatArray>, maxCostThreshold: Float = 0.50f): MatchResult {
        val rows = costMatrix.size
        if (rows == 0) return MatchResult(emptyList(), emptyList(), emptyList())
        val columns = costMatrix[0].size
        require(costMatrix.all { it.size == columns }) { "Ragged association matrix" }
        require(rows <= 100_000 && columns <= 100_000 && rows.toLong() * columns <= 1_000_000) {
            "Association matrix exceeds the shared core contract"
        }
        val costs = FloatArray(rows * columns)
        costMatrix.forEachIndexed { i, values -> values.copyInto(costs, i * columns) }
        val matches = art.gaoge.dance.engine.core.FrameKernel.matchCosts(costs, rows, columns, maxCostThreshold)
        val matchedRows = matches.map { it.first }.toSet()
        val matchedColumns = matches.map { it.second }.toSet()
        return MatchResult(matches, (0 until rows).filterNot(matchedRows::contains),
            (0 until columns).filterNot(matchedColumns::contains))
    }
}
