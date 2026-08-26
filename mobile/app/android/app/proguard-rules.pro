# Flutter Rules
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.**  { *; }
-keep class io.flutter.util.**  { *; }
-keep class io.flutter.view.**  { *; }
-keep class io.flutter.**  { *; }
-keep class io.flutter.plugins.**  { *; }

# ONNX Runtime (CRITICAL: JNI symbols must be preserved)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Woah / Dance Native Bridge & DTOs
-keep class com.danceanon.native.** { *; }
-keep class com.danceanon.dance_native.** { *; }
-dontwarn com.danceanon.**
