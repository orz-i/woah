package art.gaoge.dance

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "art.gaoge.dance/orientation",
        ).setMethodCallHandler { call, result ->
            when (call.method) {
                "getRotation" -> result.success(currentRotation())
                "waitForRotation" -> {
                    val target = (call.arguments as? Number)?.toInt()
                    if (target == null || target !in 0..3) {
                        result.error("invalid_rotation", "Invalid display rotation", null)
                    } else waitForRotation(target, result)
                }
                else -> result.notImplemented()
            }
        }

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "art.gaoge.dance/build_info",
        ).setMethodCallHandler { call, result ->
            if (call.method != "getBuildInfo") {
                result.notImplemented()
                return@setMethodCallHandler
            }

            result.success(
                mapOf(
                    "versionName" to BuildConfig.VERSION_NAME,
                    "versionCode" to BuildConfig.VERSION_CODE,
                    "gitCommit" to BuildConfig.GIT_COMMIT,
                    "buildType" to BuildConfig.BUILD_TYPE,
                ),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun currentRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: 0
        else windowManager.defaultDisplay.rotation

    private fun waitForRotation(target: Int, result: MethodChannel.Result) {
        val handler = Handler(Looper.getMainLooper())
        val deadline = SystemClock.uptimeMillis() + 2000
        var stableSince = 0L
        val observe = object : Runnable {
            override fun run() {
                val now = SystemClock.uptimeMillis()
                if (isFinishing || isDestroyed) {
                    result.error("activity_closed", "Activity closed", null)
                    return
                }
                if (currentRotation() == target) {
                    if (stableSince == 0L) stableSince = now
                    if (now - stableSince >= 100) {
                        result.success(null)
                        return
                    }
                } else stableSince = 0L
                if (now >= deadline) {
                    result.error("rotation_timeout", "Display rotation did not settle", null)
                } else handler.postDelayed(this, 32)
            }
        }
        handler.post(observe)
    }

}
