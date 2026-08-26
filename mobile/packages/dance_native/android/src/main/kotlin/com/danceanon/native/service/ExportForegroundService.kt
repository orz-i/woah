package com.danceanon.native.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class ExportForegroundService : Service() {

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
                android.util.Log.w("ExportForegroundService", "Failed to start foreground service: ${e.message}")
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: ""
                val notification = buildNotification("Exporting video...", 0)
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_UPDATE_PROGRESS -> {
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val notification = buildNotification("Exporting video ($progress%)...", progress)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                manager?.notify(NOTIFICATION_ID, notification)
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
