#!/usr/bin/env python3
"""Static checks for the Woah iOS Phase 2 first-frame analyze pipeline.

This gate is host-independent. The GitHub macOS lane remains authoritative for
Swift/Xcode compilation, while a real iPhone is still required for runtime
delegate/performance acceptance.
"""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCES = (
    ROOT
    / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native"
)
FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        FAILURES.append(message)


def verify_cache_contract() -> None:
    path = SOURCES / "IOSAnalysisCache.swift"
    check(path.is_file(), "IOSAnalysisCache.swift is missing")
    if not path.is_file():
        return
    cache = path.read_text(encoding="utf-8")
    for token in (
        'appendingPathComponent("analysis", isDirectory: true)',
        'appendingPathComponent("source_uri.txt")',
        'appendingPathComponent("analysis.json")',
        "schemaVersion: Int",
        "sourceUri: String",
        "persons: [IOSCachedPerson]",
        "let thumbnailWidth = 160",
        "let thumbnailHeight = 240",
        "kCGImageDestinationLossyCompressionQuality: 0.85",
        'appendingPathComponent("person_\\(personId).jpg")',
        "func clearAnalysisCache(cacheId: String) throws",
    ):
        check(token in cache, f"iOS analysis cache contract missing: {token}")
    check(
        'code: "INVALID_CACHE_ID"' in cache
        and '!cacheId.contains("/")' in cache,
        "iOS cache cleanup must reject path-like cache identifiers",
    )


def verify_analyze_pipeline() -> None:
    path = SOURCES / "IOSAnalyzePipeline.swift"
    check(path.is_file(), "IOSAnalyzePipeline.swift is missing")
    if not path.is_file():
        return
    pipeline = path.read_text(encoding="utf-8")
    for token in (
        "IOSVideoProbe.probe(uri: request.videoUri)",
        "AVAssetImageGenerator(asset: asset)",
        "generator.appliesPreferredTrackTransform = true",
        "generator.requestedTimeToleranceBefore = .zero",
        "generator.requestedTimeToleranceAfter = .zero",
        "request.trimStartMs",
        "preferredBackend: .xnnpack",
        "cache.saveVideoUri(cacheId: cacheId, videoUri: request.videoUri)",
        "cache.savePersonThumbnail(",
        "schemaVersion: 1",
        "cache.saveMetadata(",
        "analysisCacheId: cacheId",
        "id: Int64(index)",
    ):
        check(token in pipeline, f"iOS Phase 2 analyze pipeline missing: {token}")
    check(
        "Double(detection.x1) / Double(frameWidth)" in pipeline
        and "Double(detection.y1) / Double(frameHeight)" in pipeline,
        "Phase 2 detections must be normalized in the transformed display frame",
    )
    check(
        "preferredBackend: .xnnpack" in pipeline,
        "Phase 2 selection analysis must stay on deterministic CPU/XNNPACK like Android",
    )
    check(
        "if !committed" in pipeline and "clearAnalysisCache(cacheId: cacheId)" in pipeline,
        "Failed Phase 2 analysis must clean its partial cache",
    )


def verify_plugin_gating() -> None:
    plugin = (SOURCES / "DanceNativePlugin.swift").read_text(encoding="utf-8")
    analyze_start = plugin.find("func analyzeVideo")
    preview_start = plugin.find("func getPreviewFrame", analyze_start)
    export_start = plugin.find("func startExport", preview_start)
    cancel_start = plugin.find("func cancelJob", export_start)
    release_start = plugin.find("func releaseProject")
    build_info_start = plugin.find("private static func buildInfo", release_start)

    analyze_block = plugin[analyze_start:preview_start]
    preview_block = plugin[preview_start:export_start]
    export_block = plugin[export_start:cancel_start]
    release_block = plugin[release_start:build_info_start]

    check(
        "return try await analyzePipeline.analyze(request: request)" in analyze_block,
        "DanceNativePlugin.analyzeVideo must route into IOSAnalyzePipeline",
    )
    check(
        'code: "PLATFORM_NOT_SUPPORTED"' not in analyze_block,
        "Phase 2 analyzeVideo must no longer be a platform stub",
    )
    check(
        'code: "PLATFORM_NOT_SUPPORTED"' in preview_block,
        "getPreviewFrame must remain gated until Phase 3",
    )
    check(
        'code: "PLATFORM_NOT_SUPPORTED"' in export_block,
        "startExport must remain gated until Phase 4",
    )
    check(
        "analysisCache.clearAnalysisCache(cacheId: projectId)" in release_block,
        "releaseProject must clear the iOS analysis cache",
    )


def verify_cross_platform_selection_contract() -> None:
    android = (
        ROOT
        / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/pipeline/AnalyzePipeline.kt"
    ).read_text(encoding="utf-8")
    app_selection = (
        ROOT
        / "mobile/app/lib/features/person_selection/presentation/person_selection_controller.dart"
    ).read_text(encoding="utf-8")
    check(
        'val cacheId = "analysis_${System.currentTimeMillis()}"' in android,
        "Android analysis cache identity contract unexpectedly changed",
    )
    check(
        "selectionCandidateMinConfidence = 0.60" in app_selection,
        "The shared first-frame selectable-person threshold must remain 0.60",
    )


def main() -> int:
    verify_cache_contract()
    verify_analyze_pipeline()
    verify_plugin_gating()
    verify_cross_platform_selection_contract()
    if FAILURES:
        print("iOS Phase 2 verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    print("iOS Phase 2 static verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
