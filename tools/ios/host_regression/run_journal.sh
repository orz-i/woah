#!/bin/bash
# Native-only macOS journal recovery regressions; no simulator/device needed.
set -euo pipefail
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$(git -C "$HERE" rev-parse --show-toplevel)"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/woah-ios-journal-host.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
J=mobile/packages/dance_native/ios/dance_native/Sources/dance_native/IOSExportJobJournal.swift
/usr/bin/swiftc -o "$OUT/journal_tests" "$HERE/journal/PigeonStubs.swift" "$J" "$HERE/journal/main.swift"
"$OUT/journal_tests"
