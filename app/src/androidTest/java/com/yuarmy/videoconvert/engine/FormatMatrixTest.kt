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
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuarmy.videoconvert.data.MediaProbe
import com.yuarmy.videoconvert.data.VideoDecodeScanner
import com.yuarmy.videoconvert.data.VideoFileEntry
import com.yuarmy.videoconvert.domain.model.AudioCodecs
import com.yuarmy.videoconvert.domain.model.CodecOption
import com.yuarmy.videoconvert.domain.model.ContainerOption
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.FormatCatalog
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.VideoCodecs
import com.yuarmy.videoconvert.domain.model.VideoInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 素材由 scripts/run-device-tests.sh 推到设备后，测试自行复制到应用内部目录。
 */
@RunWith(AndroidJUnit4::class)
class FormatMatrixTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val matrixDir: File?
        get() = MatrixMedia.localDir(context)

    @Before
    fun requireMatrixMedia() {
        Assume.assumeTrue(
            "未找到矩阵素材，请先运行 scripts/run-device-tests.sh",
            matrixDir?.isDirectory == true,
        )
    }

    @Test
    fun allSourceContainersConvertToMp4() = runBlocking {
        val sources = matrixDir!!.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in VIDEO_EXTENSIONS }
            .sortedBy { it.name }
        assertTrue("矩阵素材目录里没有视频", sources.isNotEmpty())

        val failures = mutableListOf<String>()
        for (file in sources) {
            val info = infoFor(file)
            val spec = ConversionSpec(
                source = info,
                container = FormatCatalog.containers.first { it.id == "mp4" },
                videoCodec = VideoCodecs.H264,
                audioCodec = AudioCodecs.AAC,
                resolution = FormatCatalog.resolutions.first(),
                quality = Quality.AUTO,
                sourceUndecodable = isSystemUndecodable(info),
            )
            val label = "源 ${file.name} → MP4/H.264"
            val started = System.currentTimeMillis()
            val result = convert(spec)
            val elapsed = System.currentTimeMillis() - started
            val problem = verify(result, spec)
            if (problem == null) {
                Log.i(TAG, "PASS $label (${elapsed}ms, ${routeLabel(spec)})")
            } else {
                Log.e(TAG, "FAIL $label: $problem")
                failures += "$label: $problem"
            }
        }

        assertTrue(failureReport("源容器回归", sources.size, failures), failures.isEmpty())
        Log.i(TAG, "源容器回归全部通过：${sources.size}/${sources.size}")
        Unit
    }

    @Test
    fun allTargetContainerCodecCombinations() = runBlocking {
        val sample = matrixDir!!.resolve(SOURCE_SAMPLE)
        Assume.assumeTrue("缺少 $SOURCE_SAMPLE", sample.isFile)
        Assume.assumeTrue("FFmpeg 原生库未加载", FfmpegNative.available)

        val info = MediaProbe.probe(context, Uri.fromFile(sample))
        val combinations = targetCombinations()
        val failures = mutableListOf<String>()

        for ((container, video, audio) in combinations) {
            val spec = ConversionSpec(
                source = info,
                container = container,
                videoCodec = video,
                audioCodec = audio,
                resolution = FormatCatalog.resolutions.first(),
                quality = Quality.AUTO,
            )
            val label = "${container.label} · ${video.label} · ${audio.label}"
            val started = System.currentTimeMillis()
            val result = convert(spec)
            val elapsed = System.currentTimeMillis() - started
            val problem = verify(result, spec)
            if (problem == null) {
                Log.i(TAG, "PASS $label (${elapsed}ms, ${routeLabel(spec)})")
            } else {
                Log.e(TAG, "FAIL $label: $problem")
                failures += "$label: $problem"
            }
        }

        assertTrue(
            failureReport("目标矩阵回归", combinations.size, failures),
            failures.isEmpty(),
        )
        Log.i(TAG, "目标矩阵回归全部通过：${combinations.size}/${combinations.size}")
        Unit
    }

    /**
     * 每个视频编码配首个音频编码，再让每个额外音频编码配首个视频编码；
     * 覆盖矩阵里的全部选项，又不为每个交叉组合重复跑一遍编码。
     */
    private fun targetCombinations(): List<Triple<ContainerOption, CodecOption, CodecOption>> {
        val combinations = mutableListOf<Triple<ContainerOption, CodecOption, CodecOption>>()
        for (container in FormatCatalog.containers) {
            val firstAudio = container.audioCodecs.first()
            container.videoCodecs.forEach { combinations += Triple(container, it, firstAudio) }

            val firstVideo = container.videoCodecs.first()
            container.audioCodecs.drop(1).forEach {
                combinations += Triple(container, firstVideo, it)
            }
        }
        return combinations
    }

    private suspend fun convert(spec: ConversionSpec): ConversionResult {
        return when (val route = EngineRouter.route(spec)) {
            is EngineRoute.Unsupported -> ConversionResult.Failure(route.reason)
            is EngineRoute.Media3 -> {
                val primary = Media3Engine(context, route.remux).convert(spec) {}
                // 和前台服务一致：Media3 运行期失败时回退 FFmpeg。
                if (primary is ConversionResult.Failure && FfmpegNative.available) {
                    FfmpegEngine(context).convert(spec) {}
                } else {
                    primary
                }
            }
            is EngineRoute.Ffmpeg -> FfmpegEngine(context).convert(spec) {}
        }
    }

    private fun verify(result: ConversionResult, spec: ConversionSpec): String? {
        if (result !is ConversionResult.Success) {
            return (result as? ConversionResult.Failure)?.message ?: result.toString()
        }
        val output = result.output
        try {
            if (!output.isFile || output.length() == 0L) return "输出文件为空"

            val probe = MediaProbe.probeLenient(context, Uri.fromFile(output))
            val expectedVideo = ConversionPlanner.sourceVideoMimeFor(spec.videoCodec.id)
            if (probe.videoMime != null && expectedVideo != null && probe.videoMime != expectedVideo) {
                return "输出视频编码为 ${probe.videoMime}，期望 $expectedVideo"
            }
            val expectedAudio = ConversionPlanner.sourceAudioMimeFor(spec.audioCodec.id)
            if (spec.source.audioMime != null &&
                probe.audioMime != null &&
                expectedAudio != null &&
                probe.audioMime != expectedAudio
            ) {
                return "输出音频编码为 ${probe.audioMime}，期望 $expectedAudio"
            }
        } finally {
            output.delete()
        }
        return null
    }

    private fun routeLabel(spec: ConversionSpec): String = when (val route = EngineRouter.route(spec)) {
        is EngineRoute.Media3 -> if (route.remux) "Media3 流拷贝" else "Media3 硬件转码"
        is EngineRoute.Ffmpeg -> if (route.remux) "FFmpeg 流拷贝" else "FFmpeg 软件转码"
        is EngineRoute.Unsupported -> "不支持"
    }

    private fun infoFor(file: File): VideoInfo {
        val uri = Uri.fromFile(file)
        val lenient = MediaProbe.probeLenient(context, uri)
        val strict = runCatching { MediaProbe.probe(context, uri) }.getOrNull()
        val info = strict ?: lenient
        if (info.width != null && info.height != null) return info
        // MediaExtractor 打不开的容器（AVI/FLV/RM 等）用样本已知尺寸兜底，只影响码率估算。
        return info.copy(
            width = 640,
            height = 360,
            durationMs = info.durationMs ?: 6_000,
            frameRate = info.frameRate ?: 30f,
        )
    }

    private fun isSystemUndecodable(info: VideoInfo): Boolean =
        VideoDecodeScanner.check(
            context,
            VideoFileEntry(info.uri, info.displayName, info.videoMime, info.sizeBytes),
        ) != null

    private fun failureReport(title: String, total: Int, failures: List<String>): String =
        buildString {
            append("$title ${failures.size}/$total 失败")
            failures.forEach { append('\n').append("  - ").append(it) }
        }

    private companion object {
        const val TAG = "FormatMatrix"
        const val SOURCE_SAMPLE = "mp4_h264_aac.mp4"
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "ts", "wmv", "rm", "3gp",
        )
    }
}
