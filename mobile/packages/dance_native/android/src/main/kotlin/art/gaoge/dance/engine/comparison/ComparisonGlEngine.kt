package art.gaoge.dance.engine.comparison

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class ComparisonViewport(val x: Int, val top: Int, val width: Int, val height: Int)
internal object ComparisonGlLayout {
    fun viewport(width: Int, height: Int, gap: Int, videoWidth: Int,
                 videoHeight: Int, teacher: Boolean, sideBySide: Boolean = false): ComparisonViewport {
        if (sideBySide) {
            val firstWidth=(width-gap)/2
            val left=if (teacher) 0 else firstWidth+gap
            val paneWidth=if (teacher) firstWidth else width-left
            val aspect=videoWidth.toDouble()/videoHeight
            val w=minOf(paneWidth,(height*aspect).toInt()).coerceAtLeast(1)
            val h=minOf(height,(paneWidth/aspect).toInt()).coerceAtLeast(1)
            return ComparisonViewport(left+(paneWidth-w)/2,(height-h)/2,w,h)
        }
        val firstHeight = (height-gap)/2
        val top = if (teacher) 0 else firstHeight+gap
        val stageHeight = if (teacher) firstHeight else height-top
        val aspect = videoWidth.toDouble()/videoHeight
        val w = minOf(width,(stageHeight*aspect).toInt()).coerceAtLeast(1)
        val h = minOf(stageHeight,(width/aspect).toInt()).coerceAtLeast(1)
        return ComparisonViewport((width-w)/2,top+(stageHeight-h)/2,w,h)
    }
}

/** Two codec producers -> two OES textures -> ONE window surface / eglSwapBuffers. */
internal class ComparisonGlEngine(
    private val context: Context, private val output: Surface,
    initialWidth: Int, initialHeight: Int, initialGap: Int,
    private val opened: (ComparisonVideoDescription, ComparisonVideoDescription) -> Unit,
    private val failed: (Throwable) -> Unit,
    private val stalled: () -> Unit,
    private val recovered: () -> Unit,
    initialSideBySide: Boolean = false
) {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("WoahComparisonGL").apply { start() }
    private val handler = Handler(thread.looper)
    private val frameThread = HandlerThread("WoahComparisonFrames").apply { start() }
    private val frameHandler = Handler(frameThread.looper)
    private val closed = AtomicBoolean(false)
    private val request = AtomicLong(0)
    private data class RenderRequest(val teacherUs: Long,val offsetUs: Long,
        val clockNs: Long,val generation: Long,val observation: Long,
        val complete: (() -> Unit)? = null)
    private val tickPending = AtomicBoolean(false)
    private val latestTick = AtomicReference<RenderRequest?>(null)
    private val testStallMs = AtomicLong(0)
    private var injectedStalls = 0
    private var activeRequest = 0L
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var teacher: ComparisonCodecVideo? = null
    private var learner: ComparisonCodecVideo? = null
    private var teacherTexture: SurfaceTexture? = null
    private var learnerTexture: SurfaceTexture? = null
    private val textures = IntArray(2)
    private var width = initialWidth
    private var height = initialHeight
    private var gap = initialGap
    private var sideBySide = initialSideBySide
    private var redraw = true
    private var lastTarget: Long? = null
    private var lastOffset = 0L
    private var lastTeacher = -1L
    private var lastLearner = -1L
    private val diagnostics = ComparisonGlDiagnostics()
    private val vertices = ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f,
                             -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0)
        }

    private fun current(): Boolean = !closed.get() && request.get()==activeRequest
    private fun checkCurrent() { if (!current()) throw ComparisonGlCancelled() }
    private fun error(error: Throwable) {
        if (error !is ComparisonGlCancelled && !closed.get()) main.post { failed(error) }
    }
    fun resetDiagnostics() { diagnostics.reset() }

    fun open(teacherUri: Uri, learnerUri: Uri) {
        handler.post {
            try {
                initGl()
                teacherTexture = SurfaceTexture(textures[0])
                learnerTexture = SurfaceTexture(textures[1])
                teacher = ComparisonCodecVideo(context,teacherUri,teacherTexture!!,
                    frameHandler,::current)
                learner = ComparisonCodecVideo(context,learnerUri,learnerTexture!!,
                    frameHandler,::current)
                checkCurrent()
                val a=teacher!!.description; val b=learner!!.description
                main.post { if (!closed.get()) opened(a,b) }
            } catch (e: Throwable) { error(e) }
        }
    }

    fun resize(w: Int,h: Int,g: Int,horizontal: Boolean = sideBySide) {
        handler.post {
            if (w<=0 || h<=g || closed.get()) return@post
            width=w; height=h; gap=g; sideBySide=horizontal; redraw=true
            // A resized Surface may discard the old-size buffer. Paused playback
            // must repaint the already latched PAIR without waiting for play.
            val target=lastTarget
            if (target!=null && current() && window!=EGL14.EGL_NO_SURFACE) {
                try { drawAt(target,lastOffset,System.nanoTime(),diagnostics.generation()) }
                catch (e: Throwable) { error(e) }
            }
        }
    }

    fun seek(teacherUs: Long,offsetUs: Long,complete: () -> Unit) {
        val generation=request.incrementAndGet()
        latestTick.set(null)
        val observation=diagnostics.reset()
        handler.post {
            if (closed.get() || generation!=request.get()) return@post
            activeRequest=generation
            try {
                lastTarget=null
                teacher!!.seek(teacherUs)
                learner!!.seek(teacherUs+offsetUs)
                redraw=true
                drawAt(teacherUs,offsetUs,System.nanoTime(),observation)
                main.post { if (!closed.get() && generation==request.get()) complete() }
            } catch (e: Throwable) { error(e) }
        }
    }

    fun render(teacherUs: Long,offsetUs: Long,clockSampleNs: Long,
               complete: (() -> Unit)? = null) {
        if (closed.get()) return
        val next=RenderRequest(teacherUs,offsetUs,clockSampleNs,request.get(),
            diagnostics.generation(),complete)
        if (complete!=null) {
            latestTick.set(null)
            handler.post { executeRender(next) }
        }
        else {
            // One running job and one newest request, never a historical queue.
            // A VSYNC arriving during swap must update the mailbox, not be lost.
            latestTick.set(next)
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        if (!tickPending.compareAndSet(false,true)) return
        handler.post {
            try { latestTick.getAndSet(null)?.let(::executeRender) }
            finally {
                tickPending.set(false)
                if (latestTick.get()!=null && !closed.get()) scheduleTick()
            }
        }
    }

    private fun executeRender(next: RenderRequest) {
        if (closed.get() || next.generation!=request.get()) return
        activeRequest=next.generation
        val done=AtomicBoolean(false)
        val notified=AtomicBoolean(false)
        val watchdog=Runnable {
            if (!done.get() && next.generation==request.get() && !closed.get()) {
                notified.set(true)
                stalled()
            }
        }
        main.postDelayed(watchdog,50)
        try {
            val delay=testStallMs.getAndSet(0)
            if (delay>0) { injectedStalls++; Thread.sleep(delay) }
            drawAt(next.teacherUs,next.offsetUs,next.clockNs,next.observation)
            main.post {
                if (!closed.get() && next.generation==request.get()) {
                    next.complete?.invoke()
                    if (notified.get()) recovered()
                }
            }
        } catch (e: Throwable) { error(e) }
        finally {
            done.set(true)
            main.removeCallbacks(watchdog)
        }
    }

    private fun drawAt(teacherUs: Long,offsetUs: Long,clockNs: Long,observation: Long) {
        checkCurrent()
        val a=teacher ?: error("Teacher decoder missing")
        val b=learner ?: error("Learner decoder missing")
        val beginNs=System.nanoTime()
        val ta=a.prepareAt(teacherUs)
        val lb=b.prepareAt(teacherUs+offsetUs)
        val preparedNs=System.nanoTime()
        a.latchPrepared()
        b.latchPrepared()
        val latchedNs=System.nanoTime()
        checkCurrent()
        check(ta.contains(teacherUs) && lb.contains(teacherUs+offsetUs)) {
            "Joint frame coverage failed"
        }
        lastTarget=teacherUs; lastOffset=offsetUs
        if (!redraw && ta.startUs==lastTeacher && lb.startUs==lastLearner) return
        GLES20.glViewport(0,0,width,height)
        GLES20.glClearColor(0f,0f,0f,1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawTexture(a,textures[0],true)
        drawTexture(b,textures[1],false)
        check(GLES20.glGetError()==GLES20.GL_NO_ERROR) { "GL composition error" }
        checkCurrent()
        val submitNs=System.nanoTime()
        check(EGL14.eglSwapBuffers(display,window)) { "EGL joint swap failed" }
        val swappedNs=System.nanoTime()
        diagnostics.add(observation,ComparisonGlDiagnostics.Sample(ta.startUs,
            lb.startUs,teacherUs,offsetUs,submitNs,(submitNs-clockNs)/1000L,true,
            (swappedNs-beginNs)/1000L,(swappedNs-submitNs)/1000L,
            (preparedNs-beginNs)/1000L,(latchedNs-preparedNs)/1000L))
        lastTeacher=ta.startUs; lastLearner=lb.startUs; redraw=false
    }

    private fun drawTexture(video: ComparisonCodecVideo,textureId: Int,isTeacher: Boolean) {
        val d=video.description
        val rotated=d.rotation%180!=0
        val rect=ComparisonGlLayout.viewport(width,height,gap,
            if (rotated) d.height else d.width,if (rotated) d.width else d.height,isTeacher,sideBySide)
        GLES20.glViewport(rect.x,height-rect.top-rect.height,rect.width,rect.height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0)
        val transform=video.matrix.copyOf()
        if (d.rotation!=0) {
            val rotation=FloatArray(16)
            Matrix.setIdentityM(rotation,0)
            Matrix.translateM(rotation,0,.5f,.5f,0f)
            Matrix.rotateM(rotation,0,-d.rotation.toFloat(),0f,0f,1f)
            Matrix.translateM(rotation,0,-.5f,-.5f,0f)
            Matrix.multiplyMM(transform,0,video.matrix,0,rotation,0)
        }
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"texMatrix"),
            1,false,transform,0)
        val position=GLES20.glGetAttribLocation(program,"position")
        val uv=GLES20.glGetAttribLocation(program,"uv")
        vertices.position(0)
        GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices)
        GLES20.glEnableVertexAttribArray(position)
        vertices.position(2)
        GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
    }

    private fun shader(type: Int,source: String): Int {
        val shader=GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader,source)
        GLES20.glCompileShader(shader)
        val ok=IntArray(1)
        GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0)
        check(ok[0]!=0) { "GL shader: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun initGl() {
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display!=EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        check(EGL14.eglInitialize(display,null,0,null,0)) { "EGL initialize failed" }
        val configs=arrayOfNulls<EGLConfig>(1)
        val count=IntArray(1)
        val attributes=intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
            EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
            EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,EGL14.EGL_NONE)
        check(EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0) && count[0]>0)
        eglContext=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        window=EGL14.eglCreateWindowSurface(display,configs[0],output,
            intArrayOf(EGL14.EGL_NONE),0)
        check(eglContext!=EGL14.EGL_NO_CONTEXT && window!=EGL14.EGL_NO_SURFACE)
        check(EGL14.eglMakeCurrent(display,window,window,eglContext))
        EGL14.eglSwapInterval(display,1)
        val vertex=shader(GLES20.GL_VERTEX_SHADER,"""
            attribute vec2 position; attribute vec2 uv;
            uniform mat4 texMatrix; varying vec2 texUv;
            void main() { gl_Position=vec4(position,0.0,1.0);
              texUv=(texMatrix*vec4(uv,0.0,1.0)).xy; }
        """.trimIndent())
        val fragment=shader(GLES20.GL_FRAGMENT_SHADER,"""
            #extension GL_OES_EGL_image_external : require
            precision mediump float; uniform samplerExternalOES image;
            varying vec2 texUv;
            void main() { gl_FragColor=texture2D(image,texUv); }
        """.trimIndent())
        program=GLES20.glCreateProgram()
        GLES20.glAttachShader(program,vertex); GLES20.glAttachShader(program,fragment)
        GLES20.glLinkProgram(program)
        val linked=IntArray(1)
        GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0)
        check(linked[0]!=0) { "GL link: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        GLES20.glGenTextures(2,textures,0)
        textures.forEach {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,it)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        }
    }

    // Debug-only deterministic coverage of the common buffering barrier.
    fun injectRenderStall(milliseconds: Long) { testStallMs.set(milliseconds.coerceIn(1,500)) }

    fun report(complete: (JSONObject) -> Unit) {
        handler.post {
            val s=diagnostics.snapshot()
            val data=JSONObject().apply {
                put("injectedTestStalls",injectedStalls)
                put("jointSubmissions",s.samples)
                put("p95AbsMediaErrorUs",s.p95Us ?: JSONObject.NULL)
                put("maxAbsMediaErrorUs",s.maxUs ?: JSONObject.NULL)
                put("meanSignedMediaErrorUs",s.meanSignedUs ?: JSONObject.NULL)
                put("p95ClockSampleAgeUs",s.p95ClockAgeUs ?: JSONObject.NULL)
                put("p95RenderWorkUs",s.p95WorkUs ?: JSONObject.NULL)
                put("p95SwapWaitUs",s.p95SwapUs ?: JSONObject.NULL)
                put("p95PrepareUs",s.p95PrepareUs ?: JSONObject.NULL)
                put("p95TextureLatchUs",s.p95LatchUs ?: JSONObject.NULL)
                put("maxClockSampleAgeUs",s.maxClockAgeUs ?: JSONObject.NULL)
                put("coverageMisses",s.coverageMisses)
                put("observationDurationUs",s.durationUs)
                put("diagnosticsSaturated",s.saturated)
                put("teacherDecoder",teacher?.description?.decoder)
                put("learnerDecoder",learner?.description?.decoder)
                put("teacherDecodedOutputs",teacher?.decodedFrames ?: 0)
                put("learnerDecodedOutputs",learner?.decodedFrames ?: 0)
                put("teacherSkippedOutputs",teacher?.skippedFrames ?: 0)
                put("learnerSkippedOutputs",learner?.skippedFrames ?: 0)
                put("textureWaits",(teacher?.textureWaits ?: 0)+(learner?.textureWaits ?: 0))
                put("teacherSyncSeekRetries",teacher?.syncSeekRetries ?: 0)
                put("learnerSyncSeekRetries",learner?.syncSeekRetries ?: 0)
                put("windows",JSONArray().apply {
                    s.windows.forEach { w -> put(JSONObject().apply {
                        put("startUs",w.startUs); put("samples",w.samples)
                        put("p95AbsMediaErrorUs",w.p95Us); put("maxAbsMediaErrorUs",w.maxUs)
                        put("meanSignedMediaErrorUs",w.meanSignedUs)
                    }) }
                })
                put("samples",JSONArray().apply {
                    s.examples.forEach { row -> put(JSONObject().apply {
                        put("teacherPtsUs",row.teacherUs); put("learnerPtsUs",row.learnerUs)
                        put("targetTeacherUs",row.targetUs); put("errorUs",row.errorUs)
                        put("clockSampleAgeUs",row.clockAgeUs)
                    }) }
                })
            }
            main.post { complete(data) }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false,true)) return
        request.incrementAndGet()
        val released=CountDownLatch(1)
        handler.post {
            try {
                runCatching { teacher?.close() }; runCatching { learner?.close() }
                runCatching { teacherTexture?.release() }; runCatching { learnerTexture?.release() }
                if (display!=EGL14.EGL_NO_DISPLAY) {
                    if (program!=0) GLES20.glDeleteProgram(program)
                    GLES20.glDeleteTextures(2,textures,0)
                    EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
                    if (window!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window)
                    if (eglContext!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,eglContext)
                    EGL14.eglTerminate(display)
                }
            } finally {
                frameThread.quitSafely(); thread.quitSafely(); released.countDown()
            }
        }
        check(released.await(3,TimeUnit.SECONDS)) { "GL surface release timeout" }
    }
}
