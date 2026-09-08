#!/usr/bin/env python3
"""Upload a Woah Phase 1 IPA and collect the bundled YOLO probe on BrowserStack."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from phase1_report import DEFAULT_REQUIRED_BACKENDS, evaluate_phase1_report


REPORT_PREFIX = "WOAH_PHASE1_REPORT:"
ERROR_PREFIX = "WOAH_PHASE1_ERROR:"
ACCESSIBILITY_ID = "woah-ios-phase1-cloud-report"


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise SystemExit(f"Missing required environment variable: {name}")
    return value


def upload_ipa(ipa: Path, username: str, access_key: str, custom_id: str) -> str:
    import requests

    with ipa.open("rb") as stream:
        response = requests.post(
            "https://api-cloud.browserstack.com/app-automate/upload",
            auth=(username, access_key),
            files={"file": (ipa.name, stream, "application/octet-stream")},
            data={"custom_id": custom_id},
            timeout=180,
        )
    response.raise_for_status()
    payload = response.json()
    app_url = payload.get("app_url")
    if not isinstance(app_url, str) or not app_url.startswith("bs://"):
        raise RuntimeError(f"Unexpected BrowserStack upload response: {payload}")
    return app_url


def collect_device_report(
    *,
    app_url: str,
    username: str,
    access_key: str,
    device_name: str,
    platform_version: str,
    build_name: str,
) -> tuple[dict, str]:
    from appium import webdriver
    from appium.options.ios import XCUITestOptions
    from appium.webdriver.common.appiumby import AppiumBy
    from selenium.webdriver.support.ui import WebDriverWait

    options = XCUITestOptions()
    options.set_capability("platformName", "iOS")
    options.set_capability("appium:automationName", "XCUITest")
    options.set_capability("appium:deviceName", device_name)
    options.set_capability("appium:platformVersion", platform_version)
    options.set_capability("appium:app", app_url)
    options.set_capability(
        "bstack:options",
        {
            "userName": username,
            "accessKey": access_key,
            "projectName": "Woah iOS Phase 1",
            "buildName": build_name,
            "sessionName": f"Phase 1 YOLO probe - {device_name} iOS {platform_version}",
            "debug": True,
            "networkLogs": True,
        },
    )

    driver = webdriver.Remote(
        "https://hub-cloud.browserstack.com/wd/hub",
        options=options,
    )
    session_id = driver.session_id
    try:
        element = WebDriverWait(driver, 300).until(
            lambda current: current.find_element(AppiumBy.ACCESSIBILITY_ID, ACCESSIBILITY_ID)
        )

        def completed(_: object) -> str | bool:
            for attribute in ("value", "label", "name"):
                raw = element.get_attribute(attribute)
                if isinstance(raw, str) and (
                    raw.startswith(REPORT_PREFIX) or raw.startswith(ERROR_PREFIX)
                ):
                    return raw
            return False

        status = WebDriverWait(driver, 300).until(completed)
        assert isinstance(status, str)
        if status.startswith(ERROR_PREFIX):
            raise RuntimeError(status[len(ERROR_PREFIX) :])
        return json.loads(status[len(REPORT_PREFIX) :]), session_id
    finally:
        driver.quit()


def set_session_status(
    session_id: str,
    username: str,
    access_key: str,
    status: str,
    reason: str,
) -> None:
    import requests

    try:
        requests.put(
            f"https://api-cloud.browserstack.com/app-automate/sessions/{session_id}.json",
            auth=(username, access_key),
            json={"status": status, "reason": reason[:255]},
            timeout=30,
        ).raise_for_status()
    except Exception as error:  # status update must not hide the test result
        print(f"WARNING: could not update BrowserStack session status: {error}", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ipa", type=Path, required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--os-version", required=True)
    parser.add_argument("--build-name", default="Woah iOS Phase 1")
    parser.add_argument("--custom-id", default="WoahPhase1Cloud")
    parser.add_argument(
        "--required-backends",
        default=",".join(DEFAULT_REQUIRED_BACKENDS),
        help="Comma-separated backends that must succeed. Accelerated backends are still recorded when optional.",
    )
    parser.add_argument("--expected-detections", type=int, default=1)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    username = require_env("BROWSERSTACK_USERNAME")
    access_key = require_env("BROWSERSTACK_ACCESS_KEY")
    if not args.ipa.is_file():
        raise SystemExit(f"IPA does not exist: {args.ipa}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    app_url = upload_ipa(args.ipa, username, access_key, args.custom_id)
    print(f"BrowserStack app uploaded: {app_url}")

    session_id = ""
    try:
        report, session_id = collect_device_report(
            app_url=app_url,
            username=username,
            access_key=access_key,
            device_name=args.device,
            platform_version=args.os_version,
            build_name=args.build_name,
        )
        required = tuple(
            item.strip() for item in args.required_backends.split(",") if item.strip()
        )
        evaluation = evaluate_phase1_report(
            report,
            required_backends=required,
            expected_detections=args.expected_detections,
        )
        combined = {
            "browserstack": {
                "app_url": app_url,
                "session_id": session_id,
                "device": args.device,
                "os_version": args.os_version,
            },
            "probe": report,
            "evaluation": evaluation,
        }
        args.output.write_text(json.dumps(combined, indent=2, sort_keys=True), encoding="utf-8")
        if not evaluation["ok"]:
            reason = "; ".join(evaluation["errors"]) or "Phase 1 evaluation failed"
            set_session_status(session_id, username, access_key, "failed", reason)
            print(reason, file=sys.stderr)
            return 1
        set_session_status(session_id, username, access_key, "passed", "Woah Phase 1 probe passed")
        print(json.dumps(evaluation, indent=2, sort_keys=True))
        return 0
    except Exception as error:
        args.output.write_text(
            json.dumps(
                {
                    "browserstack": {
                        "app_url": app_url,
                        "session_id": session_id,
                        "device": args.device,
                        "os_version": args.os_version,
                    },
                    "error": str(error),
                },
                indent=2,
                sort_keys=True,
            ),
            encoding="utf-8",
        )
        if session_id:
            set_session_status(session_id, username, access_key, "failed", str(error))
        raise


if __name__ == "__main__":
    raise SystemExit(main())
