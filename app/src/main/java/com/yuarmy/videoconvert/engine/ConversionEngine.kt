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

import android.net.Uri
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.VideoInfo
import java.io.File

interface ConversionEngine {
    suspend fun probe(uri: Uri): VideoInfo

    suspend fun convert(
        spec: ConversionSpec,
        onProgress: (ConversionProgress) -> Unit,
    ): ConversionResult

    fun cancel()
}

data class ConversionProgress(
    val percent: Float,
    val speed: Float? = null,
    val etaMs: Long? = null,
    val note: String? = null,
)

sealed interface ConversionResult {
    data class Success(val output: File) : ConversionResult

    data class Failure(val message: String, val cause: Throwable? = null) : ConversionResult

    data object Cancelled : ConversionResult
}
