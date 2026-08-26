package com.danceanon.native.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.danceanon.native.bridge.DanceNativeException
import com.danceanon.native.bridge.JobStatusDto
import com.danceanon.native.export.ExportCoordinator
import com.danceanon.native.inference.YoloOnnxSegmenter
import com.danceanon.native.pipeline.ExportPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class ExportForegroundService : Service() {


    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private var segmenter: YoloOnnxSegmenter? = null

    companion object {
        const val CHANNEL_ID = "dance_anon_export_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.danceanon.native.service.ACTION_START"
        const val ACTION_UPDATE_PROGRESS = "com.danceanon.native.service.ACTION_UPDATE_PROGRESS"
        const val ACTION_STOP = "com.danceanon.native.service.ACTION_STOP"

        const val EXTRA_JOB_ID = "extra_job_id"
        const val EXTRA_PROGRESS = "extra_progress"

        fun start(context: Context, jobId: String) {
            val intent = Intent(context, ExportForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_JOB_ID, jobId)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("ExportForegroundService", "Failed to start foreground service: ${e.message}", e)
            }
        }

        fun updateProgress(context: Context, jobId: String, progressPercent: Int) {
            val intent = Intent(context, ExportForegroundService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_PROGRESS, progressPercent)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            val intent = Intent(context, ExportForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        segmenter = YoloOnnxSegmenter(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coordinator = ExportCoordinator.getInstance(applicationContext)
        when (intent?.action) {
            ACTION_START -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: ""
                val notification = buildNotification("Preparing video export...", 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val fgsType = if (Build.VERSION.SDK_INT >= 34) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                if (jobId.isNotBlank() && !runningJobs.containsKey(jobId)) {
                    val request = coordinator.getRequest(jobId)
                    if (request != null) {
                        val exportPipeline = ExportPipeline(applicationContext, segmenter!!)
                        val isCancelled = coordinator.getCancellationFlag(jobId)

                        val coroutine = serviceScope.launch {
                            try {
                                exportPipeline.execute(
                                    jobId = jobId,
                                    sourceUri = request.sourceUri,
                                    request = request,
                                    isCancelled = isCancelled,
                                    onStatusChange = { status ->
                                        coordinator.notifyProgress(status)
                                        updateNotification(status)
                                    }
                                )
                            } catch (e: Throwable) {
                                android.util.Log.e("ExportForegroundService", "Export execution error: ${e.message}", e)
                                val errorCode = if (e is DanceNativeException) e.code else DanceNativeException.EXPORT_FAILED
                                val failedStatus = JobStatusDto(
                                    jobId = jobId,
                                    state = "failed",
                                    currentFrame = 0L,
                                    totalFrames = 0L,
                                    fps = 0.0,
                                    progress = 0.0,
                                    outputUri = null,
                                    errorCode = errorCode,
                                    errorMessage = "${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}"
                                )
                                coordinator.notifyProgress(failedStatus)
                            } finally {
                                runningJobs.remove(jobId)
                                coordinator.onJobFinished(jobId)
                                if (runningJobs.isEmpty()) {
                                    stopForeground(STOP_FOREGROUND_REMOVE)
                                    stopSelf()
                                }
                            }
                        }
                        runningJobs[jobId] = coroutine
                    }
                }
            }
            ACTION_UPDATE_PROGRESS -> {
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val notification = buildNotification("Exporting video ($progress%)...", progress)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.notify(NOTIFICATION_ID, notification)
            }
            ACTION_STOP -> {
                val coordinator = ExportCoordinator.getInstance(applicationContext)
                coordinator.cancelAllJobs()
                serviceScope.launch {
                    try {
                        withTimeoutOrNull(4000L) {
                            runningJobs.values.toList().joinAll()
                        }
                    } finally {
                        runningJobs.clear()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(status: JobStatusDto) {
        val progressPercent = (status.progress * 100).toInt().coerceIn(0, 100)
        val text = when (status.state) {
            "preparing" -> "Preparing export..."
            "processing" -> "Exporting video ($progressPercent%)..."
            "muxing" -> "Finalizing video..."
            "completed" -> "Export completed"
            "failed" -> "Export failed"
            "cancelled" -> "Export cancelled"
            else -> "Exporting video..."
        }
        val notification = buildNotification(text, progressPercent)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.e("ExportForegroundService", "Foreground service timed out (startId=$startId, type=$fgsType)")
        val coordinator = ExportCoordinator.getInstance(applicationContext)
        coordinator.cancelAllJobs()
        serviceScope.launch {
            try {
                withTimeoutOrNull(2000L) {
                    runningJobs.values.toList().joinAll()
                }
            } finally {
                runningJobs.clear()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            val coordinator = ExportCoordinator.getInstance(applicationContext)
            coordinator.cancelAllJobs()
            serviceScope.cancel()
            segmenter?.close()
            segmenter = null
        } catch (_: Throwable) {}
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video Export",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
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

        builder.setContentTitle("Dance Anonymizer")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)

        return builder.build()
    }
}

