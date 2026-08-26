# ONNX Runtime (CRITICAL: JNI symbols must be preserved)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Woah / Dance Native Bridge & DTOs
-keep class com.danceanon.native.** { *; }
-keep class com.danceanon.dance_native.** { *; }
-dontwarn com.danceanon.**
