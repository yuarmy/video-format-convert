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

package com.yuarmy.videoconvert.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuarmy.videoconvert.data.SettingsStore
import com.yuarmy.videoconvert.report.ConnectionState
import com.yuarmy.videoconvert.report.ReportManager
import com.yuarmy.videoconvert.report.ReportSettings
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val savedFolder by SettingsStore.outputFolder.collectAsStateWithLifecycle()
    var folderInput by rememberSaveable(savedFolder) { mutableStateOf(savedFolder) }
    val effectiveFolder = SettingsStore.sanitize(folderInput)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "输出目录",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "转换完成的视频会保存到相册 Movies/$effectiveFolder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = folderInput,
                        onValueChange = { folderInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Movies 下的文件夹名") },
                        supportingText = { Text("非法字符会自动替换为下划线") },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            onClick = { folderInput = SettingsStore.DEFAULT_OUTPUT_FOLDER },
                        ) {
                            Text("恢复默认")
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                val saved = SettingsStore.setOutputFolder(folderInput)
                                folderInput = saved
                                scope.launch {
                                    snackbarHostState.showSnackbar("输出目录已保存")
                                }
                            },
                            enabled = effectiveFolder != savedFolder,
                        ) {
                            Text("保存")
                        }
                    }
                }
            }

            ReportServiceCard()

            SettingsRow(
                title = "转换历史",
                subtitle = "查看记录、再次转换或清空",
                onClick = onOpenHistory,
            )
            SettingsRow(
                title = "开源许可",
                subtitle = "GPL-3.0-or-later · 第三方组件",
                onClick = onOpenLicenses,
            )
        }
    }
}

@Composable
private fun ReportServiceCard() {
    val reportState by ReportManager.uiState.collectAsStateWithLifecycle()
    var serverUrl by rememberSaveable { mutableStateOf(ReportSettings.serverUrl.value) }
    var enrollToken by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(reportState.loggedIn) {
        if (reportState.loggedIn) {
            enrollToken = ""
        }
    }

    val statusText = when {
        reportState.busy -> "登录中…"
        reportState.loggedIn -> when (reportState.connection) {
            ConnectionState.CONNECTED -> "已连接"
            ConnectionState.CONNECTING -> "连接中…"
            ConnectionState.DISCONNECTED -> "未连接，将自动重试"
        }
        reportState.error != null -> reportState.error.orEmpty()
        else -> "未登录"
    }
    val statusColor = if (reportState.error != null && !reportState.loggedIn) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "上报服务",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "登录后，每次转换结束会把设备信息与结果推送到服务端看板。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("服务器地址") },
                placeholder = { Text("http://192.168.1.10:3000") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = enrollToken,
                onValueChange = { enrollToken = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("注册码") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = statusColor,
            )
            reportState.deviceId?.let { deviceId ->
                Text(
                    text = "设备 ID：$deviceId",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (reportState.loggedIn) {
                    OutlinedButton(onClick = { ReportManager.logout() }) {
                        Text("退出")
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { ReportManager.login(serverUrl, enrollToken) },
                    enabled = !reportState.busy && serverUrl.isNotBlank() && enrollToken.isNotBlank(),
                ) {
                    if (reportState.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(if (reportState.loggedIn) "重新登录" else "登录")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
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
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
