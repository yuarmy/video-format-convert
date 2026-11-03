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
import android.content.SharedPreferences
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MqttCredentials(
    val host: String,
    val port: Int,
    val clientId: String,
    val username: String,
    val password: String,
    val infoTopic: String,
    val eventTopic: String,
)

data class ReportConfig(
    val serverUrl: String,
    val deviceId: String,
    val mqtt: MqttCredentials,
)

object ReportSettings {

    private const val PREFS = "report_settings"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_INSTALL_ID = "install_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MQTT_HOST = "mqtt_host"
    private const val KEY_MQTT_PORT = "mqtt_port"
    private const val KEY_MQTT_CLIENT_ID = "mqtt_client_id"
    private const val KEY_MQTT_USERNAME = "mqtt_username"
    private const val KEY_MQTT_PASSWORD = "mqtt_password"
    private const val KEY_INFO_TOPIC = "info_topic"
    private const val KEY_EVENT_TOPIC = "event_topic"

    private lateinit var prefs: SharedPreferences

    private val _config = MutableStateFlow<ReportConfig?>(null)
    val config: StateFlow<ReportConfig?> = _config.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    fun install(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _serverUrl.value = prefs.getString(KEY_SERVER_URL, null).orEmpty()
        _config.value = readConfig()
    }

    /** 同一安装实例沿用 installId，卸载重装视为新设备。 */
    fun installId(): String = synchronized(this) {
        prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() } ?: run {
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
            generated
        }
    }

    fun saveLogin(serverUrl: String, deviceId: String, mqtt: MqttCredentials): ReportConfig {
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        val config = ReportConfig(normalizedUrl, deviceId, mqtt)
        synchronized(this) {
            prefs.edit()
                .putString(KEY_SERVER_URL, normalizedUrl)
                .putString(KEY_DEVICE_ID, deviceId)
                .putString(KEY_MQTT_HOST, mqtt.host)
                .putInt(KEY_MQTT_PORT, mqtt.port)
                .putString(KEY_MQTT_CLIENT_ID, mqtt.clientId)
                .putString(KEY_MQTT_USERNAME, mqtt.username)
                .putString(KEY_MQTT_PASSWORD, mqtt.password)
                .putString(KEY_INFO_TOPIC, mqtt.infoTopic)
                .putString(KEY_EVENT_TOPIC, mqtt.eventTopic)
                .apply()
        }
        _serverUrl.value = normalizedUrl
        _config.value = config
        return config
    }

    /** 保留服务器地址与 installId，便于重新登录。 */
    fun logout() {
        synchronized(this) {
            prefs.edit()
                .remove(KEY_DEVICE_ID)
                .remove(KEY_MQTT_HOST)
                .remove(KEY_MQTT_PORT)
                .remove(KEY_MQTT_CLIENT_ID)
                .remove(KEY_MQTT_USERNAME)
                .remove(KEY_MQTT_PASSWORD)
                .remove(KEY_INFO_TOPIC)
                .remove(KEY_EVENT_TOPIC)
                .apply()
        }
        _config.value = null
    }

    private fun readConfig(): ReportConfig? {
        val serverUrl = prefs.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val deviceId = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val host = prefs.getString(KEY_MQTT_HOST, null)?.takeIf { it.isNotBlank() } ?: return null
        val port = prefs.getInt(KEY_MQTT_PORT, 0).takeIf { it in 1..65535 } ?: return null
        val clientId = prefs.getString(KEY_MQTT_CLIENT_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val username = prefs.getString(KEY_MQTT_USERNAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val password = prefs.getString(KEY_MQTT_PASSWORD, null)?.takeIf { it.isNotBlank() } ?: return null
        val infoTopic = prefs.getString(KEY_INFO_TOPIC, null)?.takeIf { it.isNotBlank() } ?: return null
        val eventTopic = prefs.getString(KEY_EVENT_TOPIC, null)?.takeIf { it.isNotBlank() } ?: return null
        return ReportConfig(
            serverUrl = serverUrl,
            deviceId = deviceId,
            mqtt = MqttCredentials(
                host = host,
                port = port,
                clientId = clientId,
                username = username,
                password = password,
                infoTopic = infoTopic,
                eventTopic = eventTopic,
            ),
        )
    }
}
