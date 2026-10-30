package com.yuarmy.videoconvert.domain.model

data class CodecOption(val id: String, val label: String)

data class ContainerOption(
    val id: String,
    val label: String,
    val extension: String,
    val videoCodecs: List<CodecOption>,
    val audioCodecs: List<CodecOption>,
)

data class ResolutionOption(val maxHeight: Int?, val label: String)

enum class Quality(val label: String) {
    AUTO("自动"),
    HIGH("高质量"),
    BALANCED("均衡"),
    SMALL("小体积"),
}

object VideoCodecs {
    val H264 = CodecOption("h264", "H.264")
    val H265 = CodecOption("h265", "H.265 / HEVC")
    val VP8 = CodecOption("vp8", "VP8")
    val VP9 = CodecOption("vp9", "VP9")
    val AV1 = CodecOption("av1", "AV1")
    val MPEG4 = CodecOption("mpeg4", "MPEG-4")
    val MPEG2 = CodecOption("mpeg2", "MPEG-2")
}

object AudioCodecs {
    val AAC = CodecOption("aac", "AAC")
    val MP3 = CodecOption("mp3", "MP3")
    val OPUS = CodecOption("opus", "Opus")
    val VORBIS = CodecOption("vorbis", "Vorbis")
    val FLAC = CodecOption("flac", "FLAC")
    val AC3 = CodecOption("ac3", "AC-3")
    val PCM = CodecOption("pcm", "PCM")
}

object FormatCatalog {
    val containers = listOf(
        ContainerOption(
            id = "mp4",
            label = "MP4",
            extension = "mp4",
            videoCodecs = listOf(VideoCodecs.H264, VideoCodecs.H265, VideoCodecs.AV1),
            audioCodecs = listOf(AudioCodecs.AAC),
        ),
        ContainerOption(
            id = "mkv",
            label = "MKV",
            extension = "mkv",
            videoCodecs = listOf(VideoCodecs.H264, VideoCodecs.H265, VideoCodecs.VP9, VideoCodecs.AV1),
            audioCodecs = listOf(AudioCodecs.AAC, AudioCodecs.OPUS, AudioCodecs.FLAC, AudioCodecs.MP3, AudioCodecs.AC3),
        ),
        ContainerOption(
            id = "webm",
            label = "WebM",
            extension = "webm",
            videoCodecs = listOf(VideoCodecs.VP8, VideoCodecs.VP9, VideoCodecs.AV1),
            audioCodecs = listOf(AudioCodecs.OPUS, AudioCodecs.VORBIS),
        ),
        ContainerOption(
            id = "mov",
            label = "MOV",
            extension = "mov",
            videoCodecs = listOf(VideoCodecs.H264, VideoCodecs.H265),
            audioCodecs = listOf(AudioCodecs.AAC, AudioCodecs.PCM),
        ),
        ContainerOption(
            id = "avi",
            label = "AVI",
            extension = "avi",
            videoCodecs = listOf(VideoCodecs.MPEG4),
            audioCodecs = listOf(AudioCodecs.MP3, AudioCodecs.PCM),
        ),
        ContainerOption(
            id = "flv",
            label = "FLV",
            extension = "flv",
            videoCodecs = listOf(VideoCodecs.H264),
            audioCodecs = listOf(AudioCodecs.AAC, AudioCodecs.MP3),
        ),
        ContainerOption(
            id = "ts",
            label = "TS",
            extension = "ts",
            videoCodecs = listOf(VideoCodecs.H264, VideoCodecs.H265, VideoCodecs.MPEG2),
            audioCodecs = listOf(AudioCodecs.AAC, AudioCodecs.AC3),
        ),
    )

    val resolutions = listOf(
        ResolutionOption(null, "保持原始"),
        ResolutionOption(2160, "2160p (4K)"),
        ResolutionOption(1080, "1080p"),
        ResolutionOption(720, "720p"),
        ResolutionOption(480, "480p"),
    )
}
