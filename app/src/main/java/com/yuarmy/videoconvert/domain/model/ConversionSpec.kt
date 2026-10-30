package com.yuarmy.videoconvert.domain.model

data class ConversionSpec(
    val source: VideoInfo,
    val container: ContainerOption,
    val videoCodec: CodecOption,
    val audioCodec: CodecOption,
    val resolution: ResolutionOption,
    val quality: Quality,
)
