package art.gaoge.dance.engine.render

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The decision gate is pure; no live GLES context is required. */
class PrivacyRenderGlErrorPolicyTest {
    private val glError = 0x0502

    @Test
    fun glFailureAbortsProtectedFullBodyExport() {
        assertTrue(GlRenderer.shouldAbortProtectedExportOnGlError(glError, "job_test", 4, false))
    }

    @Test
    fun glFailureAbortsFaceOnlyProtectedExport() {
        assertTrue(GlRenderer.shouldAbortProtectedExportOnGlError(glError, "job_face", 0, true))
    }

    @Test
    fun missingMainShaderFailsClosedForProtectedFullBodyOrFaceExport() {
        assertTrue(GlRenderer.isProtectedExportRender("job_full", 4, false))
        assertTrue(GlRenderer.isProtectedExportRender("job_face", 0, true))
        assertFalse(GlRenderer.isProtectedExportRender(null, 4, true))
        assertFalse(GlRenderer.isProtectedExportRender("", 4, true))
        assertFalse(GlRenderer.isProtectedExportRender("job_unprotected", 0, false))
    }

    @Test
    fun successfulRenderAndPreviewKeepExistingPolicy() {
        assertFalse(GlRenderer.shouldAbortProtectedExportOnGlError(0, "job_test", 4, true))
        assertFalse(GlRenderer.shouldAbortProtectedExportOnGlError(glError, null, 4, false))
        assertFalse(GlRenderer.shouldAbortProtectedExportOnGlError(glError, "", 4, false))
        assertFalse(GlRenderer.shouldAbortProtectedExportOnGlError(glError, "job_unprotected", 0, false))
    }

    @Test
    fun finalProtectedPortraitCropMustAbortOnGlErrorOrMissingProgram() {
        assertTrue(GlRenderer.isProtectedExportBasePass("job_portrait"))
        assertTrue(GlRenderer.shouldAbortProtectedExportBasePassOnGlError(glError, "job_portrait"))
        assertFalse(GlRenderer.shouldAbortProtectedExportBasePassOnGlError(0, "job_portrait"))
    }

    @Test
    fun previewAndOptionalShadowCropKeepLogOnlyPolicy() {
        assertFalse(GlRenderer.isProtectedExportBasePass(null))
        assertFalse(GlRenderer.isProtectedExportBasePass(""))
        assertFalse(GlRenderer.shouldAbortProtectedExportBasePassOnGlError(glError, null))
        assertFalse(GlRenderer.shouldAbortProtectedExportBasePassOnGlError(glError, ""))
    }

    @Test
    fun missingOptionalFaceStickerShaderMustRetainOpaquePrivacyCompositor() {
        assertFalse(
            GlRenderer.shouldRenderFacePrivacyAsSticker(
                stickerEnabled = true, hasPlacements = true,
                hasFaceMask = true, stickerShaderAvailable = false
            ),
            "No shader means keep the face in the main privacy mask, not in an absent overlay"
        )
    }

    @Test
    fun validFaceStickerStillUsesOverlayOnlyWhenAllEvidenceIsPresent() {
        assertTrue(
            GlRenderer.shouldRenderFacePrivacyAsSticker(
                stickerEnabled = true, hasPlacements = true,
                hasFaceMask = true, stickerShaderAvailable = true
            )
        )
        assertFalse(GlRenderer.shouldRenderFacePrivacyAsSticker(false, true, true, true))
        assertFalse(GlRenderer.shouldRenderFacePrivacyAsSticker(true, false, true, true))
        assertFalse(GlRenderer.shouldRenderFacePrivacyAsSticker(true, true, false, true))
    }
}
