package expo.modules.beacon

import org.altbeacon.beacon.BeaconManager
import org.altbeacon.beacon.BeaconParser

/**
 * Registers iBeacon, Eddystone-UID, and Eddystone-URL parsers on the given
 * [BeaconManager] if they are not already present.
 *
 * Shared between [ExpoBeaconModule] (scan binding) and [BeaconForegroundService]
 * (background monitoring) to eliminate layout-string duplication.
 */
internal object BeaconParsers {

    private const val IBEACON_LAYOUT =
        "m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"
    private const val EDDYSTONE_UID_LAYOUT =
        "s:0-1=feaa,m:2-2=00,p:3-3:-41,i:4-13,i:14-19"
    private const val EDDYSTONE_URL_LAYOUT =
        "s:0-1=feaa,m:2-2=10,p:3-3:-41,i:4-20v"

    fun ensureRegistered(manager: BeaconManager) {
        if (manager.beaconParsers.none { it.layout?.contains("0215") == true }) {
            manager.beaconParsers.add(BeaconParser().setBeaconLayout(IBEACON_LAYOUT))
        }
        if (manager.beaconParsers.none { it.layout?.contains("s:0-1=feaa,m:2-2=00") == true }) {
            manager.beaconParsers.add(BeaconParser().setBeaconLayout(EDDYSTONE_UID_LAYOUT))
        }
        if (manager.beaconParsers.none { it.layout?.contains("s:0-1=feaa,m:2-2=10") == true }) {
            manager.beaconParsers.add(BeaconParser().setBeaconLayout(EDDYSTONE_URL_LAYOUT))
        }
    }
}
