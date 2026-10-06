package art.gaoge.dance.engine.clarity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropClarityTilePlannerTest {
    @Test
    fun `small frame uses one partial tile with the full core`() {
        val tiles = CropClarityTilePlanner.plan(width = 120, height = 80)
        assertEquals(1, tiles.size)
        assertEquals(
            CropClarityTile(
                inputLeft = 0,
                inputTop = 0,
                inputWidth = 120,
                inputHeight = 80,
                cropLeft = 0,
                cropTop = 0,
                cropRight = 120,
                cropBottom = 80
            ),
            tiles.single()
        )
    }

    @Test
    fun `portrait crop tiles retain every source pixel exactly once`() {
        val width = 608
        val height = 1080
        val tiles = CropClarityTilePlanner.plan(width = width, height = height)
        assertTrue(tiles.size > 1)

        val coverage = IntArray(width * height)
        for (tile in tiles) {
            for (localY in tile.cropTop until tile.cropBottom) {
                val y = tile.inputTop + localY
                for (localX in tile.cropLeft until tile.cropRight) {
                    val x = tile.inputLeft + localX
                    coverage[y * width + x] += 1
                }
            }
        }
        assertTrue(coverage.all { it == 1 })

        val outputCoverage = IntArray(width * 2 * height * 2)
        val outputWidth = width * 2
        for (tile in tiles) {
            assertEquals(tile.cropLeft * 2, tile.outputCropLeft)
            assertEquals(tile.cropTop * 2, tile.outputCropTop)
            assertEquals(tile.cropRight * 2, tile.outputCropRight)
            assertEquals(tile.cropBottom * 2, tile.outputCropBottom)
            for (y in tile.outputTop until tile.outputBottom) {
                for (x in tile.outputLeft until tile.outputRight) {
                    outputCoverage[y * outputWidth + x] += 1
                }
            }
        }
        assertTrue(outputCoverage.all { it == 1 })
    }

    @Test
    fun `all tiles stay inside fixed neural input envelope`() {
        val tiles = CropClarityTilePlanner.plan(width = 901, height = 1601)
        assertTrue(tiles.isNotEmpty())
        for (tile in tiles) {
            assertTrue(tile.inputWidth in 1..CropClarityModelContract.TILE_SIZE)
            assertTrue(tile.inputHeight in 1..CropClarityModelContract.TILE_SIZE)
            assertTrue(tile.cropLeft in 0 until tile.inputWidth)
            assertTrue(tile.cropTop in 0 until tile.inputHeight)
            assertTrue(tile.cropRight in 1..tile.inputWidth)
            assertTrue(tile.cropBottom in 1..tile.inputHeight)
            assertTrue(tile.cropLeft < tile.cropRight)
            assertTrue(tile.cropTop < tile.cropBottom)
        }
    }
}
