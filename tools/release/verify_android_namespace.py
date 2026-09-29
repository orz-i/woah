#!/usr/bin/env python3
"""Host-independent guard for Android Java/Kotlin namespace validity."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# Java Language Specification §3.9 reserved keywords. A package segment is an
# identifier, so none of these may appear as a segment in an Android namespace.
JAVA_RESERVED = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
    "class", "const", "continue", "default", "do", "double", "else", "enum",
    "extends", "final", "finally", "float", "for", "goto", "if", "implements",
    "import", "instanceof", "int", "interface", "long", "native", "new",
    "package", "private", "protected", "public", "return", "short", "static",
    "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "try", "void", "volatile", "while", "_",
}

IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
FAILURES: list[str] = []


def fail(message: str) -> None:
    FAILURES.append(message)


def validate_package(name: str, label: str) -> None:
    parts = name.split(".")
    if len(parts) < 2:
        fail(f"{label} must contain at least two package segments: {name}")
    for segment in parts:
        if not IDENTIFIER.fullmatch(segment):
            fail(f"{label} has an invalid Java identifier segment {segment!r}: {name}")
        if segment in JAVA_RESERVED:
            fail(f"{label} uses reserved Java keyword {segment!r}: {name}")


def extract(path: Path, pattern: str, label: str) -> str:
    text = path.read_text(encoding="utf-8")
    match = re.search(pattern, text)
    if not match:
        fail(f"Could not find {label} in {path.relative_to(ROOT)}")
        return ""
    return match.group(1)


def verify_declared_namespaces() -> None:
    app_gradle = ROOT / "mobile/app/android/app/build.gradle.kts"
    plugin_gradle = ROOT / "mobile/packages/dance_native/android/build.gradle.kts"
    example_gradle = ROOT / "mobile/packages/dance_native/example/android/app/build.gradle.kts"
    plugin_pubspec = ROOT / "mobile/packages/dance_native/pubspec.yaml"
    pigeon = ROOT / "mobile/packages/dance_native/pigeons/dance_api.dart"

    app_namespace = extract(app_gradle, r'namespace\s*=\s*"([^"]+)"', "app namespace")
    app_id = extract(app_gradle, r'applicationId\s*=\s*"([^"]+)"', "applicationId")
    plugin_namespace = extract(plugin_gradle, r'namespace\s*=\s*"([^"]+)"', "plugin namespace")
    plugin_package = extract(plugin_pubspec, r'(?m)^\s*package:\s*([^\s]+)\s*$', "Flutter plugin Android package")
    pigeon_package = extract(pigeon, r"KotlinOptions\(package:\s*'([^']+)'\)", "Pigeon Kotlin package")
    example_namespace = extract(example_gradle, r'namespace\s*=\s*"([^"]+)"', "example namespace")
    example_id = extract(example_gradle, r'applicationId\s*=\s*"([^"]+)"', "example applicationId")

    expected = {
        "app namespace": (app_namespace, "art.gaoge.dance"),
        "applicationId": (app_id, "art.gaoge.dance"),
        "plugin namespace": (plugin_namespace, "art.gaoge.dance.engine"),
        "Flutter plugin Android package": (plugin_package, "art.gaoge.dance.engine"),
        "Pigeon Kotlin package": (pigeon_package, "art.gaoge.dance.engine.bridge"),
        "example namespace": (example_namespace, "art.gaoge.dance.engine.example"),
        "example applicationId": (example_id, "art.gaoge.dance.engine.example"),
    }

    for label, (actual, wanted) in expected.items():
        if actual != wanted:
            fail(f"{label} drifted: expected {wanted}, found {actual}")
        if actual:
            validate_package(actual, label)


def verify_source_hygiene_contract() -> None:
    gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8").splitlines()
    normalized = [line.strip() for line in gitignore if line.strip() and not line.lstrip().startswith("#")]
    if "debug/" in normalized:
        fail(
            ".gitignore must not use a repository-wide `debug/` rule; it can hide "
            "real Kotlin/Java source packages such as src/main/kotlin/.../debug"
        )
    if "/debug/" not in normalized:
        fail(".gitignore should scope temporary debug output to the repository root with `/debug/`")

    plugin_gradle = (
        ROOT / "mobile/packages/dance_native/android/build.gradle.kts"
    ).read_text(encoding="utf-8")
    for token in (
        '"**/com/danceanon/**"',
        '"**/art/gaoge/dance/native/**"',
        "tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool>()",
        "tasks.withType<org.gradle.api.tasks.compile.JavaCompile>()",
        "exclude(*legacyAndroidSourcePatterns)",
    ):
        if token not in plugin_gradle:
            fail(f"Android build must quarantine stale pre-migration sources: missing {token}")

    legacy_roots = [
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/com/danceanon",
        ROOT / "mobile/packages/dance_native/android/src/main/kotlin/art/gaoge/dance/native",
    ]
    for legacy_root in legacy_roots:
        if legacy_root.exists():
            fail(
                "Stale local Android source tree is present: "
                f"{legacy_root.relative_to(ROOT)}. Delete it after pulling the latest branch."
            )


def verify_kotlin_packages() -> None:
    roots = [
        ROOT / "mobile/packages/dance_native/android/src",
        ROOT / "mobile/packages/dance_native/example/android/app/src",
    ]
    checked = 0
    for source_root in roots:
        for path in source_root.rglob("*.kt"):
            try:
                kotlin_index = path.parts.index("kotlin")
            except ValueError:
                continue

            expected = ".".join(path.parts[kotlin_index + 1 : -1])
            text = path.read_text(encoding="utf-8")
            match = re.search(r"(?m)^package\s+([A-Za-z0-9_.]+)\s*$", text)
            if not match:
                fail(f"Missing package declaration: {path.relative_to(ROOT)}")
                continue

            actual = match.group(1)
            validate_package(actual, str(path.relative_to(ROOT)))
            if actual != expected:
                fail(
                    f"Package/path mismatch in {path.relative_to(ROOT)}: "
                    f"declares {actual}, path expects {expected}"
                )
            checked += 1

    if checked == 0:
        fail("No Kotlin package declarations were checked")


def main() -> int:
    verify_declared_namespaces()
    verify_source_hygiene_contract()
    verify_kotlin_packages()

    if FAILURES:
        print("Android namespace verification FAILED:")
        for failure in FAILURES:
            print(f" - {failure}")
        return 1

    print("Android namespace verification passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
