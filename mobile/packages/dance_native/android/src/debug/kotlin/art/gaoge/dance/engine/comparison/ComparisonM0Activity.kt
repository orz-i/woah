package art.gaoge.dance.engine.comparison

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import art.gaoge.dance.engine.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deliberately debug-only M0 experiment. Launchable via adb shell am start,
 * so production Flutter routing, anonymous video processing, and iOS are untouched.
 *
 * IMPORTANT: Two ExoPlayers have distinct renderer clocks. This coordinator
 * pauses both sides on buffering and seeks together, but does NOT claim frame
 * accurate display sync. That is the hypothesis being measured.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class ComparisonM0Activity : Activity() {
    companion object {
        private const val PICK_TEACHER = 401
        private const val PICK_LEARNER = 402
        private const val MAX_OFFSET_US = 300_000_000L
        private const val MEDIA3_VERSION = "1.9.2"
    }

    private var teacherUri: Uri? = null
    private var learnerUri: Uri? = null
    private var teacherPlayer: ExoPlayer? = null
    private var learnerPlayer: ExoPlayer? = null
    private lateinit var teacherSurface: SurfaceView
    private lateinit var learnerSurface: SurfaceView
    private lateinit var title: TextView
    private lateinit var stateText: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var toggleButton: Button
    private lateinit var speedButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val frameProbe = ComparisonFrameProbe()

    @Volatile private var sessionGeneration = 0L
    @Volatile private var frameEpoch = 0L
    private var offsetUs = 0L
    private var playbackSpeed = 1f
    private var wantToPlay = false
    private var initialSeekPending = false
    private var dragActive = false
    private var bufferingEvents = 0
    private var seekEvents = 0
    private var sourceError: String? = null
    private var overlap: ComparisonOverlap? = null

    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        offsetUs = (savedInstanceState?.getLong("offset_us")
            ?: intent.getLongExtra("offset_us", 0L)).coerceIn(-MAX_OFFSET_US, MAX_OFFSET_US)
        teacherUri = (savedInstanceState?.getString("teacher_uri")
            ?: intent.getStringExtra("teacher_uri"))?.let(Uri::parse)
        learnerUri = (savedInstanceState?.getString("learner_uri")
            ?: intent.getStringExtra("learner_uri"))?.let(Uri::parse)
        buildView()
        if (teacherUri != null && learnerUri != null) openPair()
        handler.post(refresh)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("offset_us", offsetUs)
        outState.putString("teacher_uri", teacherUri?.toString())
        outState.putString("learner_uri", learnerUri?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun text(message: String, sizeSp: Float = 14f): TextView =
        TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = sizeSp
            setPadding(14, 8, 14, 8)
        }

    private fun action(label: String, click: () -> Unit): Button =
        Button(this).apply {
            this.text = label
            textSize = 12f
            setOnClickListener { click() }
        }

    private fun actions(vararg buttons: Button): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(LinearLayout(this@ComparisonM0Activity).apply {
                orientation = LinearLayout.HORIZONTAL
                buttons.forEach { addView(it) }
            })
        }

    private fun buildView() {
        val parent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(21, 21, 23))
        }
        title = text("M0｜老师 / 自己 · 音乐时间对比", 17f)
        parent.addView(title)
        // We own both video Surfaces AND their frame-metadata listeners.
        // PlayerView itself manages that listener, so using raw SurfaceViews
        // avoids inadvertently overwriting the UI component's callbacks.
        val teacherStage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val learnerStage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        teacherSurface = SurfaceView(this)
        learnerSurface = SurfaceView(this)
        teacherStage.addView(teacherSurface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        learnerStage.addView(learnerSurface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        parent.addView(text("老师 · 音频主时钟"))
        parent.addView(teacherStage, LinearLayout.LayoutParams(-1, 0, 1f))
        parent.addView(text("自己 · 静音，按音乐偏移映射"))
        parent.addView(learnerStage, LinearLayout.LayoutParams(-1, 0, 1f))

        stateText = text("选择两段视频。M0 不进行自动音乐识别。")
        stateText.gravity = Gravity.CENTER
        parent.addView(stateText)
        progressBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) { dragActive = true }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    dragActive = false
                    val bounds = overlap ?: return
                    seekCompareUs(bounds.durationUs * seekBar.progress / 1000L)
                }
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = Unit
            })
        }
        parent.addView(progressBar)
        parent.addView(actions(
            action("选择老师") { pickVideo(PICK_TEACHER) },
            action("选择自己") { pickVideo(PICK_LEARNER) },
            action("从头") { seekCompareUs(0L) },
            action("保存诊断") { saveReport() }
        ))
        toggleButton = action("播放") { togglePlayback() }
        speedButton = action("速度 1×") { cycleSpeed() }
        parent.addView(actions(
            toggleButton, speedButton,
            action("退 10 秒") { jump(-10_000_000L) },
            action("进 10 秒") { jump(10_000_000L) }
        ))
        parent.addView(actions(
            action("偏移 -100ms") { changeOffset(-100_000L) },
            action("偏移 -10ms") { changeOffset(-10_000L) },
            action("偏移 +10ms") { changeOffset(10_000L) },
            action("偏移 +100ms") { changeOffset(100_000L) }
        ))
        setContentView(parent)
    }

    private fun pickVideo(which: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, which)
    }

    @Deprecated("M0 intentionally uses the framework document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        when (requestCode) {
            PICK_TEACHER -> teacherUri = uri
            PICK_LEARNER -> learnerUri = uri
            else -> return
        }
        // Invalidate the previous pairing even if the second source is missing.
        closePlayers()
        if (teacherUri != null && learnerUri != null) openPair()
    }

    private fun openPair() {
        closePlayers()
        val teacherSource = teacherUri ?: return
        val learnerSource = learnerUri ?: return
        sourceError = null
        initialSeekPending = true
        val session = ++sessionGeneration
        frameEpoch = frameProbe.startNewEpoch()
        try {
            teacherPlayer = makePlayer(
                ComparisonFrameProbe.Side.TEACHER, teacherSource, session, teacherSurface
            )
            learnerPlayer = makePlayer(
                ComparisonFrameProbe.Side.LEARNER, learnerSource, session, learnerSurface
            )
            teacherPlayer?.prepare()
            learnerPlayer?.prepare()
        } catch (error: Exception) {
            sourceError = "创建播放器失败: ${error.message}"
            closePlayers()
        }
        updateStatus()
    }

    private fun makePlayer(
        side: ComparisonFrameProbe.Side, source: Uri, generation: Long,
        surface: SurfaceView
    ): ExoPlayer {
        val player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(source))
            setSeekParameters(SeekParameters.EXACT)
            setVideoSurfaceView(surface)
            volume = if (side == ComparisonFrameProbe.Side.TEACHER) 1f else 0f
            setPlaybackSpeed(playbackSpeed)
            setVideoFrameMetadataListener(VideoFrameMetadataListener {
                    presentationTimeUs, releaseTimeNs, _, _ ->
                if (generation == sessionGeneration) {
                    frameProbe.add(side, frameEpoch, presentationTimeUs, releaseTimeNs)
                }
            })
        }
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (generation == sessionGeneration) fitSurface(surface, videoSize)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (generation != sessionGeneration) return
                if (playbackState == Player.STATE_BUFFERING) bufferingEvents++
                reconcilePlayers()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (generation != sessionGeneration) return
                sourceError = "${side.name}: ${error.errorCodeName}: ${error.message}"
                wantToPlay = false
                teacherPlayer?.pause()
                learnerPlayer?.pause()
                updateStatus()
            }
        })
        return player
    }

    private fun fitSurface(surface: SurfaceView, video: VideoSize) {
        val stage = surface.parent as? FrameLayout ?: return
        if (video.width <= 0 || video.height <= 0) return
        stage.post {
            val w = stage.width
            val h = stage.height
            if (w <= 0 || h <= 0) return@post
            val rotated = video.unappliedRotationDegrees % 180 != 0
            val aspect = video.width.toDouble() * video.pixelWidthHeightRatio /
                video.height.toDouble()
            val displayAspect = if (rotated) 1.0 / aspect else aspect
            if (displayAspect <= 0) return@post
            val targetWidth = minOf(w, (h * displayAspect).toInt()).coerceAtLeast(1)
            val targetHeight = minOf(h, (w / displayAspect).toInt()).coerceAtLeast(1)
            surface.layoutParams = FrameLayout.LayoutParams(
                targetWidth, targetHeight, Gravity.CENTER
            )
        }
    }

    private fun computeOverlap(): ComparisonOverlap? {
        val a = teacherPlayer?.duration ?: C.TIME_UNSET
        val b = learnerPlayer?.duration ?: C.TIME_UNSET
        if (a == C.TIME_UNSET || b == C.TIME_UNSET || a <= 0 || b <= 0) return null
        return ComparisonTimeMapping.overlap(
            SourceInterval(0L, a * 1000L),
            SourceInterval(0L, b * 1000L),
            offsetUs
        )
    }

    private fun reconcilePlayers() {
        val a = teacherPlayer ?: return
        val b = learnerPlayer ?: return
        if (a.playbackState == Player.STATE_ENDED ||
            b.playbackState == Player.STATE_ENDED) {
            wantToPlay = false
        }
        overlap = computeOverlap()
        val bothReady = a.playbackState == Player.STATE_READY &&
            b.playbackState == Player.STATE_READY && overlap != null
        if (!bothReady) {
            if (a.playWhenReady) a.pause()
            if (b.playWhenReady) b.pause()
            return
        }
        if (initialSeekPending) {
            initialSeekPending = false
            seekCompareUs(0L)
            return
        }
        if (wantToPlay) {
            if (!a.playWhenReady) a.play()
            if (!b.playWhenReady) b.play()
        }
    }

    private fun seekCompareUs(requestedUs: Long) {
        val a = teacherPlayer ?: return
        val b = learnerPlayer ?: return
        val range = computeOverlap() ?: return
        val compareUs = requestedUs.coerceIn(0L, range.durationUs)
        overlap = range
        seekEvents++
        frameEpoch = frameProbe.startNewEpoch()
        // Both seek from the same comparison time; do NOT align pose differences.
        a.pause()
        b.pause()
        a.seekTo(range.teacherTimeUs(compareUs) / 1000L)
        b.seekTo(range.learnerTimeUs(compareUs) / 1000L)
        reconcilePlayers()
    }

    private fun currentCompareUs(): Long {
        val a = teacherPlayer ?: return 0L
        val range = overlap ?: return 0L
        return (a.currentPosition * 1000L - range.teacherStartUs)
            .coerceIn(0L, range.durationUs)
    }

    private fun togglePlayback() {
        if (overlap == null) {
            stateText.text = "两段视频没有公共区间，请检查来源和偏移。"
            return
        }
        wantToPlay = !wantToPlay
        if (!wantToPlay) {
            teacherPlayer?.pause()
            learnerPlayer?.pause()
        } else {
            if (currentCompareUs() >= (overlap!!.durationUs - 150_000L)) {
                seekCompareUs(0)
            }
            reconcilePlayers()
        }
        updateStatus()
    }

    private fun jump(deltaUs: Long) {
        seekCompareUs(currentCompareUs() + deltaUs)
    }

    private fun cycleSpeed() {
        playbackSpeed = when (playbackSpeed) {
            1f -> 0.75f
            0.75f -> 0.5f
            else -> 1f
        }
        frameEpoch = frameProbe.startNewEpoch()
        teacherPlayer?.setPlaybackSpeed(playbackSpeed)
        learnerPlayer?.setPlaybackSpeed(playbackSpeed)
        speedButton.text = "速度 ${playbackSpeed}×"
        updateStatus()
    }

    private fun changeOffset(deltaUs: Long) {
        val keepCompareUs = currentCompareUs()
        offsetUs = (offsetUs + deltaUs).coerceIn(-MAX_OFFSET_US, MAX_OFFSET_US)
        val range = computeOverlap()
        overlap = range
        if (range != null) seekCompareUs(keepCompareUs)
        else {
            wantToPlay = false
            teacherPlayer?.pause()
            learnerPlayer?.pause()
            frameEpoch = frameProbe.startNewEpoch()
        }
        updateStatus()
    }

    private fun updateStatus() {
        if (!::stateText.isInitialized) return
        overlap = computeOverlap()
        val range = overlap
        val current = currentCompareUs()
        val state = when {
            sourceError != null -> sourceError
            teacherUri == null || learnerUri == null -> "请选择老师和自己的视频"
            range == null -> "缓冲中 / 无公共时间区间"
            teacherPlayer?.playbackState == Player.STATE_BUFFERING ||
                learnerPlayer?.playbackState == Player.STATE_BUFFERING -> "缓冲中（两侧共同等待）"
            wantToPlay -> "播放中"
            else -> "已暂停"
        }
        val teacherMs = teacherPlayer?.currentPosition ?: 0L
        val learnerMs = learnerPlayer?.currentPosition ?: 0L
        stateText.text = String.format(Locale.US,
            "%s · 偏移 %+.3fs · %.2f×\n公共 %.2fs / %.2fs · 位置 T %.3fs / L %.3fs",
            state, offsetUs / 1e6, playbackSpeed,
            current / 1e6, (range?.durationUs ?: 0L) / 1e6,
            teacherMs / 1000.0, learnerMs / 1000.0
        )
        if (!dragActive && range != null && range.durationUs > 0) {
            progressBar.progress = (1000L * current / range.durationUs).toInt()
        }
        toggleButton.text = if (wantToPlay) "暂停" else "播放"
        if (wantToPlay && range != null &&
            current >= range.durationUs - 70_000L) {
            wantToPlay = false
            teacherPlayer?.pause()
            learnerPlayer?.pause()
        }
    }

    private fun saveReport() {
        try {
            val stats = frameProbe.snapshot(offsetUs)
            val data = JSONObject().apply {
                put("schema", "woah.comparison.m0.probe.v1")
                put("buildSha", BuildConfig.GIT_COMMIT_SHA)
                put("media3", MEDIA3_VERSION)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("apiLevel", Build.VERSION.SDK_INT)
                put("offsetUs", offsetUs)
                put("speed", playbackSpeed)
                put("teacherDurationMs", teacherPlayer?.duration ?: -1)
                put("learnerDurationMs", learnerPlayer?.duration ?: -1)
                put("overlapDurationUs", overlap?.durationUs ?: 0L)
                put("teacherVideoMime", teacherPlayer?.videoFormat?.sampleMimeType)
                put("learnerVideoMime", learnerPlayer?.videoFormat?.sampleMimeType)
                put("teacherFrameRate", teacherPlayer?.videoFormat?.frameRate ?: -1f)
                put("learnerFrameRate", learnerPlayer?.videoFormat?.frameRate ?: -1f)
                put("sourceError", sourceError ?: JSONObject.NULL)
                put("bufferingEvents", bufferingEvents)
                put("seekEvents", seekEvents)
                put("processPssKb", Debug.getPss())
                put("evidenceType", "pre_render_planned_release_metadata_only")
                put("teacherFrameEvents", stats.teacherFrames)
                put("learnerFrameEvents", stats.learnerFrames)
                put("matchedPlannedReleaseFrames", stats.pairedFrames)
                put("p50AbsMediaErrorUs", stats.p50AbsMediaErrorUs ?: JSONObject.NULL)
                put("p95AbsMediaErrorUs", stats.p95AbsMediaErrorUs ?: JSONObject.NULL)
                put("maxAbsMediaErrorUs", stats.maxAbsMediaErrorUs ?: JSONObject.NULL)
                put("meanSignedMediaErrorUs", stats.meanSignedMediaErrorUs ?: JSONObject.NULL)
                put("p95PlannedReleaseSkewUs", stats.p95PlannedReleaseSkewUs ?: JSONObject.NULL)
                put("samples", JSONArray().apply {
                    stats.examples.forEach { sample ->
                        put(JSONObject().apply {
                            put("teacherPtsUs", sample.teacherPtsUs)
                            put("learnerPtsUs", sample.learnerPtsUs)
                            put("errorUs", sample.signedMediaErrorUs)
                            put("plannedReleaseSkewUs", sample.plannedReleaseSkewUs)
                        })
                    }
                })
            }
            // Application-specific storage; original video/audio and their URIs
            // are never exported to the report or sent over a network.
            val base = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
            val folder = File(base, "comparison-m0")
            check(folder.isDirectory || folder.mkdirs()) { "Cannot create diagnostic directory" }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val report = File(folder, "m0_${stamp}.json")
            report.writeText(data.toString(2))
            File(folder, "latest.json").writeText(data.toString(2))
            stateText.text = "M0 诊断已保存: ${report.absolutePath}"
        } catch (error: Exception) {
            stateText.text = "诊断写入失败: ${error.message}"
        }
    }

    private fun closePlayers() {
        wantToPlay = false
        ++sessionGeneration
        frameEpoch = frameProbe.startNewEpoch()
        teacherPlayer?.release()
        learnerPlayer?.release()
        teacherPlayer = null
        learnerPlayer = null
        overlap = null
        initialSeekPending = false
    }

    override fun onPause() {
        wantToPlay = false
        teacherPlayer?.pause()
        learnerPlayer?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closePlayers()
        super.onDestroy()
    }
}
