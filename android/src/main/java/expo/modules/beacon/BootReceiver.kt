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
private const val ACTION_TASK_REMOVED_KEEPALIVE =
    "expo.modules.beacon.ACTION_TASK_REMOVED_KEEPALIVE"
private const val EXTRA_RETRY_COUNT = "retryCount"
private const val MAX_RECOVERY_RETRIES = 3
private const val RETRY_DELAY_MS = 10_000L
private const val RETRY_REQUEST_CODE = 0x424F4F54 // "BOOT"
private const val TASK_REMOVED_KEEPALIVE_REQUEST_CODE = 0x54524B41 // "TRKA"
private const val TASK_REMOVED_KEEPALIVE_DELAY_MS = 2_000L

/** Restarts persisted beacon monitoring after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> BeaconRecovery.handleSystemRestart(context)
        }
    }

    companion object {
        @JvmStatic
        fun scheduleServiceRetry(context: Context, retryCount: Int) {
            BeaconRecovery.scheduleServiceRetry(context, retryCount)
        }

        @JvmStatic
        fun scheduleTaskRemovedKeepAlive(context: Context) {
            BeaconRecovery.scheduleTaskRemovedKeepAlive(context)
        }
    }
}

/** Receives only explicit, app-owned PendingIntents; exported=false in the manifest. */
class BeaconInternalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RETRY_MONITORING -> BeaconRecovery.handleServiceRetry(
                context,
                intent.getIntExtra(EXTRA_RETRY_COUNT, 0),
            )
            ACTION_TASK_REMOVED_KEEPALIVE -> BeaconRecovery.handleTaskRemovedKeepAlive(context)
        }
    }
}

private object BeaconRecovery {
    fun handleSystemRestart(context: Context) {
        logMemoryKillDiagnostics(context)
        if (BeaconForegroundService.isMonitoringActive(context)) {
            tryStartService(context)
        }
    }

    fun handleServiceRetry(context: Context, retryCount: Int) {
        if (BeaconForegroundService.isMonitoringActive(context)) {
            tryStartService(context, retryCount)
        } else {
            Log.d(TAG, "Service retry skipped because beacon monitoring is inactive")
        }
    }

    fun handleTaskRemovedKeepAlive(context: Context) {
        if (!BeaconForegroundService.isMonitoringActive(context)) {
            Log.d(TAG, "Task-removed keepalive skipped because beacon monitoring is inactive")
            return
        }
        tryStartService(context)
        Log.d(TAG, "Task-removed keepalive requested beacon service recovery")
    }

    private fun tryStartService(context: Context, retryCount: Int = 0) {
        try {
            BeaconForegroundService.start(context)
            Log.d(TAG, "BeaconForegroundService start requested successfully")
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to start beacon monitoring service (retry=$retryCount)", error)
            if (retryCount < MAX_RECOVERY_RETRIES) {
                scheduleServiceRetry(context, retryCount + 1)
            } else {
                Log.e(TAG, "Giving up after $MAX_RECOVERY_RETRIES recovery retries")
            }
        }
    }

    fun scheduleServiceRetry(context: Context, retryCount: Int) {
        if (retryCount > MAX_RECOVERY_RETRIES) return
        scheduleAlarm(
            context,
            ACTION_RETRY_MONITORING,
            RETRY_REQUEST_CODE,
            RETRY_DELAY_MS,
        ) { putExtra(EXTRA_RETRY_COUNT, retryCount) }
    }

    fun scheduleTaskRemovedKeepAlive(context: Context) {
        scheduleAlarm(
            context,
            ACTION_TASK_REMOVED_KEEPALIVE,
            TASK_REMOVED_KEEPALIVE_REQUEST_CODE,
            TASK_REMOVED_KEEPALIVE_DELAY_MS,
        )
    }

    private fun scheduleAlarm(
        context: Context,
        action: String,
        requestCode: Int,
        delayMs: Long,
        configure: Intent.() -> Unit = {},
    ) {
        try {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, BeaconInternalReceiver::class.java).apply {
                this.action = action
                configure()
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                pendingIntent,
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to schedule recovery alarm for $action", error)
        }
    }

    private fun logMemoryKillDiagnostics(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            context.getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessExitReasons(null, 0, 5)
                ?.forEach { info ->
                    if (info.description?.contains("MemoryLimiter") == true) {
                        Log.w(TAG, "Previous process was killed by Android memory limits: ${info.description}")
                    }
                }
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to read historical process exit reasons", error)
        }
    }
}
