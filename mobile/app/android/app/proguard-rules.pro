# Flutter Rules
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.**  { *; }
-keep class io.flutter.util.**  { *; }
-keep class io.flutter.view.**  { *; }
-keep class io.flutter.**  { *; }
-keep class io.flutter.plugins.**  { *; }

# Google Play Core / SplitCompat (suppress warnings for Flutter deferred components)
-dontwarn com.google.android.play.core.**

# WorkManager 2.9.x / Room 2.5.x are pulled transitively by LiteRT asset delivery.
# Under AGP 9 / R8 full mode their historical consumer rules can keep the
# reflectively-created classes while still stripping the constructors Room and
# WorkManager need at runtime. That crashes release builds inside
# InitializationProvider before Flutter starts.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.work.InputMerger {
    public <init>();
}
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ONNX Runtime (CRITICAL: JNI symbols must be preserved)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Woah / Dance Native Bridge & DTOs
-keep class com.danceanon.native.** { *; }
-keep class com.danceanon.dance_native.** { *; }
-dontwarn com.danceanon.**
