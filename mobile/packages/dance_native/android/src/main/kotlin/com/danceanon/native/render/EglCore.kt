package com.danceanon.native.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

class EglCore : AutoCloseable {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    init {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("unable to initialize EGL14")
        }

        val candidateAttribs = listOf(
            // Tier 1: 8888 RGBA + RECORDABLE (Preferred for MediaCodec input surface)
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            ),
            // Tier 2: 888 RGB + RECORDABLE
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            ),
            // Tier 3: 8888 Standard Window
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
            ),
            // Tier 4: Fallback any GLES2
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
            )
        )

        var chosenConfig: EGLConfig? = null
        var chosenTier = -1
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)

        for ((index, attribs) in candidateAttribs.withIndex()) {
            val success = EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
            if (success && numConfigs[0] > 0 && configs[0] != null) {
                chosenConfig = configs[0]
                chosenTier = index + 1
                break
            }
        }

        eglConfig = chosenConfig ?: throw RuntimeException("Unable to find suitable EGLConfig for hardware rendering")

        val queryVal = IntArray(1)
        fun queryAttrib(attrib: Int): Int {
            EGL14.eglGetConfigAttrib(eglDisplay, eglConfig, attrib, queryVal, 0)
            return queryVal[0]
        }

        val configId = queryAttrib(EGL14.EGL_CONFIG_ID)
        val rSize = queryAttrib(EGL14.EGL_RED_SIZE)
        val gSize = queryAttrib(EGL14.EGL_GREEN_SIZE)
        val bSize = queryAttrib(EGL14.EGL_BLUE_SIZE)
        val aSize = queryAttrib(EGL14.EGL_ALPHA_SIZE)
        val recordable = queryAttrib(EGLExt.EGL_RECORDABLE_ANDROID)

        android.util.Log.i(
            "EglCore",
            "[Telemetry] Selected EGL Tier $chosenTier: ConfigID=$configId, RGBA=$rSize-$gSize-$bSize-$aSize, EGL_RECORDABLE_ANDROID=$recordable"
        )
        if (chosenTier >= 3) {
            android.util.Log.w(
                "EglCore",
                "[Telemetry WARNING] EGLConfig downgraded to Tier $chosenTier (Non-Recordable config!). Hardware encoder surface might encounter vendor compatibility issues."
            )
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create EGL context")
        }
    }

    fun createWindowSurface(surface: Surface): EGLSurface {
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            val err = EGL14.eglGetError()
            throw RuntimeException("Failed to create EGL window surface. EGL error: 0x${Integer.toHexString(err)}")
        }
        return eglSurface
    }

    fun createOffscreenSurface(width: Int, height: Int): EGLSurface {
        val pbufferAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )
        val eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            val err = EGL14.eglGetError()
            throw RuntimeException("Failed to create EGL pbuffer surface. EGL error: 0x${Integer.toHexString(err)}")
        }
        return eglSurface
    }

    fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            val err = EGL14.eglGetError()
            throw RuntimeException("eglMakeCurrent failed. EGL error: 0x${Integer.toHexString(err)}")
        }
    }

    fun swapBuffers(eglSurface: EGLSurface): Boolean {
        val success = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (!success) {
            val err = EGL14.eglGetError()
            android.util.Log.e("EglCore", "[Stage 2 Error] eglSwapBuffers failed! EGL error: 0x${Integer.toHexString(err)}")
        }
        return success
    }

    fun setPresentationTime(eglSurface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }


    fun releaseSurface(eglSurface: EGLSurface?) {
        if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            try {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            } catch (_: Throwable) {}
        }
    }


    override fun close() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
    }
}
