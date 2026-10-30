package com.yuarmy.videoconvert.ui.convert

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yuarmy.videoconvert.domain.model.CodecNames
import com.yuarmy.videoconvert.domain.model.CodecOption
import com.yuarmy.videoconvert.domain.model.ContainerOption
import com.yuarmy.videoconvert.domain.model.FormatCatalog
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.ResolutionOption
import com.yuarmy.videoconvert.domain.model.VideoInfo
import com.yuarmy.videoconvert.engine.EngineRoute
import com.yuarmy.videoconvert.service.ConversionController
import com.yuarmy.videoconvert.ui.common.formatDuration
import com.yuarmy.videoconvert.ui.common.formatSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(
    onBack: () -> Unit,
    onStarted: () -> Unit,
    viewModel: ConvertViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val route by viewModel.route.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val startConversion: () -> Unit = {
        val spec = viewModel.currentSpec()
        when (val currentRoute = route) {
            null -> Unit

            is EngineRoute.Unsupported -> {
                scope.launch { snackbarHostState.showSnackbar(currentRoute.reason) }
            }

            is EngineRoute.Media3 -> {
                if (spec != null) {
                    ConversionController.submit(context, spec)
                    onStarted()
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { startConversion() }

    val onStartClick: () -> Unit = {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (missing.isEmpty()) startConversion() else permissionLauncher.launch(missing.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("转换设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            state.error != null -> ErrorState(
                message = state.error.orEmpty(),
                padding = padding,
                onBack = onBack,
            )

            else -> {
                val info = state.info
                val container = state.container
                val videoCodec = state.videoCodec
                val audioCodec = state.audioCodec
                if (info != null && container != null && videoCodec != null && audioCodec != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SourceCard(info)
                        FormatCard(
                            container = container,
                            videoCodec = videoCodec,
                            audioCodec = audioCodec,
                            resolution = state.resolution,
                            quality = state.quality,
                            onContainer = viewModel::selectContainer,
                            onVideoCodec = viewModel::selectVideoCodec,
                            onAudioCodec = viewModel::selectAudioCodec,
                            onResolution = viewModel::selectResolution,
                            onQuality = viewModel::selectQuality,
                        )
                        RouteHint(route)
                        Button(
                            onClick = onStartClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("开始转换", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteHint(route: EngineRoute?) {
    val (text, isError) = when (route) {
        is EngineRoute.Media3 -> {
            if (route.remux) "将直接流拷贝，无画质损失" to false
            else "将使用设备硬件编码" to false
        }

        is EngineRoute.Unsupported -> route.reason to true
        null -> return
    }
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun SourceCard(info: VideoInfo) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = info.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            InfoRow("时长", formatDuration(info.durationMs))
            InfoRow("大小", formatSize(info.sizeBytes))
            val width = info.orientedWidth
            val height = info.orientedHeight
            val resolution = if (width != null && height != null) {
                buildString {
                    append("$width × $height")
                    if (info.rotationDegrees != 0) append(" · 旋转 ${info.rotationDegrees}°")
                }
            } else {
                "未知"
            }
            InfoRow("分辨率", resolution)
            InfoRow("视频编码", CodecNames.label(info.videoMime))
            InfoRow("音频编码", info.audioMime?.let(CodecNames::label) ?: "无")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FormatCard(
    container: ContainerOption,
    videoCodec: CodecOption,
    audioCodec: CodecOption,
    resolution: ResolutionOption,
    quality: Quality,
    onContainer: (ContainerOption) -> Unit,
    onVideoCodec: (CodecOption) -> Unit,
    onAudioCodec: (CodecOption) -> Unit,
    onResolution: (ResolutionOption) -> Unit,
    onQuality: (Quality) -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "输出格式",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            DropdownField(
                label = "容器",
                selected = container,
                options = FormatCatalog.containers,
                optionLabel = { it.label },
                onSelect = onContainer,
            )
            DropdownField(
                label = "视频编码",
                selected = videoCodec,
                options = container.videoCodecs,
                optionLabel = { it.label },
                onSelect = onVideoCodec,
            )
            DropdownField(
                label = "音频编码",
                selected = audioCodec,
                options = container.audioCodecs,
                optionLabel = { it.label },
                onSelect = onAudioCodec,
            )
            DropdownField(
                label = "分辨率",
                selected = resolution,
                options = FormatCatalog.resolutions,
                optionLabel = { it.label },
                onSelect = onResolution,
            )
            DropdownField(
                label = "质量",
                selected = quality,
                options = Quality.entries,
                optionLabel = { it.label },
                onSelect = onQuality,
            )
        }
    }
}

@Composable
private fun <T> DropdownField(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = optionLabel(selected), style = MaterialTheme.typography.bodyLarge)
            }
            Text(text = "▾", style = MaterialTheme.typography.bodyLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    padding: PaddingValues,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "无法读取这个视频",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onBack) {
            Text("返回")
        }
    }
}
