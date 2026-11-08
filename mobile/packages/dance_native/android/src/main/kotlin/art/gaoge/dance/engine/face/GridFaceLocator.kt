package art.gaoge.dance.engine.face

import android.content.Context
import art.gaoge.dance.engine.core.FrameKernel
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.litert.LiteRtModelRunner
import art.gaoge.dance.engine.litert.LiteRtRunnerPolicy
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer

/** Native inference transport. Tensor preparation, anchors, decode and NMS are Rust. */
internal class GridFaceLocator(context:Context, preferGpu:Boolean):FaceLocator {
    private val replay by lazy { art.gaoge.dance.engine.diagnostics.QaObservationReplay.open(context) }
    private val runner=LiteRtModelRunner.fromAsset(context,MODEL,"dense-face-v1",
        if(preferGpu)LiteRtRunnerPolicy.GPU_WITH_CPU_FALLBACK else LiteRtRunnerPolicy.STRICT_CPU)
    init {
        try {
            val hash=context.assets.open(MODEL).use {stream ->
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                val buffer=ByteArray(65536)
                while(true){val n=stream.read(buffer);if(n<0)break;digest.update(buffer,0,n)}
                digest.digest().joinToString(""){"%02x".format(it)}
            }
            check(hash=="3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b"){"FACE_MODEL_HASH_CONTRACT"}
            runBlocking {runner.initialize()}
            val info=checkNotNull(runner.runtimeInfo)
            check(info.inputShapes==listOf(listOf(1,192,192,3))) {"FACE_MODEL_INPUT_CONTRACT"}
            check(info.outputShapes==listOf(listOf(1,2304,16),listOf(1,2304,1))) {"FACE_MODEL_OUTPUT_CONTRACT"}
        } catch(error:Throwable) {runner.close();throw error}
    }
    @Synchronized override fun detectRgbaTopDown(rgba:ByteBuffer,width:Int,height:Int):FaceLocatorResult {
        require(width>1&&height>1&&rgba.isDirect&&rgba.limit().toLong()>=width.toLong()*height*4)
        val start=System.nanoTime()
        val input=FrameKernel.gridFaceInputNative(rgba,width,height,width*4)
        val cache=replay
        val key=cache?.key("grid-face", "$width,$height|${runner.effectiveAccelerator}|threads=${runner.runtimeInfo?.cpuNumThreads}",input)
        val cached=key?.let {cache.read(it)}
        val decoded=cached?.let {runCatching {art.gaoge.dance.engine.diagnostics.QaObservationCodec.floats(it)}.getOrNull()}
        val values=if(decoded != null) {
            cache.hit(key,cached)
            decoded
        } else {
            if(cached != null) cache.reject(key)
            val before=runner.effectiveAccelerator
            runner.getInputBuffers().single().writeFloat(input)
            runner.runInference()
            val outputs=runner.getOutputBuffers()
            val result=FrameKernel.gridFaceDecodeNative(outputs[0].readFloat(),outputs[1].readFloat(),width,height,0.35f)
            if(key != null && before==runner.effectiveAccelerator) cache.record(key,art.gaoge.dance.engine.diagnostics.QaObservationCodec.floats(result))
            result
        }
        return FaceLocatorResult(values.asList().chunked(17).map {row->
            FaceObservation(FloatRect(row[0],row[1],row[2],row[3]),row[4],
                (0 until 6).map{k->FacePoint(row[5+k*2],row[6+k*2])})
        },(System.nanoTime()-start)/1_000_000.0)
    }
    override fun close(){runner.close()}
    companion object {const val MODEL="models/face/blaze_face_full_range.tflite"}
}
