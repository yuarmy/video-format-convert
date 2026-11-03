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
import java.io.FileInputStream

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
        var stagedInput: File? = null
        try {
            descriptor = context.contentResolver.openFileDescriptor(spec.source.uri, "r")
                ?: return@withContext ConversionResult.Failure("无法打开所选视频")

            val input = stageInputIfNeeded(spec.source.uri, descriptor.fd)
                ?: return@withContext ConversionResult.Cancelled
            stagedInput = input.temporaryFile

            val parser = FfmpegProgressParser(spec.source.durationMs, onProgress)
            val args = FfmpegCommand.build(
                spec = spec,
                inputPath = input.path,
                outputPath = output.absolutePath,
                plan = plan,
            )
            // 打开 SAF 文件与整理参数可能耗时，取消可能发生在这段时间里。
            if (cancelled) {
                return@withContext ConversionResult.Cancelled
            }
            val exitCode = FfmpegNative.run(args.toTypedArray(), parser)

            when {
                cancelled -> ConversionResult.Cancelled

                exitCode == -2 -> ConversionResult.Failure("上一段 FFmpeg 转换尚未退出，请稍后重试")

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
            stagedInput?.delete()
        }
    }

    private class Input(val path: String, val temporaryFile: File?)

    /**
     * Android 11+ 的 FUSE 在 FFmpeg 重新打开 `/proc/self/fd/N` 时会按本进程身份鉴权，
     * SAF 授权打开的文件可能直接返回 EACCES。先实测能否打开，不行就拷贝到缓存再转。
     */
    private fun stageInputIfNeeded(uri: Uri, fd: Int): Input? {
        val fdPath = "/proc/self/fd/$fd"
        if (runCatching { FileInputStream(fdPath).close() }.isSuccess) {
            return Input(fdPath, temporaryFile = null)
        }

        val target = File(context.cacheDir, "inputs/${System.currentTimeMillis()}.src")
        target.parentFile?.mkdirs()
        try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选视频")
            source.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (cancelled) {
                            target.delete()
                            return null
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
        return Input(target.absolutePath, temporaryFile = target)
    }

    override fun cancel() {
        cancelled = true
        if (FfmpegNative.available) {
            FfmpegNative.cancel()
        }
    }
}
