package com.yuarmy.videoconvert.domain.model

import android.net.Uri

sealed interface JobState {
    data object Idle : JobState

    data object Preparing : JobState

    data class Running(
        val percent: Float,
        val note: String? = null,
    ) : JobState

    data object Saving : JobState

    data class Success(
        val outputUri: Uri,
        val displayName: String,
    ) : JobState

    data class Failed(val message: String) : JobState

    data object Cancelled : JobState
}
