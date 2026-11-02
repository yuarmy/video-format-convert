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

package com.yuarmy.videoconvert.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.yuarmy.videoconvert.data.HistoryEntry
import com.yuarmy.videoconvert.data.HistoryStatus
import com.yuarmy.videoconvert.data.HistoryStore
import com.yuarmy.videoconvert.data.SpecSnapshot
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.JobState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class QueueJob(
    val id: Long,
    val spec: ConversionSpec,
    val state: JobState = JobState.Queued,
)

data class QueueUiState(
    val current: QueueJob? = null,
    val pending: List<QueueJob> = emptyList(),
    val lastFinished: QueueJob? = null,
) {
    val totalCount: Int
        get() = pending.size + if (current != null) 1 else 0

    val isRunning: Boolean
        get() = current != null
}

/**
 * 进程内的转换队列。服务串行消费 [takeNext]；队列本身不跨进程持久化，
 * 历史记录由 [HistoryStore] 落盘。
 */
object ConversionController {

    private val _state = MutableStateFlow(QueueUiState())
    val state: StateFlow<QueueUiState> = _state.asStateFlow()

    private val lock = Any()
    private val pending = ArrayDeque<QueueJob>()
    private var active: QueueJob? = null
    private var lastFinished: QueueJob? = null
    private var serviceRunning = false
    private var nextJobId = System.currentTimeMillis()

    fun submit(context: Context, spec: ConversionSpec): Long {
        val id = synchronized(lock) {
            val job = QueueJob(nextJobId++, spec, JobState.Queued)
            pending.addLast(job)
            publishLocked()
            job.id
        }
        ensureService(context)
        return id
    }

    /** 把上次异常中断的任务放回队首并尝试续跑。 */
    fun resume(context: Context) {
        val hasWork = synchronized(lock) {
            val interrupted = active
            if (interrupted != null && !serviceRunning) {
                pending.addFirst(interrupted.copy(state = JobState.Queued))
                active = null
                publishLocked()
            }
            pending.isNotEmpty()
        }
        if (hasWork) ensureService(context)
    }

    fun cancel(context: Context) {
        val active = synchronized(lock) { active } ?: return
        runCatching {
            context.startService(
                Intent(context, ConversionService::class.java)
                    .setAction(ConversionService.ACTION_CANCEL)
                    .putExtra(ConversionService.EXTRA_JOB_ID, active.id),
            )
        }
    }

    fun removePending(id: Long) {
        synchronized(lock) {
            pending.removeAll { it.id == id }
            publishLocked()
        }
    }

    fun clearPending() {
        synchronized(lock) {
            pending.clear()
            publishLocked()
        }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    internal fun hasPending(): Boolean = synchronized(lock) { pending.isNotEmpty() }

    internal fun takeNext(): QueueJob? = synchronized(lock) {
        val next = pending.removeFirstOrNull() ?: return null
        val started = next.copy(state = JobState.Preparing)
        active = started
        publishLocked()
        started
    }

    internal fun report(jobId: Long, jobState: JobState) {
        var finished: QueueJob? = null
        synchronized(lock) {
            val job = active?.takeIf { it.id == jobId } ?: return
            if (!jobState.isTerminal) {
                active = job.copy(state = jobState)
                publishLocked()
                return
            }
            val done = job.copy(state = jobState)
            active = null
            lastFinished = done
            publishLocked()
            finished = done
        }
        finished?.let(::recordHistory)
    }

    /** 服务准备退出时调用；返回 true 表示队列已空，可以停止服务。 */
    internal fun onLoopExhausted(): Boolean = synchronized(lock) {
        if (pending.isEmpty()) {
            serviceRunning = false
            true
        } else {
            false
        }
    }

    internal fun onServiceStopped() {
        var interrupted: QueueJob? = null
        synchronized(lock) {
            serviceRunning = false
            val job = active
            if (job != null) {
                active = null
                interrupted = job.copy(state = JobState.Cancelled)
                lastFinished = interrupted
            }
            publishLocked()
        }
        interrupted?.let(::recordHistory)
    }

    fun reset() {
        synchronized(lock) {
            pending.clear()
            active = null
            lastFinished = null
            serviceRunning = false
            publishLocked()
        }
    }

    private fun ensureService(context: Context) {
        val shouldStart = synchronized(lock) {
            if (serviceRunning) {
                false
            } else {
                serviceRunning = true
                true
            }
        }
        if (!shouldStart) return

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ConversionService::class.java)
                    .setAction(ConversionService.ACTION_START),
            )
        }.onFailure { error ->
            val failed = synchronized(lock) {
                val jobs = listOfNotNull(active) + pending
                active = null
                pending.clear()
                serviceRunning = false
                publishLocked()
                jobs
            }
            failed.forEach { job ->
                recordHistory(job.copy(state = JobState.Failed(error.message ?: "无法启动转换服务")))
            }
        }
    }

    private fun publishLocked() {
        _state.value = QueueUiState(
            current = active,
            pending = pending.toList(),
            lastFinished = lastFinished,
        )
    }

    private fun recordHistory(job: QueueJob) {
        val state = job.state
        val entry = HistoryEntry(
            id = job.id,
            sourceName = job.spec.source.displayName,
            targetSummary = targetSummary(job.spec),
            status = when (state) {
                is JobState.Success -> HistoryStatus.SUCCESS
                is JobState.Failed -> HistoryStatus.FAILED
                else -> HistoryStatus.CANCELLED
            },
            finishedAt = System.currentTimeMillis(),
            outputUri = (state as? JobState.Success)?.outputUri?.toString(),
            outputName = (state as? JobState.Success)?.displayName,
            message = (state as? JobState.Failed)?.message,
            spec = SpecSnapshot.from(job.spec),
        )
        HistoryStore.record(entry)
    }

    private fun targetSummary(spec: ConversionSpec): String = buildString {
        append(spec.container.label)
        append(" · ")
        append(spec.videoCodec.label)
        if (spec.resolution.maxHeight != null) {
            append(" · ")
            append(spec.resolution.label)
        }
        append(" · ")
        append(spec.quality.label)
    }
}

internal val JobState.isTerminal: Boolean
    get() = this is JobState.Success || this is JobState.Failed || this is JobState.Cancelled
