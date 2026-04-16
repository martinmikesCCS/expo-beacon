package expo.modules.beacon

// Shared constants used across ExpoBeaconModule and BeaconForegroundService.
// NOTE: EXIT_MISS_THRESHOLD and scan timing deviate from iOS intentionally —
// Android requires a non-zero between-scan gap to prevent OS-level BLE
// throttling, and a higher miss threshold to tolerate brief scan pauses.
// HYSTERESIS_COUNT should stay in sync with ExpoBeaconModule.swift (iOS).

internal const val PREFS_NAME = "expo.beacon.paired"
internal const val PREFS_KEY = "paired_beacons"
internal const val EDDYSTONE_PREFS_NAME = "expo.beacon.paired_eddystones"
internal const val EDDYSTONE_PREFS_KEY = "paired_eddystones"
internal const val NOTIFICATION_CONFIG_PREFS = "expo.beacon.notification_config"
internal const val MONITORING_OPTIONS_PREFS = "expo.beacon.monitoring_options"
internal const val EVENT_LOGGING_PREFS = "expo.beacon.event_logging"
internal const val EVENT_LOGGING_ENABLED_KEY = "enabled"

/** Foreground-service scan window for background monitoring responsiveness. */
internal const val MONITORING_SCAN_PERIOD_MS = 1100L

/**
 * Gap between scan windows while the foreground service is active.
 * A non-zero gap prevents Android from throttling continuous BLE scans
 * after a few minutes — the BLE stack needs brief idle periods to avoid
 * OS-level scan suppression on many devices (Samsung, Pixel, etc.).
 */
internal const val MONITORING_BETWEEN_SCAN_PERIOD_MS = 1000L

/** Ignore monitor-based exits if ranging saw the beacon within this window. */
internal const val RECENT_RANGING_SIGHTING_GRACE_MS = 25000L

/**
 * Number of consecutive ranging misses before emitting a distance-based exit event.
 * With a ~2.1 s scan cycle (1100 ms scan + 1000 ms gap), 10 misses ≈ 21 s of
 * silence before declaring exit — tolerant of brief BLE gaps while still
 * responsive to actual departures.
 */
internal const val EXIT_MISS_THRESHOLD = 10

/** Number of consecutive readings required to confirm a distance-based enter or exit transition. */
internal const val HYSTERESIS_COUNT = 3

/**
 * AltBeacon region exit period — how long after the last sighting before
 * MonitorNotifier.didExitRegion fires. Set generously to avoid premature
 * monitor-level exits that bypass ranging hysteresis.
 */
internal const val REGION_EXIT_PERIOD_MS = 60000L

/** Default minimum RSSI (dBm) below which beacon readings are discarded as unreliable. */
internal const val DEFAULT_MIN_RSSI = -85

/** Shared log tag for the expo-beacon module. */
internal const val TAG = "ExpoBeacon"

// Note: iOS defines EDDYSTONE_MONITORING_TICK_INTERVAL, EDDYSTONE_RECENTLY_SEEN_THRESHOLD,
// and DISTANCE_EVENT_THROTTLE_INTERVAL for its CoreBluetooth-based Eddystone monitoring.
// On Android, AltBeacon's built-in MonitorNotifier and RangeNotifier handle Eddystone
// regions natively, so these timing constants are not needed.
