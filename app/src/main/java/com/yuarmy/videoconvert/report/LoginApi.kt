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

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LoginException(message: String) : Exception(message)

data class LoginResult(
    val deviceId: String,
    val mqtt: MqttCredentials,
)

object LoginApi {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    suspend fun login(
        serverUrl: String,
        enrollToken: String,
        installId: String,
        client: ClientInfo,
    ): LoginResult = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(serverUrl)
        val requestBody = JSONObject().apply {
            put("enrollToken", enrollToken)
            put("installId", installId)
            put("client", client.toJson())
        }.toString()

        val connection = URL("$base/api/device/v1/login").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw LoginException(errorMessage(text, status))
            }
            parseResult(text)
        } catch (error: LoginException) {
            throw error
        } catch (error: Exception) {
            throw LoginException("无法连接服务器：${error.message ?: error.javaClass.simpleName}")
        } finally {
            connection.disconnect()
        }
    }

    internal fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            throw LoginException("请填写服务器地址")
        }
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        val url = runCatching { URL(withScheme) }.getOrElse {
            throw LoginException("服务器地址无效")
        }
        if (url.host.isNullOrBlank()) {
            throw LoginException("服务器地址无效")
        }
        return withScheme
    }

    private fun parseResult(text: String): LoginResult {
        val json = runCatching { JSONObject(text) }.getOrElse {
            throw LoginException("登录响应不是合法 JSON")
        }
        val deviceId = json.optString("deviceId").takeIf { it.isNotBlank() }
            ?: throw LoginException("登录响应缺少 deviceId")
        val mqtt = json.optJSONObject("mqtt") ?: throw LoginException("登录响应缺少 MQTT 配置")
        val host = mqtt.optString("host").takeIf { it.isNotBlank() }
            ?: throw LoginException("登录响应缺少 MQTT 地址")
        val port = mqtt.optInt("port", 0).takeIf { it in 1..65535 }
            ?: throw LoginException("登录响应缺少 MQTT 端口")
        val clientId = mqtt.optString("clientId").takeIf { it.isNotBlank() }
            ?: throw LoginException("登录响应缺少 MQTT clientId")
        val username = mqtt.optString("username").takeIf { it.isNotBlank() }
            ?: throw LoginException("登录响应缺少 MQTT 用户名")
        val password = mqtt.optString("password").takeIf { it.isNotBlank() }
            ?: throw LoginException("登录响应缺少 MQTT 密码")
        val infoTopic = mqtt.optString("infoTopic").takeIf { it.isNotBlank() }
            ?: throw LoginException("登录响应缺少 infoTopic")
        val eventTopic = mqtt.optString("eventTopic").takeIf { it.isNotBlank() }
            ?: throw LoginException("登录响应缺少 eventTopic")
        return LoginResult(
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

    private fun errorMessage(text: String, status: Int): String {
        val message = runCatching {
            JSONObject(text).optJSONObject("error")?.optString("message")
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() } ?: "登录失败（HTTP $status）"
    }
}
