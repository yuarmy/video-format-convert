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

# 为 Android 交叉编译 GPL 版 FFmpeg 及编码依赖，产物是各 ABI 的静态库。
#
# 用法:
#   scripts/ffmpeg-build.sh
#   ABIS="arm64-v8a armeabi-v7a" scripts/ffmpeg-build.sh
#   CLEAN=1 scripts/ffmpeg-build.sh          # 清掉所选 ABI 的构建目录与产物
#   FORCE=1 scripts/ffmpeg-build.sh          # 忽略已存在的库，全部重编
#   WITH_AV1=0 scripts/ffmpeg-build.sh       # 不编 SVT-AV1（无 AV1 编码）
#   DEPS_ONLY=1 scripts/ffmpeg-build.sh      # 只编依赖，不编 FFmpeg
#   FFMPEG_ONLY=1 scripts/ffmpeg-build.sh    # 只编 FFmpeg
#   NO_DOWNLOAD=1 scripts/ffmpeg-build.sh    # 源码缺失时不自动下载
#   JOBS=8 scripts/ffmpeg-build.sh           # 并行度
#
# 依赖 NDK r28+（16KB 页对齐要求）。脚本会自动定位 NDK、CMake、Ninja 与 make；
# 主机没有 pkg-config 时使用 scripts/lib/pkg-config-shim。
#
# 产物:
#   ffmpeg/prebuilt/<abi>/include/   头文件
#   ffmpeg/prebuilt/<abi>/lib/*.a    静态库（最后由 JNI 层链接成 libffmpeg.so）
#   ffmpeg/prebuilt/<abi>/BUILD_INFO.txt

set -euo pipefail

# shellcheck source=lib/ffmpeg-common.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/ffmpeg-common.sh"

ABIS="${ABIS:-$DEFAULT_ABIS}"
JOBS="${JOBS:-$(jobs_count)}"
FORCE="${FORCE:-0}"
CLEAN="${CLEAN:-0}"
WITH_AV1="${WITH_AV1:-1}"
DEPS_ONLY="${DEPS_ONLY:-0}"
FFMPEG_ONLY="${FFMPEG_ONLY:-0}"
NO_DOWNLOAD="${NO_DOWNLOAD:-0}"

NDK="$(find_ndk)"
[ -n "$NDK" ] || die "找不到 NDK r28+。设置 NDK=/path/to/ndk，或把 sdk.dir 写入 local.properties。"
NDK_WIN="$(host_path "$NDK")"
HOST_TAG="$(host_tag)"
[ -d "$NDK/toolchains/llvm/prebuilt/$HOST_TAG" ] || \
    die "NDK 缺少 $HOST_TAG 工具链（当前 NDK 可能只装了 Windows 主机工具链）"

CMAKE="$(find_cmake)"
NINJA="$(find_ninja)"
MAKE="$(find_make)"
[ -n "$CMAKE" ] || die "找不到 cmake（可在环境变量 CMAKE 指定）"
[ -n "$NINJA" ] || die "找不到 ninja（可在环境变量 NINJA 指定）"
[ -n "$MAKE" ] || die "找不到 make（NDK r28 自带 GNU make；也可设置 MAKE）"

# Windows 版 make 会把 /bin/sh 解析成 "C:/Program Files/Git/usr/bin/sh.exe"，
# 带空格的路径在 recipe 里不加引号会直接失败，所以显式给一个短路径。
MAKE_SHELL_ARGS=(MAKE="$MAKE")
if command -v sh >/dev/null 2>&1; then
    MAKE_SHELL_ARGS+=(SHELL="$(short_path "$(command -v sh)")")
fi

CMAKE_VERSION="$("$CMAKE" --version | awk 'NR==1 {print $3}')"
CMAKE_MAJOR="${CMAKE_VERSION%%.*}"
CMAKE_POLICY_ARGS=()
if [ "${CMAKE_MAJOR:-0}" -ge 4 ] 2>/dev/null; then
    # CMake 4 移除了 <3.5 兼容；x265/libvorbis 的 CMakeLists 仍声明 2.8。
    CMAKE_POLICY_ARGS=(-DCMAKE_POLICY_VERSION_MINIMUM=3.5)
fi

case "$ROOT" in
    *" "*) warn "仓库路径包含空格，FFmpeg / x264 的构建可能失败" ;;
esac

printf 'NDK   : %s\n' "$NDK"
printf 'CMake : %s (%s)\n' "$CMAKE" "$CMAKE_VERSION"
printf 'Ninja : %s\n' "$NINJA"
printf 'Make  : %s\n' "$MAKE"
printf 'ABI   : %s\n' "$ABIS"
printf '并行  : %s\n' "$JOBS"

if [ "$NO_DOWNLOAD" != "1" ] && [ ! -d "$SRC_DIR/ffmpeg" ]; then
    step "源码缺失，先执行下载"
    WITH_AV1="$WITH_AV1" "$ROOT/scripts/ffmpeg-download.sh"
fi

for component in ffmpeg x264 x265 lame opus ogg vorbis vpx; do
    [ -d "$SRC_DIR/$component" ] || die "缺少源码 $SRC_DIR/$component，先运行 scripts/ffmpeg-download.sh"
done
if [ "$WITH_AV1" = "1" ]; then
    [ -d "$SRC_DIR/svt-av1" ] || die "缺少源码 $SRC_DIR/svt-av1，先运行 scripts/ffmpeg-download.sh"
fi

have_lib() { [ -f "$PREFIX/lib/$1" ]; }

skip_if_built() {
    local lib="$1"
    if [ "$FORCE" != "1" ] && have_lib "$lib"; then
        log "已构建，跳过"
        return 0
    fi
    return 1
}

cmake_build() {
    local name="$1" src="$2" bld="$3"
    shift 3
    rm -rf "$bld"
    mkdir -p "$bld"

    local neon_args=()
    [ "$ABI_IS_ARM32" = 1 ] && neon_args=(-DANDROID_ARM_NEON=ON)

    run_logged "$LOG_DIR/$name-configure.log" \
        "$CMAKE" -S "$src" -B "$bld" -G Ninja \
            -DCMAKE_MAKE_PROGRAM="$NINJA" \
            -DCMAKE_TOOLCHAIN_FILE="$NDK_WIN/build/cmake/android.toolchain.cmake" \
            -DANDROID_NDK="$NDK_WIN" \
            -DANDROID_ABI="$ABI" \
            -DANDROID_PLATFORM="android-$NDK_API" \
            -DANDROID_STL=c++_static \
            -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_INSTALL_PREFIX="$PREFIX_WIN" \
            -DCMAKE_INSTALL_LIBDIR=lib \
            -DCMAKE_PREFIX_PATH="$PREFIX_WIN" \
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
            ${CMAKE_POLICY_ARGS[@]+"${CMAKE_POLICY_ARGS[@]}"} \
            ${neon_args[@]+"${neon_args[@]}"} \
            "$@"
    run_logged "$LOG_DIR/$name-build.log" \
        "$CMAKE" --build "$bld" --target install --parallel "$JOBS"
}

autotools_build() {
    local name="$1" src="$2" bld="$3"
    shift 3
    rm -rf "$bld"
    mkdir -p "$bld"
    # 传 Windows 路径，否则原生 make / clang 无法解析 MSYS 的 /c/...
    run_logged "$LOG_DIR/$name-configure.log" \
        build_in "$bld" "$(host_path "$src")/configure" --prefix="$PREFIX_WIN" "$@"
    run_logged "$LOG_DIR/$name-build.log" \
        build_in "$bld" "$MAKE" -j"$JOBS" ${MAKE_SHELL_ARGS[@]+"${MAKE_SHELL_ARGS[@]}"}
    run_logged "$LOG_DIR/$name-install.log" \
        build_in "$bld" "$MAKE" install ${MAKE_SHELL_ARGS[@]+"${MAKE_SHELL_ARGS[@]}"}
}

build_ogg() {
    step "libogg $OGG_VERSION"
    skip_if_built libogg.a && return
    cmake_build ogg "$SRC_DIR/ogg" "$BUILD_DIR/$ABI/ogg" \
        -DBUILD_SHARED_LIBS=OFF \
        -DINSTALL_DOCS=OFF \
        -DINSTALL_CMAKE_PACKAGE_MODULE=ON
}

# libvorbis 的 .pc 没有把 -lm 写进 Libs.private，静态链接探测会缺数学符号。
patch_vorbis_pc() {
    local pc="$PREFIX/lib/pkgconfig/vorbis.pc"
    [ -f "$pc" ] || return 0
    grep -q '^Libs.private: .*-lm' "$pc" && return 0
    sed -i 's|^Libs.private:.*|Libs.private: -lm|' "$pc"
}

build_vorbis() {
    step "libvorbis $VORBIS_VERSION"
    patch_vorbis_pc
    skip_if_built libvorbis.a && return
    cmake_build vorbis "$SRC_DIR/vorbis" "$BUILD_DIR/$ABI/vorbis" \
        -DBUILD_SHARED_LIBS=OFF \
        -DINSTALL_CMAKE_PACKAGE_MODULE=OFF \
        -DOGG_INCLUDE_DIR="$PREFIX_WIN/include" \
        -DOGG_LIBRARY="$PREFIX_WIN/lib/libogg.a"
    patch_vorbis_pc
}

build_opus() {
    step "Opus $OPUS_VERSION"
    skip_if_built libopus.a && return
    cmake_build opus "$SRC_DIR/opus" "$BUILD_DIR/$ABI/opus" \
        -DBUILD_SHARED_LIBS=OFF \
        -DOPUS_BUILD_PROGRAMS=OFF \
        -DOPUS_BUILD_TESTING=OFF \
        -DOPUS_INSTALL_CMAKE_CONFIG_MODULE=OFF
}

build_lame() {
    step "LAME $LAME_VERSION"
    skip_if_built libmp3lame.a && return
    autotools_build lame "$SRC_DIR/lame" "$BUILD_DIR/$ABI/lame" \
        --host="$HOST_TRIPLE" \
        --disable-shared --enable-static \
        --disable-frontend --disable-decoder \
        --disable-dependency-tracking --with-pic
}

build_x264() {
    step "x264 (${X264_REF:0:12})"
    skip_if_built libx264.a && return
    local bld="$BUILD_DIR/$ABI/x264"
    rm -rf "$bld"
    mkdir -p "$bld"
    run_logged "$LOG_DIR/x264-configure.log" \
        build_in "$bld" "$(host_path "$SRC_DIR/x264")/configure" \
            --prefix="$PREFIX_WIN" \
            --host="$HOST_TRIPLE" \
            --cross-prefix="$TOOLCHAIN_WIN/bin/llvm-" \
            --enable-static --enable-pic \
            --disable-cli --disable-opencl \
            --disable-avs --disable-swscale --disable-lavf \
            --disable-ffms --disable-gpac \
            --extra-cflags="$CFLAGS"
    # x264 的依赖生成规则是一条超长单行命令，MSYS 下会被截断；空文件即可跳过，
    # 头文件改动在一次性构建里无所谓。
    : >"$bld/.depend"
    run_logged "$LOG_DIR/x264-build.log" \
        build_in "$bld" "$MAKE" -j"$JOBS" ${MAKE_SHELL_ARGS[@]+"${MAKE_SHELL_ARGS[@]}"}
    run_logged "$LOG_DIR/x264-install.log" \
        build_in "$bld" "$MAKE" install ${MAKE_SHELL_ARGS[@]+"${MAKE_SHELL_ARGS[@]}"}
}

# 增量构建时 build_x265 会被跳过，所以 .pc 修补必须在跳过判断之前完成。
patch_x265_pc() {
    local pc="$PREFIX/lib/pkgconfig/x265.pc"
    [ -f "$pc" ] || return 0
    grep -q '^Libs.private: -lc++_static' "$pc" && return 0
    # x265 从 CMAKE_CXX_IMPLICIT_LINK_LIBRARIES 生成 -l-l:libunwind.a 这类非法参数；
    # 重写为可用的静态 C++ 运行时，FFmpeg 的 pkg-config 探测会直接使用它。
    sed -i 's|^Libs.private:.*|Libs.private: -lc++_static -lc++abi -lm -ldl|' "$pc"
}

build_x265() {
    step "x265 $X265_VERSION（8-bit）"
    patch_x265_pc
    skip_if_built libx265.a && return
    # x265 的 ARM64 汇编规则直接调用 ${CMAKE_CXX_COMPILER}，绕过了 NDK 注入的
    # --target，交叉编译时 clang 会按主机 CPU 汇编而失败。关闭汇编换可构建性。
    cmake_build x265 "$SRC_DIR/x265/source" "$BUILD_DIR/$ABI/x265" \
        -DENABLE_SHARED=OFF \
        -DENABLE_CLI=OFF \
        -DENABLE_ASSEMBLY=OFF \
        -DENABLE_LIBNUMA=OFF \
        -DENABLE_SVT_HEVC=OFF \
        -DENABLE_HDR10_PLUS=OFF \
        -DHIGH_BIT_DEPTH=OFF \
        -DEXPORT_C_API=ON
    patch_x265_pc
}

build_vpx() {
    step "libvpx $VPX_VERSION"
    skip_if_built libvpx.a && return
    if [ "$ABI_IS_X86" = 1 ] && ! command -v nasm >/dev/null 2>&1; then
        die "x86/x86_64 ABI 需要 nasm 来编译 libvpx 汇编"
    fi
    local bld="$BUILD_DIR/$ABI/vpx"
    rm -rf "$bld"
    mkdir -p "$bld"
    run_logged "$LOG_DIR/vpx-configure.log" \
        build_in "$bld" "$(host_path "$SRC_DIR/vpx")/configure" \
            --target="$VPX_TARGET" \
            --prefix="$PREFIX_WIN" \
            --disable-shared --enable-static \
            --disable-examples --disable-tools --disable-docs \
            --disable-unit-tests --disable-decode-perf-tests \
            --disable-encode-perf-tests --disable-webm-io --disable-libyuv \
            --enable-vp9-highbitdepth \
            --extra-cflags="$CFLAGS" \
            --extra-cxxflags="$CXXFLAGS"
    run_logged "$LOG_DIR/vpx-build.log" \
        build_in "$bld" "$MAKE" -j"$JOBS" ${MAKE_SHELL_ARGS[@]+"${MAKE_SHELL_ARGS[@]}"}
    run_logged "$LOG_DIR/vpx-install.log" \
        build_in "$bld" "$MAKE" install ${MAKE_SHELL_ARGS[@]+"${MAKE_SHELL_ARGS[@]}"}
}

# SVT-AV1 3.1 生成的 .pc 少了 -lm，FFmpeg 的 pkg-config 链接探测会因
# exp/pow/log 未定义而失败。同样要在跳过判断之前修补。
patch_svt_av1_pc() {
    local pc="$PREFIX/lib/pkgconfig/SvtAv1Enc.pc"
    [ -f "$pc" ] || return 0
    grep -q '^Libs.private: .*-lm' "$pc" && return 0
    sed -i 's|^Libs.private:.*|Libs.private: -lm|' "$pc"
}

build_svt_av1() {
    step "SVT-AV1 $SVT_AV1_VERSION"
    patch_svt_av1_pc
    skip_if_built libSvtAv1Enc.a && return
    local c_only="OFF"
    [ "$ABI_IS_ARM64" = 1 ] || c_only="ON"
    cmake_build svt-av1 "$SRC_DIR/svt-av1" "$BUILD_DIR/$ABI/svt-av1" \
        -DBUILD_SHARED_LIBS=OFF \
        -DBUILD_APPS=OFF \
        -DBUILD_TESTING=OFF \
        -DCOMPILE_C_ONLY="$c_only" \
        -DSVT_AV1_LTO=OFF \
        -DNATIVE=OFF
    patch_svt_av1_pc
}

build_ffmpeg() {
    step "FFmpeg $FFMPEG_VERSION"
    if [ "$FORCE" != "1" ] && [ -f "$PREFIX/lib/libavcodec.a" ]; then
        log "已构建，跳过"
        return
    fi

    resolve_pkg_config
    log "pkg-config: $PKG_CONFIG"

    # FFMPEG_ONLY=1 时依赖不会重新构建，.pc 修补也不会执行，必须在这里补上。
    patch_vorbis_pc
    patch_x265_pc
    if [ "$WITH_AV1" = "1" ]; then
        patch_svt_av1_pc
    fi

    local host_cc
    host_cc="$(find_host_cc)"
    [ -n "$host_cc" ] || die "找不到可用的主机 C 编译器。设置 HOSTCC=/path/to/gcc，或把便携版 gcc 放到 ffmpeg/toolchain/。"
    log "host cc: $host_cc"

    # Windows 的 TEMP 是 C:\... 形式，MSYS 的 sh 会把反斜杠当转义吃掉，
    # configure 的临时文件 sanity test 因此失败。改用构建目录下的 POSIX 路径。
    export TMPDIR="$(posix_path "$BUILD_DIR/tmp")"
    mkdir -p "$TMPDIR"

    local bld="$BUILD_DIR/$ABI/ffmpeg"
    rm -rf "$bld"
    mkdir -p "$bld"

    local asm_flag=()
    if [ "$ABI_IS_ARM32" = 1 ]; then
        # 32 位 ARM 汇编需要 gas-preprocessor.pl（FFmpeg 9 未内置），关掉更稳。
        asm_flag=(--disable-asm)
    elif [ "$ABI_IS_X86" = 1 ] && ! command -v nasm >/dev/null 2>&1; then
        asm_flag=(--disable-asm)
    fi

    local extra=()
    local -a lib_args=(
        --enable-libx264
        --enable-libx265
        --enable-libmp3lame
        --enable-libopus
        --enable-libvorbis
        --enable-libvpx
    )
    if [ "$WITH_AV1" = "1" ]; then
        lib_args+=(--enable-libsvtav1)
    fi

    run_logged "$LOG_DIR/ffmpeg-configure.log" \
        build_in "$bld" "$(host_path "$SRC_DIR/ffmpeg")/configure" \
            --prefix="$PREFIX_WIN" \
            --target-os=android \
            --arch="$FF_ARCH" \
            --cpu="$FF_CPU" \
            --enable-cross-compile \
            --sysroot="$SYSROOT_WIN" \
            --host-cc="$host_cc" \
            --cc="$CC" \
            --cxx="$CXX" \
            --ar="$AR" \
            --nm="$NM" \
            --ranlib="$RANLIB" \
            --strip="$STRIP" \
            --enable-static --disable-shared --enable-pic \
            --disable-autodetect --disable-network \
            --disable-programs --disable-doc \
            --disable-avdevice \
            --disable-debug --disable-symver \
            --disable-iconv --disable-zlib --disable-bzlib --disable-lzma \
            --enable-gpl --enable-version3 \
            "${lib_args[@]}" \
            ${asm_flag[@]+"${asm_flag[@]}"} \
            "--pkg-config=$PKG_CONFIG" \
            --pkg-config-flags=--static \
            "--extra-libs=-lc++_static -lc++abi" \
            "--extra-cflags=-I$PREFIX_WIN/include $CFLAGS" \
            "--extra-cxxflags=-I$PREFIX_WIN/include $CXXFLAGS" \
            "--extra-ldflags=-L$PREFIX_WIN/lib $LDFLAGS" \
            ${extra[@]+"${extra[@]}"}

    # configure 记录的 SRC_PATH 是 MSYS 的 /c/... 形式，Windows 版 make 无法据此
    # 定位源文件（"No rule to make target"）。改成 C:/... 后 shell 与 clang 都能识别。
    local src_win
    src_win="$(host_path "$SRC_DIR/ffmpeg")"
    sed -i "s|^SRC_PATH=.*|SRC_PATH=$src_win|; s|^SRC_LINK=.*|SRC_LINK=$src_win|" \
        "$bld/ffbuild/config.mak"
    sed -i "1s|.*|include $src_win/Makefile|" "$bld/Makefile"

    run_logged "$LOG_DIR/ffmpeg-build.log" \
        build_in "$bld" "$MAKE" -j"$JOBS" ${MAKE_SHELL_ARGS[@]+"${MAKE_SHELL_ARGS[@]}"}
    # 非 verbose 模式会把 INSTALL 换成“printf 所有文件名; install ...”，头文件多时
    # 整条命令行超过 MSYS shell 上限被截断；命令行传入 INSTALL=install 可跳过包装。
    run_logged "$LOG_DIR/ffmpeg-install.log" \
        build_in "$bld" "$MAKE" install INSTALL=install \
            ${MAKE_SHELL_ARGS[@]+"${MAKE_SHELL_ARGS[@]}"}
}

# fftools 编译时需要 configure 生成的 config.h / ffversion.h，它们不随
# make install 安装。复制到 prebuilt/include，这样清掉 build 目录后 JNI 仍能构建。
copy_generated_headers() {
    local bld="$BUILD_DIR/$ABI/ffmpeg"
    [ -f "$bld/config.h" ] || return 0
    cp -f "$bld/config.h" "$PREFIX/include/config.h"
    [ -f "$bld/config_components.h" ] && \
        cp -f "$bld/config_components.h" "$PREFIX/include/config_components.h"
    if [ -f "$bld/libavutil/ffversion.h" ]; then
        mkdir -p "$PREFIX/include/libavutil"
        cp -f "$bld/libavutil/ffversion.h" "$PREFIX/include/libavutil/ffversion.h"
    fi
}

write_build_info() {
    local out="$PREBUILT_DIR/$ABI/BUILD_INFO.txt"
    {
        echo "# 由 scripts/ffmpeg-build.sh 生成于 $(date -u +%Y-%m-%dT%H:%M:%SZ)"
        echo "abi=$ABI"
        echo "ndk_api=$NDK_API"
        echo "ndk=$NDK"
        echo "ffmpeg=$FFMPEG_VERSION"
        echo "x264=${X264_REF:0:12}"
        echo "x265=$X265_VERSION (8-bit)"
        echo "lame=$LAME_VERSION"
        echo "opus=$OPUS_VERSION"
        echo "ogg=$OGG_VERSION"
        echo "vorbis=$VORBIS_VERSION"
        echo "vpx=$VPX_VERSION"
        if [ "$WITH_AV1" = "1" ]; then
            echo "svt-av1=$SVT_AV1_VERSION"
        else
            echo "svt-av1=disabled"
        fi
        echo "link_flags=-Wl,-z,max-page-size=16384"
        echo "system_libs=-lm -ldl -llog -landroid"
        echo "cxx_runtime=c++_static"
        echo
        echo "# 静态库"
        find "$PREFIX/lib" -maxdepth 1 -name '*.a' -printf '%f\n' 2>/dev/null | sort | sed 's/^/lib=/'
    } >"$out"
    log "构建信息: $out"
}

for abi in $ABIS; do
    setup_toolchain "$abi"
    printf '\n######## %s ########\n' "$abi"

    if [ "$CLEAN" = "1" ]; then
        log "清理 $BUILD_DIR/$abi 与 $PREBUILT_DIR/$abi"
        rm -rf "$BUILD_DIR/$abi" "$PREBUILT_DIR/$abi"
        mkdir -p "$PREFIX/lib" "$PREFIX/include" "$LOG_DIR"
    fi

    if [ "$FFMPEG_ONLY" != "1" ]; then
        build_ogg
        build_vorbis
        build_opus
        build_lame
        build_x264
        build_x265
        build_vpx
        if [ "$WITH_AV1" = "1" ]; then
            build_svt_av1
        fi
    fi

    if [ "$DEPS_ONLY" != "1" ]; then
        build_ffmpeg
        copy_generated_headers
    fi

    write_build_info
done

printf '\n完成。产物目录: %s/<abi>/\n' "$PREBUILT_DIR"
