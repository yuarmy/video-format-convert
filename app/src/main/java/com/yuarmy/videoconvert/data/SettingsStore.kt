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

package com.yuarmy.videoconvert.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SettingsStore {

    const val DEFAULT_OUTPUT_FOLDER = "视频转换"
    private const val PREFS = "settings"
    private const val KEY_OUTPUT_FOLDER = "output_folder"
    private const val MAX_FOLDER_LENGTH = 40

    private lateinit var prefs: SharedPreferences

    private val _outputFolder = MutableStateFlow(DEFAULT_OUTPUT_FOLDER)
    val outputFolder: StateFlow<String> = _outputFolder.asStateFlow()

    fun install(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _outputFolder.value = sanitize(prefs.getString(KEY_OUTPUT_FOLDER, null).orEmpty())
    }

    fun setOutputFolder(raw: String): String {
        val value = sanitize(raw)
        prefs.edit().putString(KEY_OUTPUT_FOLDER, value).apply()
        _outputFolder.value = value
        return value
    }

    fun sanitize(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim('.')
            .take(MAX_FOLDER_LENGTH)
            .trim()
        return cleaned.ifBlank { DEFAULT_OUTPUT_FOLDER }
    }
}
