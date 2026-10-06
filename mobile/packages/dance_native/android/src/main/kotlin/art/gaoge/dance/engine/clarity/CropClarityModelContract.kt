package art.gaoge.dance.engine.clarity

/** Static release contract shared with tools/litert/crop_clarity_span_x2.py. */
object CropClarityModelContract {
    const val MODEL_NAME = "crop_clarity_span_x2"
    const val ASSET_PATH = "models/litert/crop-clarity-span-x2.tflite"
    const val TILE_SIZE = 192
    const val UPSCALE = 2
    const val OUTPUT_TILE_SIZE = TILE_SIZE * UPSCALE
    const val CHANNELS = 3
    const val INPUT_FLOAT_COUNT = TILE_SIZE * TILE_SIZE * CHANNELS
    const val OUTPUT_FLOAT_COUNT = OUTPUT_TILE_SIZE * OUTPUT_TILE_SIZE * CHANNELS
}
