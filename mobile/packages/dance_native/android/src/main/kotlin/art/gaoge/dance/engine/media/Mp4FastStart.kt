package art.gaoge.dance.engine.media

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Relocate the muxer's index without decoding or changing any media sample bytes. */
internal object Mp4FastStart {
    private data class Atom(val offset: Long, val size: Long, val type: String, val header: Long) {
        val end: Long get() = offset + size
    }
    private class Cancelled : IOException()

    /** Caller owns a distinct returned temporary file. The supplied file is never modified. */
    fun prepare(source: File, cancelled: () -> Boolean = { false }): File? {
        if (cancelled()) return null
        RandomAccessFile(source, "r").use { input ->
            val atoms = atoms(input, 0, input.length())
            val moov = atoms.singleOrNull { it.type == "moov" } ?: throw IOException("MP4 index missing or duplicated")
            val ftyp = atoms.firstOrNull { it.type == "ftyp" } ?: throw IOException("MP4 file type missing")
            val media = atoms.filter { it.type == "mdat" }
            if (media.isEmpty()) throw IOException("MP4 media data missing")
            if (moov.offset < media.first().offset) return source
            val order = listOf(ftyp, moov) + atoms.filter { it != ftyp && it != moov }
            var nextOffset = 0L
            val relocated = order.associateWith { atom -> nextOffset.also { nextOffset += atom.size } }
            val result = File.createTempFile("woah-stream-", ".tmp.mp4", source.parentFile)
            try {
                RandomAccessFile(result, "rw").use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    for (atom in order) {
                        input.seek(atom.offset)
                        var remaining = atom.size
                        while (remaining > 0) {
                            if (cancelled()) throw Cancelled()
                            val count = minOf(remaining, buffer.size.toLong()).toInt()
                            input.readFully(buffer, 0, count)
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                    }
                    val newMoov = moov.copy(offset = relocated.getValue(moov))
                    val tables = patch(output, newMoov, media, relocated, cancelled, 0)
                    if (tables == 0) throw IOException("MP4 chunk offsets missing")
                    if (cancelled()) throw Cancelled()
                    output.fd.sync()
                }
                return result
            } catch (_: Cancelled) {
                result.delete()
                return null
            } catch (error: Throwable) {
                result.delete()
                throw error
            }
        }
    }

    private fun atoms(file: RandomAccessFile, start: Long, end: Long): List<Atom> {
        val result = mutableListOf<Atom>()
        var offset = start
        while (offset < end) {
            if (end - offset < 8) throw IOException("Truncated MP4 atom")
            file.seek(offset)
            val shortSize = file.readInt().toLong() and 0xffffffffL
            val type = ByteArray(4).also { file.readFully(it) }.toString(Charsets.ISO_8859_1)
            val header = if (shortSize == 1L) 16L else 8L
            if (end - offset < header) throw IOException("Truncated extended MP4 atom")
            val size = when (shortSize) {
                0L -> end - offset
                1L -> file.readLong()
                else -> shortSize
            }
            if (size < header || size > end - offset) throw IOException("Invalid MP4 atom size")
            result += Atom(offset, size, type, header)
            offset += size
        }
        return result
    }

    private fun patch(
        file: RandomAccessFile,
        parent: Atom,
        media: List<Atom>,
        relocated: Map<Atom, Long>,
        cancelled: () -> Boolean,
        depth: Int
    ): Int {
        if (depth > 12) throw IOException("MP4 atom nesting limit")
        var tables = 0
        for (box in atoms(file, parent.offset + parent.header, parent.end)) {
            if (cancelled()) throw Cancelled()
            when (box.type) {
                "trak", "mdia", "minf", "stbl" -> tables += patch(file, box, media, relocated, cancelled, depth + 1)
                "stco", "co64" -> {
                    val stride = if (box.type == "stco") 4L else 8L
                    if (box.size - box.header < 8) throw IOException("Truncated MP4 chunk table")
                    file.seek(box.offset + box.header + 4)
                    val count = file.readInt().toLong() and 0xffffffffL
                    if (count > (box.size - box.header - 8) / stride) throw IOException("Invalid MP4 chunk count")
                    var index = 0L
                    while (index < count) {
                        if (index % 1024L == 0L && cancelled()) throw Cancelled()
                        val position = box.offset + box.header + 8 + index * stride
                        file.seek(position)
                        val original = if (stride == 4L) file.readInt().toLong() and 0xffffffffL else file.readLong()
                        val container = media.firstOrNull { original >= it.offset + it.header && original < it.end }
                            ?: throw IOException("MP4 chunk points outside media data")
                        val updated = original + relocated.getValue(container) - container.offset
                        if (updated < 0 || (stride == 4L && updated > 0xffffffffL)) {
                            throw IOException("MP4 relocated chunk exceeds its offset representation")
                        }
                        file.seek(position)
                        if (stride == 4L) file.writeInt(updated.toInt()) else file.writeLong(updated)
                        index++
                    }
                    tables++
                }
                "cmov" -> throw IOException("Compressed MP4 indices are unsupported")
            }
        }
        return tables
    }
}
