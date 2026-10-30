package com.yuarmy.videoconvert.engine

import android.net.Uri
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.VideoInfo
import java.io.File

interface ConversionEngine {
    suspend fun probe(uri: Uri): VideoInfo

    suspend fun convert(
        spec: ConversionSpec,
        onProgress: (ConversionProgress) -> Unit,
    ): ConversionResult

    fun cancel()
}

data class ConversionProgress(
    val percent: Float,
    val speed: Float? = null,
    val etaMs: Long? = null,
    val note: String? = null,
)

sealed interface ConversionResult {
    data class Success(val output: File) : ConversionResult

    data class Failure(val message: String, val cause: Throwable? = null) : ConversionResult

    data object Cancelled : ConversionResult
}
