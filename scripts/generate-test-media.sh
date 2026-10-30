#!/usr/bin/env bash
# 生成视频格式转换 App 的回归测试素材。
#
# 用法:
#   scripts/generate-test-media.sh [输出目录]
#   FORCE=1 scripts/generate-test-media.sh      # 覆盖已存在的文件
#   FFMPEG=/path/to/ffmpeg scripts/generate-test-media.sh
#
# 默认使用桌面版 FFmpeg 生成小体积样本，覆盖 MVP 格式矩阵中的容器与编码组合，
# 生成结束后在输出目录写入 MANIFEST.txt（ffprobe 元数据快照）。
#
# Dolby Vision 样本依赖 dovi_tool（tools/dovi_tool/dovi_tool.exe，不纳入仓库）：
#   https://github.com/quietvoid/dovi_tool/releases
# 缺少该工具时会跳过 DV 样本并给出提示。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FFMPEG="${FFMPEG:-C:/software/ffmpeg-9.0.1-full_build/bin/ffmpeg.exe}"
case "$FFMPEG" in
  *.exe) FFPROBE_DEFAULT="${FFMPEG%ffmpeg.exe}ffprobe.exe" ;;
  *)     FFPROBE_DEFAULT="${FFMPEG%ffmpeg}ffprobe" ;;
esac
FFPROBE="${FFPROBE:-$FFPROBE_DEFAULT}"
DOVI_TOOL="${DOVI_TOOL:-$ROOT/tools/dovi_tool/dovi_tool.exe}"

OUT_DIR="${1:-test-media}"
case "$OUT_DIR" in
  /*) command -v cygpath >/dev/null 2>&1 && OUT_DIR="$(cygpath -m "$OUT_DIR")" ;;
esac

DURATION="${DURATION:-6}"
W="${W:-640}"
H="${H:-360}"
FPS="${FPS:-30}"

if ! command -v "$FFMPEG" >/dev/null 2>&1 && [ ! -x "$FFMPEG" ]; then
  echo "找不到 FFmpeg: $FFMPEG" >&2
  echo "请用 FFMPEG=/path/to/ffmpeg 指定路径。" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
echo "FFmpeg : $FFMPEG"
echo "输出   : $OUT_DIR"
echo

FAILED=()
OK=0

gen() {
  local name="$1"; shift
  local out="$OUT_DIR/$name"

  if [ -f "$out" ] && [ "${FORCE:-0}" != "1" ]; then
    printf '  skip  %s\n' "$name"
    OK=$((OK + 1))
    return 0
  fi

  if "$FFMPEG" -hide_banner -loglevel error -y \
      -f lavfi -i "testsrc2=size=${W}x${H}:rate=${FPS}:duration=${DURATION}" \
      -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=${DURATION}" \
      "$@" "$out" 2>"$OUT_DIR/.$name.log"; then
    printf '  ok    %-34s %s\n' "$name" "$(du -h "$out" | cut -f1)"
    rm -f "$OUT_DIR/.$name.log"
    OK=$((OK + 1))
  else
    printf '  FAIL  %s\n' "$name"
    sed 's/^/        /' "$OUT_DIR/.$name.log" | tail -5
    FAILED+=("$name")
    rm -f "$out" "$OUT_DIR/.$name.log"
  fi
}

# ---- MP4 / MOV ----
gen mp4_h264_aac.mp4        -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k
gen mp4_hevc_aac.mp4        -c:v libx265 -preset fast -crf 28 -pix_fmt yuv420p -c:a aac -b:a 128k
gen mp4_av1_aac.mp4         -c:v libsvtav1 -preset 10 -crf 35 -pix_fmt yuv420p -c:a aac -b:a 128k
gen mp4_hevc10_aac.mp4      -vf format=yuv420p10le -c:v libx265 -preset fast -crf 28 -c:a aac -b:a 128k
gen mp4_hevc_hdr_hlg.mp4    -vf "format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc:range=tv" \
                            -c:v libx265 -preset fast -crf 28 -c:a aac -b:a 128k -movflags +write_colr

# Dolby Vision Profile 8.4（iPhone 同规格：HEVC 10-bit + HLG 基础层 + RPU）
gen_dv84() {
  local name="mp4_hevc_dv84_hlg.mp4"
  local out="$OUT_DIR/$name"
  local tmp="$OUT_DIR/.dv84"

  if [ -f "$out" ] && [ "${FORCE:-0}" != "1" ]; then
    printf '  skip  %s\n' "$name"; OK=$((OK + 1)); return 0
  fi
  if ! command -v "$DOVI_TOOL" >/dev/null 2>&1 && [ ! -x "$DOVI_TOOL" ]; then
    printf '  skip  %-34s 缺少 dovi_tool（%s）\n' "$name" "$DOVI_TOOL"
    return 0
  fi

  local frames=$((DURATION * FPS))
  rm -rf "$tmp" && mkdir -p "$tmp"
  cat >"$tmp/cfg.json" <<EOF
{
  "cm_version": "V29",
  "profile": "8.4",
  "length": ${frames},
  "shots": [
    { "start": 0, "duration": ${frames},
      "metadata_blocks": [
        { "Level1": { "min_pq": 7, "max_pq": 3000, "avg_pq": 1500 } }
      ]
    }
  ]
}
EOF

  # 1) HEVC 10-bit HLG 基础层  2) 生成 RPU  3) 注入  4) 封装为 MP4
  if "$FFMPEG" -hide_banner -loglevel error -y \
      -f lavfi -i "testsrc2=size=${W}x${H}:rate=${FPS}:duration=${DURATION}" \
      -vf "format=yuv420p10le,setparams=color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc:range=tv" \
      -c:v libx265 -preset fast -crf 28 -x265-params log-level=error \
      -f hevc "$tmp/base.hevc" 2>"$tmp/encode.log" \
    && "$DOVI_TOOL" generate -j "$tmp/cfg.json" -o "$tmp/rpu.bin" >/dev/null 2>"$tmp/gen.log" \
    && "$DOVI_TOOL" inject-rpu -i "$tmp/base.hevc" --rpu-in "$tmp/rpu.bin" -o "$tmp/dv.hevc" >/dev/null 2>"$tmp/inject.log" \
    && "$FFMPEG" -hide_banner -loglevel error -y \
      -i "$tmp/dv.hevc" \
      -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=${DURATION}" \
      -map 0:v -map 1:a -c:v copy -bsf:v dovi_rpu=compression=none \
      -c:a aac -b:a 128k -tag:v hvc1 -strict unofficial \
      -movflags +write_colr "$out" 2>"$tmp/mux.log"; then
    printf '  ok    %-34s %s (DV 8.4 + HLG)\n' "$name" "$(du -h "$out" | cut -f1)"
    rm -rf "$tmp"; OK=$((OK + 1))
  else
    printf '  FAIL  %s\n' "$name"
    cat "$tmp"/*.log 2>/dev/null | tail -5 | sed 's/^/        /'
    rm -rf "$tmp"; rm -f "$out"; FAILED+=("$name")
  fi
}
gen_dv84
gen mp4_h264_aac_vertical.mp4 -vf scale=360:640,format=yuv420p -c:v libx264 -preset veryfast -crf 23 -c:a aac -b:a 128k
# 旋转样本：-metadata rotate= 在新版 FFmpeg 已不再写入显示矩阵，需用 -display_rotation 重封装
gen_rotated() {
  local name="mp4_h264_aac_rotated.mp4"
  local out="$OUT_DIR/$name"
  if [ -f "$out" ] && [ "${FORCE:-0}" != "1" ]; then
    printf '  skip  %s\n' "$name"; OK=$((OK + 1)); return 0
  fi
  if "$FFMPEG" -hide_banner -loglevel error -y \
      -display_rotation 90 -i "$OUT_DIR/mp4_h264_aac.mp4" -c copy "$out" 2>"$OUT_DIR/.$name.log"; then
    printf '  ok    %-34s %s\n' "$name" "$(du -h "$out" | cut -f1)"
    rm -f "$OUT_DIR/.$name.log"; OK=$((OK + 1))
  else
    printf '  FAIL  %s\n' "$name"
    sed 's/^/        /' "$OUT_DIR/.$name.log" | tail -5
    FAILED+=("$name"); rm -f "$out" "$OUT_DIR/.$name.log"
  fi
}
gen_rotated
gen mov_h264_aac.mov        -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k
gen mov_prores_pcm.mov      -c:v prores -profile:v 1 -pix_fmt yuv422p10le -c:a pcm_s16le

# ---- MKV / WebM ----
gen mkv_h264_ac3.mkv        -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -c:a ac3 -b:a 192k
gen mkv_hevc_flac.mkv       -c:v libx265 -preset fast -crf 28 -pix_fmt yuv420p -c:a flac
gen mkv_h264_mp3.mkv        -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -c:a libmp3lame -b:a 128k
gen webm_vp9_opus.webm      -c:v libvpx-vp9 -crf 34 -b:v 0 -pix_fmt yuv420p -c:a libopus -b:a 96k
gen webm_vp8_vorbis.webm    -c:v libvpx -b:v 1M -pix_fmt yuv420p -c:a libvorbis -q:a 4

# ---- 老式容器 ----
gen avi_mpeg4_mp3.avi       -c:v libxvid -q:v 4 -pix_fmt yuv420p -c:a libmp3lame -b:a 128k
gen avi_mjpeg_pcm.avi       -c:v mjpeg -q:v 3 -pix_fmt yuvj420p -c:a pcm_s16le
gen flv_h264_aac.flv        -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k
gen ts_h264_aac.ts          -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k -f mpegts
gen ts_mpeg2_ac3.ts         -c:v mpeg2video -q:v 4 -pix_fmt yuv420p -c:a ac3 -b:a 192k -f mpegts
gen wmv_wmv2_wmav2.wmv      -c:v wmv2 -b:v 1M -pix_fmt yuv420p -c:a wmav2 -b:a 96k
gen 3gp_h263_amr.3gp        -vf scale=176:144,format=yuv420p -c:v h263 -c:a libopencore_amrnb \
                            -ar 8000 -ac 1 -b:a 12.2k -f 3gp
gen rm_rv20_videoonly.rm    -c:v rv20 -b:v 800k -pix_fmt yuv420p -an

# ---- 性能测试 ----
W=1920 H=1080 DURATION=15 gen mp4_h264_aac_1080p.mp4 \
  -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -c:a aac -b:a 128k

# ---- 元数据快照 ----
MANIFEST="$OUT_DIR/MANIFEST.txt"
{
  echo "# 由 scripts/generate-test-media.sh 生成于 $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "# 容器           视频编码/分辨率/像素格式                     音频编码            时长  大小"
  for f in "$OUT_DIR"/*; do
    [ -f "$f" ] || continue
    base="$(basename "$f")"
    [ "$base" = "MANIFEST.txt" ] && continue
    vinfo="$("$FFPROBE" -v error -select_streams v:0 \
      -show_entries stream=codec_name,width,height,pix_fmt -of csv=p=0 "$f" 2>/dev/null | head -1)"
    vinfo="${vinfo%,}"
    dv="$("$FFPROBE" -v error -select_streams v:0 \
      -show_entries stream_side_data=dv_profile,dv_bl_signal_compatibility_id \
      -of csv=p=0 "$f" 2>/dev/null | head -1)"
    case "$dv" in
      "") ;;
      *,*) vinfo="$vinfo DV(profile=${dv%%,*},compat=${dv#*,})" ;;
      *)   vinfo="$vinfo DV(profile=$dv)" ;;
    esac
    ainfo="$("$FFPROBE" -v error -select_streams a:0 \
      -show_entries stream=codec_name -of csv=p=0 "$f" 2>/dev/null | head -1)"
    dur="$("$FFPROBE" -v error -show_entries format=duration -of csv=p=0 "$f" 2>/dev/null | head -1)"
    printf '%-32s %-40s %-16s %6.1fs %s\n' \
      "$base" "${vinfo:-无视频}" "${ainfo:-无音频}" "${dur:-0}" "$(du -h "$f" | cut -f1)"
  done
} >"$MANIFEST"

echo
echo "完成: $OK 个样本, 失败 ${#FAILED[@]} 个, 总计 $(du -sh "$OUT_DIR" | cut -f1)"
echo "清单: $MANIFEST"
if [ "${#FAILED[@]}" -gt 0 ]; then
  printf '失败: %s\n' "${FAILED[*]}"
  exit 1
fi
