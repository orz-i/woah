package art.gaoge.dance.engine.clarity

import kotlin.math.max
import kotlin.math.min

/** One input-space tile including overlap; crop bounds select its seam-free core. */
data class CropClarityTile(
    val inputLeft: Int,
    val inputTop: Int,
    val inputWidth: Int,
    val inputHeight: Int,
    val cropLeft: Int,
    val cropTop: Int,
    val cropRight: Int,
    val cropBottom: Int
) {
    /** Seam-free retained core inside the x2 model output tile. */
    val outputCropLeft: Int get() = cropLeft * CropClarityModelContract.UPSCALE
    val outputCropTop: Int get() = cropTop * CropClarityModelContract.UPSCALE
    val outputCropRight: Int get() = cropRight * CropClarityModelContract.UPSCALE
    val outputCropBottom: Int get() = cropBottom * CropClarityModelContract.UPSCALE

    /** Destination rectangle in the full x2 restored crop. */
    val outputLeft: Int get() = (inputLeft + cropLeft) * CropClarityModelContract.UPSCALE
    val outputTop: Int get() = (inputTop + cropTop) * CropClarityModelContract.UPSCALE
    val outputRight: Int get() = (inputLeft + cropRight) * CropClarityModelContract.UPSCALE
    val outputBottom: Int get() = (inputTop + cropBottom) * CropClarityModelContract.UPSCALE
}

/**
 * Plans overlapping fixed-size tiles for the x2 neural model.
 *
 * Adjacent cores meet at the midpoint of their actual overlap. This matters for
 * the final tile, whose start is pulled back to the image edge and may overlap
 * more than the nominal context width. Every source pixel therefore belongs to
 * exactly one retained tile core: no holes and no double-written seams.
 */
object CropClarityTilePlanner {
    const val DEFAULT_OVERLAP = 12

    private data class AxisTile(
        val start: Int,
        val size: Int,
        val keepStart: Int,
        val keepEnd: Int
    )

    fun plan(
        width: Int,
        height: Int,
        tileSize: Int = CropClarityModelContract.TILE_SIZE,
        overlap: Int = DEFAULT_OVERLAP
    ): List<CropClarityTile> {
        require(width > 0 && height > 0)
        require(tileSize > 0)
        require(overlap >= 0 && overlap * 2 < tileSize)

        val stride = tileSize - overlap * 2
        val xs = axisTiles(width, tileSize, stride)
        val ys = axisTiles(height, tileSize, stride)
        return buildList {
            for (y in ys) {
                for (x in xs) {
                    add(
                        CropClarityTile(
                            inputLeft = x.start,
                            inputTop = y.start,
                            inputWidth = x.size,
                            inputHeight = y.size,
                            cropLeft = x.keepStart,
                            cropTop = y.keepStart,
                            cropRight = x.keepEnd,
                            cropBottom = y.keepEnd
                        )
                    )
                }
            }
        }
    }

    private fun axisTiles(length: Int, tileSize: Int, stride: Int): List<AxisTile> {
        val starts = starts(length, tileSize, stride)
        return starts.mapIndexed { index, start ->
            val end = min(length, start + tileSize)
            val keepGlobalStart = if (index == 0) {
                0
            } else {
                val previousStart = starts[index - 1]
                val previousEnd = min(length, previousStart + tileSize)
                (previousEnd + start) / 2
            }
            val keepGlobalEnd = if (index == starts.lastIndex) {
                length
            } else {
                val nextStart = starts[index + 1]
                (end + nextStart) / 2
            }
            AxisTile(
                start = start,
                size = end - start,
                keepStart = keepGlobalStart - start,
                keepEnd = keepGlobalEnd - start
            )
        }
    }

    private fun starts(length: Int, tileSize: Int, stride: Int): List<Int> {
        if (length <= tileSize) return listOf(0)
        val result = mutableListOf<Int>()
        var start = 0
        while (true) {
            result += start
            if (start + tileSize >= length) break
            val next = min(start + stride, max(0, length - tileSize))
            if (next <= start) break
            start = next
        }
        return result
    }
}
