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

package com.yuarmy.videoconvert.report

import android.content.Context
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.JobState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class ReportUiState(
    val loggedIn: Boolean = false,
    val deviceId: String? = null,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val busy: Boolean = false,
    val error: String? = null,
)

object ReportManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushMutex = Mutex()

    private var appContext: Context? = null
    private var reporter: MqttReporter? = null
    private var installed = false

    @Volatile
    private var deviceInfoPending = false

    private val _uiState = MutableStateFlow(ReportUiState())
    val uiState: StateFlow<ReportUiState> = _uiState.asStateFlow()

    fun install(context: Context) {
        if (installed) return
        installed = true
        appContext = context.applicationContext
        ReportSettings.install(context.applicationContext)
        ReportOutbox.install(context.applicationContext)
        startIfLoggedIn()
    }

    fun startIfLoggedIn() {
        val context = appContext ?: return
        if (_uiState.value.loggedIn) return
        val config = ReportSettings.config.value ?: return
        _uiState.value = ReportUiState(loggedIn = true, deviceId = config.deviceId)
        connect(config)
    }

    fun login(serverUrl: String, enrollToken: String) {
        val context = appContext ?: return
        if (_uiState.value.busy) return
        val token = enrollToken.trim()
        if (serverUrl.isBlank() || token.isEmpty()) {
            _uiState.update { it.copy(error = "请填写服务器地址和注册码") }
            return
        }
        _uiState.update { it.copy(busy = true, error = null) }
        scope.launch {
            try {
                val client = DeviceInfo.collect(context)
                val result = LoginApi.login(serverUrl, token, ReportSettings.installId(), client)
                val config = ReportSettings.saveLogin(serverUrl, result.deviceId, result.mqtt)
                val previous = reporter
                reporter = null
                previous?.let { scope.launch { it.shutdown() } }
                _uiState.value = ReportUiState(
                    loggedIn = true,
                    deviceId = config.deviceId,
                    connection = ConnectionState.CONNECTING,
                )
                connect(config)
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(busy = false, error = error.message ?: "登录失败")
                }
            }
        }
    }

    fun logout() {
        _uiState.value = ReportUiState()
        deviceInfoPending = false
        ReportOutbox.clear()
        ReportSettings.logout()
        val previous = reporter
        reporter = null
        previous?.let { scope.launch { it.shutdown() } }
    }

    fun onConversionFinished(
        spec: ConversionSpec,
        state: JobState,
        jobId: Long,
        startedAtMs: Long?,
    ) {
        if (!installed || !_uiState.value.loggedIn) return
        val finishedAtMs = System.currentTimeMillis()
        scope.launch {
            runCatching {
                val config = ReportSettings.config.value ?: return@runCatching
                if (!_uiState.value.loggedIn) return@runCatching
                val payload = ReportPayload.conversionResult(
                    jobId = jobId,
                    startedAtMs = startedAtMs,
                    finishedAtMs = finishedAtMs,
                    state = state,
                    spec = spec,
                )
                ReportOutbox.add(
                    OutboxMessage(
                        eventId = payload.getString("eventId"),
                        topic = config.mqtt.eventTopic,
                        payload = payload.toString(),
                        createdAt = finishedAtMs,
                    ),
                )
                requestFlush()
            }
        }
    }

    private fun connect(config: ReportConfig) {
        var created: MqttReporter? = null
        created = MqttReporter(
            credentials = config.mqtt,
            onStateChanged = { state ->
                if (reporter === created) {
                    _uiState.update { current -> current.copy(connection = state) }
                }
            },
            onConnected = {
                if (reporter === created) {
                    deviceInfoPending = true
                    requestFlush()
                }
            },
        )
        reporter = created
        created.connect()
    }

    private fun requestFlush() {
        if (!installed) return
        scope.launch {
            if (!flushMutex.tryLock()) return@launch
            try {
                flushLocked()
            } finally {
                flushMutex.unlock()
            }
        }
    }

    private fun flushLocked() {
        val current = reporter ?: return
        val context = appContext ?: return
        val config = ReportSettings.config.value ?: return
        if (!_uiState.value.loggedIn) return

        if (deviceInfoPending) {
            val info = runCatching { DeviceInfo.collect(context) }.getOrNull()
            if (info != null) {
                val sent = runCatching {
                    current.publishBlocking(config.mqtt.infoTopic, ReportPayload.deviceInfo(info).toString())
                }.isSuccess
                if (sent) {
                    deviceInfoPending = false
                }
            }
        }

        while (true) {
            val pending = ReportOutbox.all()
            if (pending.isEmpty()) return
            for (message in pending) {
                if (reporter !== current || !_uiState.value.loggedIn) return
                val sent = runCatching {
                    current.publishBlocking(message.topic, message.payload)
                }.isSuccess
                if (!sent) return
                ReportOutbox.remove(message.eventId)
            }
        }
    }
}
