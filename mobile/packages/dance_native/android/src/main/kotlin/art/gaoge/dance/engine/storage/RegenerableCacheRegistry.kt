package art.gaoge.dance.engine.storage

import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only app-generated, reproducible analysis/preview/trim files belong here. */
class RegenerableCacheRegistry(
    root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 24L * 60 * 60 * 1000,
    private val inactiveBudgetBytes: Long = 256L * 1024 * 1024,
    private val cleanupExecutor: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "WoahCacheCleanup").apply { isDaemon = true }
    }
) {
    private val root = root.canonicalFile
    class Owner { internal var closed = false }
    private data class Entry(val owner: Owner?, var leases: Int, var released: Boolean = false)
    private val entries = mutableMapOf<String, Entry>()
    private val cleanupListeners = mutableMapOf<Owner, (String) -> Unit>()
    val trackedEntryCount: Int get() = synchronized(lock) { entries.size }
    private var lastMaintenance: Long? = null

    fun setOwnerCleanup(owner: Owner, cleanup: (String) -> Unit) {
        synchronized(lock) { cleanupListeners[owner] = cleanup }
    }

    fun maintainIfNeeded() {
        val now = clock()
        val due = synchronized(lock) {
            val last = lastMaintenance
            if (last != null && now >= last && now - last < 60_000) false
            else { lastMaintenance = now; true }
        }
        if (due) pruneInactive()
    }
    private val lock = Any()

    class Lease internal constructor(val id: String, private val end: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false, true)) end() }
    }

    fun beginAnalysis(owner: Owner): Lease = begin(owner, "analysis_${clock()}_${UUID.randomUUID()}").also { maintainIfNeeded() }
    fun beginTrim(owner: Owner): Lease = begin(owner, "trim_${UUID.randomUUID()}").also { maintainIfNeeded() }

    private fun begin(owner: Owner, id: String): Lease = synchronized(lock) {
        check(!owner.closed) { "CACHE_OWNER_CLOSED" }
        check(id !in entries) { "CACHE_ID_COLLISION" }
        entries[id] = Entry(owner, 1)
        Lease(id) { endLease(id) }
    }

    fun acquire(id: String): Lease = synchronized(lock) {
        val entry = entries[id] ?: error("CACHE_NOT_RETAINED")
        check(!entry.released) { "CACHE_RELEASED" }
        entry.leases++
        Lease(id) { endLease(id) }
    }

    fun releaseProject(owner: Owner, id: String) {
        synchronized(lock) {
            val entry = entries[id] ?: return
            if (entry.owner !== owner) return
            entry.released = true
        }
        scheduleCleanup(id)
    }

    fun releaseOwner(owner: Owner) {
        val ids = synchronized(lock) {
            owner.closed = true
            if (entries.values.none { it.owner === owner }) cleanupListeners.remove(owner)
            entries.filterValues { it.owner === owner }.map { (id, entry) ->
                entry.released = true
                id
            }
        }
        ids.forEach(::scheduleCleanup)
    }

    private fun endLease(id: String) {
        synchronized(lock) {
            val entry = entries.getValue(id)
            check(entry.leases > 0)
            entry.leases--
        }
        scheduleCleanup(id)
    }

    private fun scheduleCleanup(id: String) {
        cleanupExecutor.execute {
            val deletable = synchronized(lock) {
                entries[id]?.let { it.released && it.leases == 0 } == true
            }
            // Keep released entries during deletion; absent entries also reject late acquire.
            if (deletable) {
                val listeners = synchronized(lock) { cleanupListeners.values.toList() }
                listeners.forEach { it(id) }
                if (deleteGroup(id)) finishDeletion(id)
            }
        }
    }

    /** Run after process startup reconciliation; repeated engine attaches never reset owners. */
    fun pruneInactive() { cleanupExecutor.execute { pruneNow() } }

    private fun pruneNow() {
        val retries = synchronized(lock) { entries.filterValues { it.released && it.leases == 0 }.keys.toList() }
        retries.forEach { id ->
            val listeners = synchronized(lock) { cleanupListeners.values.toList() }
            listeners.forEach { it(id) }
            if (deleteGroup(id)) finishDeletion(id)
        }
        val now = clock()
        val candidates = discoverGroups().mapNotNull { id ->
            if (synchronized(lock) { id in entries }) return@mapNotNull null
            val files = groupFiles(id) ?: return@mapNotNull null
            val modified = files.maxOfOrNull { it.lastModified() } ?: return@mapNotNull null
            if (modified <= 0 || modified > now) return@mapNotNull null
            Triple(id, modified, files.sumOf { it.length() })
        }.sortedBy { it.second }
        var total = candidates.sumOf { it.third }
        for ((id, modified, bytes) in candidates) {
            if (now - modified < ttlMillis && total <= inactiveBudgetBytes) continue
            val claimed = synchronized(lock) {
                if (id in entries) false else {
                    entries[id] = Entry(null, 0, released = true)
                    true
                }
            }
            if (claimed && deleteGroup(id)) { total -= bytes; finishDeletion(id) }
        }
    }

    private fun finishDeletion(id: String) = synchronized(lock) {
        val entry = entries[id] ?: return@synchronized
        if (!entry.released || entry.leases != 0) return@synchronized
        entries.remove(id)
        entry.owner?.let { owner ->
            if (owner.closed && entries.values.none { it.owner === owner }) cleanupListeners.remove(owner)
        }
    }

    private fun discoverGroups(): Set<String> {
        val ids = mutableSetOf<String>()
        val analysis = File(root, "analysis")
        if (safe(analysis) && analysis.isDirectory) {
            analysis.listFiles()?.filter { ANALYSIS_ID.matches(it.name) }?.forEach { ids.add(it.name) }
        }
        root.listFiles()?.forEach { file ->
            PREVIEW.matchEntire(file.name)?.groupValues?.get(1)?.let(ids::add)
        }
        val trim = File(root, "trim_thumbnails")
        if (safe(trim) && trim.isDirectory) {
            trim.listFiles()?.forEach { file ->
                TRIM_FILE.matchEntire(file.name)?.groupValues?.get(1)?.let(ids::add)
            }
        }
        return ids
    }

    /** Unknown children or links quarantine the group, rather than broad recursive deletion. */
    private fun groupFiles(id: String): List<File>? {
        val files = mutableListOf<File>()
        if (ANALYSIS_ID.matches(id)) {
            val directory = File(root, "analysis/$id")
            if (!safe(File(root, "analysis")) || !safe(directory)) return null
            if (directory.exists()) {
                if (!directory.isDirectory) return null
                val children = directory.listFiles() ?: return null
                if (children.any { !ANALYSIS_FILE.matches(it.name) || !safe(it) || !it.isFile }) return null
                files.addAll(children)
            }
            val previewPattern = Regex("preview_${Regex.escape(id)}_[0-9]+_[0-9]+\\.jpg")
            val previews = root.listFiles()?.filter { previewPattern.matches(it.name) } ?: return null
            if (previews.any { !safe(it) || !it.isFile }) return null
            files.addAll(previews)
        } else if (TRIM_ID.matches(id)) {
            val directory = File(root, "trim_thumbnails")
            if (!safe(directory)) return null
            if (!directory.exists()) return emptyList()
            val pattern = Regex("${Regex.escape(id)}_[0-9]+\\.jpg")
            val matches = directory.listFiles()?.filter { pattern.matches(it.name) } ?: return null
            if (matches.any { !safe(it) || !it.isFile }) return null
            files.addAll(matches)
        } else return null
        return files
    }

    private fun deleteGroup(id: String): Boolean {
        val files = groupFiles(id) ?: return false
        var deleted = true
        for (file in files) {
            if (!safe(file) || (file.exists() && !file.delete())) deleted = false
        }
        if (ANALYSIS_ID.matches(id)) {
            val directory = File(root, "analysis/$id")
            if (safe(directory) && directory.isDirectory && directory.list()?.isEmpty() == true) {
                if (!directory.delete()) deleted = false
            }
        }
        return deleted
    }

    private fun safe(file: File): Boolean = try {
        file.absoluteFile == file.canonicalFile && file.canonicalPath.startsWith(root.path + File.separator)
    } catch (_: Exception) { false }

    companion object {
        private const val UUID_PATTERN = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"
        private const val ANALYSIS_PATTERN = "analysis_[0-9]+(?:_$UUID_PATTERN)?"
        private const val TRIM_PATTERN = "trim_(?:[0-9]+|$UUID_PATTERN)"
        private val ANALYSIS_ID = Regex(ANALYSIS_PATTERN)
        private val TRIM_ID = Regex(TRIM_PATTERN)
        private val ANALYSIS_FILE = Regex("(?:source_uri\\.txt|analysis\\.json|person_[0-9]+\\.webp)")
        private val PREVIEW = Regex("preview_($ANALYSIS_PATTERN)_[0-9]+_[0-9]+\\.jpg")
        private val TRIM_FILE = Regex("($TRIM_PATTERN)_[0-9]+\\.jpg")
    }
}
