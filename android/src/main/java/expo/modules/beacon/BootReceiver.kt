package expo.modules.beacon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Restarts beacon monitoring after device reboot if it was active before shutdown.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (BeaconForegroundService.isMonitoringActive(context)) {
                BeaconForegroundService.start(context)
            }
        }
    }
}
