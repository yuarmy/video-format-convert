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
import com.yuarmy.videoconvert.domain.model.VideoCodecs

sealed interface EngineRoute {
    data class Media3(val remux: Boolean) : EngineRoute

    data class Ffmpeg(val remux: Boolean, val note: String? = null) : EngineRoute

    data class Unsupported(val reason: String) : EngineRoute
}

object EngineRouter {

    fun route(spec: ConversionSpec): EngineRoute {
        val plan = ConversionPlanner.plan(spec)

        if (spec.container.id != "mp4") {
            return ffmpegRoute(plan, spec, note = null)
        }

        // 扫描阶段已经确认系统解码器不可用，Media3 的解码/封装同样不可信。
        if (spec.sourceUndecodable) {
            return ffmpegRoute(
                plan,
                spec,
                note = "系统解码器无法解码该视频，已改用 FFmpeg",
            )
        }

        // Media3 的流拷贝是整条 MediaItem 级的，无法只拷贝视频轨；
        // Dolby Vision 与“只改音频”两种情况交给 FFmpeg，避免无谓重编视频。
        if (plan.dolbyVisionCopy) {
            return ffmpegRoute(plan, spec, note = "将原样封装 Dolby Vision 视频")
        }
        if (plan.remux) {
            return EngineRoute.Media3(remux = true)
        }
        if (plan.videoCopy) {
            return ffmpegRoute(plan, spec, note = "视频直接流拷贝，仅重新编码音频")
        }

        val targetVideoMime = when (spec.videoCodec.id) {
            VideoCodecs.H264.id -> MimeTypes.VIDEO_H264
            VideoCodecs.H265.id -> MimeTypes.VIDEO_H265
            else -> null
        }
        val audioEncodable = spec.source.audioMime == null ||
            hasEncoder(MimeTypes.AUDIO_AAC)

        if (targetVideoMime != null && hasEncoder(targetVideoMime) && audioEncodable) {
            return EngineRoute.Media3(remux = false)
        }

        val reason = if (targetVideoMime == null) {
            "MP4 的 ${spec.videoCodec.label} 编码需要 FFmpeg 软件编码"
        } else {
            "本机没有 ${spec.videoCodec.label} 硬件编码器，改用 FFmpeg 软件编码"
        }
        return ffmpegRoute(plan, spec, note = reason)
    }

    fun createEngine(context: Context, spec: ConversionSpec): ConversionEngine? =
        when (val route = route(spec)) {
            is EngineRoute.Media3 -> Media3Engine(context, route.remux)
            is EngineRoute.Ffmpeg -> FfmpegEngine(context)
            is EngineRoute.Unsupported -> null
        }

    private fun ffmpegRoute(
        plan: ConversionPlan,
        spec: ConversionSpec,
        note: String?,
    ): EngineRoute {
        if (!FfmpegNative.available) {
            return EngineRoute.Unsupported(
                "${spec.container.label} 输出需要 FFmpeg 引擎，但当前设备无法加载" +
                    (FfmpegNative.loadFailureMessage?.let { "（$it）" } ?: ""),
            )
        }
        return EngineRoute.Ffmpeg(remux = plan.remux, note = note)
    }

    private fun hasEncoder(mimeType: String): Boolean {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return codecList.codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
        }
    }
}
