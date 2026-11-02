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

package com.yuarmy.videoconvert.ui.scan

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yuarmy.videoconvert.data.DecodeFailure
import com.yuarmy.videoconvert.data.VideoDecodeScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScanUiState(
    val directoryName: String? = null,
    val scanning: Boolean = false,
    val total: Int = 0,
    val scanned: Int = 0,
    val currentName: String? = null,
    val failures: List<DecodeFailure> = emptyList(),
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    val error: String? = null,
)

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var lastTreeUri: Uri? = null
    private var generation = 0

    fun startScan(treeUri: Uri) {
        lastTreeUri = treeUri
        scanJob?.cancel()
        val current = ++generation
        _uiState.value = ScanUiState(scanning = true)
        scanJob = viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    VideoDecodeScanner.treeDisplayName(getApplication(), treeUri)
                }
                if (current != generation) return@launch
                _uiState.update { it.copy(directoryName = name) }

                val report = withContext(Dispatchers.IO) {
                    VideoDecodeScanner.scan(
                        context = getApplication(),
                        treeUri = treeUri,
                        onProgress = { progress ->
                            if (current == generation) {
                                _uiState.update {
                                    it.copy(
                                        total = progress.total,
                                        scanned = progress.scanned,
                                        currentName = progress.currentName,
                                    )
                                }
                            }
                        },
                        onFailure = { failure ->
                            if (current == generation) {
                                _uiState.update { it.copy(failures = it.failures + failure) }
                            }
                        },
                    )
                }

                if (current != generation) return@launch
                _uiState.update {
                    it.copy(
                        scanning = false,
                        total = report.scanned,
                        scanned = report.scanned,
                        currentName = null,
                        failures = report.failures,
                        finished = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                if (current == generation) {
                    _uiState.update {
                        it.copy(
                            scanning = false,
                            currentName = null,
                            finished = true,
                            cancelled = true,
                        )
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                if (current == generation) {
                    _uiState.update {
                        it.copy(
                            scanning = false,
                            currentName = null,
                            error = error.message ?: "扫描失败",
                        )
                    }
                }
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
    }

    fun rescan() {
        lastTreeUri?.let(::startScan)
    }

    fun reset() {
        generation++
        scanJob?.cancel()
        _uiState.value = ScanUiState()
    }

    override fun onCleared() {
        scanJob?.cancel()
    }
}
