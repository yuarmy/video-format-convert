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
import org.json.JSONArray
import org.json.JSONObject

data class OutboxMessage(
    val eventId: String,
    val topic: String,
    val payload: String,
    val createdAt: Long,
)

/** 离线待发消息；eventId 跨重试保持不变，服务端据此幂等。 */
object ReportOutbox {

    private const val PREFS = "report_outbox"
    private const val KEY_MESSAGES = "messages"
    private const val MAX_MESSAGES = 200

    private lateinit var prefs: SharedPreferences
    private val lock = Any()

    fun install(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun add(message: OutboxMessage) {
        synchronized(lock) {
            val updated = (load() + message).takeLast(MAX_MESSAGES)
            persist(updated)
        }
    }

    fun all(): List<OutboxMessage> = synchronized(lock) { load() }

    fun remove(eventId: String) {
        synchronized(lock) {
            persist(load().filterNot { it.eventId == eventId })
        }
    }

    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY_MESSAGES).apply()
        }
    }

    private fun load(): List<OutboxMessage> {
        val raw = prefs.getString(KEY_MESSAGES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    runCatching {
                        val json = array.getJSONObject(index)
                        OutboxMessage(
                            eventId = json.getString("eventId"),
                            topic = json.getString("topic"),
                            payload = json.getString("payload"),
                            createdAt = json.optLong("createdAt"),
                        )
                    }.getOrNull()?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(messages: List<OutboxMessage>) {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(JSONObject().apply {
                put("eventId", message.eventId)
                put("topic", message.topic)
                put("payload", message.payload)
                put("createdAt", message.createdAt)
            })
        }
        prefs.edit().putString(KEY_MESSAGES, array.toString()).apply()
    }
}
