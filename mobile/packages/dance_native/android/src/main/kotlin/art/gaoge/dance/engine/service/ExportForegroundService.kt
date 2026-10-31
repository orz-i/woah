package art.gaoge.dance.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import art.gaoge.dance.engine.bridge.DanceNativeException
import art.gaoge.dance.engine.bridge.JobStatusDto
import art.gaoge.dance.engine.export.ExportCoordinator
import art.gaoge.dance.engine.export.ExportTerminalStatus
import art.gaoge.dance.engine.inference.YoloLiteRtSegmenter
import art.gaoge.dance.engine.pipeline.ExportPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import art.gaoge.dance.engine.export.OwnedExportJobs

class ExportForegroundService : Service() {
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private val runningJobs = OwnedExportJobs()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        // Persisted ID: preserve existing user notification preferences.
        const val CHANNEL_ID = "dance_anon_export_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "art.gaoge.dance.engine.service.ACTION_START"
        const val ACTION_UPDATE_PROGRESS = "art.gaoge.dance.engine.service.ACTION_UPDATE_PROGRESS"
        const val ACTION_STOP = "art.gaoge.dance.engine.service.ACTION_STOP"
        const val EXTRA_JOB_ID = "extra_job_id"
        const val EXTRA_PROGRESS = "extra_progress"
        internal const val YOLO_CPU_FALLBACK_THREADS = 4

        internal fun foregroundServiceTypeForSdk(sdkInt: Int): Int =
            if (sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        fun start(context: Context, jobId: String) {
            art.gaoge.dance.engine.export.ExportServiceController.startExportService(context, jobId)
        }

        fun updateProgress(context: Context, jobId: String, progressPercent: Int) {
            val intent = Intent(context, ExportForegroundService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_PROGRESS, progressPercent)
            }
            try { context.startService(intent) } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent(context, ExportForegroundService::class.java).apply { action = ACTION_STOP }
            try { context.startService(intent) } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runningJobs.recordStart(startId)
        when (intent?.action) {
            ACTION_START -> startWorker(intent.getStringExtra(EXTRA_JOB_ID).orEmpty())
            ACTION_UPDATE_PROGRESS -> {
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.notify(NOTIFICATION_ID, buildNotification("Exporting video ($progress%)...", progress))
            }
            ACTION_STOP -> cancelAndStop(4000L)
        }
        return START_NOT_STICKY
    }

    private fun startWorker(jobId: String) {
        val coordinator = ExportCoordinator.getInstance(applicationContext)
        // Ignore duplicate/stale Intents: they cannot finish another worker's reservation.
        if (runningJobs.contains(jobId)) return
        if (jobId.isBlank() || !coordinator.resourceGate.ownsExport(jobId)) {
            stopIfIdle()
            return
        }
        val request = coordinator.getRequest(jobId)
        try {
            check(request != null) { "Export request is unavailable" }
            val notification = buildNotification("Preparing video export...", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundServiceTypeForSdk(Build.VERSION.SDK_INT))
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Throwable) {
            try {
                coordinator.notifyProgress(ExportTerminalStatus.afterException(
                    previous = coordinator.getJobStatus(jobId),
                    cancelRequested = coordinator.getCancellationFlag(jobId).get(),
                    errorCode = DanceNativeException.EXPORT_FAILED,
                    errorMessage = "Export service preparation failed"
                ))
            } finally {
                coordinator.onJobFinished(jobId)
                stopIfIdle()
            }
            return
        }
        val isCancelled = coordinator.getCancellationFlag(jobId)
        var segmenter: YoloLiteRtSegmenter? = null
        runningJobs.launch(serviceScope, jobId, cleanup = { segmenter?.close() }, onCompletion = { _, resourcesReleased ->
            if (resourcesReleased) {
                coordinator.onJobFinished(jobId)
            } else {
                // Do not re-admit preview to a lane whose native cleanup failed.
                android.util.Log.e("ExportForegroundService", "Export resource cleanup failed; lane remains reserved")
            }
            mainHandler.post { stopIfIdle() }
        }) {
            try {
                if (isCancelled.get()) return@launch
                segmenter = YoloLiteRtSegmenter(applicationContext, cpuNumThreads = YOLO_CPU_FALLBACK_THREADS)
                ExportPipeline(applicationContext, segmenter!!).execute(
                    jobId = jobId,
                    sourceUri = request!!.sourceUri,
                    request = request!!,
                    isCancelled = isCancelled,
                    onStatusChange = { status ->
                        coordinator.notifyProgress(status)
                        updateNotification(status)
                    }
                )
            } catch (error: Throwable) {
                val terminal = ExportTerminalStatus.afterException(
                    previous = coordinator.getJobStatus(jobId),
                    cancelRequested = isCancelled.get(),
                    errorCode = if (error is DanceNativeException) error.code else DanceNativeException.EXPORT_FAILED,
                    errorMessage = "Export worker failed: ${error.javaClass.simpleName}"
                )
                coordinator.notifyProgress(terminal)
            }
        }
    }

    private fun cancelAndStop(timeoutMs: Long) {
        val coordinator = ExportCoordinator.getInstance(applicationContext)
        val cancelledJobs = runningJobs.snapshot()
        runningJobs.cancelOwned(coordinator::cancelJob)
        serviceScope.launch {
            try {
                withTimeoutOrNull(timeoutMs) { cancelledJobs.joinAll() }
            } finally {
                // A deadline stops the service, not the resource lease. Worker completion
                // remains responsible for closing its model and releasing the reservation.
                mainHandler.post {
                    val stopId = runningJobs.cancelledStopId(cancelledJobs)
                    if (stopId != null && stopSelfResult(stopId)) stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    private fun stopIfIdle() {
        val stopId = runningJobs.idleStopId() ?: return
        if (stopSelfResult(stopId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun updateNotification(status: JobStatusDto) {
        val progress = (status.progress * 100).toInt().coerceIn(0, 100)
        val text = when (status.state) {
            "preparing" -> "Preparing export..."
            "processing" -> "Exporting video ($progress%)..."
            "muxing" -> "Finalizing video..."
            "completed" -> "Export completed"
            "failed" -> "Export failed"
            "cancelled" -> "Export cancelled"
            else -> "Exporting video..."
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(text, progress))
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelAndStop(2000L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val coordinator = ExportCoordinator.getInstance(applicationContext)
        runningJobs.cancelOwned(coordinator::cancelJob)
        serviceScope.cancel()
        // Never close a model here: a cancelled coroutine can still be in native code.
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Video Export", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows progress during video export"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String, progress: Int): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setContentTitle("Woah")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()
    }
}
