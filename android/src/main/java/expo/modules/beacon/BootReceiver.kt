package expo.modules.beacon

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

private const val ACTION_RETRY_MONITORING = "expo.modules.beacon.ACTION_RETRY_MONITORING"
private const val RETRY_DELAY_MS = 10_000L
private const val RETRY_REQUEST_CODE = 0x424F4F54 // "BOOT"

/**
 * Restarts beacon monitoring after device reboot if it was active before shutdown.
 *
 * Also handles ACTION_RETRY_MONITORING — a self-scheduled alarm used to retry the
 * service start if Bluetooth is not yet fully initialized when BOOT_COMPLETED fires
 * (observed on Android 17 beta where startForegroundService() throws SecurityException
 * or ForegroundServiceStartNotAllowedException shortly after boot).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                logMemoryKillDiagnostics(context)
                if (BeaconForegroundService.isMonitoringActive(context)) {
                    tryStartService(context)
                } else if (BeaconForegroundService.isCarPlayEnabled(context)) {
                    // CarPlay-only mode: re-attach the observer so it survives reboot.
                    tryEnableCarPlay(context)
                }
            }
            ACTION_RETRY_MONITORING -> {
                if (BeaconForegroundService.isMonitoringActive(context)) {
                    tryStartService(context)
                } else if (BeaconForegroundService.isCarPlayEnabled(context)) {
                    tryEnableCarPlay(context)
                }
            }
        }
    }

    private fun tryStartService(context: Context) {
        try {
            BeaconForegroundService.start(context)
            Log.d(TAG, "BootReceiver: BeaconForegroundService started successfully")
        } catch (e: SecurityException) {
            // ForegroundServiceStartNotAllowedException extends SecurityException.
            // Can occur on Android 17 beta if Bluetooth is not yet fully initialized at boot.
            Log.e(TAG, "BootReceiver: Failed to start service (SecurityException) — retrying in ${RETRY_DELAY_MS}ms", e)
            scheduleRetry(context)
        } catch (e: Exception) {
            Log.e(TAG, "BootReceiver: Failed to start service — retrying in ${RETRY_DELAY_MS}ms", e)
            scheduleRetry(context)
        }
    }

    private fun tryEnableCarPlay(context: Context) {
        try {
            BeaconForegroundService.enableCarPlay(context)
            Log.d(TAG, "BootReceiver: CarPlay-only service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "BootReceiver: Failed to start CarPlay-only service — retrying in ${RETRY_DELAY_MS}ms", e)
            scheduleRetry(context)
        }
    }

    private fun scheduleRetry(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val retryIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_RETRY_MONITORING
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            RETRY_REQUEST_CODE,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + RETRY_DELAY_MS,
            pendingIntent
        )
    }

    /**
     * Logs if the previous process was killed by Android 17's app memory limits
     * (ApplicationExitInfo.description contains "MemoryLimiter") so the cause is
     * visible in Logcat when the service restarts after being killed.
     */
    private fun logMemoryKillDiagnostics(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val am = context.getSystemService(ActivityManager::class.java)
            am?.getHistoricalProcessExitReasons(null, 0, 5)?.forEach { info ->
                if (info.description?.contains("MemoryLimiter") == true) {
                    Log.w(TAG, "BootReceiver: previous process killed by Android 17 memory limits: ${info.description}")
                }
            }
        }
    }
}
