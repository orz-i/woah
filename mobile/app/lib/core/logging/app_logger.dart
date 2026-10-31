import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Diagnostic output is an explicit vocabulary, never arbitrary exception text.
/// Business URI/path arguments are intentionally not transformed here.
class AppLogger {
  static const _tags = {
    'NativeRepository',
    'ImportVideoController',
    'PersonSelectionController',
    'ExportController',
    'AnalysisSession',
  };
  static const _events = {
    'CAPABILITIES_REQUEST',
    'VIDEO_PROBE_REQUEST',
    'VIDEO_ANALYSIS_REQUEST',
    'GALLERY_SAVE_REQUEST',
    'VIDEO_SHARE_REQUEST',
    'VIDEO_OPEN_REQUEST',
    'DIAGNOSTIC_CREATE_REQUEST',
    'DIAGNOSTIC_SHARE_REQUEST',
    'DIAGNOSTIC_CLEAR_REQUEST',
    'IMPORT_PICK_FAILED',
    'VIDEO_PROBE_FAILED',
    'SELECTION_CANDIDATES_FILTERED',
    'VIDEO_ANALYSIS_FAILED',
    'SELECTION_PREVIEW_FAILED',
    'EXPORT_PREVIEW_TOGGLE_FAILED',
    'EXPORT_START_FAILED',
    'EXPORT_CAPABILITIES_FALLBACK',
    'EXPORT_CLEANUP_FAILED',
    'CACHE_RELEASE_DEFERRED',
  };
  static const _codes = {
    'EXPORT_FAILED',
    'EXPORT_INTERRUPTED',
    'JOB_NOT_FOUND',
    'EXPORT_BUSY',
    'INVALID_ARGS',
    'INVALID_ARGUMENT',
    'PLUGIN_DETACHED',
    'SAVE_FAILED',
    'SAVE_CANCELLED',
    'SAVE_ROLLBACK_FAILED',
    'STICKER_PERSIST_FAILED',
    'SHARE_VIDEO_FAILED',
    'OPEN_VIDEO_FAILED',
    'THUMBNAIL_FAILED',
    'NO_CONTEXT',
    'BUNDLE_FAILED',
    'SHARE_FAILED',
    'VIDEO_OPEN_FAILED',
    'VIDEO_TRACK_NOT_FOUND',
    'ANALYSIS_FAILED',
    'ANALYSIS_CACHE_MISSING',
    'CACHE_NOT_FOUND',
    'RENDER_FAILED',
    'MODEL_NOT_FOUND',
    'MODEL_INIT_FAILED',
    'MODEL_INFERENCE_FAILED',
    'OUT_OF_STORAGE',
    'PHOTO_LIBRARY_PERMISSION_DENIED',
    'SAVE_VIDEO_FAILED',
    'FOLLOW_TARGET_UNRESOLVED',
    'PREVIEW_PRIVACY_UNRESOLVED',
    'EXPORT_PRIVACY_UNRESOLVED',
    'EXPORT_CANCELLED',
  };

  static String safeCode(String? code) =>
      _codes.contains(code) ? code! : 'UNKNOWN_ERROR';

  static String errorCode(Object? error) =>
      error is PlatformException ? safeCode(error.code) : 'UNKNOWN_ERROR';

  static String _entry(String tag, String event) =>
      '[${_tags.contains(tag) ? tag : 'App'}] '
      '${_events.contains(event) ? event : 'UNCLASSIFIED_EVENT'}';

  static void d(String tag, String event) {
    if (kDebugMode) debugPrint(_entry(tag, event));
  }

  static void w(String tag, String event) {
    if (kDebugMode) debugPrint(_entry(tag, event));
  }

  static void e(String tag, String event, [Object? error, StackTrace? stack]) {
    // Never call error/stack.toString(), including in debug builds.
    debugPrint('${_entry(tag, event)} code=${errorCode(error)}');
  }

  static String exportErrorMessage(String? code) =>
      '导出未完成，请重试（${safeCode(code)}）。';

  static String exportErrorDetails(String? code) =>
      'stage=export; code=${safeCode(code)}';
}
