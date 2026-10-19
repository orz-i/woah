package art.gaoge.dance.engine.render

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** No GLES context needed: tests only the export safety decision. */
class PrivacyRenderGlErrorPolicyTest {
    private val glInvalidOperation = 0x0502

    @Test
    fun glUploadOrDrawErrorAbortsProtectedFullBodyExport() {
        assertTrue(
            GlRenderer.shouldAbortProtectedExportOnGlError(
                glInvalidOperation, "job_test", selectedRootCount = 4,
                additionalPrivacyPresent = false
            )
        )
    }

    @Test
    fun faceOnlyPrivacyAlsoAbortsRatherThanEncodingAStaleStickerOrMask() {
        assertTrue(
            GlRenderer.shouldAbortProtectedExportOnGlError(
                glInvalidOperation, "job_face", selectedRootCount = 0,
                additionalPrivacyPresent = true
            )
        )
    }

    @Test
    fun successfulGlRenderNeverAborts() {
        assertFalse(
            GlRenderer.shouldAbortProtectedExportOnGlError(
                0, "job_test", selectedRootCount = 4,
                additionalPrivacyPresent = true
            )
        )
    }

    @Test
    fun previewAndUnprotectedRenderKeepTheirExistingErrorReportingPolicy() {
        assertFalse(
            GlRenderer.shouldAbortProtectedExportOnGlError(
                glInvalidOperation, null, selectedRootCount = 4,
                additionalPrivacyPresent = false
            )
        )
        assertFalse(
            GlRenderer.shouldAbortProtectedExportOnGlError(
                glInvalidOperation, "", selectedRootCount = 4,
                additionalPrivacyPresent = false
            )
        )
        assertFalse(
            GlRenderer.shouldAbortProtectedExportOnGlError(
                glInvalidOperation, "job_unprotected", selectedRootCount = 0,
                additionalPrivacyPresent = false
            )
        )
    }
}
