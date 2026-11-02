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

# 下载并解压构建 Android FFmpeg 所需的源码。
#
# 用法:
#   scripts/ffmpeg-download.sh
#   FORCE=1 scripts/ffmpeg-download.sh        # 重新下载并解压
#
# 归档文件保存在 ffmpeg/downloads/，解压到 ffmpeg/src/<组件>/，两者均不纳入仓库。

set -euo pipefail

# shellcheck source=lib/ffmpeg-common.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib/ffmpeg-common.sh"

FORCE="${FORCE:-0}"

fetch() {
    local name="$1" url="$2" sha="$3" archive="$4"
    local out="$DOWNLOAD_DIR/$archive"

    if [ -f "$out" ]; then
        if verify_sha256 "$out" "$sha"; then
            log "已下载 $name"
            return 0
        fi
        warn "$archive 校验失败，重新下载"
        rm -f "$out"
    fi

    mkdir -p "$DOWNLOAD_DIR"
    log "下载 $name"
    download_url "$url" "$out.part"
    if ! verify_sha256 "$out.part" "$sha"; then
        rm -f "$out.part"
        die "$name 校验失败: $url"
    fi
    mv "$out.part" "$out"
}

extract() {
    local name="$1" archive="$2"
    local dir="$SRC_DIR/$name"
    local stamp="$dir/.source-stamp"

    if [ "$FORCE" != "1" ] && [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$archive" ]; then
        log "已解压 $name"
        return 0
    fi

    rm -rf "$dir"
    mkdir -p "$dir"
    log "解压 $archive"
    tar -xf "$DOWNLOAD_DIR/$archive" -C "$dir" --strip-components=1 \
        || die "解压失败: $archive"
    printf '%s\n' "$archive" >"$stamp"
}

step "FFmpeg $FFMPEG_VERSION"
fetch "FFmpeg" "$FFMPEG_URL" "$FFMPEG_SHA256" "ffmpeg-$FFMPEG_VERSION.tar.xz"
extract "ffmpeg" "ffmpeg-$FFMPEG_VERSION.tar.xz"

step "x264 (${X264_REF:0:12})"
fetch "x264" "$X264_URL" "$X264_SHA256" "$X264_ARCHIVE"
extract "x264" "$X264_ARCHIVE"

step "x265 $X265_VERSION"
fetch "x265" "$X265_URL" "$X265_SHA256" "x265-$X265_VERSION.tar.gz"
extract "x265" "x265-$X265_VERSION.tar.gz"

step "LAME $LAME_VERSION"
fetch "LAME" "$LAME_URL" "$LAME_SHA256" "lame-$LAME_VERSION.tar.gz"
extract "lame" "lame-$LAME_VERSION.tar.gz"

step "Opus $OPUS_VERSION"
fetch "Opus" "$OPUS_URL" "$OPUS_SHA256" "opus-$OPUS_VERSION.tar.gz"
extract "opus" "opus-$OPUS_VERSION.tar.gz"

step "libogg $OGG_VERSION"
fetch "libogg" "$OGG_URL" "$OGG_SHA256" "libogg-$OGG_VERSION.tar.gz"
extract "ogg" "libogg-$OGG_VERSION.tar.gz"

step "libvorbis $VORBIS_VERSION"
fetch "libvorbis" "$VORBIS_URL" "$VORBIS_SHA256" "libvorbis-$VORBIS_VERSION.tar.gz"
extract "vorbis" "libvorbis-$VORBIS_VERSION.tar.gz"

step "libvpx $VPX_VERSION"
fetch "libvpx" "$VPX_URL" "$VPX_SHA256" "libvpx-$VPX_VERSION.tar.gz"
extract "vpx" "libvpx-$VPX_VERSION.tar.gz"

step "SVT-AV1 $SVT_AV1_VERSION"
fetch "SVT-AV1" "$SVT_AV1_URL" "$SVT_AV1_SHA256" "SVT-AV1-$SVT_AV1_VERSION.tar.gz"
extract "svt-av1" "SVT-AV1-$SVT_AV1_VERSION.tar.gz"

printf '\n完成。源码目录: %s\n' "$SRC_DIR"
