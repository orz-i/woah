import 'package:flutter/foundation.dart';

class AppLogger {
  static void d(String tag, String message) {
    if (kDebugMode) {
      debugPrint('[$tag] ℹ️ $message');
    }
  }

  static void w(String tag, String message) {
    if (kDebugMode) {
      debugPrint('[$tag] ⚠️ $message');
    }
  }

  static void e(String tag, String message, [Object? error, StackTrace? stack]) {
    debugPrint('[$tag] ❌ $message ${error != null ? ': $error' : ''}');
    if (stack != null && kDebugMode) {
      debugPrint(stack.toString());
    }
  }
}
