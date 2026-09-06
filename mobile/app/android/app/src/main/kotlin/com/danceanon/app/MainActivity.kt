package com.danceanon.app

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "com.danceanon.app/build_info",
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
}
