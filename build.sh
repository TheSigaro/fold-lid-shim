#!/usr/bin/env bash
# Builds the debug APK in a container; result: out/app-debug.apk
set -euo pipefail
cd "$(dirname "$0")"
docker build --target apk --output out .
