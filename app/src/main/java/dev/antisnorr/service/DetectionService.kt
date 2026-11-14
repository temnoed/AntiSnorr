package dev.antisnorr.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.antisnorr.MainActivity
import dev.antisnorr.R
import dev.antisnorr.alert.AlertDispatcher
import dev.antisnorr.alert.AlertNotifier
import dev.antisnorr.alert.AlertReason
import dev.antisnorr.core.MonitorBus
import dev.antisnorr.core.NightLogger
import dev.antisnorr.detection.DetectionEngine

class DetectionService : Service() {

    private var engine: DetectionEngine? = null
    private var dispatcher: AlertDispatcher? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AlertNotifier.createChannels(this)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            MonitorBus.update {
                it.copy(
                    running = false,
                    status = MonitorBus.Status.ERROR,
                    error = getString(R.string.error_no_mic),
                )
            }
            stopSelf()
            return
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            stopSelf()
            return
        }

        dispatcher = AlertDispatcher(this)
        startDetection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine?.stop()
        engine = null
        dispatcher?.shutdown()
        dispatcher = null
        NightLogger.flush()
        releaseWakeLock()
        MonitorBus.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startDetection() {
        if (engine != null) return

        acquireWakeLock()
        MonitorBus.update {
            it.copy(running = true, status = MonitorBus.Status.LISTENING, error = null)
        }

        engine = DetectionEngine(
            context = applicationContext,
            onFrame = { frame ->
                MonitorBus.update { state ->
                    state.copy(
                        snoreProbability = frame.snoreProbability,
                        topLabel = frame.topLabel,
                        topProbability = frame.topProbability,
                        rmsDb = frame.rmsDb,
                        status = if (frame.snoreActive) {
                            MonitorBus.Status.SNORE
                        } else {
                            MonitorBus.Status.LISTENING
                        },
                    )
                }
            },
            onAlert = { peak -> dispatcher?.alert(AlertReason.SNORE, peak) },
            onEpisodeEnd = { startedAtMs, durationMs, peak ->
                MonitorBus.addEvent(
                    MonitorBus.Event(
                        timeMs = startedAtMs,
                        peakProbability = peak,
                        durationMs = durationMs,
                    ),
                )
            },
            onError = { messageRes ->
                MonitorBus.update {
                    it.copy(status = MonitorBus.Status.ERROR, error = getString(messageRes))
                }
                stopSelf()
            },
        ).also { it.start() }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DetectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, AlertNotifier.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_snore)
            .setContentTitle(getString(R.string.notification_monitor_title))
            .setContentText(getString(R.string.notification_monitor_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = powerManager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntiSnorr::detection")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "DetectionService"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "dev.antisnorr.action.START"
        private const val ACTION_STOP = "dev.antisnorr.action.STOP"
        private const val MAX_WAKE_LOCK_MS = 10 * 60 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, DetectionService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DetectionService::class.java))
        }
    }
}
