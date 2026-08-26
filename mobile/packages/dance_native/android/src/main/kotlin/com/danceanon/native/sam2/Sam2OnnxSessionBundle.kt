package com.danceanon.native.sam2

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/**
 * Manages the three core ONNX Runtime sessions for SAM2 Hiera Tiny.
 * Instantiated once per model lifecycle to avoid per-frame recreation.
 */
class Sam2OnnxSessionBundle(
    val env: OrtEnvironment,
    val imageFeaturesSession: OrtSession,
    val initStepSession: OrtSession,
    val temporalStepSession: OrtSession
) : AutoCloseable {

    override fun close() {
        imageFeaturesSession.close()
        initStepSession.close()
        temporalStepSession.close()
    }
}
