package com.danceanon.native.diagnostics

import android.content.Context

/** Release no-op for debug-only decoder YUV sampling. */
object VideoYuvDiagnosticSampler {
    fun capture(context: Context, sourceUri: String, jobId: String) = Unit
}
