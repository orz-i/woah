package art.gaoge.dance.engine.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import art.gaoge.dance.engine.bridge.DanceNativeException

/** Native source decoding only; the shared core chooses context timestamps. */
internal object SourceFrameReader {
    fun read(context: Context, uri: String, timestampUs: Long, rotation: Int): Bitmap {
        val retriever=MediaMetadataRetriever()
        val raw=try {
            if(uri.startsWith("content://"))retriever.setDataSource(context,Uri.parse(uri))
            else retriever.setDataSource(uri.removePrefix("file://"))
            retriever.getFrameAtTime(timestampUs.coerceAtLeast(0),MediaMetadataRetriever.OPTION_CLOSEST)
                ?:throw DanceNativeException(DanceNativeException.DECODE_FRAME_FAILED,"Source context frame is unavailable")
        }catch(error:DanceNativeException){throw error}
        catch(error:Exception){throw DanceNativeException(DanceNativeException.DECODE_FRAME_FAILED,"Source context decode failed",error)}
        finally{try{retriever.release()}catch(_:Exception){}}
        val software=if(android.os.Build.VERSION.SDK_INT>=26 && raw.config==Bitmap.Config.HARDWARE){
            raw.copy(Bitmap.Config.ARGB_8888,false).also{if(it!==raw)raw.recycle()}
        }else raw
        if(rotation==0)return software
        return Bitmap.createBitmap(software,0,0,software.width,software.height,Matrix().apply{postRotate(rotation.toFloat())},true).also{if(it!==software)software.recycle()}
    }
}
