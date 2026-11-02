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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuarmy.videoconvert.data.MediaProbe
import com.yuarmy.videoconvert.domain.model.AudioCodecs
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.FormatCatalog
import com.yuarmy.videoconvert.domain.model.JobState
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.VideoCodecs
import com.yuarmy.videoconvert.domain.model.VideoInfo
import com.yuarmy.videoconvert.service.ConversionController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class FfmpegEngineTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()

    @Before
    fun setUp() {
        assertTrue(
            "libffmpeg_jni.so 加载失败：${FfmpegNative.loadFailureMessage}",
            FfmpegNative.available,
        )
    }

    @After
    fun tearDown() {
        files.forEach { it.delete() }
        // 取消标志会留在 native 全局里，复位以免影响后续用例。
        if (FfmpegNative.available) FfmpegNative.cancel()
    }

    @Test
    fun nativeLibraryEncodesRawVideo() {
        val input = rawVideo(frames = 30)
        val output = file("native.mp4")

        val exitCode = FfmpegNative.run(
            arrayOf(
                "-y", "-hide_banner",
                "-f", "rawvideo",
                "-pix_fmt", "yuv420p",
                "-s", "${WIDTH}x$HEIGHT",
                "-r", "30",
                "-i", input.absolutePath,
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "30",
                "-pix_fmt", "yuv420p",
                output.absolutePath,
            ),
            noopCallback,
        )

        assertEquals("FFmpeg 退出码", 0, exitCode)
        assertTrue("输出文件为空", output.length() > 0)
    }

    @Test
    fun engineConvertsThroughCommandBuilder() = runBlocking {
        val source = encodeSourceMp4()
        val info = infoFor(source, audioMime = null)
        val spec = ConversionSpec(
            source = info,
            container = FormatCatalog.containers.first { it.id == "mkv" },
            videoCodec = VideoCodecs.H264,
            audioCodec = AudioCodecs.AAC,
            resolution = FormatCatalog.resolutions.first(),
            quality = Quality.HIGH,
            sourceUndecodable = true,
        )

        var lastPercent = -1f
        val result = FfmpegEngine(context).convert(spec) { progress ->
            lastPercent = maxOf(lastPercent, progress.percent)
        }

        assertTrue("转换失败：$result", result is ConversionResult.Success)
        result as ConversionResult.Success
        assertTrue("输出文件为空", result.output.length() > 0)
        assertTrue("没有收到进度回调", lastPercent >= 0f)
        assertTrue("进度没有推进", lastPercent > 0f)
    }

    @Test
    fun serviceConvertsToMkvAndPublishesToGallery() = runBlocking {
        val asset = InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_h264_aac.mp4")
        val sample = file("service_source.mp4")
        asset.use { input -> sample.outputStream().use(input::copyTo) }
        val info = MediaProbe.probe(context, Uri.fromFile(sample))
        val spec = ConversionSpec(
            source = info,
            container = FormatCatalog.containers.first { it.id == "mkv" },
            videoCodec = VideoCodecs.H264,
            audioCodec = AudioCodecs.AAC,
            resolution = FormatCatalog.resolutions.first(),
            quality = Quality.AUTO,
        )

        ConversionController.reset()
        assertTrue(EngineRouter.route(spec) is EngineRoute.Ffmpeg)
        ConversionController.submit(context, spec)

        val terminal = withTimeout(120_000) {
            ConversionController.state.first { state ->
                state is JobState.Success || state is JobState.Failed || state is JobState.Cancelled
            }
        }
        assertTrue("service ended in $terminal", terminal is JobState.Success)

        val success = terminal as JobState.Success
        try {
            val saved = context.contentResolver.openInputStream(success.outputUri)
                ?.use { it.readBytes() }
            assertNotNull(saved)
            assertTrue(saved!!.isNotEmpty())
        } finally {
            context.contentResolver.delete(success.outputUri, null, null)
        }
    }

    @Test
    fun routeCopiesVideoWhenOnlyAudioChanges() {
        val source = encodeSourceMp4()
        val spec = ConversionSpec(
            source = infoFor(source, audioMime = "audio/ac3"),
            container = FormatCatalog.containers.first { it.id == "mp4" },
            videoCodec = VideoCodecs.H264,
            audioCodec = AudioCodecs.AAC,
            resolution = FormatCatalog.resolutions.first(),
            quality = Quality.AUTO,
        )

        val route = EngineRouter.route(spec)
        assertTrue(route is EngineRoute.Ffmpeg)
        assertFalse((route as EngineRoute.Ffmpeg).remux)
    }

    @Test
    fun cancelStopsRunningCommand() {
        val input = rawVideo(frames = 600)
        val output = file("cancel.mp4")
        val exitCode = AtomicInteger(Int.MIN_VALUE)

        val worker = thread(name = "ffmpeg-cancel-test") {
            exitCode.set(
                FfmpegNative.run(
                    arrayOf(
                        "-y", "-hide_banner",
                        "-re",
                        "-f", "rawvideo",
                        "-pix_fmt", "yuv420p",
                        "-s", "${WIDTH}x$HEIGHT",
                        "-r", "30",
                        "-i", input.absolutePath,
                        "-c:v", "libx264",
                        "-preset", "veryfast",
                        "-crf", "30",
                        output.absolutePath,
                    ),
                    noopCallback,
                ),
            )
        }

        Thread.sleep(1500)
        FfmpegNative.cancel()
        worker.join(15_000)

        assertFalse("取消后 FFmpeg 仍在运行", worker.isAlive)
        assertNotEquals("取消后应返回非零退出码", 0, exitCode.get())
    }

    private fun infoFor(source: File, audioMime: String?): VideoInfo = VideoInfo(
        uri = Uri.fromFile(source),
        displayName = source.name,
        sizeBytes = source.length(),
        durationMs = 1000,
        width = WIDTH,
        height = HEIGHT,
        rotationDegrees = 0,
        bitrate = null,
        videoMime = "video/avc",
        audioMime = audioMime,
        frameRate = 30f,
    )

    private fun encodeSourceMp4(): File {
        val input = rawVideo(frames = 30)
        val output = file("source.mp4")
        val exitCode = FfmpegNative.run(
            arrayOf(
                "-y", "-hide_banner",
                "-f", "rawvideo",
                "-pix_fmt", "yuv420p",
                "-s", "${WIDTH}x$HEIGHT",
                "-r", "30",
                "-i", input.absolutePath,
                "-c:v", "libx264",
                "-preset", "veryfast",
                "-crf", "30",
                "-pix_fmt", "yuv420p",
                output.absolutePath,
            ),
            noopCallback,
        )
        assertEquals("生成测试素材失败", 0, exitCode)
        return output
    }

    private fun rawVideo(frames: Int): File {
        val file = file("raw_${frames}.yuv")
        val frame = ByteArray(WIDTH * HEIGHT * 3 / 2)
        java.io.FileOutputStream(file).use { out ->
            repeat(frames) { index ->
                for (y in 0 until HEIGHT) {
                    for (x in 0 until WIDTH) {
                        frame[y * WIDTH + x] = ((x + y + index * 3) and 0xFF).toByte()
                    }
                }
                frame.fill(128.toByte(), WIDTH * HEIGHT, frame.size)
                out.write(frame)
            }
        }
        return file
    }

    private fun file(name: String): File =
        File(context.cacheDir, "ffmpeg-test/$name").apply {
            parentFile?.mkdirs()
            delete()
            files += this
        }

    private companion object {
        const val WIDTH = 128
        const val HEIGHT = 128
        val noopCallback = FfmpegNative.LogCallback { _, _ -> }
    }
}
