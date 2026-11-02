/*
 * Copyright (C) 2026 yuarmy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yuarmy.videoconvert.engine

import com.yuarmy.videoconvert.domain.model.AudioCodecs
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.VideoCodecs
import kotlin.math.roundToInt

object FfmpegCommand {

    fun build(
        spec: ConversionSpec,
        inputPath: String,
        outputPath: String,
        plan: ConversionPlan = ConversionPlanner.plan(spec),
    ): List<String> {
        val args = mutableListOf(
            "-y",
            "-hide_banner",
            "-nostdin",
            "-i", inputPath,
            // content:// 里可能带字幕、数据轨，只保留第一条视频与可选的第一条音频。
            "-map", "0:v:0",
            "-map", "0:a:0?",
        )

        appendVideoArgs(args, spec, plan)
        appendAudioArgs(args, spec, plan)

        if (spec.container.id == "mp4" || spec.container.id == "mov") {
            args += listOf("-movflags", "+faststart")
        }
        args += outputPath
        return args
    }

    private fun appendVideoArgs(
        args: MutableList<String>,
        spec: ConversionSpec,
        plan: ConversionPlan,
    ) {
        if (plan.videoCopy) {
            args += listOf("-c:v", "copy")
            if (plan.dolbyVisionCopy) {
                args += listOf("-bsf:v", "dovi_rpu", "-strict", "unofficial")
                args += listOf("-tag:v", "hvc1")
            }
            return
        }

        val encoder = VIDEO_ENCODERS[spec.videoCodec.id]
            ?: error("FFmpeg 不支持编码 ${spec.videoCodec.id}")
        args += listOf("-c:v", encoder)

        plan.scaleHeight?.let { height ->
            args += listOf("-vf", "scale=-2:$height,setsar=1")
        }
        args += listOf("-pix_fmt", "yuv420p")

        when (spec.videoCodec.id) {
            VideoCodecs.H264.id -> {
                args += listOf("-preset", "veryfast")
                args += listOf("-crf", crfFor(spec, 18, 23, 28))
            }

            VideoCodecs.H265.id -> {
                args += listOf("-preset", "veryfast")
                args += listOf("-crf", crfFor(spec, 22, 28, 33))
                args += listOf("-x265-params", "log-level=error")
            }

            VideoCodecs.VP8.id -> {
                args += listOf("-crf", crfFor(spec, 28, 34, 42))
                args += listOf("-b:v", "0")
                args += listOf("-deadline", "good", "-cpu-used", "4")
            }

            VideoCodecs.VP9.id -> {
                args += listOf("-crf", crfFor(spec, 28, 34, 42))
                args += listOf("-b:v", "0")
                args += listOf("-row-mt", "1", "-deadline", "good", "-cpu-used", "4")
            }

            VideoCodecs.AV1.id -> {
                args += listOf("-preset", "8")
                args += listOf("-crf", crfFor(spec, 28, 35, 45))
            }

            else -> {
                // MPEG-4 / MPEG-2 没有稳定的 CRF 语义，按目标像素量给码率。
                val bitrate = mpegBitrate(spec)
                args += listOf(
                    "-b:v", bitrate.toString(),
                    "-maxrate", (bitrate * 1.5).roundToInt().toString(),
                    "-bufsize", (bitrate * 2).toString(),
                )
            }
        }

        if (spec.videoCodec.id == VideoCodecs.H265.id &&
            (spec.container.id == "mp4" || spec.container.id == "mov")
        ) {
            args += listOf("-tag:v", "hvc1")
        }
        if (spec.videoCodec.id == VideoCodecs.MPEG4.id && spec.container.id == "avi") {
            args += listOf("-vtag", "XVID")
        }
    }

    private fun appendAudioArgs(
        args: MutableList<String>,
        spec: ConversionSpec,
        plan: ConversionPlan,
    ) {
        // 扫描失败时可能探测不到音频 MIME，但不能因此忽略用户选的目标音频编码。
        if (plan.audioCopy && spec.source.audioMime != null) {
            args += listOf("-c:a", "copy")
            return
        }

        val encoder = AUDIO_ENCODERS[spec.audioCodec.id]
            ?: error("FFmpeg 不支持编码 ${spec.audioCodec.id}")
        args += listOf("-c:a", encoder)
        AUDIO_BITRATES[spec.audioCodec.id]?.let { bitrate ->
            args += listOf("-b:a", bitrate)
        }
    }

    private fun crfFor(spec: ConversionSpec, high: Int, balanced: Int, small: Int): String =
        when (spec.quality) {
            Quality.AUTO -> balanced.toString()
            Quality.HIGH -> high.toString()
            Quality.BALANCED -> balanced.toString()
            Quality.SMALL -> small.toString()
        }

    private fun mpegBitrate(spec: ConversionSpec): Int {
        val sourceHeight = spec.source.orientedHeight ?: return 1_500_000
        val sourceWidth = spec.source.orientedWidth ?: return 1_500_000
        val targetHeight = minOf(sourceHeight, spec.resolution.maxHeight ?: sourceHeight)
        val targetWidth = (sourceWidth.toDouble() * targetHeight / sourceHeight).roundToInt()
        val frameRate = spec.source.frameRate ?: 30f
        val bitsPerPixel = when (spec.quality) {
            Quality.HIGH -> 0.12
            Quality.SMALL -> 0.025
            else -> 0.06
        }
        return (targetWidth.toLong() * targetHeight * frameRate * bitsPerPixel)
            .toInt()
            .coerceIn(300_000, 20_000_000)
    }

    private val VIDEO_ENCODERS = mapOf(
        VideoCodecs.H264.id to "libx264",
        VideoCodecs.H265.id to "libx265",
        VideoCodecs.VP8.id to "libvpx",
        VideoCodecs.VP9.id to "libvpx-vp9",
        VideoCodecs.AV1.id to "libsvtav1",
        VideoCodecs.MPEG4.id to "mpeg4",
        VideoCodecs.MPEG2.id to "mpeg2video",
    )

    private val AUDIO_ENCODERS = mapOf(
        AudioCodecs.AAC.id to "aac",
        AudioCodecs.MP3.id to "libmp3lame",
        AudioCodecs.OPUS.id to "libopus",
        AudioCodecs.VORBIS.id to "libvorbis",
        AudioCodecs.FLAC.id to "flac",
        AudioCodecs.AC3.id to "ac3",
        AudioCodecs.PCM.id to "pcm_s16le",
    )

    private val AUDIO_BITRATES = mapOf(
        AudioCodecs.AAC.id to "192k",
        AudioCodecs.MP3.id to "192k",
        AudioCodecs.OPUS.id to "128k",
        AudioCodecs.VORBIS.id to "160k",
        AudioCodecs.AC3.id to "192k",
    )
}
