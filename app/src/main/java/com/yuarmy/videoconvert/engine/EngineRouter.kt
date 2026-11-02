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

import android.content.Context
import android.media.MediaCodecList
import androidx.media3.common.MimeTypes
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.VideoCodecs

sealed interface EngineRoute {
    data class Media3(val remux: Boolean) : EngineRoute

    data class Unsupported(val reason: String) : EngineRoute
}

object EngineRouter {

    fun route(spec: ConversionSpec, sourceUndecodable: Boolean = false): EngineRoute {
        if (spec.container.id != "mp4") {
            return EngineRoute.Unsupported(
                "${spec.container.label} 输出需要 FFmpeg 引擎，将在 M3 接入",
            )
        }

        val targetVideoMime = when (spec.videoCodec.id) {
            VideoCodecs.H264.id -> MimeTypes.VIDEO_H264
            VideoCodecs.H265.id -> MimeTypes.VIDEO_H265
            else -> return EngineRoute.Unsupported("M2 的 MP4 快路径仅支持 H.264 / H.265")
        }

        val scaleNeeded = spec.resolution.maxHeight?.let { height ->
            (spec.source.orientedHeight ?: 0) > height
        } ?: false

        val remux = spec.quality == Quality.AUTO &&
            !scaleNeeded &&
            spec.source.videoMime == targetVideoMime &&
            (spec.source.audioMime == null || spec.source.audioMime == MimeTypes.AUDIO_AAC)

        if (remux) {
            return EngineRoute.Media3(remux = true)
        }

        if (sourceUndecodable) {
            return EngineRoute.Unsupported(
                "系统解码器无法解码该视频，转码需要 FFmpeg 引擎（M3）",
            )
        }

        if (!hasEncoder(targetVideoMime)) {
            return EngineRoute.Unsupported(
                "本机没有 ${spec.videoCodec.label} 硬件编码器，M3 将由 FFmpeg 软件编码",
            )
        }
        if (spec.source.audioMime != null && !hasEncoder(MimeTypes.AUDIO_AAC)) {
            return EngineRoute.Unsupported("本机没有 AAC 硬件编码器，M3 将由 FFmpeg 兜底")
        }

        return EngineRoute.Media3(remux = false)
    }

    fun createEngine(context: Context, spec: ConversionSpec): ConversionEngine? =
        when (val route = route(spec)) {
            is EngineRoute.Media3 -> Media3Engine(context, route.remux)
            is EngineRoute.Unsupported -> null
        }

    private fun hasEncoder(mimeType: String): Boolean {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return codecList.codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }
}
