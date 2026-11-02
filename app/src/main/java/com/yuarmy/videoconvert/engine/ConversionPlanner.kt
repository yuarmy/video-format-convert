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

data class ConversionPlan(
    val videoCopy: Boolean,
    val audioCopy: Boolean,
    val scaleHeight: Int?,
    val dolbyVisionCopy: Boolean,
) {
    val remux: Boolean
        get() = videoCopy && audioCopy
}

/**
 * 判断哪些轨道可以原样拷贝。目标编码与源编码一致、没有缩放、
 * 质量保持“自动”时才允许拷贝，否则用户的选择会被悄悄忽略。
 */
object ConversionPlanner {

    const val DOLBY_VISION_MIME = "video/dolby-vision"

    fun plan(spec: ConversionSpec): ConversionPlan {
        val scaleHeight = spec.resolution.maxHeight?.takeIf { maxHeight ->
            (spec.source.orientedHeight ?: 0) > maxHeight
        }
        val sourceVideoMime = spec.source.videoMime

        // .mov 会丢 dvvC 配置盒，只有 .mp4 能保住 Dolby Vision 元数据。
        val dolbyVisionCopy = sourceVideoMime == DOLBY_VISION_MIME &&
            spec.container.id == "mp4" &&
            spec.videoCodec.id == VideoCodecs.H265.id &&
            spec.quality == Quality.AUTO &&
            scaleHeight == null

        val sameVideoCodec = sourceVideoMime != null &&
            sourceVideoMime == sourceVideoMimeFor(spec.videoCodec.id)
        val videoCopy = dolbyVisionCopy ||
            (sameVideoCodec && spec.quality == Quality.AUTO && scaleHeight == null)

        val audioCopy = spec.source.audioMime == null ||
            spec.source.audioMime == sourceAudioMimeFor(spec.audioCodec.id)

        return ConversionPlan(
            videoCopy = videoCopy,
            audioCopy = audioCopy,
            scaleHeight = scaleHeight,
            dolbyVisionCopy = dolbyVisionCopy,
        )
    }

    fun sourceVideoMimeFor(codecId: String): String? = when (codecId) {
        VideoCodecs.H264.id -> "video/avc"
        VideoCodecs.H265.id -> "video/hevc"
        VideoCodecs.VP8.id -> "video/x-vnd.on2.vp8"
        VideoCodecs.VP9.id -> "video/x-vnd.on2.vp9"
        VideoCodecs.AV1.id -> "video/av01"
        VideoCodecs.MPEG4.id -> "video/mp4v-es"
        VideoCodecs.MPEG2.id -> "video/mpeg2"
        else -> null
    }

    fun sourceAudioMimeFor(codecId: String): String? = when (codecId) {
        AudioCodecs.AAC.id -> "audio/mp4a-latm"
        AudioCodecs.MP3.id -> "audio/mpeg"
        AudioCodecs.OPUS.id -> "audio/opus"
        AudioCodecs.VORBIS.id -> "audio/vorbis"
        AudioCodecs.FLAC.id -> "audio/flac"
        AudioCodecs.AC3.id -> "audio/ac3"
        AudioCodecs.PCM.id -> "audio/raw"
        else -> null
    }
}
