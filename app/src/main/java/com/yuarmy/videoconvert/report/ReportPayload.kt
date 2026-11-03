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

import com.yuarmy.videoconvert.domain.model.CodecNames
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.JobState
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

object ReportPayload {

    fun deviceInfo(client: ClientInfo): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("type", "device_info")
        put("eventId", UUID.randomUUID().toString())
        put("reportedAt", Instant.now().toString())
        put("client", client.toJson())
    }

    fun conversionResult(
        jobId: Long,
        startedAtMs: Long?,
        finishedAtMs: Long,
        state: JobState,
        spec: ConversionSpec,
    ): JSONObject = JSONObject().apply {
        val source = spec.source
        put("schemaVersion", 1)
        put("type", "conversion_result")
        put("eventId", UUID.randomUUID().toString())
        put("jobId", jobId.toString())
        put("reportedAt", iso(finishedAtMs))
        put("status", statusOf(state))
        putNullable("startedAt", startedAtMs?.let(::iso))
        put("finishedAt", iso(finishedAtMs))
        putNullable("elapsedMs", startedAtMs?.let { (finishedAtMs - it).coerceAtLeast(0) })

        put("source", JSONObject().apply {
            put("filename", source.displayName.take(512))
            putNullable("container", source.displayName.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() })
            putNullable("sizeBytes", source.sizeBytes)
            putNullable("durationMs", source.durationMs)
            putNullable("width", source.width)
            putNullable("height", source.height)
            put("rotation", source.rotationDegrees)
            putNullable("bitrate", source.bitrate)
            putNullable("videoMime", source.videoMime)
            put("videoCodecLabel", CodecNames.label(source.videoMime))
            putNullable("audioMime", source.audioMime)
            put("audioCodecLabel", CodecNames.label(source.audioMime))
            putNullable("frameRate", source.frameRate)
        })

        put("target", JSONObject().apply {
            put("container", spec.container.id)
            put("containerLabel", spec.container.label)
            put("videoCodec", spec.videoCodec.id)
            put("videoCodecLabel", spec.videoCodec.label)
            put("audioCodec", spec.audioCodec.id)
            put("audioCodecLabel", spec.audioCodec.label)
            putNullable("maxHeight", spec.resolution.maxHeight)
            put("quality", spec.quality.name)
        })

        putNullable("error", (state as? JobState.Failed)?.message?.take(500))
    }

    private fun statusOf(state: JobState): String = when (state) {
        is JobState.Success -> "succeeded"
        is JobState.Failed -> "failed"
        else -> "cancelled"
    }

    private fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }
}
