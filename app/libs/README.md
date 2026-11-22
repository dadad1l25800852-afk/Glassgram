This folder is intentionally free of binary libraries in the source ZIP.
The APK workflow downloads `libXray.aar` from the OFFICIAL XTLS/libXray
release v26.11.9, logs its SHA-256 and includes it during Gradle build.
Locally, download the same official AAR into this directory before building.
Source and license: https://github.com/XTLS/libXray (MIT).
Do not rename random third-party VPN APKs or AARs as libXray.aar.
