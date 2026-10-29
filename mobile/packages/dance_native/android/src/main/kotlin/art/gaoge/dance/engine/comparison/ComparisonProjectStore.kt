package art.gaoge.dance.engine.comparison

import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Project failures are surfaced to the caller; files are never partially published. */
internal class ComparisonProjectStore(private val directory: File) {
    fun load(): List<String> = directory.listFiles()?.filter { it.extension=="json" }
        ?.sortedByDescending { it.lastModified() }?.map {
            AtomicFile(it).readFully().toString(Charsets.UTF_8)
        } ?: emptyList()
    fun save(json: String) {
        val value=JSONObject(json)
        require(value.getInt("schemaVersion")==1)
        val id=value.getString("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        require(value.getLong("positionUs")>=0 && value.getLong("offsetUs") in -172_800_000_000L..172_800_000_000L)
        ComparisonMusicMapping(value.getLong("offsetUs"),value.optDouble("timeScale",1.0))
        require(value.getDouble("speed") in listOf(.5,.75,1.0))
        check(directory.isDirectory || directory.mkdirs())
        val atomic=AtomicFile(File(directory,"$id.json"))
        val stream=atomic.startWrite()
        try { stream.write(json.toByteArray(Charsets.UTF_8));atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream);throw error }
    }
}
