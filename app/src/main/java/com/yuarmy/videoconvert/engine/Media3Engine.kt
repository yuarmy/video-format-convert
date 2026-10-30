package com.yuarmy.videoconvert.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.yuarmy.videoconvert.data.MediaProbe
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.VideoCodecs
import com.yuarmy.videoconvert.domain.model.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
class Media3Engine(
    private val context: Context,
    private val remux: Boolean,
) : ConversionEngine {

    @Volatile
    private var transformer: Transformer? = null

    @Volatile
    private var fallbackApplied = false

    override suspend fun probe(uri: Uri): VideoInfo = withContext(Dispatchers.IO) {
        MediaProbe.probe(context, uri)
    }

    override suspend fun convert(
        spec: ConversionSpec,
        onProgress: (ConversionProgress) -> Unit,
    ): ConversionResult {
        val outputFile = File(
            context.cacheDir,
            "conversions/${System.currentTimeMillis()}.${spec.container.extension}",
        )
        outputFile.parentFile?.mkdirs()
        outputFile.delete()

        val completion = CompletableDeferred<ConversionResult>()
        val transformer = withContext(Dispatchers.Main.immediate) {
            var built: Transformer? = null
            try {
                built = buildTransformer(spec, outputFile, completion)
                this@Media3Engine.transformer = built
                built.start(buildEditedMediaItem(spec), outputFile.absolutePath)
                built
            } catch (error: Exception) {
                this@Media3Engine.transformer = null
                runCatching { built?.cancel() }
                completion.complete(
                    ConversionResult.Failure(error.message ?: "无法启动转换", error),
                )
                null
            }
        }

        if (transformer == null) {
            outputFile.delete()
            return completion.await()
        }

        val result = try {
            coroutineScope {
                val progressJob = launch(Dispatchers.Main.immediate) {
                    val holder = ProgressHolder()
                    while (isActive) {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(
                                ConversionProgress(
                                    percent = (holder.progress / 100f).coerceIn(0f, 1f),
                                    note = if (fallbackApplied) {
                                        "硬件编码不可用，已切换软件编码"
                                    } else {
                                        null
                                    },
                                ),
                            )
                        }
                        delay(200)
                    }
                }
                try {
                    completion.await()
                } finally {
                    progressJob.cancel()
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                transformer.cancel()
            }
            outputFile.delete()
            throw cancelled
        } finally {
            this.transformer = null
        }

        if (result !is ConversionResult.Success) {
            outputFile.delete()
        }
        return result
    }

    override fun cancel() {
        transformer?.cancel()
    }

    private fun buildTransformer(
        spec: ConversionSpec,
        outputFile: File,
        completion: CompletableDeferred<ConversionResult>,
    ): Transformer {
        val builder = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (!completion.isCompleted) {
                        completion.complete(ConversionResult.Success(outputFile))
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    if (!completion.isCompleted) {
                        completion.complete(
                            ConversionResult.Failure(
                                exportException.message ?: "转换失败",
                                exportException,
                            ),
                        )
                    }
                }

                override fun onFallbackApplied(
                    composition: Composition,
                    originalTransformationRequest: TransformationRequest,
                    fallbackTransformationRequest: TransformationRequest,
                ) {
                    fallbackApplied = true
                }
            })

        if (!remux) {
            builder.setVideoMimeType(videoMime(spec.videoCodec.id))
            if (spec.source.audioMime != null) {
                builder.setAudioMimeType(MimeTypes.AUDIO_AAC)
            }

            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setEnableFallback(true)
                .apply {
                    bitrateFor(spec)?.let { bitrate ->
                        setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(bitrate).build(),
                        )
                    }
                }
                .build()
            builder.setEncoderFactory(encoderFactory)
        }

        return builder.build()
    }

    private fun buildEditedMediaItem(spec: ConversionSpec): EditedMediaItem {
        val builder = EditedMediaItem.Builder(MediaItem.fromUri(spec.source.uri))
        val maxHeight = spec.resolution.maxHeight
        if (!remux && maxHeight != null && (spec.source.orientedHeight ?: 0) > maxHeight) {
            builder.setEffects(
                Effects(emptyList(), listOf(Presentation.createForHeight(maxHeight))),
            )
        }
        return builder.build()
    }

    private fun videoMime(codecId: String): String = when (codecId) {
        VideoCodecs.H264.id -> MimeTypes.VIDEO_H264
        VideoCodecs.H265.id -> MimeTypes.VIDEO_H265
        else -> error("Media3 快路径不支持编码 $codecId")
    }

    private fun bitrateFor(spec: ConversionSpec): Int? {
        if (spec.quality == Quality.AUTO) return null

        val sourceWidth = spec.source.orientedWidth ?: return null
        val sourceHeight = spec.source.orientedHeight ?: return null
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val targetHeight = minOf(sourceHeight, spec.resolution.maxHeight ?: sourceHeight)
        val targetWidth = (sourceWidth.toDouble() * targetHeight / sourceHeight).roundToInt()
        val frameRate = spec.source.frameRate ?: 30f
        val bitsPerPixel = when (spec.quality) {
            Quality.HIGH -> 0.12
            Quality.BALANCED -> 0.06
            Quality.SMALL -> 0.025
            Quality.AUTO -> return null
        }

        return (targetWidth.toLong() * targetHeight * frameRate * bitsPerPixel)
            .toInt()
            .coerceIn(300_000, 20_000_000)
    }
}
