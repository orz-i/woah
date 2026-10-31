package art.gaoge.dance.engine.storage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.*
import art.gaoge.dance.engine.export.OwnedExportJobs
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import art.gaoge.dance.engine.storage.RegenerableCacheRegistry.Owner

class RegenerableCacheRegistryTest {
    private val roots = mutableListOf<File>()
    private val temporary = object {
        fun newFolder(): File = Files.createTempDirectory("woah-cache-fixture-").toFile().also { roots.add(it) }
    }
    @AfterTest fun cleanSyntheticFixtures() {
        roots.forEach { root ->
            Files.walk(root.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
    private fun assertThrows(type: Class<out Throwable>, block: () -> Unit) {
        val error = assertFails(block = block)
        assertTrue(type.isInstance(error))
    }
    private val direct = Executor { it.run() }
    private fun registry(root: File, now: Long = 100_000L, ttl: Long = 1000L, budget: Long = 1024L) =
        RegenerableCacheRegistry(root, { now }, ttl, budget, direct)
    private fun analysis(root: File, id: String, time: Long = 90_000L): File {
        val file = File(root, "analysis/$id/analysis.json")
        file.parentFile!!.mkdirs()
        file.writeText("synthetic fixture")
        assertTrue(file.setLastModified(time))
        return file
    }
    @Test fun hundredSyntheticSessionsReleaseOnlyTheirOwnFiles() {
        val root = temporary.newFolder()
        val sentinel = File(root, "exports/user-result.mp4").apply { parentFile!!.mkdirs(); writeText("KEEP") }
        val store = registry(root)
        repeat(100) {
            val owner = Owner()
            val creation = store.beginAnalysis(owner)
            val file = analysis(root, creation.id)
            creation.close()
            store.releaseProject(owner, creation.id)
            store.releaseProject(owner, creation.id)
            assertFalse(file.exists())
        }
        assertEquals("KEEP", sentinel.readText())
        assertEquals(0, store.trackedEntryCount)
    }
    @Test fun releaseWaitsForRealPreviewAndExportCompletion() {
        val root = temporary.newFolder(); val store = registry(root); val owner = Owner()
        val creation = store.beginAnalysis(owner); val file = analysis(root, creation.id)
        creation.close()
        val preview = store.acquire(creation.id); val export = store.acquire(creation.id)
        store.releaseProject(owner, creation.id)
        assertTrue(file.exists())
        assertThrows(IllegalStateException::class.java) { store.acquire(creation.id) }
        preview.close(); preview.close(); assertTrue(file.exists())
        // An early cancelled status does not close this actual worker lease.
        assertTrue(file.exists())
        export.close(); export.close(); assertFalse(file.exists())
    }
    @Test fun detachProtectsInFlightAnalysisAndOtherEngine() {
        val root = temporary.newFolder(); val store = registry(root); val a = Owner(); val b = Owner()
        val first = store.beginAnalysis(a); val second = store.beginAnalysis(b)
        val fa = analysis(root, first.id); val fb = analysis(root, second.id)
        store.releaseOwner(a)
        assertTrue(fa.exists()); assertTrue(fb.exists())
        assertThrows(IllegalStateException::class.java) { store.beginAnalysis(a) }
        first.close(); assertFalse(fa.exists()); assertTrue(fb.exists())
        store.releaseProject(a, second.id); second.close(); assertTrue(fb.exists())
        store.releaseOwner(b); assertFalse(fb.exists())
    }
    @Test fun trimReturnedPathsRemainRetainedUntilOwnerDetach() {
        val root = temporary.newFolder(); val store = registry(root, budget = 0); val owner = Owner()
        val lease = store.beginTrim(owner)
        val file = File(root, "trim_thumbnails/${lease.id}_0.jpg").apply { parentFile!!.mkdirs(); writeText("fixture") }
        lease.close(); store.pruneInactive(); assertTrue(file.exists())
        store.releaseOwner(owner); assertFalse(file.exists())
    }
    @Test fun orphanTtlAndInactiveBudgetExcludeRetainedGroups() {
        val root = temporary.newFolder(); val store = registry(root)
        val old = analysis(root, "analysis_1", 98_999)
        val recent = analysis(root, "analysis_2", 99_001)
        val active = store.beginAnalysis(Owner()); val activeFile = analysis(root, active.id, 1)
        active.close(); store.pruneInactive()
        assertFalse(old.exists()); assertTrue(recent.exists()); assertTrue(activeFile.exists())
        registry(root, budget = 0).pruneInactive() // Synthetic process restart: no live owners.
        assertFalse(recent.exists()); assertFalse(activeFile.exists())
    }
    @Test fun futureTimestampsAndUnknownNamesAreConservativelySkipped() {
        val root = temporary.newFolder(); val future = analysis(root, "analysis_1", 100_001)
        val unknown = analysis(root, "analysis_2")
        File(unknown.parentFile, "user-note.txt").writeText("KEEP")
        val unrelated = analysis(root, "user-project")
        registry(root, budget = 0).pruneInactive()
        assertTrue(future.exists()); assertTrue(unknown.exists()); assertTrue(unrelated.exists())
    }
    @Test fun linksCannotEscapeWhitelistAndSourceUriIsNeverFollowed() {
        val root = temporary.newFolder(); val outside = temporary.newFolder()
        val source = File(outside, "source.mp4").apply { writeText("KEEP") }
        val file = analysis(root, "analysis_1")
        File(file.parentFile, "source_uri.txt").apply { writeText(source.absolutePath); setLastModified(90_000) }
        val linked = File(root, "analysis/analysis_2")
        Files.createSymbolicLink(linked.toPath(), outside.toPath())
        val poisoned = analysis(root, "analysis_3")
        Files.createSymbolicLink(File(poisoned.parentFile, "person_0.webp").toPath(), source.toPath())
        registry(root).pruneInactive()
        assertFalse(file.exists()); assertTrue(poisoned.exists()); assertEquals("KEEP", source.readText())
    }
    @Test fun exactPreviewGroupDoesNotDeletePrefixCollisionsOrExcludedTrees() {
        val root = temporary.newFolder()
        analysis(root, "analysis_1")
        val preview = File(root, "preview_analysis_1_0_1.jpg").apply { writeText("fixture"); setLastModified(90_000) }
        val unknown = File(root, "preview_analysis_1_user.jpg").apply { writeText("KEEP") }
        val sentinels = listOf("exports", "export_live_preview", "comparison", "models", "diagnostics", "stickers", "profile").map {
            File(root, "$it/keep.bin").apply { parentFile!!.mkdirs(); writeText("KEEP") }
        }
        registry(root).pruneInactive()
        assertFalse(preview.exists()); assertTrue(unknown.exists())
        sentinels.forEach { assertEquals("KEEP", it.readText()) }
    }
    @Test fun cancelledNativeWorkerKeepsCacheUntilActualCompletionCallback() = runBlocking {
        val root = temporary.newFolder(); val store = registry(root); val owner = Owner()
        val creation = store.beginAnalysis(owner); val file = analysis(root, creation.id); creation.close()
        val exportLease = store.acquire(creation.id)
        val jobs = OwnedExportJobs(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1); val drain = CountDownLatch(1)
        val finished = CompletableDeferred<Unit>()
        jobs.launch(scope, "synthetic-export", cleanup = {}, onCompletion = { _, clean ->
            if (clean) exportLease.close()
            finished.complete(Unit)
        }) { entered.countDown(); check(drain.await(5, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            store.releaseOwner(owner); scope.cancel()
            assertTrue(file.exists()); assertFalse(finished.isCompleted)
            drain.countDown(); withTimeout(3000) { finished.await() }
            assertFalse(file.exists()); assertEquals(0, store.trackedEntryCount)
        } finally { drain.countDown(); scope.cancel() }
    }

    @Test fun memoryCleanupWaitsForRealLeaseAndFailedDiskCleanupRetries() {
        val root = temporary.newFolder(); val store = registry(root); val owner = Owner()
        var clears = 0
        store.setOwnerCleanup(owner) { clears++ }
        val lease = store.beginAnalysis(owner); val file = analysis(root, lease.id)
        val unknown = File(file.parentFile, "unknown.bin").apply { writeText("fixture") }
        store.releaseOwner(owner)
        assertEquals(0, clears); assertTrue(file.exists())
        lease.close(); assertEquals(1, clears); assertTrue(file.exists())
        assertEquals(1, store.trackedEntryCount)
        assertTrue(unknown.delete())
        store.pruneInactive(); assertFalse(file.exists()); assertEquals(0, store.trackedEntryCount)
        assertThrows(IllegalStateException::class.java) { store.acquire(lease.id) }
    }

    @Test fun identicalClockStillCreatesUniqueIdsAndInvalidAcquireCannotTouchFiles() {
        val root = temporary.newFolder(); val store = registry(root); val owner = Owner()
        val a = store.beginAnalysis(owner); val b = store.beginAnalysis(owner)
        assertNotEquals(a.id, b.id)
        listOf("../exports", "/tmp", "analysis_1/../../exports", "..").forEach {
            assertThrows(IllegalStateException::class.java) { store.acquire(it) }
            store.releaseProject(owner, it)
        }
        a.close(); b.close(); store.releaseOwner(owner)
    }
}
