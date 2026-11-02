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

import android.net.Uri

data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val rotationDegrees: Int,
    val bitrate: Long?,
    val videoMime: String?,
    val audioMime: String?,
    val frameRate: Float?,
) {
    val orientedWidth: Int?
        get() = if (rotationDegrees % 180 == 90) height else width

    val orientedHeight: Int?
        get() = if (rotationDegrees % 180 == 90) width else height
}
