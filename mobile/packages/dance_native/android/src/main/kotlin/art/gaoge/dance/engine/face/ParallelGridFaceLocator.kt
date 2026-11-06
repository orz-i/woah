package art.gaoge.dance.engine.face

import android.content.Context
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Native ownership and bounded parallel scheduling, with one interpreter per worker. */
internal class ParallelGridFaceLocator(context:Context,preferGpu:Boolean):FaceLocator {
    private val workers=ArrayList<Worker>()
    private var closed=false
    init {
        try {repeat(2){workers+=Worker(context.applicationContext,preferGpu,it)}}
        catch(error:Throwable){workers.forEach{it.close()};throw error}
    }
    override val supportsParallelBatch=true
    @Synchronized override fun detectRgbaTopDown(rgba:ByteBuffer,width:Int,height:Int):FaceLocatorResult =
        detectBatchRgbaTopDown(listOf(FaceLocatorRequest(rgba,width,height))).single()
    @Synchronized override fun detectBatchRgbaTopDown(requests:List<FaceLocatorRequest>):List<FaceLocatorResult> {
        check(!closed)
        val result=ArrayList<FaceLocatorResult>(requests.size)
        for(wave in requests.chunked(workers.size)) {
            val futures=wave.mapIndexed{i,r->workers[i].submit(r)}
            var failure:Throwable?=null
            val outputs=futures.map{try{it.get()}catch(e:Throwable){failure=failure?:e;null}}
            if(failure!=null)throw IllegalStateException("Face inference worker failed",failure)
            result+=outputs.map{checkNotNull(it)}
        }
        return result
    }
    @Synchronized override fun close(){if(closed)return;closed=true;workers.forEach{it.close()}}
    private class Worker(private val context:Context,preferGpu:Boolean,index:Int) {
        private val executor=Executors.newSingleThreadExecutor{r->Thread(r,"GridFace-$index").apply{isDaemon=true}}
        private var locator:GridFaceLocator
        private var gpu=preferGpu
        init {try{locator=executor.submit(Callable{GridFaceLocator(context,preferGpu)}).get()}
            catch(e:Throwable){executor.shutdownNow();throw e}}
        fun submit(r:FaceLocatorRequest)=executor.submit(Callable{
            try {locator.detectRgbaTopDown(r.rgba,r.width,r.height)}
            catch(error:RuntimeException) {
                if(!gpu)throw error
                locator.close();gpu=false;locator=GridFaceLocator(context,false)
                locator.detectRgbaTopDown(r.rgba,r.width,r.height)
            }
        })
        fun close(){try{executor.submit{locator.close()}.get()}finally{executor.shutdownNow()}}
    }
}
