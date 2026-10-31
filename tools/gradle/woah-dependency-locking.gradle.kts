import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import java.util.Properties

// Only repository-owned app/native configurations used by supported validation.
// Do not mutate dependencies or lockfiles inside external pub plugins.
val woahLockedConfigurations = setOf(
    "debugCompileClasspath", "debugRuntimeClasspath",
    "releaseCompileClasspath", "releaseRuntimeClasspath",
    "debugUnitTestCompileClasspath", "debugUnitTestRuntimeClasspath",
)
val woahEngineArtifacts = setOf(
    "armeabi_v7a_debug", "arm64_v8a_debug", "x86_64_debug",
    "armeabi_v7a_release", "arm64_v8a_release", "x86_64_release",
)
val woahFlutterProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val woahFlutterRoot = woahFlutterProperties.getProperty("flutter.sdk")
    ?: System.getenv("FLUTTER_ROOT") ?: System.getenv("FLUTTER_HOME")
    ?: error("Flutter SDK is required for dependency version validation")
val woahEngineVersion = "1.0.0-" + file("$woahFlutterRoot/bin/cache/engine.stamp").readText().trim()

dependencyLocking {
    lockMode.set(LockMode.STRICT)
    // ABI membership is selected by Flutter's target-platform, not a library
    // update. These exact SDK engine artifacts are version-checked below;
    // flutter_embedding and every other module remain locked.
    ignoredDependencies.addAll(woahEngineArtifacts.map { "io.flutter:$it" })
    // Kotlin 2.3.20 metadata-only common shim changes graph membership when
    // AGP propagates compile constraints to runtime. Its exact version is
    // checked below; actual stdlib binaries remain locked.
    ignoredDependencies.add("org.jetbrains.kotlin:kotlin-stdlib-common")
}
configurations.configureEach {
    if (name in woahLockedConfigurations) {
        resolutionStrategy.activateDependencyLocking()
        incoming.afterResolve {
            resolutionResult.allComponents.forEach { component ->
                val module = component.id as? ModuleComponentIdentifier
                if (module != null && module.group == "org.jetbrains.kotlin" && module.module == "kotlin-stdlib-common") {
                    check(module.version == "2.3.20") { "Unexpected Kotlin common metadata version: ${module.version}" }
                }
                if (module != null && module.group == "io.flutter" && module.module in woahEngineArtifacts) {
                    check(module.version == woahEngineVersion) {
                        "Unexpected Flutter ABI engine version: ${module.displayName}; expected $woahEngineVersion"
                    }
                }
            }
        }
    }
}
tasks.register("verifyWoahDependencyLocks") {
    group = "verification"
    description = "Resolve locked app/native configurations without assembling/signing binaries"
    doLast {
        val selected = configurations.filter { it.name in woahLockedConfigurations && it.isCanBeResolved }
        check(selected.map { it.name }.toSet() == woahLockedConfigurations) { "Missing expected lock configurations" }
        selected.forEach { configuration ->
            val unresolved = configuration.incoming.resolutionResult.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
            check(unresolved.isEmpty()) { "Unresolved locked dependencies in ${configuration.name}: ${unresolved.map { it.attempted.displayName + ": " + it.failure.message }}" }
            configuration.incoming.resolutionResult.allComponents.forEach { component ->
                val module = component.id as? ModuleComponentIdentifier
                if (module != null) println("WOAH_DEPENDENCY ${project.path} ${configuration.name} ${module.displayName}")
            }
        }
    }
}
