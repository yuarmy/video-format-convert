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

package com.yuarmy.videoconvert.engine

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import android.app.Instrumentation

/**
 * adb 推到应用外部目录的文件在 Android 15 的 FUSE 下只能列目录、读不到内容，
 * 因此素材放在 /data/local/tmp，测试启动时通过 UiAutomation 以 shell 身份复制到应用内部目录。
 */
internal object MatrixMedia {

    private const val DEFAULT_REMOTE_DIR = "/data/local/tmp/vc-matrix"

    fun localDir(context: Context): File? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val remote = InstrumentationRegistry.getArguments()
            .getString("matrixSrc")
            ?.trimEnd('/')
            ?: DEFAULT_REMOTE_DIR
        val names = runCatching { listRemote(instrumentation, remote) }.getOrNull()
            ?: return null
        if (names.isEmpty()) return null

        val dir = File(context.filesDir, "matrix")
        dir.mkdirs()
        for (name in names) {
            val target = File(dir, name)
            if (target.isFile && target.length() > 0) continue
            runCatching { copyFromShell(instrumentation, "$remote/$name", target) }
        }
        return dir.takeIf {
            it.listFiles()?.any { file -> file.isFile && file.length() > 0 } == true
        }
    }

    private fun listRemote(instrumentation: Instrumentation, remote: String): List<String> =
        shell(instrumentation, "ls -1 $remote")
            .lines()
            .map(String::trim)
            .filter(String::isNotEmpty)

    private fun copyFromShell(instrumentation: Instrumentation, source: String, target: File) {
        instrumentation.uiAutomation.executeShellCommand("cat $source").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun shell(instrumentation: Instrumentation, command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileReader(descriptor.fileDescriptor).use { it.readText() }
        }
}
