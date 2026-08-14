#!/bin/sh
set -eu

./gradlew assembleRelease
mkdir -p apk
rm -f apk/ax3000t-flasher-latest.apk
cp app/build/outputs/apk/release/app-release.apk apk/ax3000t-flasher-latest.apk
sha256sum apk/ax3000t-flasher-latest.apk > apk/ax3000t-flasher-latest.apk.sha256
