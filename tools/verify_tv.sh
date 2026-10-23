#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist/qa
collect_qa() {
  adb pull /sdcard/Android/data/com.b5solucoes.tcllight/files/qa dist/qa/screens || true
  adb shell dumpsys gfxinfo com.b5solucoes.tcllight > dist/qa/gfxinfo.txt || true
  adb logcat -d -s AndroidRuntime:E > dist/qa/crashes.txt || true
}
trap collect_qa EXIT
adb shell settings put system system_locales pt-BR
gradle :app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true --no-daemon --stacktrace
