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
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuarmy.videoconvert.data.MediaProbe
import com.yuarmy.videoconvert.domain.model.AudioCodecs
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.FormatCatalog
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.ResolutionOption
import com.yuarmy.videoconvert.domain.model.VideoCodecs
import com.yuarmy.videoconvert.domain.model.VideoInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 1080p 素材的转码耗时基线。不断言绝对速度，只把结果写进 logcat，
 * 便于对比不同设备或版本；素材由 scripts/run-device-tests.sh 推送。
 */
@RunWith(AndroidJUnit4::class)
class PerformanceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sample: File?
        get() = MatrixMedia.localDir(context)?.resolve(SAMPLE)

    private lateinit var info: VideoInfo

    @Before
    fun requireSample() {
        val file = sample
        Assume.assumeTrue("缺少 $SAMPLE，请先运行 scripts/run-device-tests.sh", file?.isFile == true)
        info = MediaProbe.probe(context, Uri.fromFile(file!!))
        Assume.assumeTrue("样本不是 1080p", info.orientedHeight == 1080)
    }

    @Test
    fun hardwareTranscode_1080p_to_720p() = runBlocking {
        val spec = spec(
            containerId = "mp4",
            resolution = ResolutionOption(720, "720p"),
        )
        Assume.assumeTrue(
            "本机没有 H.264 硬件编码器，跳过",
            EngineRouter.route(spec) is EngineRoute.Media3,
        )
        benchmark(spec, "Media3 硬件转码 1080p→720p")
    }

    @Test
    fun softwareTranscode_1080p_to_720p() = runBlocking {
        Assume.assumeTrue("FFmpeg 原生库未加载", FfmpegNative.available)
        val spec = spec(
            containerId = "mkv",
            resolution = ResolutionOption(720, "720p"),
        )
        assertTrue("MKV 应走 FFmpeg", EngineRouter.route(spec) is EngineRoute.Ffmpeg)
        benchmark(spec, "FFmpeg 软件转码 1080p→720p")
    }

    private suspend fun benchmark(spec: ConversionSpec, label: String) {
        val started = SystemClock.elapsedRealtime()
        val result = EngineRouter.createEngine(context, spec)!!.convert(spec) {}
        val elapsedMs = SystemClock.elapsedRealtime() - started

        assertTrue("$label 失败：$result", result is ConversionResult.Success)
        val output = (result as ConversionResult.Success).output
        try {
            assertTrue("$label 输出为空", output.length() > 0)
            val sourceMs = info.durationMs ?: 0
            val speed = if (elapsedMs > 0) sourceMs.toDouble() / elapsedMs else 0.0
            Log.i(
                TAG,
                "$label: ${elapsedMs}ms, ${"%.2f".format(speed)}x 实时, " +
                    "${output.length() / 1024} KB",
            )
            assertFalse("$label 速度异常", speed.isNaN() || speed < 0)
        } finally {
            output.delete()
        }
    }

    private fun spec(containerId: String, resolution: ResolutionOption) = ConversionSpec(
        source = info,
        container = FormatCatalog.containers.first { it.id == containerId },
        videoCodec = VideoCodecs.H264,
        audioCodec = AudioCodecs.AAC,
        resolution = resolution,
        quality = Quality.BALANCED,
    )

    private companion object {
        const val TAG = "PerformanceTest"
        const val SAMPLE = "mp4_h264_aac_1080p.mp4"
    }
}
