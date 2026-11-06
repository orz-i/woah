package art.gaoge.dance.engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameKernelWorkLifecycleTest {
    @Test fun cancellationWaitsForResourceReleaseAndWinsOverTeardownError() {
        val work = FrameKernel.WorkLifecycle()
        assertFalse(work.canAdmitReplacement)
        assertTrue(work.requestCancel())
        assertTrue(work.canAdmitReplacement)
        assertTrue(work.acceptingProgress)
        work.observe("failed")
        assertFalse(work.acceptingProgress)
        assertEquals("cancelled", work.outcomeAfterRelease())
        assertEquals("cancelled", work.release())
        assertFalse(work.requestCancel())
        assertEquals("cancelled", work.release())
    }
    @Test fun publishedCompletionSurvivesLateCancellation() {
        val work = FrameKernel.WorkLifecycle()
        work.observe("completed")
        assertFalse(work.canAdmitReplacement)
        assertFalse(work.requestCancel())
        assertFalse(work.acceptingProgress)
        assertEquals("completed", work.release())
    }
    @Test fun cancelledBeforeEntryAndMissingOutcomeHaveDefinedResults() {
        val queued = FrameKernel.WorkLifecycle()
        queued.requestCancel()
        assertEquals("cancelled", queued.release())
        assertEquals("failed", FrameKernel.WorkLifecycle().release())
    }
}
