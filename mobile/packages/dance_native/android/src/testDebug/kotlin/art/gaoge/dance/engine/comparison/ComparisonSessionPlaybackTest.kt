package art.gaoge.dance.engine.comparison

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.view.Choreographer
import android.view.Surface
import art.gaoge.dance.engine.bridge.comparison.ComparisonCommand
import art.gaoge.dance.engine.bridge.comparison.ComparisonPracticeDto
import io.flutter.view.TextureRegistry
import kotlin.test.*
import org.mockito.Mockito.*

/** Exercise the actual session command path while platform/rendering callbacks are suspended. */
class ComparisonSessionPlaybackTest {
    private fun play(position: Long,loopStart: Long?,loopEnd: Long?): Long {
        val registry=mock(TextureRegistry::class.java)
        val producer=mock(TextureRegistry.SurfaceProducer::class.java)
        `when`(registry.createSurfaceProducer()).thenReturn(producer)
        `when`(producer.surface).thenReturn(mock(Surface::class.java))
        val choreographer=mock(Choreographer::class.java)
        mockStatic(Uri::class.java).use { uris ->
        uris.`when`<Uri> { Uri.parse(anyString()) }.thenReturn(mock(Uri::class.java))
        mockStatic(Choreographer::class.java).use { statics ->
            statics.`when`<Choreographer> { Choreographer.getInstance() }.thenReturn(choreographer)
            mockConstruction(Handler::class.java).use {
                mockConstruction(ComparisonGlEngine::class.java).use {
                    val practice=ComparisonPracticeDto(false,false,loopStart,loopEnd,loopStart!=null)
                    val session=ComparisonSession(mock(Context::class.java),registry,
                        "file:///reference.mp4","file:///target.mp4",0,position,1.0,320,240,true,practice)
                    try {
                        val field=ComparisonSession::class.java.getDeclaredField("range")
                        field.isAccessible=true
                        field.set(session,ComparisonOverlap(0,23_000_000,0))
                        return session.control(ComparisonCommand.PLAY,0,1.0).positionUs
                    } finally { session.close() }
                }
            }
        }
        }
    }
    @Test fun final100msLoopDoesNotRestartTheFullClip() {
        assertEquals(22_900_000L,play(22_900_000,22_900_000,23_000_000))
    }
    @Test fun pausedNearTheEndOfAWiderLoopKeepsItsPosition() {
        assertEquals(22_950_000L,play(22_950_000,20_000_000,23_000_000))
    }
    @Test fun outsideActiveLoopRestartsAtItsOwnA() {
        assertEquals(20_000_000L,play(0,20_000_000,23_000_000))
    }
    @Test fun withoutLoopTheFullClipStillReplaysFromZero() {
        assertEquals(0L,play(22_950_000,null,null))
    }
}
