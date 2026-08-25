package com.danceanon.dance_native

import com.danceanon.native.bridge.DanceNativeApi
import com.danceanon.native.bridge.DanceNativeApiImpl
import com.danceanon.native.bridge.DanceProcessingEvents
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** DanceNativePlugin */
class DanceNativePlugin :
    FlutterPlugin,
    MethodCallHandler {
    private lateinit var channel: MethodChannel
    private var apiImpl: DanceNativeApiImpl? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "dance_native")
        channel.setMethodCallHandler(this)

        val eventEmitter = DanceProcessingEvents(flutterPluginBinding.binaryMessenger)
        val impl = DanceNativeApiImpl(flutterPluginBinding.applicationContext, eventEmitter)
        apiImpl = impl
        DanceNativeApi.setUp(flutterPluginBinding.binaryMessenger, impl)
    }

    override fun onMethodCall(
        call: MethodCall,
        result: Result
    ) {
        if (call.method == "getPlatformVersion") {
            result.success("Android ${android.os.Build.VERSION.RELEASE}")
        } else {
            result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        DanceNativeApi.setUp(binding.binaryMessenger, null)
        apiImpl = null
    }
}
