package expo.modules.beacon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives ACTION_BEACON_EVENT broadcasts from BeaconForegroundService
 * and forwards them to the Expo module event system. The service puts the
 * resolved JS event name and the ready-made payload bundle in the intent,
 * so this receiver only unpacks them.
 *
 * Architecture note: System broadcasts (scoped via setPackage + RECEIVER_NOT_EXPORTED)
 * are used rather than LocalBroadcastManager or a bound-service callback because the
 * foreground service must survive JS module destruction (e.g., during hot reload).
 * System broadcasts decouple the service lifecycle from the module lifecycle.
 */
class BeaconEventReceiver(
    private val onEvent: (eventName: String, params: Map<String, Any>) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BEACON_EVENT) return

        val eventName = intent.getStringExtra(EXTRA_EVENT_NAME) ?: return
        val bundle = intent.getBundleExtra(EXTRA_EVENT_PARAMS) ?: return
        val params = mutableMapOf<String, Any>()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            bundle.get(key)?.let { params[key] = it }
        }
        onEvent(eventName, params)
    }
}
