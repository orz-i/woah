package art.gaoge.dance.engine.diagnostics

import android.media.MediaCodecList
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Fail-closed text schema. Raw debug tensor/frame evidence is deliberately separate.
 * Vocabulary is frozen from production diagnostic keys, not learned from user/history data.
 */
internal object DiagnosticTextPrivacy {
    const val REDACTED = "[redacted]"
    private val uuid = "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"
    private val job = Regex("job_(?:$uuid|[0-9]+)")
    private val session = Regex("[0-9]{8}_[0-9]{6}_[0-9a-fA-F]{8}")
    private val artifact = Regex(
        "(?:yolo_tensor_job_(?:$uuid|[0-9]+)_(?:(?:cpu_probe|cpu_mt4_probe)_)?[0-9]+_(?:input|output0|output1)\\.f32s|" +
        "inference_rgba_job_(?:$uuid|[0-9]+)_[0-9]+_-?[0-9]+_[0-9]+x[0-9]+\\.rgba|" +
        "decoder_yuv_job_(?:$uuid|[0-9]+)_[0-9]+_[0-9]+_64x64_32x32\\.yuvs)")
    fun validJob(value: String?) = value != null && job.matches(value)
    fun validSession(value: String) = session.matches(value)
    fun validArtifact(value: String) = artifact.matches(value)
    private val codecs by lazy {
        runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.map { it.name }.toSet() }
            .getOrDefault(emptySet())
    }
    private val trustedBuild: Map<String, String> get() = mapOf(
        "MODEL" to Build.MODEL, "MANUFACTURER" to Build.MANUFACTURER,
        "BRAND" to Build.BRAND, "DEVICE" to Build.DEVICE,
        "HARDWARE" to Build.HARDWARE, "PRODUCT" to Build.PRODUCT,
        "RELEASE" to Build.VERSION.RELEASE)
    private val assets = setOf("models/litert/yolo11n-seg-fp16.tflite",
        "models/face/blaze_face_full_range.tflite", "models/litert/crop-clarity-span-x2.tflite",
        "yolo11n-seg-fp16.tflite", "blaze_face_full_range.tflite", "crop-clarity-span-x2.tflite")

    fun text(value: String, key: String): String {
        if (value == REDACTED) return value
        if (key in setOf("uri", "source_uri", "video_uri", "public_uri", "file_path",
            "output_path", "file_name", "filename", "path", "description", "stack_trace",
            "message", "error", "sourceUri", "outputPath", "filePath")) return REDACTED
        if (key in setOf("exception_message", "root_cause_message")) {
            return if (NativeDiagnosticPrivacy.safeCode(value) == value) value else REDACTED
        }
        if (key == "level" && value == "CRITICAL") return value
        if (key == "event" && value in setOf("REACQUIRE_SUCCESS", "OCCLUSION_END",
            "YOLO_EXPORT_GPU_ACTIVE", "YOLO_EXPORT_CPU_FALLBACK")) return value
        if (key in setOf("exception_class", "root_cause_class") && value in reasonTypes) return value
        if (key == "cause_chain" && value.split(" -> ").let { it.size <= 16 && it.all { item -> item in reasonTypes } }) return value
        if (key in setOf("event", "component", "stage") && value in symbols) return value
        if (value in enums) return value
        if (key == "job_id" && Regex("job_(?:$uuid|[0-9]+)(?:_cpu_probe|_cpu_mt4_probe)?").matches(value)) return value
        if (key == "pipeline_lifecycle_job_id" && validJob(value)) return value
        if (key == "analysis_cache_id" && Regex("analysis_[0-9]+(?:_$uuid)?").matches(value)) return value
        if (key == "cpu_mt4_shadow_adaptive_schedules" && value in setOf("dense", "safe", "balanced", "aggressive", "sparse")) return value
        if (key == "session_id" && validSession(value)) return value
        if ((key.endsWith("_artifact") || key == "artifact") && validArtifact(value)) return value
        if ((key.contains("sha256") || key.endsWith("_hash") || key == "git_commit_sha") &&
            Regex("[0-9a-fA-F]{7,64}").matches(value)) return value
        if (key in setOf("model", "model_name", "asset_path", "model_asset") && value in assets) return value
        if (key in setOf("codec_name", "canonical_codec_name") && value in codecs) return value
        if (trustedBuild[key] == value) return value
        if (key == "SUPPORTED_ABIS" && value in Build.SUPPORTED_ABIS) return value
        if (key in setOf("wall_time", "created_at", "updated_at", "build_timestamp") &&
            Regex("[0-9TZ:.+ -]{10,40}").matches(value)) return value
        if (key in setOf("litert_version", "versionName", "app_version", "version_name") &&
            Regex("[0-9]+(?:\\.[0-9]+){1,3}(?:\\+[0-9]+)?").matches(value)) return value
        if (key == "packageName" && value == "art.gaoge.dance") return value
        if (key == "tracked_states" && Regex("[0-9]+:(?:NEW|ACTIVE|OCCLUDED|REACQUIRING|LOST|REMOVED)").matches(value)) return value
        if (key.contains("reason") && value.split(':').let { parts ->
            parts.size == 3 && parts[0] in reasonStages && parts[1] in reasonTypes &&
                NativeDiagnosticPrivacy.safeCode(parts[2]) == parts[2]
        }) return value
        if (key in setOf("error_code", "code") && NativeDiagnosticPrivacy.safeCode(value) == value) return value
        return REDACTED
    }

    private fun allowedKey(key: String, parent: String): Boolean =
        key in keys || key in enums ||
            (parent == "stage_timings" && (key in stages ||
                key.removePrefix("yoloCpuMt4Probe_") in stages && key.startsWith("yoloCpuMt4Probe_"))) ||
            (parent.endsWith("_by_track_id") && Regex("[0-9]+").matches(key)) ||
            (parent in setOf("adaptive_tracks", "adaptive_sources", "adaptive_reasons", "adaptive_metrics") &&
                key in setOf("dense", "safe", "balanced", "aggressive", "sparse"))

    fun value(input: Any?, key: String = "", depth: Int = 0): Any {
        if (depth > 24) return REDACTED
        return when (input) {
            null, JSONObject.NULL -> JSONObject.NULL
            is Number, is Boolean -> input
            is String -> text(input, key)
            is JSONObject -> JSONObject().apply {
                val names = input.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    if (allowedKey(name, key)) put(name, value(input.opt(name), name, depth + 1))
                }
            }
            is Map<*, *> -> JSONObject().apply {
                input.forEach { (name, item) ->
                    if (name is String && allowedKey(name, key)) put(name, value(item, name, depth + 1))
                }
            }
            is JSONArray -> JSONArray().apply {
                for (i in 0 until input.length()) put(value(input.opt(i), key, depth + 1))
            }
            is List<*> -> JSONArray().apply { input.forEach { put(value(it, key, depth + 1)) } }
            else -> REDACTED
        }
    }

    fun json(input: JSONObject): JSONObject = value(input) as JSONObject

    /** Staging copy only. Malformed historic text never falls back to raw bytes. */
    fun sanitizeStagedText(file: File) {
        when (file.extension) {
            "json" -> file.writeText(runCatching { json(JSONObject(file.readText())).toString(2) }
                .getOrDefault("{\"redacted\":true}"))
            "jsonl" -> file.writeText(file.readLines().mapNotNull { line ->
                runCatching { json(JSONObject(line)).toString() }.getOrNull()
            }.joinToString("\n", postfix = "\n"))
            "txt" -> file.writeText("stage=diagnostics; code=NATIVE_LOG_REDACTED\n")
        }
    }

    private val reasonStages = setOf("native", "capabilities", "probe", "analysis", "preview",
        "export", "cancel", "status", "release", "comparison", "inference", "decode", "model",
        "diagnostics", "gallery", "sticker", "share", "open", "thumbnail")
    private val reasonTypes = setOf("CANCELLED", "PERMISSION", "IO", "ARGUMENT", "STATE", "MEMORY", "NATIVE")
    private val enums = setOf(
        "ACTIVE", "ACTIVE_OVERLAP", "AMBIGUOUS_GLOBAL", "AMBIGUOUS_GROUP", "AMBIGUOUS_PEAK",
        "BITMAP_CPU_FALLBACK", "BODY_ANCHOR_RESIDUAL", "BODY_MASK_COMPENSATED", "BOTTOM_TO_TOP", "CANCELLED",
        "CANONICAL_MODEL_RGBA", "CANONICAL_YUV_CPU", "CANONICAL_YUV_CPU_THEN_SURFACE_OES_RGBA", "COMPLETED", "CPU",
        "CPU_MT4_FIRST_FRAME_ID_MAP_FAILED", "CPU_MT4_REFERENCE", "DEBUG", "DETECTED_FACE", "DETECTOR_SEED_EXPIRED",
        "DIRECT", "DORMANT", "ERROR", "EVIDENCE_GAP_EXPIRED", "EXPANDED_REACQUIRE",
        "EXPORTING", "FAILED", "FRESH_DEPTH_CORE", "FRESH_DEPTH_CORE_FALLBACK", "FRESH_EXPLICIT_OCCLUDER_CORE",
        "FULL_EXPORT", "FULL_EXPORT_DEBUG", "GLOBAL_AMBIGUITY", "GPU", "ID_HANDOFF",
        "INDEPENDENT_ARTIFACT_WINDOW_WITH_SPARSE_PARITY_THEN_PRODUCTION_CPU4T_REUSE", "INDEPENDENT_CPU4T_FULL_EXPORT", "INFO", "INITIALIZE", "INITIAL_ASSIGNED_ID_COUNT_MISMATCH",
        "INSUFFICIENT_INLIERS", "INVALID_INPUT", "INVALID_TIME", "LEFT_TO_RIGHT", "LOST",
        "LOW_CORRELATION", "MAX_GAP", "MISSING_CPU_MT4_FIRST_FRAME", "MISSING_CPU_MT4_INFERENCE_FRAME", "MISSING_PRODUCTION_FIRST_FRAME",
        "MISSING_PRODUCTION_INFERENCE_FRAME", "MISSING_PRODUCTION_TRACKS_FIRST_FRAME", "NEW", "NONE", "NORMAL",
        "NO_CANDIDATE", "NO_IDENTITY_PROTECTED_TRACKS", "NO_INFERENCE_FRAME", "NO_PIXEL_STATE", "NO_STATE",
        "NO_VIDEO_MIME", "NO_VIDEO_TRACK", "OCCLUDED", "OCCLUSION", "OES",
        "OUTPUT_IMAGE_NULL", "PREDICT", "PREDICTED_FACE", "PREPARING", "PREVIEW",
        "PROBABILITY_MARGIN", "PRODUCTION_FALLBACK", "PRODUCTION_TRACKS", "PROTECTED_GPU_CANDIDATE_COUNT", "PROTECTED_GPU_MOTION",
        "PROTECTED_GPU_OWNERSHIP_AMBIGUOUS", "PROTECTED_LOW_GPU_CONFIDENCE", "PROTECTED_NON_ACTIVE", "PROTECTED_PERSON_OVERLAP", "PROTECTED_TRACK_MISSING",
        "REACQUIRING", "REMOVED", "RIGHT_TO_LEFT", "ROI_PLAN_UNAVAILABLE", "SAFE_GPU_SCHEDULER_ONLY",
        "SELECTED", "SOURCE_ROI_256", "STEP_TOO_LARGE", "SURFACE_OES_RGBA", "SURFACE_TEXTURE",
        "TEXTURE_2D", "TOP_TO_BOTTOM", "UNCOMMITTED_OVERLAP", "UNKNOWN", "UNSELECTED",
        "WARN", "WARNING", "YOLO_HEAD_FALLBACK", "YUV_SAMPLE_LAYOUT_INVALID", "balanced",
        "completed", "off", "on", "quality", "speed"
    )
    private val symbols = setOf(
        "ANALYZE_CANONICAL_INPUT_FALLBACK", "ANALYZE_SELECTION_SIGNATURE", "ASSOCIATION_AMBIGUOUS", "AUTO_REFRAME_ID_HANDOFF", "AUTO_REFRAME_PROXY_CHANGE",
        "AUTO_REFRAME_SAMPLE", "AnalyzePipeline", "CANCELLED", "CANONICAL_YUV_INFERENCE_ACTIVE", "CANONICAL_YUV_INFERENCE_FALLBACK",
        "COMPLETED", "CROP_CLARITY_AB_ARMED", "CROP_CLARITY_AB_BASELINE_FAILED", "CROP_CLARITY_AB_CAPTURE_FAILED", "CROP_CLARITY_AB_INIT_FAILED",
        "CROP_CLARITY_AB_PAIR_CAPTURED", "CROP_CLARITY_AB_READBACK_FAILED", "CROP_CLARITY_AB_READY", "CROP_CLARITY_AB_TARGET_UNAVAILABLE", "CropClarityAbCapture",
        "CrossDeviceTrackingDiagnostics", "DECODER_YUV_CAPTURED", "DECODER_YUV_CAPTURE_FAILED", "DIAGNOSTICS_INITIALIZED", "DIAGNOSTIC_BUNDLE_CREATED",
        "DIAGNOSTIC_SNAPSHOT_TIMEOUT", "DanceNativeApiImpl", "DiagnosticBundleExporter", "EXPORTING", "ExportPipeline",
        "FACE_ONLY_CLASS_FALLBACK", "FACE_ONLY_IDENTITY_ROOTS_RESOLVED", "FACE_ONLY_PRODUCTION_TRACK_SIGNATURE", "FACE_ONLY_STICKER_PLACEMENT_SIGNATURE", "FACE_PRIVACY_CLASS_EVIDENCE_INFERRED",
        "FACE_PRIVACY_TEMPORAL_CLASS_EVIDENCE", "FACE_PRIVACY_UNRESOLVED", "FACE_ROI_DETECTOR_DIAGNOSTIC", "FOREGROUND_OCCLUDER_ACCEPTED", "FOREGROUND_OCCLUDER_REJECTED_AMBIGUOUS",
        "FULL_BODY_PRIVACY_INPUT_SIGNATURE", "FULL_BODY_PRIVACY_MASK_MISSING_FAIL_CLOSED", "FULL_BODY_PRODUCTION_TRACK_SIGNATURE", "FaceOnlyPrivacyFrameProcessor", "GLOBAL_ASSIGNMENT_COMMIT",
        "GLOBAL_ASSOCIATION_AMBIGUOUS", "GROUP_ASSIGNMENT_COMMIT", "GROUP_DETECTION_RESERVED", "GROUP_RESERVED_STRICT_RESCUE_CANDIDATE", "GROUP_RESERVED_STRICT_RESCUE_ELIGIBLE",
        "GlRenderer", "INFERENCE_RGBA_CAPTURED", "INFERENCE_RGBA_CAPTURE_SKIPPED", "InferencePixelDiagnostics", "LiteRtModelRunner",
        "MIXED_FULL_BODY_STALE_MASK_SUPPRESSED", "NEW_TRACK_CREATED", "NativeDiagnostics", "OCCLUSION_GROUP_CREATE", "OCCLUSION_GROUP_END",
        "OCCLUSION_GROUP_MEMBERSHIP_CHANGE", "OCCLUSION_GROUP_REACQUIRE_START", "OCCLUSION_PAIR_OVERLAP_EDGE", "ORDINARY_RECOVERY_COMMIT", "POST_CROP_FIRST_FRAME",
        "POST_CROP_PRIVACY_COMPOSITION_ENABLED", "PREPARING", "PREVIEW", "PRIVACY_CLASS_EVIDENCE_INFERRED", "PRIVACY_CLASS_TEMPORAL_SUMMARY",
        "PRIVACY_COVERAGE_DROP", "PRIVACY_FRESH_CLASS_PRIMARY_COMPOSITION", "PRIVACY_GL_RENDER_ERROR_FAIL_CLOSED", "PRIVACY_GL_SHADER_MISSING_FAIL_CLOSED", "PRIVACY_POST_CROP_GL_ERROR_FAIL_CLOSED",
        "PRIVACY_POST_CROP_SHADER_MISSING_FAIL_CLOSED", "PRIVACY_RENDER_OCCLUDER_COMPOSED", "PRIVACY_SEVERE_UNDERCOVERAGE", "PRIVACY_STALE_SELECTED_REPLACED_BY_FRESH_CLASS", "PROTECTED_GROUP_UNCERTAIN_OCCLUDER_EVIDENCE",
        "PROTECTED_GROUP_UNCERTAIN_OCCLUDER_IGNORED", "PROTECTED_LOST_ANCHOR_OCCLUSION_CONFIRMED", "PROTECTED_LOST_ANCHOR_OCCLUSION_REJECTED_UNCERTAIN_OCCLUDER", "PROTECTED_LOST_OCCLUSION_REJECTED_UNCERTAIN_OCCLUDER", "PROTECTED_OFFSCREEN_DORMANT",
        "PROTECTED_TRACK_RETAINED_LOST", "PrivacyClassTemporalTracker", "PrivacyOcclusionResolver", "REACQUIRE_START", "REACQUIRE_TIMEOUT",
        "SAM_GPU_COMPILE", "SAM_MODEL_EXTRACT", "SCENE_MOTION_ESTIMATED", "SCENE_MOTION_REJECTED", "SELECTED_MASK_LOW_BBOX_OCCUPANCY",
        "SELECTED_TARGET_MISSING", "SELECTED_TARGET_MISSING_SAMPLED", "SELECTED_TARGET_RECOVERED", "SceneMotionEstimator", "TRACK_REMOVED",
        "TrackManager", "UNRESOLVED_IDENTITY_DETECTION_RESERVED", "VideoYuvDiagnosticSampler", "YOLO", "YOLO_CPU_MT4_PROBE_ACTIVE",
        "YOLO_CPU_MT4_PROBE_FAILED", "YOLO_CPU_MT4_PROBE_UNAVAILABLE", "YOLO_CPU_MT4_PRODUCTION_REUSE_PARITY_MISMATCH", "YOLO_CPU_MT4_TRACK_SHADOW", "YOLO_CPU_MT4_TRACK_SHADOW_DISABLED",
        "YOLO_DETECTION_SIGNATURE_CAPTURED", "YOLO_PREVIEW_GPU_RELEASED_FOR_EXPORT", "YOLO_TENSOR_CAPTURED", "YoloTensorDiagnostics"
    )
    private val stages = setOf(
        "canonicalYuvToRgba", "drainEncoder", "faceDetectorCpu", "faceDetectorWall", "faceDeterministicCpuTracking",
        "faceMaskBuild", "faceOnlyPrivacy", "facePixelMotionCpu", "facePrivacyResolve", "faceRoiReadback",
        "fboLetterbox", "privacyClassTracking", "readback640", "renderEffects", "tracking",
        "yoloCandidateScan", "yoloCpuInference", "yoloCpuMt4Probe", "yoloDecode", "yoloDiagnostics",
        "yoloInputWrite", "yoloLiteRtRun", "yoloMaskAwareNms", "yoloMaskDecode", "yoloMaskIouScan",
        "yoloMaskMaterialize", "yoloOutputRead", "yoloPipelineTotal", "yoloPreprocess"
    )
    private val keys = setOf(
        "BRAND", "DEVICE", "FINGERPRINT", "HARDWARE", "MANUFACTURER",
        "MODEL", "PRODUCT", "RELEASE", "SDK_INT", "SOC_MANUFACTURER",
        "SOC_MODEL", "SUPPORTED_ABIS", "ab", "actualDisplayAcceptance", "adaptive_metrics",
        "adaptive_reasons", "adaptive_shadow_matrix_enabled", "adaptive_sources", "adaptive_tracks", "additional_privacy_present",
        "albumHashMatches", "alignment", "analysisCacheId", "analysis_cache_id", "analysis_cache_id_present",
        "analysis_candidate_ids_ge_0_60", "analysis_pts_us", "anchor_fresh_overlap_ratio", "android_api", "apiLevel",
        "api_level", "app", "app_version", "applied", "artifact",
        "asset_path", "assigned_score", "audioClockSource", "audioInterpolationBoundUs", "audioPlayerCount",
        "audioSelected", "audioVideoTrackDisabled", "automaticMatching", "availMemMb", "availableProcessors",
        "available_bytes", "avg_ms", "backend", "baseline", "bbox",
        "bbox_iou", "bbox_overlap", "bbox_overlap_relaxed_for_fresh_depth", "bbox_proto_area", "bbox_q0_0625px",
        "best_inliers", "best_score", "bitmap_grid_sha256", "blurStrength", "body_mask_guided_detection_indices",
        "borderColorArgb", "borderWidth", "bottom", "bufferingEvents", "build",
        "buildSha", "build_timestamp", "byte_count", "bytes", "camera_target_error_x",
        "camera_target_error_x_spans", "camera_velocity_x", "camera_velocity_x_spans_per_s", "camera_velocity_y", "cancelState",
        "candidate_age", "candidate_bbox_bottom", "candidate_bbox_left", "candidate_bbox_right", "candidate_bbox_top",
        "candidate_confidence", "candidate_conservative_unknown", "candidate_count", "candidate_detection_index", "candidate_fresh",
        "candidate_id", "candidate_mask_height", "candidate_mask_width", "candidate_privacy_class", "candidate_residual_track_ids",
        "candidate_roi_proto_bottom", "candidate_roi_proto_left", "candidate_roi_proto_right", "candidate_roi_proto_top", "candidate_sampling_bottom",
        "candidate_sampling_left", "candidate_sampling_right", "candidate_sampling_top", "canonical_codec_name", "canonical_color_range",
        "canonical_color_standard", "canonical_fallback_reason", "canonical_rgba_sha256", "canonical_yuv_fallback_reason", "canonical_yuv_validation_scope",
        "canonical_yuv_validation_window_completed", "canonical_yuv_validation_window_us", "capture_mode", "capture_ordinal", "carve",
        "cause_chain", "clockBacksteps", "clockSampleAgeUs", "code", "codec_name",
        "col_best", "col_confusable_geometry", "col_margin", "col_second_best", "color_range",
        "color_standard", "color_transfer", "comparisonExportFixtures", "compile_ms", "complete",
        "component", "composition_height", "composition_width", "confidence", "confidence_q1e4",
        "confirmed_parity_frames", "conservative_mixed_full_body_occluder_policy_enabled", "conservative_unknown", "conservative_unobserved_occluder_policy", "continuity_age_us",
        "count", "coverageMisses", "coverage_ratio", "cpu_full_source", "cpu_full_tracks",
        "cpu_inference_ordinal", "cpu_mt4_probe_fallback_reason", "cpu_mt4_probe_inference_frames", "cpu_mt4_probe_threads", "cpu_mt4_production_reuse_frames",
        "cpu_mt4_production_reuse_parity_exact", "cpu_mt4_production_reuse_parity_frames", "cpu_mt4_production_reuse_parity_interval_frames", "cpu_mt4_reference_source_policy", "cpu_mt4_shadow_adaptive_schedules",
        "cpu_mt4_signature_scope", "cpu_num_threads", "createdAt", "created_at", "crop",
        "crop_bottom", "crop_center_x", "crop_center_y", "crop_clarity_scale", "crop_left",
        "crop_rect", "crop_right", "crop_top", "cross_device_adaptive_shadow_matrix_enabled", "cross_device_adaptive_shadow_tracker_steps",
        "currentFrame", "current_identity_track_id", "current_overlap_ratio", "current_proxy_id", "current_proxy_iou",
        "debug_opt_in", "decoded_frames", "decoder_color_range", "decoder_color_standard", "decoder_color_transfer",
        "decoder_height", "decoder_mime", "decoder_name", "decoder_width", "description",
        "det_count", "det_index", "details", "detection_bbox", "detection_count",
        "detection_index", "detection_indices", "detections", "detector_rejected", "device",
        "diagnostic_candidate_ids_ge_0_60", "diagnostic_scope", "diagnosticsSaturated", "dilated_area", "distance",
        "duplicate_candidate_ownership", "duplicate_surface_timestamp_count", "durationUs", "duration_us", "dx",
        "dy", "effective_accelerator", "effective_area", "effective_cpu_num_threads", "effectsJson",
        "elapsed_ms", "enabled", "encoded_frames", "endUs", "enhanced",
        "error", "errorCode", "errorMessage", "errorType", "errorUs",
        "error_code", "error_type", "escalated_full_body_ids", "event", "evidence",
        "evidenceType", "exception_class", "exception_message", "exists", "expected_bytes",
        "expected_frames", "expected_pair_count", "expected_selected", "explicit_occluder", "export_diagnostics_complete",
        "face_appearance_reacquire_detector_frames_by_track_id", "face_appearance_reacquire_detector_track_frames", "face_body_compensated_frames_by_track_id", "face_body_compensated_track_frames", "face_body_mask_guided_frames_by_track_id",
        "face_body_mask_guided_track_frames", "face_detected_frames_by_track_id", "face_detected_track_frames", "face_detector_call_count", "face_detector_calls_by_track_id",
        "face_detector_observation_count", "face_detector_rejected_call_count", "face_detector_rejected_calls_by_track_id", "face_detector_zero_observation_call_count", "face_deterministic_cpu_primary_fallback_frames",
        "face_deterministic_cpu_primary_inference_frames", "face_deterministic_cpu_primary_preferred", "face_dormant_exact_reacquired_frames_by_track_id", "face_dormant_exact_reacquired_track_frames", "face_dormant_pixel_motion_bridge_frames_by_track_id",
        "face_dormant_pixel_motion_bridge_track_frames", "face_dormant_probe_motion_rejected_frames_by_track_id", "face_dormant_probe_motion_rejected_track_frames", "face_dormant_reactivated_by_face_detection_events", "face_dormant_reactivated_by_face_detection_events_by_track_id",
        "face_dormant_reactivation_probe_frames_by_track_id", "face_dormant_reactivation_probe_track_frames", "face_dormant_reactivation_sticker_max_height_by_track_id", "face_dormant_reactivation_sticker_max_width_by_track_id", "face_dormant_suppressed_frames_by_track_id",
        "face_dormant_suppressed_track_frames", "face_dormant_suppression_reasons", "face_dormant_suppression_reasons_by_track_id", "face_evidence_gap_reacquire_detector_frames_by_track_id", "face_evidence_gap_reacquire_detector_rejected_frames_by_track_id",
        "face_evidence_gap_reacquire_detector_rejected_track_frames", "face_evidence_gap_reacquire_detector_success_frames_by_track_id", "face_evidence_gap_reacquire_detector_success_track_frames", "face_evidence_gap_reacquire_detector_track_frames", "face_evidence_gap_reacquire_detector_zero_observation_frames_by_track_id",
        "face_evidence_gap_reacquire_detector_zero_observation_track_frames", "face_fallback_frames_by_track_id", "face_fallback_track_frames", "face_fresh_body_motion_frames_by_track_id", "face_fresh_body_motion_track_frames",
        "face_occlusion_hold_frames_by_track_id", "face_occlusion_hold_max_age_us", "face_occlusion_hold_track_frames", "face_occlusion_reacquire_detector_frames_by_track_id", "face_occlusion_reacquire_detector_track_frames",
        "face_only_ids", "face_only_person_ids", "face_partial_occlusion_max_center_step_by_track_id", "face_partial_occlusion_max_consecutive_center_step_by_track_id", "face_partial_occlusion_pixel_motion_frames_by_track_id",
        "face_partial_occlusion_pixel_motion_track_frames", "face_pixel_motion_backend", "face_pixel_motion_detector_seed_max_age_us", "face_pixel_motion_evidence_gap_us", "face_pixel_motion_frames_by_track_id",
        "face_pixel_motion_reject_reasons", "face_pixel_motion_reject_reasons_by_track_id", "face_pixel_motion_rejected_frames_by_track_id", "face_pixel_motion_rejected_track_frames", "face_pixel_motion_track_frames",
        "face_position_clamped_frames_by_track_id", "face_position_clamped_track_frames", "face_predicted_frames_by_track_id", "face_predicted_track_frames", "face_recent_body_motion_bridge_frames_by_track_id",
        "face_recent_body_motion_bridge_track_frames", "face_roi_read_count", "face_sticker_max_center_step_by_track_id", "face_sticker_max_consecutive_center_step_by_track_id", "face_sticker_max_height_by_track_id",
        "face_sticker_max_width_by_track_id", "face_sticker_min_height_by_track_id", "face_sticker_min_width_by_track_id", "fallback_ids", "fallback_missed_frames",
        "fallback_observation_age_frames", "fallback_observed", "fallback_reason", "fallback_selected", "fallback_states",
        "fallback_to_asset", "fields", "fileName", "filePath", "file_list",
        "file_name", "file_path", "fillColorArgb", "fillMode", "followJson",
        "foot_y_delta", "fps", "frame", "frames_since_last_observation", "freeMemoryMb",
        "fresh_conservative_unknown", "fresh_depth_core_eligible", "fresh_depth_core_pixels", "fresh_depth_core_total_pixels", "fresh_full_body_class_primary_enabled",
        "fresh_selected", "fresh_selected_covered_track_ids", "fresh_selected_singleton_residual_ids", "fresh_selected_target", "fresh_unselected",
        "full", "full_body_person_ids", "gap", "geometry_source", "git_commit_sha",
        "gl_error", "gl_error_hex", "gl_renderer", "gl_vendor", "gl_version",
        "grace_frames", "group_id", "group_started_at_us", "handoff_age_us", "handoff_iou",
        "hard_selected", "hard_unselected", "hasAudio", "height", "hungarian_selected",
        "id", "identity_frames_since_observation", "identity_protected", "identity_protected_track_ids", "identity_stable",
        "identity_state", "identity_track_id", "image_format", "image_height", "image_width",
        "importance", "index", "inferred_selected", "inferred_unselected", "injectedTestStalls",
        "inliers", "input_artifact", "input_count", "input_path", "interruptedBeforeRetry",
        "intersection_area", "inverse", "isLowRamDevice", "jobId", "job_id",
        "jointSubmissions", "largeMemoryClass", "last_bbox", "last_observed_bbox", "last_overlap_us",
        "latched_frames", "late_strong_handoff", "learner", "learnerAnchorUs", "learnerAudioMuted",
        "learnerDecodedOutputs", "learnerDecoder", "learnerDurationMs", "learnerFrameEvents", "learnerFrameRate",
        "learnerPicture", "learnerPtsUs", "learnerSkippedOutputs", "learnerSyncSeekRetries", "learnerUri",
        "learnerUs", "learnerVideoHeight", "learnerVideoMime", "learnerVideoWidth", "learner_uri",
        "left", "legStretch", "legStretchEnabled", "legZoneBottom", "legZoneTop",
        "level", "litert_version", "lost_frames", "lowMemory", "manualMusicAnchor",
        "manual_music_anchor", "manual_offset_us", "manufacturer", "mapped_detection_indices", "mapped_residual_track_ids",
        "mapped_unknown_detection_indices", "mask_assoc_binary_sha256", "mask_assoc_foreground_pixels", "mask_assoc_near_threshold_pixels", "mask_bbox_fill_ratio",
        "mask_height", "mask_iou", "mask_overlap", "mask_sha256", "mask_source",
        "mask_width", "matchedPlannedReleaseFrames", "maxAbsMediaErrorUs", "maxAudioInterpolationAheadUs", "maxAudioPublishedStepUs",
        "maxClockBackstepUs", "maxClockSampleAgeUs", "maxMemoryMb", "max_gap_reached", "max_local_candidate_count",
        "max_matched_motion_ratio_q1e4", "max_matched_overlap_ratio_q1e4", "max_missed", "max_ms", "max_surface_pts_delta_us",
        "meanSignedMediaErrorUs", "media3", "memory", "memoryClass", "method",
        "min_area", "min_local_candidate_count", "min_matched_confidence_q1e4", "min_score", "mirrorLearner",
        "mirrorTeacher", "missed_frames", "missing_duration_frames", "missing_frames", "missing_protected_track_count",
        "missing_streak", "mixed_explicit_fresh_depth_core_eligible", "ml_environment", "mode", "model",
        "motionAlignment", "motion_dx", "motion_dy", "multi_group_reservation", "musicCalibration",
        "name", "new_track_ids", "nominal_frames", "non_active_protected_track_count", "non_monotonic_surface_timestamp_count",
        "normalized_foot_y_delta", "observationDurationUs", "observation_count", "observed_target", "observed_this_frame",
        "occluded_by_track_ids", "occluder_id", "occluder_track_id", "occluder_track_ids", "offsetUs",
        "opacity", "other_near_last_observed", "output0_artifact", "output0_count", "output1_artifact",
        "output1_count", "outputAspectRatio", "outputPath", "outputUri", "output_height",
        "output_width", "overlapDurationUs", "overlap_ratio", "ownership_mode", "ownership_pixels",
        "p50AbsMediaErrorUs", "p50_ms", "p95AbsMediaErrorUs", "p95ClockSampleAgeUs", "p95PlannedReleaseSkewUs",
        "p95PrepareUs", "p95RenderWorkUs", "p95SwapWaitUs", "p95TextureLatchUs", "p95_ms",
        "p95_surface_pts_delta_us", "packageName", "pair_count", "pairs", "panX",
        "panY", "pendingUri", "person_bbox_q0_0625px", "persons", "phase",
        "pid", "pipeline_lifecycle_job_id", "pipeline_lifecycle_stage", "pixel_reject_reason", "pixels",
        "placements", "plannedReleaseSkewUs", "positionUs", "predicted_bbox", "prediction_anchor_iou",
        "prefer_fresh_class_primary", "prev_state", "previous_identity_track_id", "previous_proxy_id", "previous_proxy_mode",
        "previous_track_ids", "previously_same_group", "prior_occluder_relation", "privacy_composited", "privacy_selected",
        "processPssKb", "process_exits", "processingProfile", "profile", "progress",
        "projectId", "protected_bbox_iou", "protected_identity_evidence_ok", "protected_mask_iou", "protected_track_count",
        "proxy_mode", "proxy_raw_center_x", "proxy_stabilization_delta_x", "proxy_stabilized_center_x", "pss_kb",
        "pts_us", "publicUri", "public_uri", "raw_area", "reacquire_frames",
        "readable", "real", "reason", "reason_name", "redacted",
        "relativePath", "removed_area", "render_height", "render_mode", "render_visible_mask_overlap",
        "render_width", "rendered_frames", "rendererStalls", "repeatedSaveSameUri", "requestId",
        "requested_accelerator", "requested_trim_start_us", "required", "required_margin", "reservation_owner_group_count",
        "reservation_owner_groups", "reservation_owner_track_ids", "reserved_detection_indices", "residual_track_ids", "resourceCloseFailures",
        "rgba_grid_sha256", "rgba_sha256", "right", "roi_pixel_source", "role",
        "root_cause_class", "root_cause_message", "root_observed_target", "root_target_frames_since_observation", "root_target_state",
        "row_best", "row_confusable_geometry", "row_margin", "row_second_best", "rss_kb",
        "same_crop_matrix", "same_decoded_frame", "same_privacy_state", "sample_count", "sample_frames",
        "sample_sha256", "samples", "savedUri", "scale", "schema",
        "schemaVersion", "scope", "score", "seekEvents", "selectedPersonIds",
        "selected_detection_indices", "selected_face", "selected_id", "selected_ids", "selected_prototypes",
        "selected_root_count", "selection_class", "sessionId", "session_id", "sha256",
        "should_infer", "sideBySide", "signature_scope", "silent", "sizeBytes",
        "size_bytes", "skinWhiten", "smoothFactor", "snapshot_files_excluded_as_historical", "source",
        "sourceError", "sourceExportStatus", "sourceUri", "source_fps", "source_height",
        "source_material_included", "source_rect_q0_0625px", "source_width", "sources", "spec",
        "speed", "stack_trace", "stage", "stage_timings", "stale_selected_track_ids",
        "startUs", "started_at_us", "state", "state_allows_carve", "status",
        "strength_off", "strength_on", "strength_requested", "strength_uniform", "success",
        "summary", "support_det_index", "support_source", "surface_transform", "surface_wait_timeout_count",
        "synthetic_track_ids", "targetFps", "targetHeight", "targetLearnerUs", "targetPersonId",
        "targetTeacherUs", "targetWidth", "target_bbox_bottom", "target_bbox_left", "target_bbox_right",
        "target_bbox_top", "target_center_x", "target_center_y", "target_confidence", "target_conservative_unknown",
        "target_detection_index", "target_fps", "target_frames_since_observation", "target_handoff_age_us", "target_height",
        "target_id", "target_last_handoff_age_us", "target_length", "target_mask_height", "target_mask_width",
        "target_occlusion_proxy_id", "target_path", "target_person_id", "target_privacy_class", "target_proxy_id",
        "target_proxy_iou", "target_proxy_mode", "target_residual_track_ids", "target_roi_proto_bottom", "target_roi_proto_left",
        "target_roi_proto_right", "target_roi_proto_top", "target_sampling_bottom", "target_sampling_left", "target_sampling_right",
        "target_sampling_top", "target_source", "target_state", "target_width", "teacher",
        "teacherAnchorUs", "teacherDecodedOutputs", "teacherDecoder", "teacherDurationMs", "teacherFrameEvents",
        "teacherFrameRate", "teacherPicture", "teacherPtsUs", "teacherSkippedOutputs", "teacherSyncSeekRetries",
        "teacherUri", "teacherUs", "teacherVideoHeight", "teacherVideoMime", "teacherVideoWidth",
        "teacher_uri", "textureWaits", "texture_type", "thread", "threads",
        "threshold", "thresholdMb", "timeAccuracy", "timeScale", "timestamp",
        "timestamp_ms", "tiny_fresh_depth_core_supported_by_geometry", "tiny_fresh_depth_core_suppressed", "title", "top",
        "totalFrames", "totalMemMb", "totalMemoryMb", "trace_available", "trace_file",
        "track_a_id", "track_a_predicted_bbox", "track_b_id", "track_b_predicted_bbox", "track_count",
        "track_id", "track_ids", "track_last_observed_bbox", "track_predicted_bbox", "track_state",
        "tracked_fallback_target", "tracked_ids", "tracked_states", "tracks", "trim_start_us",
        "u_pixel_stride", "u_row_stride", "uniform_location", "uniform_verified", "unknown",
        "unknown_detection_indices", "unmasked_observed_track_ids", "unresolved_ids", "unselected_prototypes", "updatedAt",
        "updatedAtMs", "updated_at", "uri", "usableLearnerFrames", "usableTeacherFrames",
        "v_pixel_stride", "v_row_stride", "variant", "versionCode", "versionName",
        "version_code", "version_name", "videoBitrate", "videoWindowSurfaces", "wall_time",
        "warmup_ms", "width", "windows", "winner_mask_iou", "winner_score",
        "winner_track_id", "would_strict_global_commit", "y_pixel_stride", "y_row_stride", "yolo_compile_ms",
        "yolo_effective_accelerator", "yolo_effective_cpu_num_threads", "yolo_gpu_fallback_reason", "yolo_inference_input_path", "yolo_requested_accelerator",
        "yolo_warmup_ms", "zoom"
    )
}
