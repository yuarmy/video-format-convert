#!/usr/bin/env bash
# Copyright (C) 2026 yuarmy
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
# SPDX-License-Identifier: GPL-3.0-or-later

# 把回归素材推到设备，并在真机上运行格式矩阵 / 性能测试。
#
# 用法:
#   scripts/run-device-tests.sh [matrix|perf|all]    # 默认 all
#   FORCE=1 scripts/run-device-tests.sh matrix       # 强制重新推送素材
#   ADB=/path/to/adb scripts/run-device-tests.sh     # 指定 adb
#   MEDIA_DIR=/path/to/media scripts/run-device-tests.sh
#
# 前置条件：已安装 Debug APK（./gradlew installDebug）。素材缺少时会自动生成。

set -uo pipefail

# adb 参数里的 /sdcard 不能被 MSYS 转成 Windows 路径。
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ADB="${ADB:-C:/software/platform-tools/adb.exe}"
PKG="com.yuarmy.videoconvert"
REMOTE_DIR="/data/local/tmp/vc-matrix"
MEDIA_DIR="${MEDIA_DIR:-$ROOT/test-media}"
TARGET="${1:-all}"

case "$TARGET" in
  matrix|perf|all) ;;
  *) echo "用法: $0 [matrix|perf|all]" >&2; exit 2 ;;
esac

if ! command -v "$ADB" >/dev/null 2>&1 && [ ! -x "$ADB" ]; then
  echo "找不到 adb: $ADB（可用 ADB=/path/to/adb 指定）" >&2
  exit 1
fi

if [ -z "${JAVA_HOME:-}" ] && [ -x "C:/Program Files/Android/Android Studio/jbr/bin/java.exe" ]; then
  export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
fi

# connectedAndroidTest 结束后会卸载应用，每次都确认一次。
if [ -z "$("$ADB" shell pm path "$PKG" 2>/dev/null | tr -d '\r')" ]; then
  echo "安装 Debug APK..."
  ./gradlew installDebug || exit 1
fi

if [ ! -d "$MEDIA_DIR" ] || [ -z "$(ls -A "$MEDIA_DIR" 2>/dev/null)" ]; then
  echo "素材目录为空，生成测试素材..."
  scripts/generate-test-media.sh "$MEDIA_DIR" || exit 1
fi

if [ "${FORCE:-0}" = "1" ] || [ -z "$("$ADB" shell ls "$REMOTE_DIR" 2>/dev/null | tr -d '\r')" ]; then
  echo "推送素材到设备: $REMOTE_DIR"
  # MSYS_NO_PATHCONV 关掉了自动转换，Windows 版 adb 需要显式给本地路径。
  PUSH_SRC="$MEDIA_DIR"
  if command -v cygpath >/dev/null 2>&1; then
    PUSH_SRC="$(cygpath -m "$MEDIA_DIR")"
  fi
  "$ADB" shell mkdir -p "$REMOTE_DIR"
  "$ADB" push "$PUSH_SRC/." "$REMOTE_DIR/" >/dev/null || exit 1
else
  echo "素材已存在，跳过推送（FORCE=1 可重新推送）"
fi

CLASSES=()
case "$TARGET" in
  matrix) CLASSES=("$PKG.engine.FormatMatrixTest") ;;
  perf)   CLASSES=("$PKG.engine.PerformanceTest") ;;
  all)    CLASSES=("$PKG.engine.FormatMatrixTest" "$PKG.engine.PerformanceTest") ;;
esac
CLASS_ARG="$(IFS=,; echo "${CLASSES[*]}")"

echo "运行真机测试: $CLASS_ARG"
./gradlew connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class="$CLASS_ARG"
