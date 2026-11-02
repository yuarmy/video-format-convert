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

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yuarmy.videoconvert.MainActivity
import com.yuarmy.videoconvert.R
import com.yuarmy.videoconvert.data.MediaStoreSaver
import com.yuarmy.videoconvert.data.SettingsStore
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.JobState
import com.yuarmy.videoconvert.engine.ConversionEngine
import com.yuarmy.videoconvert.engine.ConversionResult
import com.yuarmy.videoconvert.engine.EngineRoute
import com.yuarmy.videoconvert.engine.EngineRouter
import com.yuarmy.videoconvert.engine.FfmpegEngine
import com.yuarmy.videoconvert.engine.FfmpegNative
import com.yuarmy.videoconvert.engine.Media3Engine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

class ConversionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var conversionJob: Job? = null
    private var engine: ConversionEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var loopActive = false
    private var lastStartId = 0

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var finishedNormally = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "视频转换", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                lastStartId = startId
                startLoop()
            }
            ACTION_CANCEL -> {
                cancelRequested = true
                conversionJob?.cancel()
                engine?.cancel()
                if (loopJob?.isActive != true) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 原生 FFmpeg 不响应协程取消，必须先通知它退出，否则会继续占满 CPU。
        engine?.cancel()
        conversionJob?.cancel()
        loopJob?.cancel()
        scope.cancel()
        releaseWakeLock()
        if (!finishedNormally) {
            ConversionController.onServiceStopped()
        }
        super.onDestroy()
    }

    private fun startLoop() {
        if (loopActive) return
        loopActive = true
        startForeground(NOTIFICATION_ID, buildNotification(0, "准备中", null, indeterminate = true))
        acquireWakeLock()
        loopJob = scope.launch {
            var normalExit = false
            try {
                while (true) {
                    val job = ConversionController.takeNext()
                    if (job == null) {
                        if (ConversionController.onLoopExhausted()) {
                            normalExit = true
                            break
                        }
                        continue
                    }
                    val conversion = scope.launch { runConversion(job) }
                    conversionJob = conversion
                    conversion.join()
                    conversionJob = null
                }
            } finally {
                loopActive = false
                withContext(NonCancellable) {
                    // 队列刚清空时的并发提交可能已经排在新循环或更新 startId 上，
                    // 只有确认没有待处理任务时才允许停服务，否则任务会丢失。
                    val stopping = !loopActive &&
                        !ConversionController.hasPending() &&
                        stopSelfResult(lastStartId)
                    finishedNormally = normalExit && stopping
                    when {
                        stopping -> {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            releaseWakeLock()
                        }
                        // 还有待处理任务才自己续跑；否则等系统投递排队中的
                        // ACTION_START，避免在主线程上递归启动造成死转。
                        !loopActive && ConversionController.hasPending() -> startLoop()
                    }
                }
            }
        }
    }

    private suspend fun runConversion(job: QueueJob) {
        var output: File? = null
        cancelRequested = false
        val spec = job.spec
        try {
            updateNotification(
                percent = 0,
                text = "正在准备转换",
                jobName = spec.source.displayName,
                indeterminate = true,
            )
            // 路由可能触发 FFmpeg 原生库首次加载，不能放在服务主线程上。
            val route = withContext(Dispatchers.IO) { EngineRouter.route(spec) }
            val result = when (route) {
                is EngineRoute.Unsupported -> {
                    ConversionController.report(job.id, JobState.Failed(route.reason))
                    return
                }

                is EngineRoute.Media3 -> {
                    val primary = execute(
                        engine = Media3Engine(this, route.remux),
                        job = job,
                        defaultNote = null,
                    )
                    // Media3 运行期才失败的场景（编码器初始化失败、源容器不受支持）
                    // 统一回退到 FFmpeg，避免让用户再点一次。
                    val canFallback = withContext(Dispatchers.IO) { FfmpegNative.available }
                    if (primary is ConversionResult.Failure &&
                        !cancelRequested &&
                        canFallback
                    ) {
                        execute(
                            engine = FfmpegEngine(this),
                            job = job,
                            defaultNote = "硬件编码不可用，已切换 FFmpeg 软件编码",
                        )
                    } else {
                        primary
                    }
                }

                is EngineRoute.Ffmpeg -> execute(
                    engine = FfmpegEngine(this),
                    job = job,
                    defaultNote = route.note,
                )
            }

            when (result) {
                is ConversionResult.Success -> {
                    output = result.output
                    ConversionController.report(job.id, JobState.Saving)
                    updateNotification(
                        percent = 100,
                        text = "正在保存到相册",
                        jobName = spec.source.displayName,
                        indeterminate = true,
                    )
                    val displayName = outputDisplayName(spec)
                    val folder = SettingsStore.outputFolder.value
                    val savedUri = withContext(Dispatchers.IO) {
                        MediaStoreSaver.save(
                            context = this@ConversionService,
                            source = result.output,
                            displayName = displayName,
                            mimeType = mimeTypeFor(spec),
                            relativeFolder = folder,
                        )
                    }
                    ConversionController.report(job.id, JobState.Success(savedUri, displayName))
                }

                is ConversionResult.Failure -> {
                    ConversionController.report(
                        job.id,
                        if (cancelRequested) JobState.Cancelled else JobState.Failed(result.message),
                    )
                }

                ConversionResult.Cancelled -> {
                    ConversionController.report(job.id, JobState.Cancelled)
                }
            }
        } catch (cancelled: CancellationException) {
            // 只取消当前任务，队列循环要继续处理后续任务。
            ConversionController.report(job.id, JobState.Cancelled)
        } catch (error: Exception) {
            ConversionController.report(job.id, JobState.Failed(error.message ?: "转换失败"))
        } finally {
            output?.delete()
            engine = null
        }
    }

    private suspend fun execute(
        engine: ConversionEngine,
        job: QueueJob,
        defaultNote: String?,
    ): ConversionResult {
        this.engine = engine
        val result = engine.convert(job.spec) { progress ->
            val percent = progress.percent.coerceIn(0f, 1f)
            val note = progress.note ?: defaultNote
            ConversionController.report(job.id, JobState.Running(percent, note))
            updateNotification(
                percent = (percent * 100).roundToInt(),
                text = "正在转换",
                jobName = job.spec.source.displayName,
                note = note,
            )
        }
        this.engine = null
        return result
    }

    private fun outputDisplayName(spec: ConversionSpec): String {
        val base = spec.source.displayName.substringBeforeLast('.')
            .ifBlank { "video" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .take(80)
        return "${base}_converted.${spec.container.extension}"
    }

    private fun mimeTypeFor(spec: ConversionSpec): String = when (spec.container.id) {
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "flv" -> "video/x-flv"
        "ts" -> "video/mp2t"
        else -> "video/*"
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VideoConvert:conversion",
        ).apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun updateNotification(
        percent: Int,
        text: String,
        jobName: String?,
        note: String? = null,
        indeterminate: Boolean = false,
    ) {
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            runCatching {
                NotificationManagerCompat.from(this).notify(
                    NOTIFICATION_ID,
                    buildNotification(percent, text, jobName, note, indeterminate),
                )
            }
        }
    }

    private fun buildNotification(
        percent: Int,
        text: String,
        jobName: String?,
        note: String? = null,
        indeterminate: Boolean = false,
    ): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            REQUEST_CANCEL,
            Intent(this, ConversionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val queued = ConversionController.pendingCount()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(jobName ?: "视频转换")
            .setContentText(note ?: text)
            .setSubText(if (queued > 0) "队列中还有 $queued 个任务" else null)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .addAction(0, "取消当前", cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.yuarmy.videoconvert.action.START"
        const val ACTION_CANCEL = "com.yuarmy.videoconvert.action.CANCEL"
        const val EXTRA_JOB_ID = "com.yuarmy.videoconvert.extra.JOB_ID"

        private const val CHANNEL_ID = "conversion"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CANCEL = 1
        private const val REQUEST_OPEN = 2
    }
}
