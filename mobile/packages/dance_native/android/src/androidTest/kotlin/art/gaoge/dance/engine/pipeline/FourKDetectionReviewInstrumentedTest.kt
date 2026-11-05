package art.gaoge.dance.engine.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import art.gaoge.dance.engine.inference.YoloLiteRtSegmenter
import art.gaoge.dance.engine.inference.YoloLiteRtTensorAdapter
import art.gaoge.dance.engine.inference.YoloPreprocessor
import art.gaoge.dance.engine.litert.LiteRtModelRunner
import art.gaoge.dance.engine.litert.LiteRtRunnerPolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in diagnosis of reviewed frames derived from the unchanged public fixture. */
@RunWith(AndroidJUnit4::class)
class FourKDetectionReviewInstrumentedTest {
    @Test fun freshMirrorFaceIsProtectedWhileItsOldIdentityIsDormant() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("review4K") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.getExternalFilesDir(null), "network_privacy_qa")
        val bitmap = BitmapFactory.decodeFile(File(root, "review_group_290.png").absolutePath)
        val mapper = art.gaoge.dance.engine.geometry.ModelCoordinateMapper(3840, 2160)
        val egl = art.gaoge.dance.engine.render.EglCore()
        val surface = egl.createOffscreenSurface(64, 64)
        egl.makeCurrent(surface)
        val texture = IntArray(1)
        android.opengl.GLES20.glGenTextures(1, texture, 0)
        android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, texture[0])
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,
            android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,
            android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
        android.opengl.GLUtils.texImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val processor = art.gaoge.dance.engine.privacy.FaceOnlyPrivacyFrameProcessor.create(context, mapper)
        try {
            val stale = art.gaoge.dance.engine.tracking.TrackedPerson(
                1, art.gaoge.dance.engine.inference.FloatRect(671f, 882f, 875f, 1551f), null, .8f,
                missedFrames = 81, framesSinceLastObservation = 123,
                state = art.gaoge.dance.engine.tracking.TrackState.OCCLUDED, observedThisFrame = false
            )
            val detection = art.gaoge.dance.engine.inference.PersonDetection(
                art.gaoge.dance.engine.inference.FloatRect(812f, 1219f, 1003f, 1738f), .64f)
            val coverage = art.gaoge.dance.engine.inference.HighResolutionPrivacyCoverage.FaceObservations()
            fun resolve(persons: List<art.gaoge.dance.engine.tracking.TrackedPerson>, pts: Long) = processor.resolveFrame(
                frameTexture = texture[0], texMatrix = art.gaoge.dance.engine.render.RenderCoordinateConvention.bitmapTextureMatrix(),
                textureType = art.gaoge.dance.engine.render.SourceTextureType.TEXTURE_2D,
                persons = persons, faceOnlyTrackIds = persons.map { it.id }.toSet(), ptsUs = pts
            )
            fun maskAt(result: art.gaoge.dance.engine.privacy.FaceOnlyPrivacyFrameResult): Int {
                val mask = result.resolvedPrivacy?.privacyMask ?: return 0
                val x = mapper.sourceToProtoX(893f).toInt()
                val y = mapper.sourceToProtoY(1275f).toInt()
                return mask.buffer.get(y * mask.width + x).toInt() and 255
            }
            val before = maskAt(resolve(listOf(stale), 0))
            val current = coverage.current(listOf(detection))
            val afterResult = resolve(current, 40_000)
            val after = maskAt(afterResult)
            File(root, "fresh-observation-check.json").writeText(JSONObject()
                .put("before_mask", before).put("after_mask", after)
                .put("actual_face_detector_observations", afterResult.detectorObservationCount)
                .put("old_identity_unchanged", stale.state == art.gaoge.dance.engine.tracking.TrackState.OCCLUDED)
                .put("anonymous_track_ids", JSONArray(current.filter { it.id < 0 }.map { it.id })).toString(2))
            kotlin.test.assertEquals(0, before)
            kotlin.test.assertEquals(255, after, "Fresh visible mirror face must receive privacy independently of stale identity")
        } finally {
            processor.close()
            android.opengl.GLES20.glDeleteTextures(1, texture, 0)
            bitmap.recycle()
            egl.releaseSurface(surface)
            egl.close()
        }
    }

    @Test fun reorderedActual4kFilePreservesEveryEncodedPacket(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("fastStartFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.getExternalFilesDir(null), "network_privacy_qa")
        val source = File(root, "4k_baseline_geometry/solo_4k_body_solid.mp4")
        kotlin.test.assertTrue(source.isFile)
        val before = packetProof(source)
        val prepared = kotlin.test.assertNotNull(art.gaoge.dance.engine.media.Mp4FastStart.prepare(source))
        kotlin.test.assertTrue(prepared != source, "This fixture must exercise real index relocation")
        val tag = args.getString("outputTag", "faststart_reordered_solo")!!
        require(tag.matches(Regex("[a-z0-9_]+")))
        val output = File(root, "$tag.mp4")
        kotlin.test.assertFalse(output.exists(), "Keep previous evidence")
        kotlin.test.assertTrue(prepared.renameTo(output))
        val after = packetProof(output)
        val same = before.toString() == after.toString()
        File(root, "$tag.json").writeText(JSONObject().put("source", source.name)
            .put("output", output.name).put("before", before).put("after", after)
            .put("all_encoded_samples_and_pts_identical", same).toString(2))
        kotlin.test.assertTrue(same, "Index relocation must preserve every audio/video packet and timestamp")
    }

    private fun packetProof(file: File): JSONArray {
        val probe = android.media.MediaExtractor()
        val count = try { probe.setDataSource(file.absolutePath); probe.trackCount } finally { probe.release() }
        val tracks = JSONArray()
        val bytes = ByteArray(4 * 1024 * 1024)
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        for (index in 0 until count) {
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val mime = extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                extractor.selectTrack(index)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val times = java.security.MessageDigest.getInstance("SHA-256")
                var packets = 0
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    digest.update(bytes, 0, size)
                    times.update(java.nio.ByteBuffer.allocate(8).putLong(extractor.sampleTime).array())
                    packets++
                    if (!extractor.advance()) break
                }
                fun hex(value: ByteArray) = value.joinToString("") { "%02x".format(it.toInt() and 255) }
                tracks.put(JSONObject().put("mime", mime).put("packets", packets)
                    .put("payload_sha256", hex(digest.digest())).put("pts_sha256", hex(times.digest())))
            } finally { extractor.release() }
        }
        return tracks
    }

    @Test fun croppedGpuInputsMatchVisualPixelRegions() {
        val egl = art.gaoge.dance.engine.render.EglCore()
        val surface = egl.createOffscreenSurface(64, 64)
        egl.makeCurrent(surface)
        val fbo = art.gaoge.dance.engine.render.InferenceFbo(64)
        val renderer = art.gaoge.dance.engine.render.InferenceRenderer()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val colors = listOf(android.graphics.Color.RED, android.graphics.Color.GREEN,
            android.graphics.Color.BLUE, android.graphics.Color.YELLOW)
        for (y in 0 until 100) for (x in 0 until 100) bitmap.setPixel(x, y, colors[(y / 50) * 2 + x / 50])
        val texture = IntArray(1)
        android.opengl.GLES20.glGenTextures(1, texture, 0)
        try {
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, texture[0])
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
            android.opengl.GLES20.glTexParameteri(android.opengl.GLES20.GL_TEXTURE_2D,
                android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
            android.opengl.GLUtils.texImage2D(android.opengl.GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            for ((index, view) in art.gaoge.dance.engine.inference.HighResolutionPrivacyCoverage.views(100, 100).withIndex()) {
                val window = art.gaoge.dance.engine.inference.HighResolutionPrivacyCoverage.textureWindow(view, 100, 100)
                val crop = FloatArray(16)
                android.opengl.Matrix.setIdentityM(crop, 0)
                android.opengl.Matrix.translateM(crop, 0, window.left, window.top, 0f)
                android.opengl.Matrix.scaleM(crop, 0, window.width, window.height, 1f)
                val transform = FloatArray(16)
                android.opengl.Matrix.multiplyMM(transform, 0,
                    art.gaoge.dance.engine.render.RenderCoordinateConvention.bitmapTextureMatrix(), 0, crop, 0)
                val mapper = art.gaoge.dance.engine.geometry.ModelCoordinateMapper(60, 60, 64)
                renderer.renderToFbo(texture[0], transform, mapper, fbo, art.gaoge.dance.engine.render.SourceTextureType.TEXTURE_2D)
                val workspace = art.gaoge.dance.engine.inference.PreprocessorWorkspace(64)
                YoloPreprocessor.processRgbaBuffer(fbo.readRgbaPixels(), mapper, workspace,
                    art.gaoge.dance.engine.inference.RgbaRowOrder.BOTTOM_TO_TOP)
                val center = 32 * 64 + 32
                val expected = colors[index]
                val actual = listOf(workspace.floatArray[center], workspace.floatArray[4096 + center],
                    workspace.floatArray[8192 + center])
                val wanted = listOf(android.graphics.Color.red(expected), android.graphics.Color.green(expected),
                    android.graphics.Color.blue(expected))
                kotlin.test.assertTrue(actual.zip(wanted).all { (a, b) -> kotlin.math.abs(a * 255 - b) < 2 },
                    "Visual crop $index must preserve its original quadrant: $actual")
            }
        } finally {
            android.opengl.GLES20.glDeleteTextures(1, texture, 0)
            bitmap.recycle()
            renderer.close()
            fbo.close()
            egl.releaseSurface(surface)
            egl.close()
        }
    }

    @Test fun compareWholeFrameAndOverlappingViews(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("review4K") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.getExternalFilesDir(null), "network_privacy_qa")
        val runner = LiteRtModelRunner.fromAsset(context, YoloLiteRtSegmenter.DEFAULT_ASSET_PATH,
            "4k_detection_review", LiteRtRunnerPolicy.STRICT_CPU, cpuNumThreads = 4)
        val rows = JSONArray()
        try {
            runner.initialize()
            val shapes = runner.runtimeInfo!!.outputShapes
            val adapter = YoloLiteRtTensorAdapter(shapes[0], shapes[1])
            for (frame in listOf(217, 290, 507)) {
                val bitmap = BitmapFactory.decodeFile(File(root, "review_group_$frame.png").absolutePath)
                try {
                    val crops = listOf(
                        intArrayOf(0, 0, bitmap.width, bitmap.height),
                        intArrayOf(0, 0, bitmap.width * 3 / 5, bitmap.height * 3 / 5),
                        intArrayOf(bitmap.width * 2 / 5, 0, bitmap.width * 3 / 5, bitmap.height * 3 / 5),
                        intArrayOf(0, bitmap.height * 2 / 5, bitmap.width * 3 / 5, bitmap.height * 3 / 5),
                        intArrayOf(bitmap.width * 2 / 5, bitmap.height * 2 / 5, bitmap.width * 3 / 5, bitmap.height * 3 / 5)
                    )
                    for ((view, crop) in crops.withIndex()) {
                        val pixels = Bitmap.createBitmap(bitmap, crop[0], crop[1], crop[2], crop[3])
                        try {
                            val prep = YoloPreprocessor.processBitmap(pixels)
                            val input = FloatArray(prep.floatBuffer.capacity())
                            prep.floatBuffer.rewind()
                            prep.floatBuffer.get(input)
                            runner.getInputBuffers()[0].writeFloat(input)
                            runner.runInference()
                            val outputs = runner.getOutputBuffers()
                            val out0 = outputs[0].readFloat()
                            val out1 = outputs[1].readFloat()
                            for (threshold in listOf(0.25f, 0.15f, 0.05f)) {
                                val detections = adapter.parseDetections(out0, out1, prep, threshold)
                                rows.put(JSONObject().put("frame", frame).put("view", view)
                                    .put("crop", JSONArray(crop.toList())).put("threshold", threshold)
                                    .put("detections", JSONArray(detections.map { detection ->
                                        JSONObject().put("confidence", detection.confidence)
                                            .put("bbox", JSONArray(listOf(
                                                detection.bbox.left + crop[0], detection.bbox.top + crop[1],
                                                detection.bbox.right + crop[0], detection.bbox.bottom + crop[1])))
                                    })))
                            }
                        } finally { if (pixels !== bitmap) pixels.recycle() }
                    }
                } finally { bitmap.recycle() }
            }
        } finally {
            File(root, "4k-detection-review.json").writeText(rows.toString(2))
            runner.close()
        }
    }
}
