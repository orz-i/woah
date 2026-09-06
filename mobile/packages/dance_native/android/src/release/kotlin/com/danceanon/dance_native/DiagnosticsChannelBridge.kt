package com.danceanon.dance_native

import android.content.Context
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Release builds deliberately expose no diagnostic MethodChannel methods. */
internal object DiagnosticsChannelBridge {
    @Suppress("UNUSED_PARAMETER")
    fun handle(
        call: MethodCall,
        result: MethodChannel.Result,
        context: Context?
    ): Boolean = false
}
