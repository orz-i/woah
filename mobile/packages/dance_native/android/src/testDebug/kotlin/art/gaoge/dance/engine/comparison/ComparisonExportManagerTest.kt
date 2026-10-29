package art.gaoge.dance.engine.comparison

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.*
import org.mockito.Answers
import org.mockito.Mockito.*

class ComparisonExportManagerTest {
    private fun withManager(test: (ComparisonExportManager,File)->Unit) {
        val root=Files.createTempDirectory("woah-comparison-manager-").toFile()
        val context=mock(Context::class.java)
        `when`(context.filesDir).thenReturn(File(root,"files").apply { mkdirs() })
        `when`(context.cacheDir).thenReturn(File(root,"cache").apply { mkdirs() })
        `when`(context.noBackupFilesDir).thenReturn(File(root,"no-backup").apply { mkdirs() })
        mockConstruction(Intent::class.java,withSettings().defaultAnswer(Answers.RETURNS_SELF)).use {
            mockConstruction(AtomicFile::class.java,withSettings().defaultAnswer { call ->
                when(call.method.name) {
                    "readFully" -> throw FileNotFoundException()
                    "startWrite" -> FileOutputStream(File(root,"metadata.tmp"))
                    "finishWrite" -> { (call.arguments[0] as FileOutputStream).close();null }
                    else -> Answers.RETURNS_DEFAULTS.answer(call)
                }
            }).use {
                val ctor=ComparisonExportManager::class.java.getDeclaredConstructor(Context::class.java)
                ctor.isAccessible=true
                test(ctor.newInstance(context),root)
            }
        }
    }
    private fun spec()=ComparisonExportSpec("test_project","file:///reference.mp4","file:///target.mp4",
        0,1.0,0,1_000_000,320,240,8,true,false,false,ComparisonPicture(),ComparisonPicture())

    @Test fun interruptedWorkerCannotThrowOrModifyItsRetry() = withManager { manager,root ->
        val first=manager.start(spec())
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val workerError=AtomicReference<Throwable?>()
        val token=AtomicReference<AtomicBoolean>()
        val notifications=mutableListOf<String>()
        val worker=thread(name="comparison-old-export") {
            try {
                mockConstruction(ComparisonExportPipeline::class.java,withSettings().defaultAnswer { args ->
                    if (args.method.name=="execute") {
                        token.set(args.arguments[3] as AtomicBoolean)
                        @Suppress("UNCHECKED_CAST")
                        val progress=args.arguments[4] as (String,Double)->Unit
                        File(root,"files/comparison-exports/${first.jobId}.partial.mp4").writeText("old partial")
                        progress("held worker",.1)
                        progress("late old progress",.9)
                        throw ComparisonExportCancelled()
                    } else Answers.RETURNS_DEFAULTS.answer(args)
                }).use {
                    manager.execute(first.jobId) {
                        notifications.add(it.jobId)
                        entered.countDown()
                        check(release.await(10,TimeUnit.SECONDS))
                    }
                }
            } catch (error: Throwable) { workerError.set(error) }
        }
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            manager.interrupted(first.jobId)
            val retry=manager.retry(first.jobId)
            val newPartial=File(root,"files/comparison-exports/${retry.jobId}.partial.mp4").apply { writeText("new work") }
            assertTrue(token.get().get(),"Retry must not reset the old worker's cancellation token")
            release.countDown();worker.join(10000)
            assertFalse(worker.isAlive)
            assertNull(workerError.get())
            assertEquals(listOf(first.jobId),notifications,"Old callbacks must not report the retry's status")
            assertEquals(retry.jobId,manager.latest()!!.jobId)
            assertEquals("queued",manager.latest()!!.state)
            assertEquals(0.0,manager.latest()!!.progress)
            assertEquals("new work",newPartial.readText())
            assertFalse(File(root,"files/comparison-exports/${first.jobId}.partial.mp4").exists())
        } finally { release.countDown();worker.join(10000) }
    }

    @Test fun obsoleteQueuedWorkerIsIgnoredAfterRetry() = withManager { manager,_ ->
        val first=manager.start(spec())
        manager.interrupted(first.jobId)
        val retry=manager.retry(first.jobId)
        mockConstruction(ComparisonExportPipeline::class.java).use { pipelines ->
            manager.execute(first.jobId) { fail("An obsolete worker must not notify") }
            assertTrue(pipelines.constructed().isEmpty())
        }
        assertEquals(retry.jobId,manager.latest()!!.jobId)
        assertEquals("queued",manager.latest()!!.state)
    }

    @Test fun oldServiceInterruptionCannotCancelTheRetry() = withManager { manager,_ ->
        val first=manager.start(spec())
        manager.interrupted(first.jobId)
        val retry=manager.retry(first.jobId)
        manager.interrupted(first.jobId)
        assertEquals("queued",manager.latest()!!.state)
        assertEquals(retry.jobId,manager.latest()!!.jobId)
        manager.interrupted(retry.jobId)
        assertEquals("interrupted",manager.latest()!!.state)
    }
}
