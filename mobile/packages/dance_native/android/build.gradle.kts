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
        }
    }

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.json:json:20240303")
    testImplementation("org.mockito:mockito-core:5.0.0")

    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test")
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
                if (srcFile.exists() && (!destFile.exists() || destFile.length() != srcFile.length())) {
                    srcFile.copyTo(destFile, overwrite = true)
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
    dependsOn(verifyLiteRtModelAssets, verifyNoOnnxRuntime, verifyNoPlayServicesLiteRt)
}
