package expo.modules.beacon

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic safety-net that re-arms the [BeaconForegroundService] whenever
 * CarPlay observation is enabled but the service has been killed (low memory,
 * OEM cleaners, etc.).
 *
 * [BeaconForegroundService.enableCarPlay] is idempotent: if the service is
 * alive this is a cheap no-op; if it is dead it cold-starts a fresh
 * foreground instance and re-attaches [CarPlayMonitor].
 *
 * WorkManager guarantees a minimum 15-minute period. A second AlarmManager
 * loop in [BootReceiver] covers the sub-15-min gap AND — critically on
 * API 31+ — is the path the OS allows to call startForegroundService from
 * background: broadcast receivers running an exact-alarm trigger are granted
 * the brief foreground-importance window required. If enableCarPlay throws
 * here from a background worker (ForegroundServiceStartNotAllowedException),
 * the next alarm tick will recover.
 */
internal class CarPlayWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        if (!BeaconForegroundService.isCarPlayEnabled(ctx)) {
            Log.d(TAG, "Watchdog tick: CarPlay disabled, no action")
            return Result.success()
        }
        try {
            BeaconForegroundService.enableCarPlay(ctx)
            Log.d(TAG, "Watchdog tick: ensured BeaconForegroundService is running for CarPlay")
        } catch (t: Throwable) {
            // Expected on API 31+ when the worker runs while the app is fully
            // backgrounded — startForegroundService is blocked. The AlarmManager
            // loop in BootReceiver runs in a receiver context and IS permitted
            // to make the same call, so recovery still happens within ~11 min.
            Log.d(
                TAG,
                "Watchdog tick: enableCarPlay deferred to alarm loop (${t.javaClass.simpleName}): ${t.message}",
            )
        }
        // Always success — don't burn WorkManager's retry budget on a known
        // OS-level background restriction.
        return Result.success()
    }

    companion object {
        private const val TAG = "CarPlayWatchdog"
        private const val WORK_NAME = "expo-beacon-carplay-watchdog"

        /**
         * Schedule the periodic watchdog. Idempotent — [ExistingPeriodicWorkPolicy.KEEP]
         * preserves the existing schedule across calls so we don't reset the
         * next-run timer every time the user re-enables CarPlay.
         */
        @JvmStatic
        fun schedule(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<CarPlayWatchdogWorker>(
                    15, TimeUnit.MINUTES,
                ).addTag(WORK_NAME).build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(
                        WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request,
                    )
                Log.d(TAG, "Watchdog scheduled (15 min period)")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to schedule CarPlay watchdog", t)
            }
        }

        /** Cancel the periodic watchdog. Safe to call when not scheduled. */
        @JvmStatic
        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context.applicationContext)
                    .cancelUniqueWork(WORK_NAME)
                Log.d(TAG, "Watchdog cancelled")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to cancel CarPlay watchdog", t)
            }
        }
    }
}
