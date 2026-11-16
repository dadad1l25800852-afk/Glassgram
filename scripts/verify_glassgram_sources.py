#!/usr/bin/env python3
"""Static isolation preflight; run before building GlassGram."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
gradle = (ROOT / "TMessagesProj/build.gradle").read_text()
manifest = (ROOT / "TMessagesProj/src/main/AndroidManifest.xml").read_text()
properties = (ROOT / "gradle.properties").read_text()
errors = []

if not re.search(r"defaultConfig\.applicationId\s*=\s*['\"]app\.glassgram\.android['\"]", gradle):
    errors.append("Gradle applicationId is not the isolated GlassGram package")
if not re.search(r"(?m)^APP_PACKAGE=app\.glassgram\.android\s*$", properties):
    errors.append("APP_PACKAGE must be app.glassgram.android")
if 'android:label="GlassGram"' not in manifest:
    errors.append("Launcher label is not GlassGram")
for name in ("com.google.gms.google-services", "com.google.firebase.crashlytics"):
    if not re.search(r"id\s+['\"]" + re.escape(name) + r"['\"]\s+apply\s+false", gradle):
        errors.append(f"{name} plugin must remain disabled until dedicated credentials exist")
for authority in re.findall(r'android:authorities="([^"]+)"', manifest):
    if not authority.startswith("${applicationId}."):
        errors.append(f"Non-isolated provider authority: {authority}")
if errors:
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    raise SystemExit(1)
print("PASS: GlassGram static package/authority isolation checks")
print("Runtime and final APK identity must still be verified after build.")
