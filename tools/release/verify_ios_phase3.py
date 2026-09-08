#!/usr/bin/env python3
"""Static contract gate for Woah iOS Phase 3 Metal preview.

This verifier can run on Windows. It proves repository intent and cross-platform
contracts only. GitHub-hosted macOS/Xcode remains authoritative for Swift/Metal
compilation; real-device visual/privacy/performance acceptance remains separate.
"""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCES = ROOT / "mobile/packages/dance_native/ios/dance_native/Sources/dance_native"
FAILURES: list[str] = []


def check(condition: bool, message: str) -> None:
    if not condition:
        FAILURES.append(message)


def text(name: str) -> str:
    path = SOURCES / name
    check(path.is_file(), f"Missing iOS Phase 3 source: {name}")
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def verify_preview_pipeline() -> None:
    pipeline = text("IOSPreviewPipeline.swift")
    for token in (
        "analysisCache.loadVideoUri(cacheId: request.analysisCacheId)",
        "analysisCache.loadMetadata(cacheId: cacheId)",
        "AVAssetImageGenerator(asset: asset)",
        "generator.appliesPreferredTrackTransform = true",
        "generator.requestedTimeToleranceBefore = .zero",
        "generator.requestedTimeToleranceAfter = .zero",
        "preferredBackend: .xnnpack",
        "IOSPreviewIdentityMatcher.assign(",
        'code: "PREVIEW_PRIVACY_UNRESOLVED"',
        "fullBodyIds.union(faceOnlyIds)",
        ".subtracting(fullBodyIds)",
        "renderer.render(",
        "kCGImageDestinationLossyCompressionQuality: 0.85",
        '"preview_\\(cacheId)_\\(timestampMs)_\\(nonce).jpg"',
        "frameAnalysisCache[key] = entry",
        "func clearForAnalysis(cacheId: String)",
    ):
        check(token in pipeline, f"Phase 3 preview pipeline missing: {token}")

    check(
        "Keep preview inference deterministic" in pipeline,
        "Preview inference must document the XNNPACK determinism boundary",
    )


def verify_identity_contract() -> None:
    matcher = text("IOSPreviewIdentityMatcher.swift")
    for token in (
        "maxCostThreshold: Float32 = 0.70",
        "1.0 - bboxIoU",
        "candidates.sort",
        "usedIds",
        "assignedIds",
        "cached.map(\\.id).max()",
    ):
        check(token in matcher, f"Phase 3 identity matcher missing: {token}")

    android = (
        ROOT
        / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon/native/pipeline/PreviewPipeline.kt"
    ).read_text(encoding="utf-8")
    check(
        "HungarianSolver.match(costMatrix, maxCostThreshold = 0.70f)" in android,
        "Android preview matching threshold changed; revisit iOS parity",
    )


def verify_metal_renderer() -> None:
    renderer = text("IOSMetalPreviewRenderer.swift")
    for token in (
        "import Metal",
        "MTLCreateSystemDefaultDevice()",
        'makeFunction(name: "woahPreviewKernel")',
        "makeComputePipelineState",
        "dispatchThreads(",
        "commandBuffer.waitUntilCompleted()",
        "min(sourceWidth, 1280)",
        "preprocess.scale",
        "preprocess.padLeft",
        "preprocess.padTop",
        "dilate(target.detection.mask, radius: 1)",
        "erode(candidate.detection.mask, radius: 1)",
        "bboxOverlapRatio(target.detection, candidate.detection)",
        "normalizedFootDelta >= 0.10",
        "maskOverlapRatio(effective, candidate.detection.mask) > 0.02",
        "privacy * carve / 255",
        'case "blur": return 2',
        'case "gradient": return 3',
        'case "mosaic": return 5',
        "borderWidth * 3.0",
        "stickerEnabled",
        "conservativeFaceRect(",
        "woahPreviewKernel",
        "float4(1.0, 0.84, 0.0, 1.0)",
    ):
        check(token in renderer, f"Phase 3 Metal renderer missing: {token}")

    check(
        "ambiguous overlap always keeps privacy" in renderer,
        "iOS preview occlusion policy must remain privacy-first when depth is ambiguous",
    )
    check(
        "transparent asset holes" in renderer,
        "Sticker mode must document and preserve fail-closed face privacy",
    )


def verify_runtime_wiring_and_gates() -> None:
    plugin = text("DanceNativePlugin.swift")
    runner = text("IOSYoloRunner.swift")
    check(
        "let preprocess: IOSYoloPreprocessResult" in runner,
        "Phase 3 requires YOLO preprocess mapping in IOSYoloInferenceResult",
    )
    preview_start = plugin.find("func getPreviewFrame")
    export_start = plugin.find("func startExport", preview_start)
    cancel_start = plugin.find("func cancelJob", export_start)
    release_start = plugin.find("func releaseProject")
    build_info_start = plugin.find("private static func buildInfo", release_start)
    preview_block = plugin[preview_start:export_start]
    export_block = plugin[export_start:cancel_start]
    release_block = plugin[release_start:build_info_start]
    check(
        "return try await previewPipeline.render(request: request)" in preview_block,
        "DanceNativePlugin.getPreviewFrame must route through IOSPreviewPipeline",
    )
    check(
        'code: "PLATFORM_NOT_SUPPORTED"' not in preview_block,
        "Phase 3 getPreviewFrame must no longer be a platform stub",
    )
    check(
        'code: "PLATFORM_NOT_SUPPORTED"' in export_block,
        "Phase 3 must keep startExport gated until Phase 4",
    )
    check(
        "previewPipeline.clearForAnalysis(cacheId: projectId)" in release_block,
        "releaseProject must clear in-memory/on-disk preview state",
    )


def main() -> int:
    verify_preview_pipeline()
    verify_identity_contract()
    verify_metal_renderer()
    verify_runtime_wiring_and_gates()
    if FAILURES:
        print("iOS Phase 3 verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1
    print("iOS Phase 3 static verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
