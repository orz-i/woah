#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
CORE="$ROOT/crates/frame-kernel"
N="$ROOT/mobile/packages/dance_native/ios/dance_native/Sources/dance_native"
OUT="${TMPDIR:-/tmp}/frame-kernel-comparison-host"
mkdir -p "$OUT"
swiftc -O -I "$CORE/include" -o "$OUT/composition" \
  "$N/IOSComparisonTimeCore.swift" "$N/IOSComparisonModels.swift" \
  "$ROOT/mobile/packages/dance_native/ios/tests/comparison_composition/main.swift" \
  "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/composition"

swiftc -O -I "$CORE/include" -o "$OUT/music" \
  "$N/IOSComparisonMusicFeatures.swift" "$N/IOSComparisonMusicMatcher.swift" \
  "$ROOT/mobile/packages/dance_native/ios/tests/comparison_music/main.swift" \
  "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/music"
swiftc -O -I "$CORE/include" -o "$OUT/pcm" \
  "$N/IOSComparisonMusicFeatures.swift" "$N/IOSComparisonMusicPcmDecoder.swift" \
  "$ROOT/mobile/packages/dance_native/ios/tests/comparison_music_pcm/main.swift" \
  "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/pcm"
swiftc -O -I "$CORE/include" -o "$OUT/features" \
  "$N/IOSComparisonMusicFeatures.swift" "$ROOT/tools/ios/test_comparison_music_features.swift" \
  "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/features"
swiftc -O -I "$CORE/include" -o "$OUT/export" \
  "$N/IOSComparisonExportSpec.swift" \
  "$ROOT/mobile/packages/dance_native/ios/tests/comparison_export/main.swift" \
  "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/export"

swiftc -O -parse-as-library -D COMPARISON_EXPORT_STANDALONE -I "$CORE/include" -o "$OUT/export-av" \
  "$N/IOSComparisonTimeCore.swift" "$N/IOSComparisonModels.swift" \
  "$N/IOSComparisonFrameIndex.swift" "$N/IOSComparisonCompositor.swift" \
  "$N/IOSComparisonComposition.swift" "$N/IOSComparisonExportSpec.swift" \
  "$N/IOSComparisonExportPipeline.swift" \
  "$ROOT/mobile/packages/dance_native/ios/tests/comparison_export_av/Fixtures.swift" \
  "$ROOT/mobile/packages/dance_native/ios/tests/comparison_export_av/main.swift" \
  "$CORE/target/release/libframe_kernel.a" -liconv
"$OUT/export-av"
