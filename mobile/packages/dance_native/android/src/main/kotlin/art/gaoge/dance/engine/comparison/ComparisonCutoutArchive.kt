package art.gaoge.dance.engine.comparison

import android.content.Context
import art.gaoge.dance.engine.BuildConfig
import art.gaoge.dance.engine.inference.FloatRect
import art.gaoge.dance.engine.inference.YoloLiteRtSegmenter
import kotlinx.coroutines.CancellationException
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Cache contract, not a durable identity claim. Bump on any incompatible
 * preprocessing/model/tracking/alpha change. Git build identity further
 * prevents restoring masks across a native algorithm revision. */
internal data class ComparisonCutoutArchiveKey(
    val origin:Long,val offset:Long,val scale:Double,val start:Long,val end:Long,
    val mirrorR:Boolean,val mirrorT:Boolean,val backend:String,
    val referenceDigest:String,val targetDigest:String,val modelDigest:String
) {
    fun encoded()=listOf("android-cutout-v1/shared-identity-v3/alpha-grid-v2/detail-overlay-v2/source-reuse-v1/detail-backoff-v1",BuildConfig.GIT_COMMIT_SHA,android.os.Build.VERSION.SDK_INT,
        android.os.Build.FINGERPRINT,origin,offset,scale.toBits(),start,end,mirrorR,mirrorT,backend,
        referenceDigest,targetDigest,modelDigest).joinToString("|")
}
internal data class ComparisonCutoutSelection(val reference:ComparisonCutoutSeed,val target:ComparisonCutoutSeed)
internal data class ComparisonCutoutRestored(val selection:ComparisonCutoutSelection,val cache:ComparisonCutoutCache)

/** Only sealed, complete, selected-alpha tracks are persisted. The archive
 * lives in app-private, OS-evictable cache, never in the backup/project JSON.
 * A file is verified in full before reconstructing anonymous B1 storage.
 * All writes are staged and atomically renamed; no partial file is visible. */
internal class ComparisonCutoutArchive(private val root:File,private val scratch:File) {
    companion object {
        private val lock=Any()
        private const val MAX_BYTES=512L*1024*1024
        private const val MAGIC=0x57434131
        private const val EXPIRY_MS=30L*24*60*60*1000
        private val names=Regex("[A-Za-z0-9_-]{1,80}\\.cutout")
        fun modelDigest(context:Context,current:()->Boolean):String {
            val hash=MessageDigest.getInstance("SHA-256")
            context.assets.open(YoloLiteRtSegmenter.DEFAULT_ASSET_PATH).use { input ->
                val bytes=ByteArray(256*1024)
                while(true){checkCurrent(current);val n=input.read(bytes);if(n<0)break;hash.update(bytes,0,n)}
            }
            return hash.digest().joinToString(""){"%02x".format(it)}
        }
        private fun checkCurrent(current:()->Boolean){if(!current())throw CancellationException("Cutout archive cancelled")}
    }
    private fun file(id:String):File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        return File(root,"$id.cutout")
    }
    private fun maintain(protected:File?=null){
        val now=System.currentTimeMillis()
        root.listFiles()?.filter{it.isFile && it.name.startsWith("stage-") && it.name.endsWith(".tmp")}
            ?.forEach{it.delete()} // operations serialized; no active stage here
        val files=root.listFiles()?.filter{it.isFile && names.matches(it.name)}?.sortedBy{it.lastModified()} ?: return
        var total=files.sumOf{it.length()};var count=files.size
        for(f in files){
            if(f==protected)continue
            if(now-f.lastModified()>EXPIRY_MS || total>MAX_BYTES || count>4){
                val size=f.length();if(f.delete()){total-=size;count--}
            }
        }
    }
    private fun hash(file:File,length:Long,current:()->Boolean):ByteArray {
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use{input->
            val b=ByteArray(256*1024);var left=length
            while(left>0){checkCurrent(current);val n=input.read(b,0,minOf(left,b.size.toLong()).toInt())
                require(n>0);md.update(b,0,n);left-=n}
        }
        return md.digest()
    }
    private fun writeSeed(out:DataOutputStream,seed:ComparisonCutoutSeed){
        out.writeLong(seed.ptsUs);out.writeFloat(seed.x);out.writeFloat(seed.y)
    }
    private fun readSeed(input:DataInputStream)=ComparisonCutoutSeed(input.readLong(),input.readFloat(),input.readFloat())
    private fun writeTrack(out:DataOutputStream,track:ComparisonCutoutTrackCache,seed:ComparisonCutoutSeed,current:()->Boolean){
        val times=track.times;require(times.size in 1..ComparisonCutoutAnalysis.MAX_FRAMES && seed.ptsUs in times)
        val layer=requireNotNull(track.layer(seed.ptsUs));val p=layer.placement
        out.writeInt(times.size);out.writeInt(layer.width);out.writeInt(layer.height)
        listOf(p.anchor.left,p.anchor.top,p.anchor.right,p.anchor.bottom).forEach(out::writeFloat)
        for(pts in times){
            checkCurrent(current);val l=requireNotNull(track.layer(pts))
            require(l.sourcePtsUs==pts && l.width==layer.width && l.height==layer.height && l.placement==p)
            val data=ByteBuffer.allocate(l.width*l.height);l.uploadTo(data)
            out.writeLong(pts);out.write(data.array())
        }
    }
    private fun readTrack(input:DataInputStream,times:List<Long>,seed:ComparisonCutoutSeed,reference:Boolean,
                          mirror:Boolean,digest:String,current:()->Boolean):ComparisonCutoutTrackCache {
        val n=input.readInt();val w=input.readInt();val h=input.readInt()
        require(n==times.size && n in 1..ComparisonCutoutAnalysis.MAX_FRAMES && w in 1..384 && h in 1..384)
        val b=FloatArray(4){input.readFloat()}
        require(b.all{it.isFinite() && it in 0f..1f} && b[2]>b[0] && b[3]>b[1])
        val p=ComparisonCutoutPlacement(FloatRect(b[0],b[1],b[2],b[3]),if(reference).27 else .73,.88,.70,mirror)
        p.quad(960,540,w.toDouble()/h)
        val disk=ComparisonCutoutDiskTrack(scratch,times)
        try{
            for(pts in times){
                checkCurrent(current);require(input.readLong()==pts)
                val bytes=ByteArray(w*h);input.readFully(bytes);require(bytes.any{it.toInt()!=0})
                disk.add(pts,w,h,bytes,p)
            }
            disk.seal(seed.ptsUs)
            return ComparisonCutoutTrackCache(disk,digest)
        }catch(e:Throwable){disk.close();throw e}
    }
    fun save(id:String,key:ComparisonCutoutArchiveKey,selection:ComparisonCutoutSelection,
             cache:ComparisonCutoutCache,current:()->Boolean):Boolean=synchronized(lock){
        val target=file(id)
        root.mkdirs();require(root.isDirectory);maintain(target)
        cache.requireComplete()
        require(cache.reference.fingerprint==key.referenceDigest && cache.target.fingerprint==key.targetDigest)
        val stage=File.createTempFile("stage-",".tmp",root)
        try{
            DataOutputStream(BufferedOutputStream(stage.outputStream())).use{out->
                out.writeInt(MAGIC);out.writeUTF(key.encoded());writeSeed(out,selection.reference);writeSeed(out,selection.target)
                writeTrack(out,cache.reference,selection.reference,current)
                writeTrack(out,cache.target,selection.target,current)
            }
            val size=stage.length();require(size in 1..MAX_BYTES-32)
            val checksum=hash(stage,size,current)
            FileOutputStream(stage,true).use{it.write(checksum);it.fd.sync()}
            checkCurrent(current)
            check(stage.renameTo(target))
            target.setLastModified(System.currentTimeMillis());maintain(target)
            true
        }finally{stage.delete()}
    }
    fun restore(id:String,key:ComparisonCutoutArchiveKey,
                referenceTimes:(ComparisonCutoutSeed)->List<Long>,targetTimes:(ComparisonCutoutSeed)->List<Long>,
                current:()->Boolean):ComparisonCutoutRestored?=synchronized(lock){
        val source=file(id)
        maintain()
        if(!source.isFile)return@synchronized null
        var result:ComparisonCutoutRestored?=null
        try{
            val size=source.length();require(size in 33..MAX_BYTES)
            val expected=RandomAccessFile(source,"r").use{it.seek(size-32);ByteArray(32).also(it::readFully)}
            require(MessageDigest.isEqual(expected,hash(source,size-32,current)))
            DataInputStream(BufferedInputStream(source.inputStream())).use{input->
                require(input.readInt()==MAGIC && input.readUTF()==key.encoded())
                val seeds=ComparisonCutoutSelection(readSeed(input),readSeed(input))
                val r=readTrack(input,referenceTimes(seeds.reference),seeds.reference,true,key.mirrorR,key.referenceDigest,current)
                try{
                    val t=readTrack(input,targetTimes(seeds.target),seeds.target,false,key.mirrorT,key.targetDigest,current)
                    result=ComparisonCutoutRestored(seeds,ComparisonCutoutCache(r,t))
                }catch(e:Throwable){r.close();throw e}
                val tail=ByteArray(32);input.readFully(tail);require(tail.contentEquals(expected) && input.read()==-1)
            }
            checkCurrent(current)
            source.setLastModified(System.currentTimeMillis())
            result
        }catch(e:CancellationException){result?.cache?.close();throw e}
        catch(e:Exception){result?.cache?.close();source.delete();null}
    }
    fun forget(id:String):Unit=synchronized(lock){val f=file(id);if(f.exists())check(f.delete())}
}
