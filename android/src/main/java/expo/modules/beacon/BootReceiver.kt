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
/**
 * Periodic self-rescheduling watchdog action handled by [BootReceiver].
 * Public on the manifest so the AlarmManager can route back to us after the
 * process has been killed.
 */
internal const val ACTION_CARPLAY_WATCHDOG = "expo.modules.beacon.ACTION_CARPLAY_WATCHDOG"
private const val ACTION_TASK_REMOVED_KEEPALIVE = "expo.modules.beacon.ACTION_TASK_REMOVED_KEEPALIVE"
private const val EXTRA_RETRY_COUNT = "retryCount"
/** Cap on self-scheduled retries, mirroring the service's MAX_STARTFOREGROUND_RETRIES. */
private const val MAX_BOOT_RETRIES = 3
private const val RETRY_DELAY_MS = 10_000L
private const val RETRY_REQUEST_CODE = 0x424F4F54 // "BOOT"
private const val CARPLAY_WATCHDOG_REQUEST_CODE = 0x43504C57 // "CPLW"
private const val TASK_REMOVED_KEEPALIVE_REQUEST_CODE = 0x54524B41 // "TRKA"
private const val TASK_REMOVED_KEEPALIVE_DELAY_MS = 2_000L

/**
 * Cadence for the AlarmManager-based CarPlay watchdog. Set to **11 minutes**
 * so we stay safely above Android's per-app rate limit on
 * `setExactAndAllowWhileIdle()` (~10 minutes on API 23+, tightened again on
 * API 31+). Going lower risks silent coalescing or dropping by the framework.
 * This still beats WorkManager's 15-minute periodic floor.
 */
private const val CARPLAY_WATCHDOG_INTERVAL_MS = 11L * 60L * 1000L

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
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                logMemoryKillDiagnostics(context)
                if (BeaconForegroundService.isMonitoringActive(context)) {
                    tryStartService(context)
                }
                if (BeaconForegroundService.isCarPlayEnabled(context)) {
                    // CarPlay observation: re-attach the observer so it survives
                    // reboot / app update / direct-boot, and arm the
                    // self-rescheduling alarm loop.
                    tryEnableCarPlay(context)
                    scheduleCarPlayWatchdogAlarm(context)
                }
            }
            ACTION_RETRY_MONITORING -> {
                val retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)
                if (BeaconForegroundService.isMonitoringActive(context)) {
                    tryStartService(context, retryCount)
                } else if (BeaconForegroundService.isCarPlayEnabled(context)) {
                    tryEnableCarPlay(context, retryCount)
                }
            }
            ACTION_CARPLAY_WATCHDOG -> {
                if (!BeaconForegroundService.isCarPlayEnabled(context)) {
                    // CarPlay was disabled since the alarm was set — let the
                    // chain die. cancelCarPlayWatchdogAlarm() is also called
                    // from disableCarPlay(), this is just defence-in-depth.
                    Log.d(TAG, "BootReceiver: watchdog tick skipped (CarPlay disabled)")
                    return
                }
                tryEnableCarPlay(context)
                // Reschedule the next tick. setExactAndAllowWhileIdle is one-shot.
                // enableCarPlay() above also arms this alarm, but re-arming here
                // keeps the chain alive even if enableCarPlay throws before it
                // reaches its own scheduling call.
                scheduleCarPlayWatchdogAlarm(context)
            }
            ACTION_TASK_REMOVED_KEEPALIVE -> {
                val monitoringActive = BeaconForegroundService.isMonitoringActive(context)
                val carPlayEnabled = BeaconForegroundService.isCarPlayEnabled(context)
                if (!monitoringActive && !carPlayEnabled) {
                    Log.d(TAG, "BootReceiver: task-removed keepalive skipped (nothing active)")
                    return
                }
                if (carPlayEnabled) {
                    tryEnableCarPlay(context)
                    scheduleCarPlayWatchdogAlarm(context)
                } else {
                    tryStartService(context)
                }
                Log.d(TAG, "BootReceiver: task-removed keepalive ensured service is running")
            }
        }
    }

    private fun tryStartService(context: Context, retryCount: Int = 0) {
        try {
            BeaconForegroundService.start(context)
            Log.d(TAG, "BootReceiver: BeaconForegroundService started successfully")
        } catch (e: SecurityException) {
            // ForegroundServiceStartNotAllowedException extends SecurityException.
            // Can occur on Android 17 beta if Bluetooth is not yet fully initialized at boot.
            Log.e(TAG, "BootReceiver: Failed to start service (SecurityException) — retrying in ${RETRY_DELAY_MS}ms", e)
            scheduleRetry(context, retryCount)
        } catch (e: Exception) {
            Log.e(TAG, "BootReceiver: Failed to start service — retrying in ${RETRY_DELAY_MS}ms", e)
            scheduleRetry(context, retryCount)
        }
    }

    private fun tryEnableCarPlay(context: Context, retryCount: Int = 0) {
        try {
            BeaconForegroundService.enableCarPlay(context)
            Log.d(TAG, "BootReceiver: CarPlay-only service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "BootReceiver: Failed to start CarPlay-only service — retrying in ${RETRY_DELAY_MS}ms", e)
            scheduleRetry(context, retryCount)
        }
    }

    private fun scheduleRetry(context: Context, retryCount: Int) {
        if (retryCount >= MAX_BOOT_RETRIES) {
            Log.e(TAG, "BootReceiver: giving up after $MAX_BOOT_RETRIES retries")
            return
        }
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val retryIntent = Intent(context, BootReceiver::class.java).apply {
            action = ACTION_RETRY_MONITORING
            putExtra(EXTRA_RETRY_COUNT, retryCount + 1)
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

    companion object {
        /**
         * Arm (or re-arm) the AlarmManager-based CarPlay watchdog. One-shot
         * alarm using `setExactAndAllowWhileIdle` — the receiver re-schedules
         * itself on each fire as long as CarPlay observation remains enabled.
         *
         * Cadence is governed by [CARPLAY_WATCHDOG_INTERVAL_MS], deliberately
         * above the per-app exact-alarm quota.
         *
         * Safe to call from any context; idempotent thanks to
         * `FLAG_UPDATE_CURRENT` on the PendingIntent.
         */
        @JvmStatic
        fun scheduleCarPlayWatchdogAlarm(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, BootReceiver::class.java).apply {
                action = ACTION_CARPLAY_WATCHDOG
                // Explicit package so the implicit-broadcast restriction on
                // Android 8+ doesn't drop the delivery.
                `package` = context.packageName
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                CARPLAY_WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + CARPLAY_WATCHDOG_INTERVAL_MS,
                    pendingIntent,
                )
                Log.d(TAG, "BootReceiver: CarPlay watchdog alarm armed (${CARPLAY_WATCHDOG_INTERVAL_MS}ms)")
            } catch (t: Throwable) {
                // Defensive: setExactAndAllowWhileIdle can throw SecurityException on
                // some configurations even though it doesn't strictly require
                // SCHEDULE_EXACT_ALARM. Fall back is the WorkManager 15-min job.
                Log.w(TAG, "BootReceiver: failed to arm CarPlay watchdog alarm", t)
            }
        }

        /** Cancel the periodic CarPlay watchdog alarm. */
        @JvmStatic
        fun cancelCarPlayWatchdogAlarm(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, BootReceiver::class.java).apply {
                action = ACTION_CARPLAY_WATCHDOG
                `package` = context.packageName
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                CARPLAY_WATCHDOG_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                alarmManager.cancel(pendingIntent)
                Log.d(TAG, "BootReceiver: CarPlay watchdog alarm cancelled")
            } catch (t: Throwable) {
                Log.w(TAG, "BootReceiver: failed to cancel CarPlay watchdog alarm", t)
            }
        }

        /**
         * Schedule a near-term service keepalive after the user swipes the app
         * task away. Some devices tear down the process despite a foreground
         * service; this closes that gap before the slower periodic watchdogs run.
         */
        @JvmStatic
        fun scheduleTaskRemovedKeepAlive(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, BootReceiver::class.java).apply {
                action = ACTION_TASK_REMOVED_KEEPALIVE
                `package` = context.packageName
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                TASK_REMOVED_KEEPALIVE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + TASK_REMOVED_KEEPALIVE_DELAY_MS,
                    pendingIntent,
                )
                Log.d(TAG, "BootReceiver: task-removed keepalive armed (${TASK_REMOVED_KEEPALIVE_DELAY_MS}ms)")
            } catch (t: Throwable) {
                Log.w(TAG, "BootReceiver: failed to arm task-removed keepalive", t)
            }
        }
    }
}
