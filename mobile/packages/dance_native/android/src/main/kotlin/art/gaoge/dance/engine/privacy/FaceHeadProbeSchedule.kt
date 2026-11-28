package art.gaoge.dance.engine.privacy

/**
 * Orders two fresh detector crops; never supplies geometry or grants ownership.
 * A preference belongs to one eligible current owner and expires across gaps,
 * seeks, or scope changes. A narrow success immediately restores narrow-first.
 */
internal class FaceHeadProbeSchedule {
    private var owner: Int? = null
    private var lastPtsUs: Long = -1
    private var lastHeadWinUs: Long = -1
    private var consecutiveHeadWins = 0

    fun begin(currentOwner: Int?, ptsUs: Long) {
        if (currentOwner == null || owner != currentOwner || ptsUs < lastPtsUs ||
            (lastPtsUs >= 0 && ptsUs - lastPtsUs > MAX_GAP_US)) {
            consecutiveHeadWins = 0
            lastHeadWinUs = -1
        }
        owner = currentOwner
        lastPtsUs = ptsUs
        if (lastHeadWinUs >= 0 && ptsUs - lastHeadWinUs > MAX_GAP_US) {
            consecutiveHeadWins = 0
            lastHeadWinUs = -1
        }
    }

    fun preferHead(trackId: Int): Boolean =
        owner == trackId && consecutiveHeadWins >= PROMOTION_WINS

    fun record(trackId: Int, headWon: Boolean, narrowWon: Boolean) {
        if (owner != trackId) return
        when {
            narrowWon -> {
                consecutiveHeadWins = 0
                lastHeadWinUs = -1
            }
            headWon -> {
                consecutiveHeadWins = (consecutiveHeadWins + 1).coerceAtMost(PROMOTION_WINS)
                lastHeadWinUs = lastPtsUs
            }
            // One failed frame must not erase an established preference,
            // but cannot promote it or renew its clock.
            consecutiveHeadWins < PROMOTION_WINS -> {
                consecutiveHeadWins = 0
                lastHeadWinUs = -1
            }
        }
    }

    private companion object {
        const val PROMOTION_WINS = 2
        const val MAX_GAP_US = 150_000L
    }
}
