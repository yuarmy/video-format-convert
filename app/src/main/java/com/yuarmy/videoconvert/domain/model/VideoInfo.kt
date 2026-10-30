package com.yuarmy.videoconvert.domain.model

import android.net.Uri

data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val rotationDegrees: Int,
    val bitrate: Long?,
    val videoMime: String?,
    val audioMime: String?,
    val frameRate: Float?,
) {
    val orientedWidth: Int?
        get() = if (rotationDegrees % 180 == 90) height else width

    val orientedHeight: Int?
        get() = if (rotationDegrees % 180 == 90) width else height
}
