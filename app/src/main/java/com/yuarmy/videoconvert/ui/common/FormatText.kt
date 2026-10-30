package com.yuarmy.videoconvert.ui.common

import java.util.Locale

fun formatDuration(durationMs: Long?): String {
    if (durationMs == null || durationMs <= 0) return "未知"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatSize(sizeBytes: Long?): String {
    if (sizeBytes == null || sizeBytes <= 0) return "未知"
    val megabytes = sizeBytes / 1024.0 / 1024.0
    return if (megabytes >= 1024) {
        String.format(Locale.US, "%.1f GB", megabytes / 1024)
    } else {
        String.format(Locale.US, "%.1f MB", megabytes)
    }
}
