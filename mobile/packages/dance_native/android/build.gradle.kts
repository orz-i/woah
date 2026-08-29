import java.time.Instant
import java.security.MessageDigest

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

group = "com.danceanon.dance_native"
version = "1.0-SNAPSHOT"

buildscript {
    val kotlinVersion = "2.3.20"
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.0.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.library")
}

val diagnosticGitCommitSha = System.getenv("GIT_COMMIT_SHA")?.takeIf { it.isNotBlank() }
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "HEAD")
        }.standardOutput.asText.get().trim()
    }.getOrDefault("unknown")

val diagnosticBuildTimestamp = System.getenv("BUILD_TIMESTAMP")?.takeIf { it.isNotBlank() }
    ?: Instant.now().toString()

android {
    namespace = "com.danceanon.dance_native"

    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            java.srcDirs("src/androidTest/kotlin")
            // Benchmark-only fixtures. These directories are packaged only into
            // the instrumentation APK and never into the production AAR/APK.
            assets.srcDirs(
                file("../../../../testdata/face_benchmark_frames"),
                file("../../../../testdata/models/face")
            )
        }
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "GIT_COMMIT_SHA", "\"$diagnosticGitCommitSha\"")
        buildConfigField("String", "BUILD_TIMESTAMP", "\"$diagnosticBuildTimestamp\"")
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("tflite")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.useJUnitPlatform()
                it.outputs.upToDateWhen { false }
                it.testLogging {
                    events("passed", "skipped", "failed", "standardOut", "standardError")
                    showStandardStreams = true
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("com.google.ai.edge.litert:litert:2.1.5")
    // Face locator runtime is packaged but remains dormant until an explicit
    // internal caller opts in. YOLO/TrackManager remains identity authority.
    implementation("com.google.mediapipe:tasks-vision:1.0.0")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.json:json:20240303")
    testImplementation("org.mockito:mockito-core:5.0.0")

    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test")
    // Benchmark-only bundled alternative for same-ROI A/B on real devices.
    androidTestImplementation("com.google.mlkit:face-detection:16.1.7")
}

val syncFaceDetectorAsset = tasks.register("syncFaceDetectorAsset") {
    doLast {
        val source = file("../../../../testdata/models/face/blaze_face_full_range.tflite")
        if (!source.exists() || source.length() == 0L) {
            throw GradleException("Missing face detector model fixture: ${source.absolutePath}")
        }
        val targetDir = file("src/main/assets/models/face")
        val target = File(targetDir, "blaze_face_full_range.tflite")
        targetDir.mkdirs()
        val needsCopy = !target.exists() ||
            target.length() != source.length() ||
            sha256(target) != sha256(source)
        if (needsCopy) {
            source.copyTo(target, overwrite = true)
        }
    }
}

val verifyFaceDetectorAsset = tasks.register("verifyFaceDetectorAsset") {
    dependsOn(syncFaceDetectorAsset)
    doLast {
        val model = file("src/main/assets/models/face/blaze_face_full_range.tflite")
        if (!model.exists() || model.length() == 0L) {
            throw GradleException("Missing packaged face detector model: ${model.absolutePath}")
        }
        val expectedSha256 = "3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b"
        val actualSha256 = sha256(model)
        if (actualSha256 != expectedSha256) {
            throw GradleException(
                "Unexpected face detector model hash: expected=$expectedSha256 actual=$actualSha256"
            )
        }
    }
}

val syncLiteRtModelAssets = tasks.register("syncLiteRtModelAssets") {
    doLast {
        val targetDir = file("src/main/assets/models/litert")
        val repoModelsDir = rootProject.file("../../models/litert")
        if (repoModelsDir.exists()) {
            val litertFiles = listOf(
                "yolo11n-seg-fp16.tflite",
                "sam2_image_features.tflite",
                "sam2_init_step.tflite",
                "sam2_temporal_step.tflite"
            )
            targetDir.mkdirs()
            for (fname in litertFiles) {
                val srcFile = File(repoModelsDir, fname)
                val destFile = File(targetDir, fname)
                if (srcFile.exists()) {
                    val needsCopy = !destFile.exists() ||
                        destFile.length() != srcFile.length() ||
                        sha256(destFile) != sha256(srcFile)
                    if (needsCopy) {
                        srcFile.copyTo(destFile, overwrite = true)
                    }
                }
            }
        }
    }
}

val verifyLiteRtModelAssets = tasks.register("verifyLiteRtModelAssets") {
    dependsOn(syncLiteRtModelAssets)
    doLast {
        val requiredModels = listOf(
            "models/litert/yolo11n-seg-fp16.tflite",
            "models/litert/sam2_image_features.tflite",
            "models/litert/sam2_init_step.tflite",
            "models/litert/sam2_temporal_step.tflite"
        )
        for (m in requiredModels) {
            val modelFile = file("src/main/assets/$m")
            if (!modelFile.exists() || modelFile.length() == 0L) {
                throw GradleException(
                    "Missing required LiteRT model asset: ${modelFile.absolutePath}. Ensure Phase 2-6 exports are complete."
                )
            }
        }
    }
}

val verifyNoOnnxRuntime = tasks.register("verifyNoOnnxRuntime") {
    doLast {
        configurations.forEach { cfg ->
            cfg.dependencies.forEach { dep ->
                val group = dep.group ?: ""
                val name = dep.name
                if (group.contains("onnxruntime", ignoreCase = true) || name.contains("onnxruntime", ignoreCase = true)) {
                    throw GradleException("Hard Migration Violation: ONNX Runtime dependency found in $cfg: $group:$name")
                }
            }
        }
    }
}

val verifyNoPlayServicesLiteRt = tasks.register("verifyNoPlayServicesLiteRt") {
    doLast {
        configurations.forEach { cfg ->
            cfg.dependencies.forEach { dep ->
                val group = dep.group ?: ""
                val name = dep.name
                if (group.contains("play-services-tflite", ignoreCase = true) || group.contains("tensorflow-lite", ignoreCase = true) || name.contains("play-services-tflite", ignoreCase = true)) {
                    throw GradleException("Hard Migration Violation: Play Services / Legacy TFLite dependency found in $cfg: $group:$name. Must use App-bundled LiteRT 2.1.5.")
                }
            }
        }
    }
}

tasks.matching { it.name.startsWith("preBuild") || it.name.startsWith("compile") }.configureEach {
    dependsOn(
        verifyLiteRtModelAssets,
        verifyFaceDetectorAsset,
        verifyNoOnnxRuntime,
        verifyNoPlayServicesLiteRt
    )
}
