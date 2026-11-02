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

package com.yuarmy.videoconvert.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.JobState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ConversionController {

    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()

    @Volatile
    private var pendingSpec: ConversionSpec? = null

    fun submit(context: Context, spec: ConversionSpec) {
        pendingSpec = spec
        _state.value = JobState.Preparing
        val intent = Intent(context, ConversionService::class.java)
            .setAction(ConversionService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { error ->
                pendingSpec = null
                _state.value = JobState.Failed(error.message ?: "无法启动转换服务")
            }
    }

    fun cancel(context: Context) {
        context.startService(
            Intent(context, ConversionService::class.java)
                .setAction(ConversionService.ACTION_CANCEL),
        )
    }

    fun reset() {
        _state.value = JobState.Idle
    }

    internal fun consumePendingSpec(): ConversionSpec? = pendingSpec.also { pendingSpec = null }

    internal fun publish(state: JobState) {
        _state.value = state
    }
}
