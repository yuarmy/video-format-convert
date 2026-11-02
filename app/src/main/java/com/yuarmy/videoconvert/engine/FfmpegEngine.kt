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
import android.net.Uri
import com.yuarmy.videoconvert.data.MediaProbe
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FfmpegEngine(private val context: Context) : ConversionEngine {

    @Volatile
    private var cancelled = false

    override suspend fun probe(uri: Uri): VideoInfo = withContext(Dispatchers.IO) {
        MediaProbe.probe(context, uri)
    }

    override suspend fun convert(
        spec: ConversionSpec,
        onProgress: (ConversionProgress) -> Unit,
    ): ConversionResult = withContext(Dispatchers.IO) {
        if (!FfmpegNative.available) {
            return@withContext ConversionResult.Failure(
                "FFmpeg 引擎未加载：${FfmpegNative.loadFailureMessage ?: "未知原因"}",
            )
        }
        if (cancelled) {
            return@withContext ConversionResult.Cancelled
        }

        val plan = ConversionPlanner.plan(spec)
        // Dolby Vision 必须落在 .mp4 里，否则 dvvC 配置盒会丢失。
        val extension = if (plan.dolbyVisionCopy) "mp4" else spec.container.extension
        val output = File(
            context.cacheDir,
            "conversions/${System.currentTimeMillis()}.$extension",
        )
        output.parentFile?.mkdirs()
        output.delete()

        var descriptor: android.os.ParcelFileDescriptor? = null
        try {
            descriptor = context.contentResolver.openFileDescriptor(spec.source.uri, "r")
                ?: return@withContext ConversionResult.Failure("无法打开所选视频")

            val parser = FfmpegProgressParser(spec.source.durationMs, onProgress)
            val args = FfmpegCommand.build(
                spec = spec,
                inputPath = "/proc/self/fd/${descriptor.fd}",
                outputPath = output.absolutePath,
                plan = plan,
            )
            val exitCode = FfmpegNative.run(args.toTypedArray(), parser)

            when {
                cancelled -> ConversionResult.Cancelled

                exitCode == 0 && output.length() > 0 -> ConversionResult.Success(output)

                exitCode == 0 -> ConversionResult.Failure("FFmpeg 没有生成有效的输出文件")

                else -> ConversionResult.Failure(parser.failureMessage(exitCode))
            }.also { result ->
                if (result !is ConversionResult.Success) output.delete()
            }
        } catch (error: Exception) {
            output.delete()
            ConversionResult.Failure(error.message ?: "FFmpeg 转换失败", error)
        } finally {
            descriptor?.close()
        }
    }

    override fun cancel() {
        cancelled = true
        if (FfmpegNative.available) {
            FfmpegNative.cancel()
        }
    }
}
