package expo.modules.beacon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives ACTION_BEACON_EVENT broadcasts from BeaconForegroundService
 * and forwards them to the Expo module event system.
 */
class BeaconEventReceiver(
    private val onEvent: (eventName: String, params: Map<String, Any>) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BEACON_EVENT) return

        val identifier = intent.getStringExtra("identifier") ?: return
        val uuid = intent.getStringExtra("uuid") ?: ""
        val major = intent.getIntExtra("major", 0)
        val minor = intent.getIntExtra("minor", 0)
        val eventType = intent.getStringExtra("event") ?: return

        val params = mapOf(
            "identifier" to identifier,
            "uuid" to uuid,
            "major" to major,
            "minor" to minor,
            "event" to eventType,
            "distance" to intent.getDoubleExtra("distance", -1.0)
        )

        val eventName = when (eventType) {
            "enter" -> "onBeaconEnter"
            "exit" -> "onBeaconExit"
            "distance" -> "onBeaconDistance"
            else -> return
        }
        onEvent(eventName, params)
    }
}
