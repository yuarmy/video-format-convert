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

/**
 * FFmpeg 的统计行（frame=... time=... speed=...）是 INFO 级日志，
 * 这里从日志里抠出时间和速度换算进度；源时长未知时再从 Duration 行补齐。
 */
internal class FfmpegProgressParser(
    sourceDurationMs: Long?,
    private val onProgress: (ConversionProgress) -> Unit,
) : FfmpegNative.LogCallback {

    private val lock = Any()
    private val errorLines = ArrayDeque<String>()
    private var durationMs = sourceDurationMs?.takeIf { it > 0 } ?: 0L

    override fun onLog(level: Int, line: String) {
        if (level <= AV_LOG_ERROR && line.isNotBlank()) {
            synchronized(lock) {
                errorLines.addLast(line.trim())
                while (errorLines.size > MAX_ERROR_LINES) errorLines.removeFirst()
            }
        }

        synchronized(lock) {
            if (durationMs <= 0) {
                findDuration(line)?.let { durationMs = it }
            }
        }

        val timeMs = findTime(line) ?: return
        val speed = SPEED.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val total = synchronized(lock) { durationMs }
        val percent = if (total > 0) (timeMs.toFloat() / total).coerceIn(0f, 1f) else 0f
        val etaMs = if (total > 0 && speed != null && speed > 0f) {
            ((total - timeMs) / speed).toLong().coerceAtLeast(0L)
        } else {
            null
        }
        onProgress(
            ConversionProgress(
                percent = percent,
                speed = speed,
                etaMs = etaMs,
            ),
        )
    }

    fun failureMessage(exitCode: Int): String {
        val lines = synchronized(lock) { errorLines.toList() }
        if (lines.isEmpty()) {
            return "FFmpeg 转换失败（退出码 $exitCode）"
        }
        return lines.takeLast(MAX_REPORTED_ERRORS)
            .joinToString("\n")
            .take(MAX_MESSAGE_LENGTH)
    }

    private fun findDuration(line: String): Long? {
        val match = DURATION.find(line) ?: return null
        return match.toMillis()
    }

    private fun findTime(line: String): Long? {
        val match = TIME.find(line) ?: return null
        return match.toMillis()
    }

    private fun MatchResult.toMillis(): Long {
        val hours = groupValues[1].toLong()
        val minutes = groupValues[2].toLong()
        val seconds = groupValues[3].toDouble()
        return ((hours * 3600 + minutes * 60 + seconds) * 1000).toLong()
    }

    private companion object {
        const val AV_LOG_ERROR = 16
        const val MAX_ERROR_LINES = 8
        const val MAX_REPORTED_ERRORS = 3
        const val MAX_MESSAGE_LENGTH = 500

        val DURATION = Regex("""Duration:\s*(\d+):(\d{2}):(\d{2}(?:\.\d+)?)""")
        val TIME = Regex("""\btime=\s*(\d+):(\d{2}):(\d{2}(?:\.\d+)?)""")
        val SPEED = Regex("""\bspeed=\s*([\d.]+)x""")
    }
}
