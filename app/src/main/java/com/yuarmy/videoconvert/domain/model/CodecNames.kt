package com.yuarmy.videoconvert.domain.model

object CodecNames {
    private val labels = mapOf(
        "video/avc" to "H.264",
        "video/hevc" to "H.265 / HEVC",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/av01" to "AV1",
        "video/mp4v-es" to "MPEG-4",
        "video/mpeg2" to "MPEG-2",
        "audio/mp4a-latm" to "AAC",
        "audio/mpeg" to "MP3",
        "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis",
        "audio/flac" to "FLAC",
        "audio/ac3" to "AC-3",
    )

    fun label(mime: String?): String = when {
        mime == null -> "未知"
        else -> labels[mime] ?: mime
    }
}
