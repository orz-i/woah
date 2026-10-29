package art.gaoge.dance.engine.comparison

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import art.gaoge.dance.engine.service.ExportForegroundService
import kotlinx.coroutines.*

/** A separate worker avoids starting privacy inference for a plain comparison export. */
class ComparisonExportService : Service() {
    private val scope=CoroutineScope(Dispatchers.IO+SupervisorJob())
    private var runningId: String?=null
    private val running get()=runningId!=null
    private var destroyed=false
    private var pendingId: String?=null
    private var lastNotification=0L
    private var wake: PowerManager.WakeLock?=null
    private val manager get()=ComparisonExportManager.get(this)
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        val id=intent?.getStringExtra("id") ?: return START_NOT_STICKY
        if (intent.action==CANCEL) {
            if (manager.latest()?.jobId==id) manager.cancel(id)
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (manager.latest()?.jobId!=id || manager.latest()?.state !in listOf("queued","cancelling")) {
            if (!running) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (running) { pendingId=id;return START_NOT_STICKY }
        begin(id)
        return START_NOT_STICKY
    }
    private fun begin(id: String) {
        createChannel()
        val initial=notification(id,"准备导出",0)
        if (Build.VERSION.SDK_INT>=29) startForeground(NOTIFICATION,initial,ExportForegroundService.foregroundServiceTypeForSdk(Build.VERSION.SDK_INT))
        else startForeground(NOTIFICATION,initial)
        runningId=id
        wake=(getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Woah:ComparisonExport")
            .apply { acquire(6*60*60*1000L) }
        scope.launch {
            try {
                manager.execute(id) { status ->
                    if (System.currentTimeMillis()-lastNotification>1000) {
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION,
                            notification(id,status.stage,(status.progress*100).toInt()))
                        lastNotification=System.currentTimeMillis()
                    }
                }
            } catch (error: Throwable) {
                // Also covers notification or persistence failures outside the codec pipeline.
                runCatching { manager.interrupted(id) }
                if (art.gaoge.dance.engine.BuildConfig.DEBUG) android.util.Log.w(
                    "WoahComparisonExport","worker stopped: ${error.javaClass.simpleName}")
            } finally {
                releaseWake()
                withContext(Dispatchers.Main+NonCancellable) {
                    runningId=null
                    if (destroyed) return@withContext
                    val next=pendingId;pendingId=null
                    if (next!=null && manager.latest()?.jobId==next && manager.latest()?.state in listOf("queued","cancelling")) begin(next)
                    else { stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() }
                }
            }
        }
    }
    @Synchronized private fun releaseWake() {
        wake?.let { if (it.isHeld) runCatching { it.release() } };wake=null
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT>=26) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(ExportForegroundService.CHANNEL_ID,"视频导出",NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(id: String,text: String,percent: Int): Notification {
        val builder=if (Build.VERSION.SDK_INT>=26) Notification.Builder(this,ExportForegroundService.CHANNEL_ID) else Notification.Builder(this)
        val cancel=PendingIntent.getService(this,1002,Intent(this,ComparisonExportService::class.java).apply {
            action=CANCEL;putExtra("id",id)
        },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(this,1002,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("舞蹈对比导出")
            .setContentText(text).setProgress(100,percent,false).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"取消",cancel).build()).build()
    }
    override fun onTimeout(startId: Int,fgsType: Int) { runningId?.let { manager.interrupted(it) };stopSelf() }
    override fun onDestroy() {
        destroyed=true
        runningId?.let { manager.interrupted(it) }
        scope.cancel();releaseWake()
        super.onDestroy()
    }
    companion object {
        private const val NOTIFICATION=1002
        private const val CANCEL="art.gaoge.dance.comparison.EXPORT_CANCEL"
        fun start(context: Context,id: String) {
            val intent=Intent(context,ComparisonExportService::class.java).putExtra("id",id)
            if (Build.VERSION.SDK_INT>=26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
