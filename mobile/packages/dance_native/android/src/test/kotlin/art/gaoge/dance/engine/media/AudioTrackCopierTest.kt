package art.gaoge.dance.engine.media

import android.media.MediaExtractor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AudioTrackCopierTest {
    @Test
    fun `ordinary audio access units preserve sync status`() {
        assertFalse(muxableAudioSampleIsSync(0))
        assertTrue(muxableAudioSampleIsSync(MediaExtractor.SAMPLE_FLAG_SYNC))
    }

    @Test
    fun `encrypted samples cannot masquerade as codec config`() {
        for (sync in listOf(0, MediaExtractor.SAMPLE_FLAG_SYNC)) {
            assertFailsWith<IllegalArgumentException> {
                muxableAudioSampleIsSync(MediaExtractor.SAMPLE_FLAG_ENCRYPTED or sync)
            }
        }
    }

    @Test
    fun `partial samples cannot masquerade as end of stream`() {
        for (sync in listOf(0, MediaExtractor.SAMPLE_FLAG_SYNC)) {
            assertFailsWith<IllegalArgumentException> {
                muxableAudioSampleIsSync(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME or sync)
            }
        }
    }

    @Test
    fun `unknown flags fail closed`() {
        assertFailsWith<IllegalArgumentException> { muxableAudioSampleIsSync(0x100) }
    }
}
