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
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuarmy.videoconvert.domain.model.AudioCodecs
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.FormatCatalog
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.ResolutionOption
import com.yuarmy.videoconvert.domain.model.VideoCodecs
import com.yuarmy.videoconvert.domain.model.VideoInfo
import com.yuarmy.videoconvert.engine.EngineRoute
import com.yuarmy.videoconvert.engine.EngineRouter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoDecodeScannerTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratchFiles = mutableListOf<File>()

    private lateinit var sourceInfo: VideoInfo

    @Before
    fun setUp() {
        val asset = InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_h264_aac.mp4")
        val sample = File(context.cacheDir, "scan-test/sample_h264_aac.mp4")
        sample.parentFile?.mkdirs()
        asset.use { input -> sample.outputStream().use(input::copyTo) }
        scratchFiles += sample
        sourceInfo = MediaProbe.probe(context, Uri.fromFile(sample))
    }

    @After
    fun tearDown() {
        scratchFiles.forEach { it.delete() }
    }

    @Test
    fun check_shouldPassForSystemDecodableVideo() {
        val file = VideoFileEntry(
            uri = sourceInfo.uri,
            displayName = sourceInfo.displayName,
            mimeType = "video/mp4",
            sizeBytes = sourceInfo.sizeBytes,
        )
        assertNull(VideoDecodeScanner.check(context, file))
    }

    @Test
    fun check_shouldReportUnsupportedContainer() {
        val broken = File(context.cacheDir, "scan-test/broken.mp4").apply {
            parentFile?.mkdirs()
            writeText("this is not a video file")
        }
        scratchFiles += broken

        val failure = VideoDecodeScanner.check(
            context,
            VideoFileEntry(
                uri = Uri.fromFile(broken),
                displayName = broken.name,
                mimeType = "video/mp4",
                sizeBytes = broken.length(),
            ),
        )
        assertNotNull(failure)
        assertEquals(DecodeFailureKind.CONTAINER_UNSUPPORTED, failure?.kind)
    }

    @Test
    fun route_shouldSendUndecodableSourceToFfmpeg() {
        val remuxSpec = spec(
            resolution = FormatCatalog.resolutions.first(),
            quality = Quality.AUTO,
        ).copy(sourceUndecodable = true)
        val remuxRoute = EngineRouter.route(remuxSpec)
        assertTrue(remuxRoute is EngineRoute.Ffmpeg)
        assertTrue((remuxRoute as EngineRoute.Ffmpeg).remux)

        val transcodeSpec = spec(
            resolution = ResolutionOption(480, "480p"),
            quality = Quality.BALANCED,
        ).copy(sourceUndecodable = true)
        val transcodeRoute = EngineRouter.route(transcodeSpec)
        assertTrue(transcodeRoute is EngineRoute.Ffmpeg)
        assertFalse((transcodeRoute as EngineRoute.Ffmpeg).remux)
    }

    private fun spec(resolution: ResolutionOption, quality: Quality): ConversionSpec {
        return ConversionSpec(
            source = sourceInfo,
            container = FormatCatalog.containers.first { it.id == "mp4" },
            videoCodec = VideoCodecs.H264,
            audioCodec = AudioCodecs.AAC,
            resolution = resolution,
            quality = quality,
        )
    }
}
