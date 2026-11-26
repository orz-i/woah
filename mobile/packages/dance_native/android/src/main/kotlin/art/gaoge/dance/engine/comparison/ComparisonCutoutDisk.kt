package art.gaoge.dance.engine.comparison

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Process-scoped private scratch. Unlink the file while its descriptor is
 * open (Android/Linux); neither a killed process nor a failed analysis
 * leaves subject masks on disk. The final owner closes the descriptor.
 * The index contains offsets, not pixels. Each read verifies length/CRC.
 */
internal class ComparisonCutoutDisk(directory: File, private val budget: Long) : AutoCloseable {
    private val file: RandomAccessFile
    private var end = 0L
    private var closed = false
    init {
        require(budget > 0)
        val path = File.createTempFile("woah-cutout-", ".scratch", directory)
        val opened = try { RandomAccessFile(path, "rw") }
            catch (error: Throwable) { path.delete(); throw error }
        if (!path.delete()) {
            opened.close(); path.delete()
            error("无法创建临时抠像缓存")
        }
        file = opened
    }
    data class Record(val offset: Long, val length: Int)
    val bytes: Long @Synchronized get() = end
    @Synchronized fun append(payload: ByteArray): Record {
        check(!closed)
        require(payload.isNotEmpty() && payload.size <= 8 * 1024 * 1024)
        val offset = end
        val next = Math.addExact(offset, payload.size.toLong() + 8)
        require(next <= budget) { "抠像分析缓存超出空间限制，请缩短 A/B 选段" }
        file.seek(offset)
        file.writeInt(payload.size)
        file.writeInt(CRC32().apply { update(payload) }.value.toInt())
        file.write(payload)
        end = next
        return Record(offset, payload.size)
    }
    @Synchronized fun read(record: Record): ByteArray {
        check(!closed)
        require(record.offset >= 0 && record.length in 1..8*1024*1024 &&
            record.offset + 8 + record.length <= end)
        file.seek(record.offset)
        check(file.readInt() == record.length)
        val expected = file.readInt()
        val payload = ByteArray(record.length)
        file.readFully(payload)
        check(CRC32().apply { update(payload) }.value.toInt() == expected) { "抠像缓存校验失败" }
        return payload
    }
    @Synchronized override fun close() {
        if (!closed) { closed = true; file.close() }
    }
}

/** Selected alpha frames are grouped by 32 presentation samples. Only two
 * decoded chunks are kept in RAM. A missing observation is indexed explicitly. */
internal class ComparisonCutoutDiskTrack(directory: File, times: List<Long>) : AutoCloseable {
    companion object {
        const val CHUNK = 32
        const val BUDGET = 256L * 1024 * 1024
    }
    private val pts = times.toLongArray()
    private val disk = ComparisonCutoutDisk(directory, BUDGET)
    private val records = arrayOfNulls<ComparisonCutoutDisk.Record>(pts.size)
    private val known = BooleanArray(pts.size)
    private var visible = 0
    private var count = 0
    private var owners = 1
    private var sealed = false
    private var placement: ComparisonCutoutPlacement? = null
    private var width = 0
    private var height = 0
    private val chunks = LinkedHashMap<Int, Array<ComparisonCutoutLayer?>>(3,.75f,true)
    val frameCount @Synchronized get() = count
    val visibleCount @Synchronized get() = visible
    val bytes get() = disk.bytes
    @Synchronized fun add(at: Long, w: Int, h: Int, alpha: ByteArray?, fixed: ComparisonCutoutPlacement) {
        check(!sealed && owners > 0)
        val i = pts.binarySearch(at)
        require(i >= 0 && !known[i])
        require(w in 1..256 && h in 1..256 && (alpha == null || alpha.size == w*h))
        if (placement == null) { placement=fixed; width=w; height=h }
        require(placement == fixed && width == w && height == h)
        if (alpha != null) {
            val payload = ByteBuffer.allocate(8+alpha.size).putLong(at).put(alpha).array()
            records[i] = disk.append(payload)
            visible++
        }
        known[i]=true;count++
    }
    @Synchronized fun seal(anchor: Long) {
        check(!sealed && count == pts.size && records[pts.binarySearch(anchor)] != null)
        sealed=true
    }
    @Synchronized fun retain(): ComparisonCutoutDiskTrack {
        check(owners > 0); owners++; return this
    }
    @Synchronized fun layer(at: Long): ComparisonCutoutLayer? {
        check(sealed && owners > 0)
        val i=pts.binarySearch(at)
        if(i<0 || records[i]==null)return null
        val chunk=i/CHUNK
        return runCatching {
            val cached=chunks[chunk] ?: Array<ComparisonCutoutLayer?>(minOf(CHUNK,pts.size-chunk*CHUNK)) { j ->
                val index=chunk*CHUNK+j
                records[index]?.let { record ->
                    val data=ByteBuffer.wrap(disk.read(record))
                    check(data.long==pts[index] && data.remaining()==width*height)
                    val alpha=ByteArray(width*height);data.get(alpha)
                    ComparisonCutoutLayer(pts[index],width,height,alpha,requireNotNull(placement))
                }
            }.also {
                chunks[chunk]=it
                if(chunks.size>2)chunks.remove(chunks.keys.first())
            }
            cached[i%CHUNK]
        }.getOrNull() // Cache I/O failure hides preview; strict export aborts.
    }
    @Synchronized override fun close() {
        if(owners<=0)return
        if(--owners==0) { chunks.clear();disk.close() }
    }
}
