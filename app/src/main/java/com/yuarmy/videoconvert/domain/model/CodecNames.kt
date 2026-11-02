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

package com.yuarmy.videoconvert.domain.model

object CodecNames {
    private val labels = mapOf(
        "video/avc" to "H.264",
        "video/hevc" to "H.265 / HEVC",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/av01" to "AV1",
        "video/mp4v-es" to "MPEG-4",
        "video/mpeg2" to "MPEG-2",
        "video/dolby-vision" to "Dolby Vision",
        "audio/mp4a-latm" to "AAC",
        "audio/mpeg" to "MP3",
        "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis",
        "audio/flac" to "FLAC",
        "audio/ac3" to "AC-3",
        "audio/raw" to "PCM",
    )

    fun label(mime: String?): String = when {
        mime == null -> "未知"
        else -> labels[mime] ?: mime
    }
}
