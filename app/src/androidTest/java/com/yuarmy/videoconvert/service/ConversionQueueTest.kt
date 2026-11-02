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
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuarmy.videoconvert.data.HistoryStatus
import com.yuarmy.videoconvert.data.HistoryStore
import com.yuarmy.videoconvert.data.MediaProbe
import com.yuarmy.videoconvert.domain.model.AudioCodecs
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.FormatCatalog
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.VideoCodecs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConversionQueueTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratchFiles = mutableListOf<File>()
    private lateinit var spec: ConversionSpec

    @Before
    fun setUp() {
        HistoryStore.install(context)
        val asset = InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_h264_aac.mp4")
        val sample = File(context.cacheDir, "queue-test/sample_h264_aac.mp4")
        sample.parentFile?.mkdirs()
        asset.use { input -> sample.outputStream().use(input::copyTo) }
        scratchFiles += sample
        spec = ConversionSpec(
            source = MediaProbe.probe(context, Uri.fromFile(sample)),
            container = FormatCatalog.containers.first { it.id == "mp4" },
            videoCodec = VideoCodecs.H264,
            audioCodec = AudioCodecs.AAC,
            resolution = FormatCatalog.resolutions.first(),
            quality = Quality.AUTO,
        )
    }

    @After
    fun tearDown() {
        scratchFiles.forEach { it.delete() }
        ConversionController.reset()
    }

    @Test
    fun queueRunsTwoJobsAndRecordsHistory() = runBlocking {
        val firstId = ConversionController.submit(context, spec)
        val secondId = ConversionController.submit(context, spec)
        assertNotEquals(firstId, secondId)

        val entries = withTimeout(120_000) {
            HistoryStore.entries.first { list ->
                list.count { it.id == firstId || it.id == secondId } == 2
            }
        }
        val ours = entries.filter { it.id == firstId || it.id == secondId }

        try {
            assertEquals(2, ours.size)
            assertTrue("状态异常：${ours.map { it.status }}", ours.all { it.status == HistoryStatus.SUCCESS })
            ours.forEach { entry ->
                val uri = Uri.parse(entry.outputUri)
                val saved = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                assertTrue(saved != null && saved.isNotEmpty())
            }
        } finally {
            ours.forEach { entry ->
                entry.outputUri?.let { context.contentResolver.delete(Uri.parse(it), null, null) }
                HistoryStore.remove(entry.id)
            }
        }
    }
}
