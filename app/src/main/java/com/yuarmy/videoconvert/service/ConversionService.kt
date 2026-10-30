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
import com.yuarmy.videoconvert.domain.model.ConversionSpec
import com.yuarmy.videoconvert.domain.model.JobState
import com.yuarmy.videoconvert.engine.ConversionEngine
import com.yuarmy.videoconvert.engine.ConversionResult
import com.yuarmy.videoconvert.engine.EngineRoute
import com.yuarmy.videoconvert.engine.EngineRouter
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
    private var conversionJob: Job? = null
    private var engine: ConversionEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var cancelRequested = false

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
                if (conversionJob?.isActive != true) {
                    val spec = ConversionController.consumePendingSpec()
                    if (spec == null) {
                        stopSelf()
                    } else {
                        cancelRequested = false
                        startForeground(
                            NOTIFICATION_ID,
                            buildNotification(0, "准备中", indeterminate = true),
                        )
                        acquireWakeLock()
                        conversionJob = scope.launch { runConversion(spec) }
                    }
                }
            }

            ACTION_CANCEL -> {
                cancelRequested = true
                conversionJob?.cancel()
                engine?.cancel()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private suspend fun runConversion(spec: ConversionSpec) {
        var output: File? = null
        try {
            when (val route = EngineRouter.route(spec)) {
                is EngineRoute.Unsupported -> {
                    ConversionController.publish(JobState.Failed(route.reason))
                }

                is EngineRoute.Media3 -> {
                    val media3Engine = Media3Engine(this, route.remux)
                    engine = media3Engine
                    ConversionController.publish(JobState.Running(0f))

                    val result = media3Engine.convert(spec) { progress ->
                        val percent = progress.percent.coerceIn(0f, 1f)
                        ConversionController.publish(JobState.Running(percent, progress.note))
                        updateNotification(
                            percent = (percent * 100).roundToInt(),
                            text = "正在转换",
                            note = progress.note,
                        )
                    }

                    when (result) {
                        is ConversionResult.Success -> {
                            output = result.output
                            ConversionController.publish(JobState.Saving)
                            updateNotification(100, "正在保存到相册", indeterminate = true)
                            val displayName = outputDisplayName(spec)
                            val savedUri = withContext(Dispatchers.IO) {
                                MediaStoreSaver.save(
                                    context = this@ConversionService,
                                    source = result.output,
                                    displayName = displayName,
                                    mimeType = mimeTypeFor(spec),
                                )
                            }
                            ConversionController.publish(JobState.Success(savedUri, displayName))
                        }

                        is ConversionResult.Failure -> {
                            ConversionController.publish(
                                if (cancelRequested) JobState.Cancelled else JobState.Failed(result.message),
                            )
                        }

                        ConversionResult.Cancelled -> {
                            ConversionController.publish(JobState.Cancelled)
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            ConversionController.publish(JobState.Cancelled)
            throw cancelled
        } catch (error: Exception) {
            ConversionController.publish(JobState.Failed(error.message ?: "转换失败"))
        } finally {
            output?.delete()
            engine = null
            withContext(NonCancellable) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                releaseWakeLock()
                stopSelf()
            }
        }
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
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VideoConvert:conversion",
        ).apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) lock.release() }
        wakeLock = null
    }

    private fun updateNotification(
        percent: Int,
        text: String,
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
                NotificationManagerCompat.from(this)
                    .notify(NOTIFICATION_ID, buildNotification(percent, text, note, indeterminate))
            }
        }
    }

    private fun buildNotification(
        percent: Int,
        text: String,
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("视频转换")
            .setContentText(note ?: text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .addAction(0, "取消", cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.yuarmy.videoconvert.action.START"
        const val ACTION_CANCEL = "com.yuarmy.videoconvert.action.CANCEL"

        private const val CHANNEL_ID = "conversion"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CANCEL = 1
        private const val REQUEST_OPEN = 2
    }
}
