import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val validateReleaseSigning by tasks.registering {
    doLast {
        check(hasReleaseSigning) {
            "Release signing is not configured. Provide mobile/app/android/key.properties " +
                "or WOAH_KEYSTORE_PATH / WOAH_KEYSTORE_PASSWORD / WOAH_KEY_ALIAS / WOAH_KEY_PASSWORD."
        }
        check(rootProject.file(releaseStoreFile!!).isFile) {
            "Release keystore does not exist: $releaseStoreFile"
        }
    }
}

tasks.configureEach {
    if (
        name == "packageRelease" ||
        name == "bundleRelease" ||
        name == "assembleRelease"
    ) {
        dependsOn(validateReleaseSigning)
    }
}

val releaseSigningPropertiesFile = rootProject.file("key.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.exists()) {
        FileInputStream(releaseSigningPropertiesFile).use(::load)
    }
}

fun releaseSigningValue(propertyName: String, environmentName: String): String? =
    System.getenv(environmentName)?.trim()?.takeIf { it.isNotEmpty() }
        ?: releaseSigningProperties.getProperty(propertyName)?.trim()?.takeIf { it.isNotEmpty() }

val releaseStoreFile = releaseSigningValue("storeFile", "WOAH_KEYSTORE_PATH")
val releaseStorePassword = releaseSigningValue("storePassword", "WOAH_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSigningValue("keyAlias", "WOAH_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("keyPassword", "WOAH_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val woahGitCommit = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--short=12", "HEAD")
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0 && output.isNotBlank()) output else "unknown"
}.getOrDefault("unknown")

android {
    namespace = "art.gaoge.dance"
    compileSdk = 36
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "art.gaoge.dance"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 24
        // Google Play requires new apps and updates submitted from 2026-08-31
        // onward to target Android 16 / API 36 or newer.
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        buildConfigField("String", "GIT_COMMIT", "\"$woahGitCommit\"")
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("tflite")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}



kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
