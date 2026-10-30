package com.yuarmy.videoconvert.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.coroutines.coroutineContext

data class VideoFileEntry(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
)

enum class DecodeFailureKind(val label: String) {
    CONTAINER_UNSUPPORTED("容器不支持"),
    NO_VIDEO_TRACK("没有视频轨道"),
    DECODER_MISSING("缺少解码器"),
    DECODER_INIT_FAILED("解码器初始化失败"),
    DECODE_FAILED("解码失败"),
}

data class DecodeFailure(
    val file: VideoFileEntry,
    val kind: DecodeFailureKind,
    val detail: String?,
    val videoMime: String? = null,
)

data class ScannerProgress(
    val total: Int,
    val scanned: Int,
    val currentName: String?,
)

data class ScanReport(
    val scanned: Int,
    val failures: List<DecodeFailure>,
)

object VideoDecodeScanner {

    private const val MAX_SAMPLE_FRAMES = 8
    private const val DECODE_TIMEOUT_MS = 2_000L

    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "f4v", "ts", "m2ts", "mts",
        "mpg", "mpeg", "wmv", "asf", "rm", "rmvb", "3gp", "3g2", "ogv", "vob", "divx", "mxf",
    )

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    fun treeDisplayName(context: Context, treeUri: Uri): String? {
        val rootUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.query(
                rootUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    suspend fun listVideos(context: Context, treeUri: Uri): List<VideoFileEntry> {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return emptyList()
        val videos = mutableListOf<VideoFileEntry>()
        val visited = mutableSetOf<String>()
        val pending = ArrayDeque<String>()
        pending += rootId

        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val parentId = pending.removeFirst()
            if (!visited.add(parentId)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val cursor = runCatching {
                context.contentResolver.query(childrenUri, projection, null, null, null)
            }.getOrNull() ?: continue

            cursor.use {
                while (it.moveToNext()) {
                    val documentId = it.getString(0) ?: continue
                    val displayName = it.getString(1) ?: "未命名视频"
                    val mimeType = it.getString(2)
                    val size = if (it.isNull(3)) null else it.getLong(3)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending += documentId
                    } else if (isVideo(displayName, mimeType)) {
                        videos += VideoFileEntry(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            displayName = displayName,
                            mimeType = mimeType,
                            sizeBytes = size,
                        )
                    }
                }
            }
        }
        return videos
    }

    suspend fun scan(
        context: Context,
        treeUri: Uri,
        onProgress: (ScannerProgress) -> Unit = {},
        onFailure: (DecodeFailure) -> Unit = {},
    ): ScanReport {
        val files = listVideos(context, treeUri)
        val failures = mutableListOf<DecodeFailure>()
        files.forEachIndexed { index, file ->
            coroutineContext.ensureActive()
            onProgress(ScannerProgress(files.size, index, file.displayName))
            val failure = check(context, file)
            if (failure != null) {
                failures += failure
                onFailure(failure)
            }
        }
        onProgress(ScannerProgress(files.size, files.size, null))
        return ScanReport(files.size, failures)
    }

    fun check(context: Context, file: VideoFileEntry): DecodeFailure? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, file.uri, null)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    trackIndex = index
                    trackFormat = format
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                return DecodeFailure(file, DecodeFailureKind.NO_VIDEO_TRACK, "未找到视频轨道")
            }

            val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            val decoderName = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(trackFormat)
            } catch (error: RuntimeException) {
                return DecodeFailure(
                    file,
                    DecodeFailureKind.DECODER_MISSING,
                    error.message ?: "系统无法匹配解码器",
                    mime,
                )
            } ?: return DecodeFailure(
                file,
                DecodeFailureKind.DECODER_MISSING,
                "系统没有 ${mime ?: "该格式"} 的解码器",
                mime,
            )

            try {
                codec = MediaCodec.createByCodecName(decoderName)
                codec.configure(trackFormat, null, null, 0)
                codec.start()
            } catch (error: MediaCodec.CodecException) {
                return DecodeFailure(file, DecodeFailureKind.DECODER_INIT_FAILED, describe(error), mime)
            } catch (error: IllegalArgumentException) {
                return DecodeFailure(
                    file,
                    DecodeFailureKind.DECODER_INIT_FAILED,
                    error.message ?: "解码器不接受该视频参数",
                    mime,
                )
            } catch (error: IllegalStateException) {
                return DecodeFailure(
                    file,
                    DecodeFailureKind.DECODER_INIT_FAILED,
                    error.message ?: "解码器状态异常",
                    mime,
                )
            }

            return decodeSample(extractor, codec, trackIndex, file, mime)
        } catch (error: IOException) {
            return DecodeFailure(
                file,
                DecodeFailureKind.CONTAINER_UNSUPPORTED,
                error.message ?: "无法读取文件",
            )
        } catch (error: RuntimeException) {
            return DecodeFailure(
                file,
                DecodeFailureKind.CONTAINER_UNSUPPORTED,
                error.message ?: "系统无法解析该容器",
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun decodeSample(
        extractor: MediaExtractor,
        codec: MediaCodec,
        trackIndex: Int,
        file: VideoFileEntry,
        mime: String?,
    ): DecodeFailure? {
        return try {
            extractor.selectTrack(trackIndex)
            val bufferInfo = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + DECODE_TIMEOUT_MS
            var inputFrames = 0

            while (inputFrames < MAX_SAMPLE_FRAMES && SystemClock.elapsedRealtime() < deadline) {
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer == null) {
                        return DecodeFailure(
                            file,
                            DecodeFailureKind.DECODE_FAILED,
                            "无法获取解码器输入缓冲区",
                            mime,
                        )
                    }
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    } else {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            sampleSize,
                            extractor.sampleTime,
                            0,
                        )
                        extractor.advance()
                        inputFrames++
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER,
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                    -> Unit

                    else -> if (outputIndex >= 0) {
                        codec.releaseOutputBuffer(outputIndex, false)
                        return null
                    }
                }
            }

            DecodeFailure(
                file,
                DecodeFailureKind.DECODE_FAILED,
                "解码器在 ${DECODE_TIMEOUT_MS}ms 内没有输出画面",
                mime,
            )
        } catch (error: MediaCodec.CodecException) {
            DecodeFailure(file, DecodeFailureKind.DECODE_FAILED, describe(error), mime)
        } catch (error: IllegalStateException) {
            DecodeFailure(
                file,
                DecodeFailureKind.DECODE_FAILED,
                error.message ?: "解码器状态异常",
                mime,
            )
        }
    }

    private fun isVideo(displayName: String, mimeType: String?): Boolean {
        if (mimeType?.startsWith("video/") == true) return true
        val extension = displayName.substringAfterLast('.', "").lowercase()
        return extension.isNotEmpty() && extension in videoExtensions
    }

    private fun describe(error: MediaCodec.CodecException): String {
        return error.diagnosticInfo.takeIf { it.isNotBlank() }
            ?: error.message.orEmpty().takeIf { it.isNotBlank() }
            ?: "codec error ${error.errorCode}"
    }
}
