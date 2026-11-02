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

package com.yuarmy.videoconvert.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.FormatCatalog
import com.yuarmy.videoconvert.domain.model.Quality
import com.yuarmy.videoconvert.domain.model.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class HistoryStatus {
    SUCCESS,
    FAILED,
    CANCELLED,
}

data class HistoryEntry(
    val id: Long,
    val sourceName: String,
    val targetSummary: String,
    val status: HistoryStatus,
    val finishedAt: Long,
    val outputUri: String?,
    val outputName: String?,
    val message: String?,
    val spec: SpecSnapshot?,
)

/** 转换参数的可持久化快照；目录选项在读取时从 [FormatCatalog] 还原。 */
data class SpecSnapshot(
    val source: SourceSnapshot,
    val containerId: String,
    val videoCodecId: String,
    val audioCodecId: String,
    val maxHeight: Int?,
    val qualityName: String,
    val sourceUndecodable: Boolean,
) {
    fun toSpec(): ConversionSpec? {
        val container = FormatCatalog.containers.firstOrNull { it.id == containerId } ?: return null
        val videoCodec = container.videoCodecs.firstOrNull { it.id == videoCodecId } ?: return null
        val audioCodec = container.audioCodecs.firstOrNull { it.id == audioCodecId } ?: return null
        val quality = Quality.entries.firstOrNull { it.name == qualityName } ?: return null
        val resolution = FormatCatalog.resolutions.firstOrNull { it.maxHeight == maxHeight }
            ?: FormatCatalog.resolutions.first()
        return ConversionSpec(
            source = source.toVideoInfo(),
            container = container,
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            resolution = resolution,
            quality = quality,
            sourceUndecodable = sourceUndecodable,
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("source", source.toJson())
        put("container", containerId)
        put("videoCodec", videoCodecId)
        put("audioCodec", audioCodecId)
        putNullable("maxHeight", maxHeight)
        put("quality", qualityName)
        put("sourceUndecodable", sourceUndecodable)
    }

    companion object {
        fun from(spec: ConversionSpec): SpecSnapshot = SpecSnapshot(
            source = SourceSnapshot.from(spec.source),
            containerId = spec.container.id,
            videoCodecId = spec.videoCodec.id,
            audioCodecId = spec.audioCodec.id,
            maxHeight = spec.resolution.maxHeight,
            qualityName = spec.quality.name,
            sourceUndecodable = spec.sourceUndecodable,
        )

        fun fromJson(json: JSONObject): SpecSnapshot = SpecSnapshot(
            source = SourceSnapshot.fromJson(json.getJSONObject("source")),
            containerId = json.getString("container"),
            videoCodecId = json.getString("videoCodec"),
            audioCodecId = json.getString("audioCodec"),
            maxHeight = json.nullableInt("maxHeight"),
            qualityName = json.getString("quality"),
            sourceUndecodable = json.optBoolean("sourceUndecodable", false),
        )
    }
}

data class SourceSnapshot(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val rotationDegrees: Int,
    val bitrate: Long?,
    val videoMime: String?,
    val audioMime: String?,
    val frameRate: Float?,
) {
    fun toVideoInfo(): VideoInfo = VideoInfo(
        uri = Uri.parse(uri),
        displayName = displayName,
        sizeBytes = sizeBytes,
        durationMs = durationMs,
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        bitrate = bitrate,
        videoMime = videoMime,
        audioMime = audioMime,
        frameRate = frameRate,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("uri", uri)
        put("displayName", displayName)
        putNullable("sizeBytes", sizeBytes)
        putNullable("durationMs", durationMs)
        putNullable("width", width)
        putNullable("height", height)
        put("rotationDegrees", rotationDegrees)
        putNullable("bitrate", bitrate)
        putNullable("videoMime", videoMime)
        putNullable("audioMime", audioMime)
        putNullable("frameRate", frameRate)
    }

    companion object {
        fun from(info: VideoInfo): SourceSnapshot = SourceSnapshot(
            uri = info.uri.toString(),
            displayName = info.displayName,
            sizeBytes = info.sizeBytes,
            durationMs = info.durationMs,
            width = info.width,
            height = info.height,
            rotationDegrees = info.rotationDegrees,
            bitrate = info.bitrate,
            videoMime = info.videoMime,
            audioMime = info.audioMime,
            frameRate = info.frameRate,
        )

        fun fromJson(json: JSONObject): SourceSnapshot = SourceSnapshot(
            uri = json.getString("uri"),
            displayName = json.getString("displayName"),
            sizeBytes = json.nullableLong("sizeBytes"),
            durationMs = json.nullableLong("durationMs"),
            width = json.nullableInt("width"),
            height = json.nullableInt("height"),
            rotationDegrees = json.optInt("rotationDegrees", 0),
            bitrate = json.nullableLong("bitrate"),
            videoMime = json.nullableString("videoMime"),
            audioMime = json.nullableString("audioMime"),
            frameRate = json.nullableFloat("frameRate"),
        )
    }
}

object HistoryStore {

    private const val PREFS = "conversion_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 100

    private lateinit var prefs: SharedPreferences

    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun install(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _entries.value = load()
    }

    fun record(entry: HistoryEntry) {
        synchronized(this) {
            val updated = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
            _entries.value = updated
            persist(updated)
        }
    }

    fun remove(id: Long) {
        synchronized(this) {
            val updated = _entries.value.filterNot { it.id == id }
            _entries.value = updated
            persist(updated)
        }
    }

    fun clear() {
        synchronized(this) {
            _entries.value = emptyList()
            persist(emptyList())
        }
    }

    private fun load(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    runCatching { decodeEntry(array.getJSONObject(index)) }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun HistoryEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sourceName", sourceName)
        put("targetSummary", targetSummary)
        put("status", status.name)
        put("finishedAt", finishedAt)
        putNullable("outputUri", outputUri)
        putNullable("outputName", outputName)
        putNullable("message", message)
        spec?.let { put("spec", it.toJson()) }
    }

    private fun decodeEntry(json: JSONObject): HistoryEntry = HistoryEntry(
        id = json.getLong("id"),
        sourceName = json.getString("sourceName"),
        targetSummary = json.getString("targetSummary"),
        status = HistoryStatus.valueOf(json.getString("status")),
        finishedAt = json.getLong("finishedAt"),
        outputUri = json.nullableString("outputUri"),
        outputName = json.nullableString("outputName"),
        message = json.nullableString("message"),
        spec = json.optJSONObject("spec")?.let { runCatching { SpecSnapshot.fromJson(it) }.getOrNull() },
    )
}

private fun JSONObject.putNullable(key: String, value: Any?) {
    if (value == null) put(key, JSONObject.NULL) else put(key, value)
}

private fun JSONObject.nullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONObject.nullableInt(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key)

private fun JSONObject.nullableFloat(key: String): Float? =
    if (!has(key) || isNull(key)) null else optDouble(key).toFloat()

private fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
