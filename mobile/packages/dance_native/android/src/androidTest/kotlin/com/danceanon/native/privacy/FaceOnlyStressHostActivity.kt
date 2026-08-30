package com.danceanon.native.privacy

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/** androidTest-only foreground host preventing OEM background-freeze policies from suspending stress tests. */
class FaceOnlyStressHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
    }
}
