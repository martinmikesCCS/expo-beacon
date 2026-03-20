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
        val eventType = intent.getStringExtra("event") ?: return
        val beaconType = intent.getStringExtra("beaconType") ?: "ibeacon"
        val distance = intent.getDoubleExtra("distance", -1.0)

        if (beaconType == "eddystone") {
            val namespace = intent.getStringExtra("namespace") ?: ""
            val instance = intent.getStringExtra("instance") ?: ""

            val params = mapOf(
                "identifier" to identifier,
                "namespace" to namespace,
                "instance" to instance,
                "event" to eventType,
                "distance" to distance
            )

            val eventName = when (eventType) {
                "enter" -> "onEddystoneEnter"
                "exit" -> "onEddystoneExit"
                "distance" -> "onEddystoneDistance"
                else -> return
            }
            onEvent(eventName, params)
        } else {
            val uuid = intent.getStringExtra("uuid") ?: ""
            val major = intent.getIntExtra("major", 0)
            val minor = intent.getIntExtra("minor", 0)

            val params = mapOf(
                "identifier" to identifier,
                "uuid" to uuid,
                "major" to major,
                "minor" to minor,
                "event" to eventType,
                "distance" to distance
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
}
