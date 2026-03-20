package expo.modules.beacon

// Shared constants used across ExpoBeaconModule and BeaconForegroundService.
// IMPORTANT: Timing constants (EXIT_MISS_THRESHOLD, HYSTERESIS_COUNT) must stay
// in sync with ExpoBeaconModule.swift (iOS).

internal const val PREFS_NAME = "expo.beacon.paired"
internal const val PREFS_KEY = "paired_beacons"
internal const val EDDYSTONE_PREFS_NAME = "expo.beacon.paired_eddystones"
internal const val EDDYSTONE_PREFS_KEY = "paired_eddystones"
internal const val NOTIFICATION_CONFIG_PREFS = "expo.beacon.notification_config"
internal const val MONITORING_OPTIONS_PREFS = "expo.beacon.monitoring_options"

/** Number of consecutive ranging misses before emitting a distance-based exit event. */
internal const val EXIT_MISS_THRESHOLD = 3

/** Number of consecutive readings required to confirm a distance-based enter or exit transition. */
internal const val HYSTERESIS_COUNT = 3

/** Shared log tag for the expo-beacon module. */
internal const val TAG = "ExpoBeacon"

// Note: iOS defines EDDYSTONE_MONITORING_TICK_INTERVAL, EDDYSTONE_RECENTLY_SEEN_THRESHOLD,
// and DISTANCE_EVENT_THROTTLE_INTERVAL for its CoreBluetooth-based Eddystone monitoring.
// On Android, AltBeacon's built-in MonitorNotifier and RangeNotifier handle Eddystone
// regions natively, so these timing constants are not needed.
