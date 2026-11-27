#!/usr/bin/env python3
"""Write only the default-on native canvas flag, never other Dart defines."""
import base64
import os
from pathlib import Path

KEY = "WOAH_ENABLE_CUTOUT_PREVIEW"


def enabled(encoded: str) -> bool:
    values = [
        base64.b64decode(token, validate=True).decode("utf-8")
        for token in encoded.split(",")
        if token
    ]
    matches = [value for value in values if value.partition("=")[0] == KEY]
    if len(matches) > 1:
        raise ValueError("Duplicate cutout preview build define")
    if not matches:
        return True
    if matches[0] not in (f"{KEY}=true", f"{KEY}=false"):
        raise ValueError("Invalid cutout preview boolean")
    return matches[0] == f"{KEY}=true"


def main() -> None:
    resources = Path(os.environ["TARGET_BUILD_DIR"]) / os.environ[
        "UNLOCALIZED_RESOURCES_FOLDER_PATH"
    ]
    resources.mkdir(parents=True, exist_ok=True)
    marker = resources / "WoahCutoutPreview.flag"
    # Overwrite on every build so an incremental disabled build cannot reuse true.
    marker.write_text("false\n", encoding="utf-8")
    if enabled(os.environ.get("DART_DEFINES", "")):
        marker.write_text("true\n", encoding="utf-8")


if __name__ == "__main__":
    main()
