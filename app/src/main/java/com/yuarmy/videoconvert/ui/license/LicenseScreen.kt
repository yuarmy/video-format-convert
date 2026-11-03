/*
 * Copyright (C) 2026 yuarmy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.yuarmy.videoconvert.ui.license

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class LicenseDoc(
    val id: String,
    val name: String,
    val description: String,
    val license: String,
    val assetPath: String,
    val sourceUrl: String,
)

private const val GPL_TEXT = "licenses/gpl-3.0.txt"
private const val APACHE_TEXT = "licenses/apache-2.0.txt"
private const val EDL_TEXT = "licenses/eclipse-distribution-license-1.0.txt"

private val LicenseDocs = listOf(
    LicenseDoc(
        id = "app",
        name = "视频转换",
        description = "本应用完整源代码随发行版提供",
        license = "GPL-3.0-or-later",
        assetPath = GPL_TEXT,
        sourceUrl = "https://www.gnu.org/licenses/gpl-3.0.html",
    ),
    LicenseDoc(
        id = "ffmpeg",
        name = "FFmpeg",
        description = "音视频转换引擎，启用 --enable-gpl（含 x264 / x265 / LAME）",
        license = "GPL-3.0-or-later",
        assetPath = GPL_TEXT,
        sourceUrl = "https://ffmpeg.org/legal.html",
    ),
    LicenseDoc(
        id = "media3",
        name = "AndroidX Media3",
        description = "硬件转码、流拷贝与媒体封装",
        license = "Apache-2.0",
        assetPath = APACHE_TEXT,
        sourceUrl = "https://www.apache.org/licenses/LICENSE-2.0",
    ),
    LicenseDoc(
        id = "paho",
        name = "Eclipse Paho MQTT",
        description = "设备登录后的 MQTT 上报客户端",
        license = "EPL-2.0 / EDL-1.0（按 EDL-1.0 使用）",
        assetPath = EDL_TEXT,
        sourceUrl = "https://www.eclipse.org/paho/",
    ),
    LicenseDoc(
        id = "androidx",
        name = "AndroidX Jetpack",
        description = "Compose、Navigation、Lifecycle、Activity",
        license = "Apache-2.0",
        assetPath = APACHE_TEXT,
        sourceUrl = "https://www.apache.org/licenses/LICENSE-2.0",
    ),
    LicenseDoc(
        id = "kotlin",
        name = "Kotlin 标准库与协程",
        description = "Kotlin、kotlinx.coroutines",
        license = "Apache-2.0",
        assetPath = APACHE_TEXT,
        sourceUrl = "https://www.apache.org/licenses/LICENSE-2.0",
    ),
)

@Composable
fun LicenseScreen(onBack: () -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = LicenseDocs.firstOrNull { it.id == selectedId }

    if (selected != null) {
        BackHandler { selectedId = null }
        LicenseDetailScreen(doc = selected, onBack = { selectedId = null })
    } else {
        LicenseListScreen(onBack = onBack, onSelect = { selectedId = it.id })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseListScreen(
    onBack: () -> Unit,
    onSelect: (LicenseDoc) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开源许可") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LicenseIntroCard() }
            item {
                Text(
                    text = "应用与第三方组件",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(LicenseDocs, key = { it.id }) { doc ->
                LicenseRow(doc = doc, onClick = { onSelect(doc) })
            }
            item {
                Text(
                    text = "点击组件可查看并复制完整许可证文本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun LicenseIntroCard() {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "GNU General Public License v3",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "本应用以 GPL-3.0-or-later 发布，并内置启用 GPL 的 FFmpeg，" +
                    "因此整体以 GPL 分发。你可以自由使用、研究、修改和再分发，" +
                    "但再分发时须以同一许可证公开源代码，且不提供任何担保。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LicenseRow(doc: LicenseDoc, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = doc.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = doc.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = doc.license,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseDetailScreen(
    doc: LicenseDoc,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var content by remember(doc.id) { mutableStateOf<String?>(null) }
    var error by remember(doc.id) { mutableStateOf<String?>(null) }
    var attempt by remember(doc.id) { mutableIntStateOf(0) }

    LaunchedEffect(doc.id, attempt) {
        content = null
        error = null
        runCatching { readAsset(context, doc.assetPath) }
            .onSuccess { content = it }
            .onFailure { error = it.message ?: "无法读取许可证文本" }
    }

    fun copyText() {
        val text = content ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(doc.license, text))
        scope.launch { snackbarHostState.showSnackbar("已复制完整许可证文本") }
    }

    val paragraphs = remember(content) { content.orEmpty().toParagraphs() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(doc.license) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = ::copyText, enabled = content != null) {
                        Text("复制全文")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            DetailHeader(
                doc = doc,
                onOpenSource = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(doc.sourceUrl)))
                    }.onFailure {
                        scope.launch { snackbarHostState.showSnackbar("没有可用的浏览器") }
                    }
                },
            )

            when {
                error != null -> ErrorContent(
                    message = error.orEmpty(),
                    onRetry = { attempt++ },
                )

                content == null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                else -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    SelectionContainer {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(paragraphs) { paragraph ->
                                Text(
                                    text = paragraph,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 18.sp,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(
    doc: LicenseDoc,
    onOpenSource: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = doc.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = doc.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = doc.license,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            OutlinedButton(onClick = onOpenSource) {
                Text("在浏览器打开原文")
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "无法读取许可证文本",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onRetry) {
            Text("重试")
        }
    }
}

private suspend fun readAsset(context: Context, path: String): String = withContext(Dispatchers.IO) {
    context.assets.open(path).bufferedReader().use { it.readText() }
}

private fun String.toParagraphs(): List<String> =
    split(Regex("\\n[ \\t]*\\n"))
        .map { it.trim('\n') }
        .filter { it.isNotBlank() }
