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

cmake_minimum_required(VERSION 3.22.1)
project(ffmpeg_jni C)

set(FFMPEG_DIR "${CMAKE_CURRENT_SOURCE_DIR}/..")
set(FFMPEG_PREBUILT "${FFMPEG_DIR}/prebuilt/${ANDROID_ABI}")
set(FFMPEG_SRC "${FFMPEG_DIR}/src/ffmpeg")
set(FFTOOLS "${FFMPEG_SRC}/fftools")
# configure 生成的 config.h / ffversion.h 不在安装头文件里
set(FFMPEG_GENERATED "${FFMPEG_DIR}/build/${ANDROID_ABI}/ffmpeg")

if(NOT EXISTS "${FFMPEG_PREBUILT}/lib/libavcodec.a")
    message(FATAL_ERROR
        "缺少 ${ANDROID_ABI} 的 FFmpeg 静态库。先运行 scripts/ffmpeg-build.sh。")
endif()
if(NOT EXISTS "${FFTOOLS}/ffmpeg.c")
    message(FATAL_ERROR
        "缺少 FFmpeg 源码：${FFMPEG_SRC}。先运行 scripts/ffmpeg-download.sh。")
endif()

set(FFMPEG_INCLUDES "${FFMPEG_PREBUILT}/include" "${FFTOOLS}" "${FFMPEG_SRC}")
if(EXISTS "${FFMPEG_GENERATED}")
    list(APPEND FFMPEG_INCLUDES "${FFMPEG_GENERATED}")
endif()
if(NOT EXISTS "${FFMPEG_PREBUILT}/include/config.h" AND
   NOT EXISTS "${FFMPEG_GENERATED}/config.h")
    message(FATAL_ERROR
        "找不到 FFmpeg 的 config.h。重新运行 scripts/ffmpeg-build.sh 生成预编译目录。")
endif()

# fftools 的 CLI 入口是 main，不能直接链接进 App 进程；改名后由 JNI 调用。
# 同时取消两个信号标志的 static，JNI 取消时才能从外部置位中断转码。
set(FTOOLS_MAIN "${FFTOOLS}/ffmpeg.c")
file(READ "${FTOOLS_MAIN}" FTOOLS_MAIN_SOURCE)
string(FIND "${FTOOLS_MAIN_SOURCE}" "static volatile int received_sigterm = 0;" SIGTERM_POS)
string(FIND "${FTOOLS_MAIN_SOURCE}" "static volatile int received_nb_signals = 0;" SIGNALS_POS)
if(SIGTERM_POS EQUAL -1 OR SIGNALS_POS EQUAL -1)
    message(FATAL_ERROR
        "ffmpeg.c 的信号标志与预期不符，取消功能会失效；请检查 FFmpeg 版本。")
endif()
string(REPLACE "static volatile int received_sigterm = 0;"
               "volatile int received_sigterm = 0;" FTOOLS_MAIN_SOURCE "${FTOOLS_MAIN_SOURCE}")
string(REPLACE "static volatile int received_nb_signals = 0;"
               "volatile int received_nb_signals = 0;" FTOOLS_MAIN_SOURCE "${FTOOLS_MAIN_SOURCE}")
set(FTOOLS_MAIN_PATCHED "${CMAKE_CURRENT_BINARY_DIR}/ffmpeg_main.c")
file(WRITE "${FTOOLS_MAIN_PATCHED}" "${FTOOLS_MAIN_SOURCE}")
set_source_files_properties("${FTOOLS_MAIN_PATCHED}"
    PROPERTIES COMPILE_DEFINITIONS "main=ffmpeg_main")

add_library(ffmpeg_jni SHARED
    ffmpeg_jni.c
    fftools_stubs.c
    "${FTOOLS_MAIN_PATCHED}"
    "${FFTOOLS}/cmdutils.c"
    "${FFTOOLS}/opt_common.c"
    "${FFTOOLS}/ffmpeg_dec.c"
    "${FFTOOLS}/ffmpeg_demux.c"
    "${FFTOOLS}/ffmpeg_enc.c"
    "${FFTOOLS}/ffmpeg_filter.c"
    "${FFTOOLS}/ffmpeg_hw.c"
    "${FFTOOLS}/ffmpeg_mux.c"
    "${FFTOOLS}/ffmpeg_mux_init.c"
    "${FFTOOLS}/ffmpeg_opt.c"
    "${FFTOOLS}/ffmpeg_sched.c"
    "${FFTOOLS}/sync_queue.c"
    "${FFTOOLS}/thread_queue.c"
    "${FFMPEG_SRC}/compat/strtod.c"
)

target_include_directories(ffmpeg_jni PRIVATE ${FFMPEG_INCLUDES})

# 这些宏必须与 ffmpeg-build.sh 传给 configure 的 CPPFLAGS 一致，
# 否则 fftools 与静态库对 strtod 等符号的预期会不一致。
target_compile_options(ffmpeg_jni PRIVATE
    -std=gnu17
    -fPIC
    -pthread
    -D_ISOC11_SOURCE
    -D_FILE_OFFSET_BITS=64
    -D_LARGEFILE_SOURCE
    -Dstrtod=avpriv_strtod
    -DPIC
    -DANDROID
    -Wno-unused-parameter
    -Wno-deprecated-declarations
)

target_link_directories(ffmpeg_jni PRIVATE "${FFMPEG_PREBUILT}/lib")
target_link_options(ffmpeg_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,--no-undefined"
    # 静态库里的汇编数据符号（如 ff_tx_tab_*）在 .so 中默认可抢占，
    # adrp+add 类重定位会被 lld 拒绝；FFmpeg 自建共享库同样带 -Bsymbolic。
    "-Wl,-Bsymbolic"
    "-Wl,--exclude-libs,ALL"
)

# FFmpeg 各库互相引用，静态链接必须成组解析；x265 需要静态 C++ 运行时。
target_link_libraries(ffmpeg_jni
    "-Wl,--start-group"
    avformat avcodec avfilter swresample swscale avutil
    x265 x264 vpx SvtAv1Enc vorbis vorbisenc vorbisfile ogg opus mp3lame
    "-Wl,--end-group"
    c++_static c++abi
    log android mediandk dl m
)
