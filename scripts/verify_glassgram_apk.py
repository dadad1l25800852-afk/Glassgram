#!/usr/bin/env python3
"""Fail closed when a GlassGram APK would replace an existing exteraless install.

Usage: python3 scripts/verify_glassgram_apk.py path/to/app.apk
Requires Android SDK aapt or aapt2 in PATH, or ANDROID_HOME/ANDROID_SDK_ROOT.
"""
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

EXPECTED_PACKAGE = "app.glassgram.android"
UNSAFE_PACKAGES = {"com.exteraless.app", "org.telegram.messenger"}


def find_aapt():
    for command in ("aapt",):
        executable = shutil.which(command)
        if executable:
            return executable
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(variable)
        if not root:
            continue
        build_tools = Path(root) / "build-tools"
        if build_tools.is_dir():
            for version in sorted(build_tools.iterdir(), reverse=True):
                binary = version / "aapt"
                if binary.is_file():
                    return str(binary)
    raise RuntimeError("Android SDK aapt not found; cannot verify APK safely")


def main():
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify_glassgram_apk.py APK_PATH")
    apk = Path(sys.argv[1])
    if not apk.is_file():
        raise SystemExit(f"APK does not exist: {apk}")
    result = subprocess.run(
        [find_aapt(), "dump", "badging", str(apk)],
        check=True, capture_output=True, text=True,
    )
    match = re.search(r"^package: name='([^']+)'", result.stdout, re.MULTILINE)
    if not match:
        raise RuntimeError("Could not read final APK applicationId")
    package = match.group(1)
    print(f"APK applicationId: {package}")
    if package in UNSAFE_PACKAGES:
        raise SystemExit(f"BLOCKED: {package} may conflict with an existing Telegram client")
    if package != EXPECTED_PACKAGE:
        raise SystemExit(f"BLOCKED: expected {EXPECTED_PACKAGE}, got {package}")
    print("PASS: isolated GlassGram application ID confirmed")
    print("Note: this does not verify provider authorities, login, or runtime behavior.")


if __name__ == "__main__":
    main()
