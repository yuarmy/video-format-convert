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

import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
}

class MqttReporter(
    private val credentials: MqttCredentials,
    private val onStateChanged: (ConnectionState) -> Unit,
    private val onConnected: () -> Unit,
) {

    private val client = MqttAsyncClient(
        "tcp://${credentials.host}:${credentials.port}",
        credentials.clientId,
        MemoryPersistence(),
    )

    init {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                onStateChanged(ConnectionState.CONNECTED)
                onConnected()
            }

            override fun connectionLost(cause: Throwable?) {
                onStateChanged(ConnectionState.DISCONNECTED)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) = Unit

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
    }

    fun connect() {
        onStateChanged(ConnectionState.CONNECTING)
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            keepAliveInterval = 60
            connectionTimeout = 10
            userName = credentials.username
            password = credentials.password.toCharArray()
        }
        try {
            client.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    onStateChanged(ConnectionState.DISCONNECTED)
                }
            })
        } catch (_: MqttException) {
            onStateChanged(ConnectionState.DISCONNECTED)
        }
    }

    fun publishBlocking(topic: String, payload: String) {
        if (!client.isConnected) {
            throw MqttException(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED.toInt())
        }
        val token = client.publish(topic, payload.toByteArray(Charsets.UTF_8), 1, false)
        token.waitForCompletion(PUBLISH_TIMEOUT_MS)
    }

    fun shutdown() {
        runCatching {
            if (client.isConnected) {
                client.disconnect().waitForCompletion(SHUTDOWN_TIMEOUT_MS)
            }
        }
        runCatching { client.close() }
    }

    private companion object {
        const val PUBLISH_TIMEOUT_MS = 8_000L
        const val SHUTDOWN_TIMEOUT_MS = 2_000L
    }
}
