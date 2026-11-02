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
 * 打包进去的 FFmpeg 只覆盖 arm64-v8a，其他 ABI 上加载会失败。
 * 用 [available] 让 UI 提前给出提示，而不是等到转换时才抛 UnsatisfiedLinkError。
 */
object FfmpegNative {

    fun interface LogCallback {
        fun onLog(level: Int, line: String)
    }

    private val loadFailure: Throwable? =
        runCatching { System.loadLibrary("ffmpeg_jni") }.exceptionOrNull()

    val available: Boolean
        get() = loadFailure == null

    val loadFailureMessage: String?
        get() = loadFailure?.let { it.message ?: it.javaClass.simpleName }

    /**
     * 阻塞执行一次 FFmpeg 命令，返回退出码；同一时刻只允许一个任务。
     * [callback] 会在 FFmpeg 的任意线程上被调用。
     */
    @JvmStatic
    external fun run(args: Array<String>, callback: LogCallback): Int

    @JvmStatic
    external fun cancel()

    @JvmStatic
    external fun version(): String
}
