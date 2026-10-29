package art.gaoge.dance.engine.comparison

import art.gaoge.dance.engine.bridge.comparison.ComparisonPictureDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComparisonPictureTest {
    @Test fun identityPreservesTheExistingFitIncludingOddPixelRounding() {
        for ((w,h) in listOf(1080 to 1400,1381 to 715)) for (side in listOf(false,true)) {
            for (reference in listOf(false,true)) for ((vw,vh) in listOf(1920 to 1080,1080 to 1920)) {
                val pane=ComparisonGlLayout.pane(w,h,8,reference,side)
                val fit=ComparisonGlLayout.viewport(w,h,8,vw,vh,reference,side)
                val quad=ComparisonPicture().quad(pane,fit)
                val left=pane.x+(1.0+quad.shiftX-quad.scaleX)*pane.width/2
                val top=pane.top+(1.0-quad.shiftY-quad.scaleY)*pane.height/2
                assertEquals(fit.x.toDouble(),left,.001)
                assertEquals(fit.top.toDouble(),top,.001)
                assertEquals(fit.width.toDouble(),quad.scaleX.toDouble()*pane.width,.001)
                assertEquals(fit.height.toDouble(),quad.scaleY.toDouble()*pane.height,.001)
            }
        }
    }
    @Test fun zoomExpandsIntoThePaneRatherThanTheOriginalLetterboxedRectangle() {
        val pane=ComparisonViewport(0,0,100,100)
        val fit=ComparisonViewport(25,0,50,100)
        assertEquals(ComparisonQuad(1f,2f,0f,0f),ComparisonPicture(2.0).quad(pane,fit))
    }
    @Test fun screenPanKeepsItsDirectionAcrossRotatedAspectAndMirrorChoice() {
        val pane=ComparisonViewport(0,0,100,100)
        for (fit in listOf(ComparisonViewport(25,0,50,100),ComparisonViewport(0,25,100,50))) {
            val quad=ComparisonPicture(2.0,.5,-.25).quad(pane,fit)
            assertEquals(if (fit.width==50) 0f else .5f,quad.shiftX)
            assertEquals(if (fit.height==50) 0f else .25f,quad.shiftY)
        }
    }
    @Test fun panesDoNotOverlapAndTheDividerDoesNotBecomePictureArea() {
        for (side in listOf(false,true)) {
            val a=ComparisonGlLayout.pane(1080,1400,8,true,side)
            val b=ComparisonGlLayout.pane(1080,1400,8,false,side)
            if (side) assertEquals(8,b.x-a.x-a.width)
            else assertEquals(8,b.top-a.top-a.height)
            assertTrue(a.width>0 && a.height>0 && b.width>0 && b.height>0)
        }
    }
    @Test fun extremePanCannotMoveANarrowPictureOutsideItsPane() {
        val pane=ComparisonViewport(0,0,100,100)
        val quad=ComparisonPicture(4.0,1.0,0.0).quad(pane,ComparisonViewport(45,0,10,100))
        assertEquals(60.0,(1.0+quad.shiftX-quad.scaleX)*50,.001)
        assertEquals(100.0,(1.0+quad.shiftX+quad.scaleX)*50,.001)
    }
    @Test fun rejectsInvalidNumbersAndDefaultsOldBridgePayloads() {
        for (dto in listOf(ComparisonPictureDto(Double.NaN,0.0,0.0),
            ComparisonPictureDto(4.01,0.0,0.0),ComparisonPictureDto(2.0,Double.POSITIVE_INFINITY,0.0),
            ComparisonPictureDto(2.0,0.0,-1.01))) {
            assertFailsWith<IllegalArgumentException> { ComparisonPicture.fromDto(dto) }
        }
        assertEquals(ComparisonPicture(),ComparisonPicture.fromDto(null))
    }
}
