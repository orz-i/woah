package com.danceanon.native.export

import android.content.Context
import android.content.Intent
import android.os.Build
import com.danceanon.native.service.ExportForegroundService

object ExportServiceController {

    fun startExportService(context: Context, jobId: String) {
        val intent = Intent(context, ExportForegroundService::class.java).apply {
            action = ExportForegroundService.ACTION_START
            putExtra(ExportForegroundService.EXTRA_JOB_ID, jobId)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("ExportServiceController", "Failed to start export foreground service: ", e)
        }
    }

    fun stopExportService(context: Context) {
        val intent = Intent(context, ExportForegroundService::class.java).apply {
            action = ExportForegroundService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}
    }
}
