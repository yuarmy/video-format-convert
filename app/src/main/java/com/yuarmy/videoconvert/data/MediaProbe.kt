package com.yuarmy.videoconvert.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.yuarmy.videoconvert.domain.model.VideoInfo
import java.io.IOException

object MediaProbe {

    fun probe(context: Context, uri: Uri): VideoInfo {
        val displayName = readDisplayName(context, uri)
        val size = readSize(context, uri)
        val (videoMime, audioMime) = readTrackMimes(context, uri)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                throw IOException("这个文件里没有视频轨道")
            }
            return VideoInfo(
                uri = uri,
                displayName = displayName,
                sizeBytes = size,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                rotationDegrees = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull(),
                videoMime = videoMime,
                audioMime = audioMime,
                frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull(),
            )
        } finally {
            retriever.release()
        }
    }

    /**
     * 扫描失败的文件可能无法被 MediaMetadataRetriever 打开，但仍需进入格式选择页，
     * 因此这里只用 MediaExtractor 尽力取元数据，失败时保留文件名与大小。
     */
    fun probeLenient(context: Context, uri: Uri): VideoInfo {
        val displayName = readDisplayName(context, uri)
        val size = readSize(context, uri)
        var videoMime: String? = null
        var audioMime: String? = null
        var durationMs: Long? = null
        var width: Int? = null
        var height: Int? = null
        var rotationDegrees = 0

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (durationMs == null) {
                    durationMs = runCatching {
                        format.getLong(MediaFormat.KEY_DURATION) / 1_000
                    }.getOrNull()
                }
                if (videoMime == null && mime.startsWith("video/")) {
                    videoMime = mime
                    width = runCatching { format.getInteger(MediaFormat.KEY_WIDTH) }.getOrNull()
                    height = runCatching { format.getInteger(MediaFormat.KEY_HEIGHT) }.getOrNull()
                    rotationDegrees = runCatching {
                        format.getInteger(MediaFormat.KEY_ROTATION)
                    }.getOrDefault(0)
                } else if (audioMime == null && mime.startsWith("audio/")) {
                    audioMime = mime
                }
            }
        } catch (_: RuntimeException) {
        } catch (_: IOException) {
        } finally {
            extractor.release()
        }

        return VideoInfo(
            uri = uri,
            displayName = displayName,
            sizeBytes = size,
            durationMs = durationMs,
            width = width,
            height = height,
            rotationDegrees = rotationDegrees,
            bitrate = null,
            videoMime = videoMime,
            audioMime = audioMime,
            frameRate = null,
        )
    }

    private fun readDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "未命名视频"
    }

    private fun readSize(context: Context, uri: Uri): Long? {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return null
    }

    private fun readTrackMimes(context: Context, uri: Uri): Pair<String?, String?> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            var videoMime: String? = null
            var audioMime: String? = null
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    videoMime == null && mime.startsWith("video/") -> videoMime = mime
                    audioMime == null && mime.startsWith("audio/") -> audioMime = mime
                }
            }
            videoMime to audioMime
        } catch (_: RuntimeException) {
            null to null
        } catch (_: IOException) {
            null to null
        } finally {
            extractor.release()
        }
    }
}
