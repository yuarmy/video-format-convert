package com.yuarmy.videoconvert.engine

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuarmy.videoconvert.data.MediaProbe
import com.yuarmy.videoconvert.data.MediaStoreSaver
import com.yuarmy.videoconvert.domain.model.AudioCodecs
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.FormatCatalog
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.ResolutionOption
import com.yuarmy.videoconvert.domain.model.JobState
import com.yuarmy.videoconvert.domain.model.VideoCodecs
import com.yuarmy.videoconvert.domain.model.VideoInfo
import com.yuarmy.videoconvert.service.ConversionController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Media3EngineTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratchFiles = mutableListOf<File>()

    private lateinit var sourceUri: Uri
    private lateinit var sourceInfo: VideoInfo

    @Before
    fun setUp() {
        val asset = InstrumentationRegistry.getInstrumentation().context.assets
            .open("sample_h264_aac.mp4")
        val sample = File(context.cacheDir, "engine-test/sample_h264_aac.mp4")
        sample.parentFile?.mkdirs()
        asset.use { input -> sample.outputStream().use(input::copyTo) }
        sourceUri = Uri.fromFile(sample)
        sourceInfo = MediaProbe.probe(context, sourceUri)
    }

    @After
    fun tearDown() {
        scratchFiles.forEach { it.delete() }
    }

    @Test
    fun remux_shouldStreamCopyWhenFormatMatches() = runBlocking {
        val spec = spec(
            resolution = FormatCatalog.resolutions.first { it.maxHeight == null },
            quality = Quality.AUTO,
        )
        val route = EngineRouter.route(spec)
        assertTrue(route is EngineRoute.Media3)
        assertTrue((route as EngineRoute.Media3).remux)

        val result = Media3Engine(context, remux = true).convert(spec) {}
        assertTrue(result is ConversionResult.Success)
        val output = (result as ConversionResult.Success).output
        scratchFiles += output

        assertTrue(output.length() > 0)
        val outputInfo = MediaProbe.probe(context, Uri.fromFile(output))
        assertEquals(sourceInfo.orientedWidth, outputInfo.orientedWidth)
        assertEquals(sourceInfo.orientedHeight, outputInfo.orientedHeight)
        assertEquals(
            sourceInfo.durationMs?.toDouble() ?: 0.0,
            outputInfo.durationMs?.toDouble() ?: 0.0,
            1000.0,
        )
    }

    @Test
    fun transcode_shouldDownscaleToRequestedHeight() = runBlocking {
        val spec = spec(
            resolution = ResolutionOption(480, "480p"),
            quality = Quality.BALANCED,
        )
        val route = EngineRouter.route(spec)
        assertTrue(route is EngineRoute.Media3)
        assertFalse((route as EngineRoute.Media3).remux)

        val result = Media3Engine(context, remux = false).convert(spec) {}
        assertTrue(result is ConversionResult.Success)
        val output = (result as ConversionResult.Success).output
        scratchFiles += output

        assertTrue(output.length() > 0)
        val outputInfo = MediaProbe.probe(context, Uri.fromFile(output))
        assertEquals(480.0, outputInfo.orientedHeight?.toDouble() ?: 0.0, 2.0)
    }

    @Test
    fun saveToMediaStore_shouldPublishAndClearPendingFlag() {
        val bytes = ByteArray(4096) { index -> index.toByte() }
        val source = File(context.cacheDir, "engine-test/saver-source.mp4").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
        scratchFiles += source

        val displayName = "vc_saver_test_${System.currentTimeMillis()}.mp4"
        val uri = MediaStoreSaver.save(context, source, displayName, "video/mp4")

        try {
            val saved = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            assertNotNull(saved)
            assertEquals(bytes.size, saved?.size)

            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                val projection = arrayOf(MediaStore.Video.Media.IS_PENDING)
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        } finally {
            if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                context.contentResolver.delete(uri, null, null)
            } else {
                uri.path?.let { File(it).delete() }
            }
        }
    }

    @Test
    fun service_shouldRunConversionAndPublishToGallery() = runBlocking {
        ConversionController.reset()
        val spec = spec(
            resolution = FormatCatalog.resolutions.first { it.maxHeight == null },
            quality = Quality.AUTO,
        )
        ConversionController.submit(context, spec)

        val terminal = withTimeout(60_000) {
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
    fun service_shouldStopWhenCancelled() = runBlocking {
        ConversionController.reset()
        val spec = spec(
            resolution = ResolutionOption(480, "480p"),
            quality = Quality.HIGH,
        )
        ConversionController.submit(context, spec)

        withTimeout(10_000) {
            ConversionController.state.first { it is JobState.Running }
        }
        ConversionController.cancel(context)

        val terminal = withTimeout(30_000) {
            ConversionController.state.first { state ->
                state is JobState.Cancelled || state is JobState.Failed || state is JobState.Success
            }
        }
        assertTrue("service ended in $terminal", terminal is JobState.Cancelled)
    }

    @Test
    fun route_shouldRejectContainersWithoutFfmpeg() {
        val mkv = FormatCatalog.containers.first { it.id == "mkv" }
        val spec = ConversionSpec(
            source = sourceInfo,
            container = mkv,
            videoCodec = VideoCodecs.H264,
            audioCodec = AudioCodecs.AAC,
            resolution = FormatCatalog.resolutions.first(),
            quality = Quality.AUTO,
        )
        assertTrue(EngineRouter.route(spec) is EngineRoute.Unsupported)
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
