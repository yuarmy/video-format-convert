package com.yuarmy.videoconvert.ui.convert

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.yuarmy.videoconvert.data.MediaProbe
import com.yuarmy.videoconvert.domain.model.CodecOption
import com.yuarmy.videoconvert.domain.model.ContainerOption
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.FormatCatalog
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.ResolutionOption
import com.yuarmy.videoconvert.domain.model.VideoInfo
import com.yuarmy.videoconvert.engine.EngineRoute
import com.yuarmy.videoconvert.engine.EngineRouter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ConvertUiState(
    val loading: Boolean = true,
    val info: VideoInfo? = null,
    val error: String? = null,
    val container: ContainerOption? = null,
    val videoCodec: CodecOption? = null,
    val audioCodec: CodecOption? = null,
    val resolution: ResolutionOption = FormatCatalog.resolutions.first(),
    val quality: Quality = Quality.AUTO,
)

class ConvertViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

    private val sourceUri: Uri? = savedStateHandle.get<String>("uri")?.let(Uri::parse)

    private val _uiState = MutableStateFlow(ConvertUiState())
    val uiState: StateFlow<ConvertUiState> = _uiState.asStateFlow()

    val route: StateFlow<EngineRoute?> = uiState
        .map { state -> state.toSpec()?.let(EngineRouter::route) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun currentSpec(): ConversionSpec? = _uiState.value.toSpec()

    init {
        val uri = sourceUri
        if (uri == null) {
            _uiState.update { it.copy(loading = false, error = "未收到视频地址") }
        } else {
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { MediaProbe.probe(getApplication(), uri) }
                }
                result
                    .onSuccess(::onProbed)
                    .onFailure { error ->
                        _uiState.update {
                            it.copy(loading = false, error = error.message ?: "读取视频信息失败")
                        }
                    }
            }
        }
    }

    private fun onProbed(info: VideoInfo) {
        val container = FormatCatalog.containers.first()
        _uiState.update {
            it.copy(
                loading = false,
                error = null,
                info = info,
                container = container,
                videoCodec = container.videoCodecs.first(),
                audioCodec = container.audioCodecs.first(),
            )
        }
    }

    fun selectContainer(container: ContainerOption) {
        _uiState.update { state ->
            val videoCodec = state.videoCodec?.takeIf { it in container.videoCodecs }
                ?: container.videoCodecs.first()
            val audioCodec = state.audioCodec?.takeIf { it in container.audioCodecs }
                ?: container.audioCodecs.first()
            state.copy(container = container, videoCodec = videoCodec, audioCodec = audioCodec)
        }
    }

    fun selectVideoCodec(codec: CodecOption) {
        _uiState.update { it.copy(videoCodec = codec) }
    }

    fun selectAudioCodec(codec: CodecOption) {
        _uiState.update { it.copy(audioCodec = codec) }
    }

    fun selectResolution(resolution: ResolutionOption) {
        _uiState.update { it.copy(resolution = resolution) }
    }

    fun selectQuality(quality: Quality) {
        _uiState.update { it.copy(quality = quality) }
    }
}

private fun ConvertUiState.toSpec(): ConversionSpec? {
    val info = info ?: return null
    val container = container ?: return null
    val videoCodec = videoCodec ?: return null
    val audioCodec = audioCodec ?: return null
    return ConversionSpec(
        source = info,
        container = container,
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        resolution = resolution,
        quality = quality,
    )
}
