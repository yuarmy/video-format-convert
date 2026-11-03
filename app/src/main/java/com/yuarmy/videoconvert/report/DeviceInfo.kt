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
import android.os.Build
import org.json.JSONObject

data class ClientInfo(
    val appVersion: String,
    val appVersionCode: Int,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abi: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("appVersion", appVersion)
        put("appVersionCode", appVersionCode)
        put("manufacturer", manufacturer)
        put("brand", brand)
        put("model", model)
        put("device", device)
        put("androidRelease", androidRelease)
        put("sdkInt", sdkInt)
        put("abi", abi)
    }
}

object DeviceInfo {

    fun collect(context: Context): ClientInfo {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            packageInfo.versionCode.toLong()
        }
        return ClientInfo(
            appVersion = packageInfo.versionName ?: "unknown",
            appVersionCode = versionCode.coerceIn(0, 2_000_000_000).toInt(),
            manufacturer = Build.MANUFACTURER.ifBlank { "unknown" },
            brand = Build.BRAND.ifBlank { "unknown" },
            model = Build.MODEL.ifBlank { "unknown" },
            device = Build.DEVICE.ifBlank { "unknown" },
            androidRelease = Build.VERSION.RELEASE.ifBlank { "unknown" },
            sdkInt = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        )
    }
}
