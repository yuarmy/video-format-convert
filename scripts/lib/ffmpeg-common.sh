# shellcheck shell=bash
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

# 由 scripts/ffmpeg-download.sh 与 scripts/ffmpeg-build.sh 共享的配置与函数。
# 本文件不直接执行。

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FFMPEG_DIR="$ROOT/ffmpeg"
DOWNLOAD_DIR="$FFMPEG_DIR/downloads"
SRC_DIR="$FFMPEG_DIR/src"
BUILD_DIR="$FFMPEG_DIR/build"
PREBUILT_DIR="$FFMPEG_DIR/prebuilt"

# App 的 minSdk 是 26，所有原生库都按该 API 级别编译。
NDK_API="${NDK_API:-26}"
DEFAULT_ABIS="${DEFAULT_ABIS:-arm64-v8a}"

FFMPEG_VERSION="9.0.2"
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
FFMPEG_SHA256="8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e"

# x264 没有正式发布标签，固定到 stable 分支的提交，保证可复现。
X264_REF="31e19f92f00c7003fa115047ce50978bc98c3a0d"
X264_ARCHIVE="x264-${X264_REF:0:8}.tar.gz"
X264_URL="https://github.com/mirror/x264/archive/$X264_REF.tar.gz"
X264_SHA256="d053c9d86988d6bc78237ca5205865c5ddf99c98ef4cd9927eec8f6d388f6dd9"

X265_VERSION="4.1"
X265_URL="https://bitbucket.org/multicoreware/x265_git/get/$X265_VERSION.tar.gz"
X265_SHA256="7d23cdcdbd510728202c0dfbf7c51eda26a395de2096c504c2b10d6035711102"

LAME_VERSION="3.100"
LAME_URL="https://downloads.sourceforge.net/project/lame/lame/$LAME_VERSION/lame-$LAME_VERSION.tar.gz"
LAME_SHA256="ddfe36cab873794038ae2c1210557ad34857a4b6bdc515785d1da9e175b1da1e"

# 使用 GitHub 归档而不是 xiph.org 发布包：后者在部分 Windows 环境会因
# schannel 吊销检查失败而无法下载。三个项目都自带 CMake 构建与 .pc 生成。
OPUS_VERSION="1.5.2"
OPUS_URL="https://github.com/xiph/opus/archive/refs/tags/v$OPUS_VERSION.tar.gz"
OPUS_SHA256="9480e329e989f70d69886ded470c7f8cfe6c0667cc4196d4837ac9e668fb7404"

OGG_VERSION="1.3.6"
OGG_URL="https://github.com/xiph/ogg/archive/refs/tags/v$OGG_VERSION.tar.gz"
OGG_SHA256="95b643da661155d79db9de2fca55daed3a8d491039829def246aacb3d9201c81"

VORBIS_VERSION="1.3.7"
VORBIS_URL="https://github.com/xiph/vorbis/archive/refs/tags/v$VORBIS_VERSION.tar.gz"
VORBIS_SHA256="270c76933d0934e42c5ee0a54a36280e2d87af1de3cc3e584806357e237afd13"

VPX_VERSION="1.15.2"
VPX_URL="https://storage.googleapis.com/downloads.webmproject.org/releases/webm/libvpx-$VPX_VERSION.tar.gz"
VPX_SHA256="79c99e7038c281e63f4540c51c264efd6ab1a8a005a9221d6e1f77109e6f57b5"

SVT_AV1_VERSION="3.1.2"
SVT_AV1_URL="https://github.com/AOMediaCodec/SVT-AV1/archive/refs/tags/v$SVT_AV1_VERSION.tar.gz"
SVT_AV1_SHA256="f1ec3d800156d10356bdc10a7a38a81dd427c76edc3ef15dcaa7e37edfc4774f"

log()  { printf '  %s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }
warn() { printf '警告: %s\n' "$*" >&2; }
die()  { printf '错误: %s\n' "$*" >&2; exit 1; }

# MSYS/Cygwin 下把 POSIX 路径转成 C:/... 形式，交给 CMake 等原生程序时更稳。
host_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s\n' "$1"
    fi
}

# PATH 只能放 POSIX 形式，否则 C:/... 里的冒号会被当成路径分隔符。
posix_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -u "$1"
    else
        printf '%s\n' "$1"
    fi
}

# 供 make 的 SHELL 变量使用：Windows 下取 8.3 短路径，避免 "Program Files" 里的空格。
short_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -d "$1" | sed 's|\\|/|g'
    else
        printf '%s\n' "$1"
    fi
}

jobs_count() {
    if command -v nproc >/dev/null 2>&1; then
        nproc
    elif [ -n "${NUMBER_OF_PROCESSORS:-}" ]; then
        printf '%s\n' "$NUMBER_OF_PROCESSORS"
    else
        printf '4\n'
    fi
}

version_gt() {
    [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -1)" = "$1" ]
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

verify_sha256() {
    [ "$(sha256_of "$1")" = "$2" ]
}

download_url() {
    local url="$1" out="$2"
    if command -v curl >/dev/null 2>&1; then
        curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$out" "$url"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "$out" "$url"
    else
        die "需要 curl 或 wget 才能下载源码"
    fi
}

# 运行命令并写日志；失败时打印尾部日志。
run_logged() {
    local log_file="$1"
    shift
    mkdir -p "$(dirname "$log_file")"
    "$@" >"$log_file" 2>&1 && return 0
    local rc=$?
    printf '\n命令失败（退出码 %s）: %s\n' "$rc" "$*" >&2
    printf '日志: %s\n' "$log_file" >&2
    tail -n 30 "$log_file" >&2
    exit "$rc"
}

# 在指定目录中运行命令。
build_in() {
    local dir="$1"
    shift
    ( cd "$dir" && "$@" )
}

# 从 local.properties / 环境变量 / 默认位置定位 NDK，取 r28+ 的最高版本。
find_ndk() {
    [ -n "${NDK:-}" ] && [ -d "$NDK" ] && { printf '%s\n' "$NDK"; return 0; }

    local sdk_dirs=()
    if [ -f "$ROOT/local.properties" ]; then
        local sdk
        sdk=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -1 \
            | sed -e 's/\\\\/\//g' -e 's/\\:/:/g' -e 's/\\/\//g')
        [ -n "$sdk" ] && sdk_dirs+=("$sdk")
    fi
    [ -n "${ANDROID_HOME:-}" ] && sdk_dirs+=("$ANDROID_HOME")
    [ -n "${ANDROID_SDK_ROOT:-}" ] && sdk_dirs+=("$ANDROID_SDK_ROOT")
    if [ -n "${LOCALAPPDATA:-}" ]; then
        sdk_dirs+=("$LOCALAPPDATA/Android/Sdk")
    fi
    sdk_dirs+=("$HOME/Android/Sdk")

    local best="" best_ver="" sdk_dir ndk_dir rev
    for sdk_dir in "${sdk_dirs[@]}"; do
        [ -d "$sdk_dir/ndk" ] || continue
        for ndk_dir in "$sdk_dir"/ndk/*; do
            [ -f "$ndk_dir/source.properties" ] || continue
            rev=$(sed -n 's/^Pkg\.Revision *= *//p' "$ndk_dir/source.properties" | head -1)
            case "$rev" in
                [0-9]*)
                    if [ "${rev%%.*}" -lt 28 ] 2>/dev/null; then
                        continue
                    fi
                    if [ -z "$best_ver" ] || version_gt "$rev" "$best_ver"; then
                        best="$ndk_dir"
                        best_ver="$rev"
                    fi
                    ;;
            esac
        done
    done
    [ -n "$best" ] && printf '%s\n' "$best"
}

# 每个 ABI 的 FFmpeg / 编译器参数。
abi_config() {
    ABI="$1"
    case "$ABI" in
        arm64-v8a)
            FF_ARCH="aarch64"; FF_CPU="armv8-a"
            CLANG_TRIPLE="aarch64-linux-android"
            HOST_TRIPLE="aarch64-linux-android"
            VPX_TARGET="arm64-android-gcc"
            ABI_CFLAGS=""
            ;;
        armeabi-v7a)
            FF_ARCH="arm"; FF_CPU="armv7-a"
            CLANG_TRIPLE="armv7a-linux-androideabi"
            HOST_TRIPLE="arm-linux-androideabi"
            VPX_TARGET="armv7-android-gcc"
            ABI_CFLAGS="-march=armv7-a -mfpu=neon -mfloat-abi=softfp -mthumb"
            ;;
        x86_64)
            FF_ARCH="x86_64"; FF_CPU="x86-64"
            CLANG_TRIPLE="x86_64-linux-android"
            HOST_TRIPLE="x86_64-linux-android"
            VPX_TARGET="x86_64-android-gcc"
            ABI_CFLAGS=""
            ;;
        x86)
            FF_ARCH="x86"; FF_CPU="i686"
            CLANG_TRIPLE="i686-linux-android"
            HOST_TRIPLE="i686-linux-android"
            VPX_TARGET="x86-android-gcc"
            ABI_CFLAGS=""
            ;;
        *)
            die "不支持的 ABI: $ABI（可选 arm64-v8a / armeabi-v7a / x86_64 / x86）"
            ;;
    esac
    ABI_IS_ARM64=0
    ABI_IS_ARM32=0
    ABI_IS_X86=0
    case "$ABI" in
        arm64-v8a) ABI_IS_ARM64=1 ;;
        armeabi-v7a) ABI_IS_ARM32=1 ;;
        x86_64|x86) ABI_IS_X86=1 ;;
    esac
}

host_tag() {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) printf 'windows-x86_64\n' ;;
        Linux*)               printf 'linux-x86_64\n' ;;
        Darwin*)              printf 'darwin-x86_64\n' ;;
        *)                    die "不认识的主机平台: $(uname -s)" ;;
    esac
}

# 为当前 ABI 导出编译器、归档工具与公共编译参数。
setup_toolchain() {
    abi_config "$1"
    HOST_TAG="$(host_tag)"
    TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
    [ -d "$TOOLCHAIN" ] || die "NDK 缺少 $HOST_TAG 工具链: $TOOLCHAIN"

    SYSROOT="$TOOLCHAIN/sysroot"
    CC="$TOOLCHAIN/bin/${CLANG_TRIPLE}${NDK_API}-clang"
    CXX="$TOOLCHAIN/bin/${CLANG_TRIPLE}${NDK_API}-clang++"
    AR="$TOOLCHAIN/bin/llvm-ar"
    NM="$TOOLCHAIN/bin/llvm-nm"
    RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    STRIP="$TOOLCHAIN/bin/llvm-strip"
    [ -x "$CC" ] || die "找不到目标编译器: $CC"

    PREFIX="$PREBUILT_DIR/$ABI"
    PREFIX_WIN="$(host_path "$PREFIX")"
    TOOLCHAIN_WIN="$(host_path "$TOOLCHAIN")"
    SYSROOT_WIN="$(host_path "$SYSROOT")"
    NDK_WIN="$(host_path "$NDK")"

    export CC CXX AR NM RANLIB STRIP
    export AS="$CC"
    export LD="$CC"
    # 个别项目（如 SVT-AV1）会以裸命令名调用 llvm-ar / llvm-ranlib。
    export PATH="$(posix_path "$TOOLCHAIN")/bin:$PATH"
    export CFLAGS="-O3 -fPIC -DANDROID $ABI_CFLAGS"
    export CXXFLAGS="$CFLAGS"
    # 16KB 页对齐：最终打包进 APK 的 .so 必须带此链接参数（Android 15+ 要求）。
    export LDFLAGS="-Wl,-z,max-page-size=16384"

    LOG_DIR="$BUILD_DIR/logs/$ABI"
    mkdir -p "$PREFIX/lib" "$PREFIX/include" "$LOG_DIR"
}

# 优先选 3.x：x265 4.1 显式把 CMP0025/CMP0054 设为 OLD，CMake 4 已拒绝这种写法。
find_cmake() {
    if [ -n "${CMAKE:-}" ]; then
        printf '%s\n' "$CMAKE"
        return 0
    fi
    local sdk_root="$ROOT"
    [ -n "${NDK:-}" ] && sdk_root="$(cd "$NDK/../.." 2>/dev/null && pwd)"
    local candidates=() cm version
    if command -v cmake >/dev/null 2>&1; then
        candidates+=("$(command -v cmake)")
    fi
    while IFS= read -r cm; do
        [ -n "$cm" ] && candidates+=("$cm")
    done < <(find "$sdk_root/cmake" -maxdepth 4 -type f -name cmake.exe 2>/dev/null | sort -V)
    while IFS= read -r cm; do
        [ -n "$cm" ] && candidates+=("$cm")
    done < <(find "$sdk_root/cmake" -maxdepth 4 -type f -name cmake 2>/dev/null | sort -V)

    local legacy="" newest=""
    for cm in "${candidates[@]}"; do
        version=$("$cm" --version 2>/dev/null | awk 'NR==1 {print $3}')
        [ -n "$version" ] || continue
        newest="$cm"
        case "$version" in
            [0-3].*) legacy="$cm" ;;
        esac
    done
    if [ -n "$legacy" ]; then
        printf '%s\n' "$legacy"
    elif [ -n "$newest" ]; then
        printf '%s\n' "$newest"
    fi
}

find_ninja() {
    if [ -n "${NINJA:-}" ]; then
        printf '%s\n' "$NINJA"
        return 0
    fi
    # 与 CMake 同目录的 ninja 版本最匹配，优先使用。
    if [ -n "${CMAKE:-}" ]; then
        local dir nj
        dir="$(dirname "$CMAKE")"
        for nj in "$dir/ninja.exe" "$dir/ninja"; do
            if [ -x "$nj" ]; then
                printf '%s\n' "$nj"
                return 0
            fi
        done
    fi
    if command -v ninja >/dev/null 2>&1; then
        command -v ninja
        return 0
    fi
    local sdk_root="$ROOT"
    [ -n "${NDK:-}" ] && sdk_root="$(cd "$NDK/../.." 2>/dev/null && pwd)"
    local nj
    nj=$(find "$sdk_root/cmake" -maxdepth 4 -type f -name ninja.exe 2>/dev/null | sort -V | tail -1)
    [ -z "$nj" ] && nj=$(find "$sdk_root/cmake" -maxdepth 4 -type f -name ninja 2>/dev/null | sort -V | tail -1)
    [ -n "$nj" ] && printf '%s\n' "$nj"
}

# FFmpeg 的 tablegen 代码生成器要在构建机上运行，所以交叉编译仍然需要
# 原生 C 编译器。NDK 的 clang 默认目标是 windows-gnu 但缺 mingw 运行库，
# 链接会失败，因此必须实际编译并链接一个空程序来验证。
host_cc_works() {
    local out="$BUILD_DIR/.hostcc-test.exe"
    mkdir -p "$BUILD_DIR"
    printf 'int main(void){return 0;}\n' | $1 -x c - -o "$out" >/dev/null 2>&1
    local rc=$?
    rm -f "$out"
    return $rc
}

find_host_cc() {
    if [ -n "${HOSTCC:-}" ]; then
        printf '%s\n' "$HOSTCC"
        return 0
    fi
    local cc
    for cc in gcc cc clang cl; do
        cc="$(command -v "$cc" 2>/dev/null)" || continue
        host_cc_works "$cc" && { printf '%s\n' "$cc"; return 0; }
    done
    # 便携工具链放在 ffmpeg/toolchain/（已忽略），配好一次即可复用。
    for cc in "$FFMPEG_DIR"/toolchain/*/bin/gcc.exe "$FFMPEG_DIR"/toolchain/*/bin/gcc; do
        [ -x "$cc" ] || continue
        host_cc_works "$cc" && { printf '%s\n' "$cc"; return 0; }
    done
}

find_make() {
    if [ -n "${MAKE:-}" ]; then
        printf '%s\n' "$MAKE"
        return 0
    fi
    local m
    if command -v make >/dev/null 2>&1; then
        command -v make
        return 0
    fi
    m="$NDK/prebuilt/$HOST_TAG/bin/make.exe"
    [ -x "$m" ] && printf '%s\n' "$m"
}

# 优先系统 pkg-config；缺失时退回仓库内的最小实现（Windows Git Bash 常见）。
resolve_pkg_config() {
    local pc=""
    if [ -n "${PKG_CONFIG:-}" ]; then
        pc="$PKG_CONFIG"
    elif command -v pkg-config >/dev/null 2>&1; then
        pc="$(command -v pkg-config)"
    else
        local candidate
        for candidate in /usr/bin/pkg-config /usr/local/bin/pkg-config \
                         /ucrt64/bin/pkg-config /mingw64/bin/pkg-config; do
            if [ -x "$candidate" ]; then
                pc="$candidate"
                break
            fi
        done
    fi
    if [ -z "$pc" ]; then
        pc="$ROOT/scripts/lib/pkg-config-shim"
        chmod +x "$pc" 2>/dev/null || true
    fi
    PKG_CONFIG="$pc"
    export PKG_CONFIG
    # 只搜索当前 ABI 的 prefix，避免误用主机的 .pc 文件。
    export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
    export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig"
}
