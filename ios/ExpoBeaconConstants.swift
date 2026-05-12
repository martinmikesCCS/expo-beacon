import Foundation

// MARK: - UserDefaults keys

internal let PAIRED_BEACONS_KEY = "expo.beacon.paired"
internal let PAIRED_EDDYSTONES_KEY = "expo.beacon.paired_eddystones"
internal let IS_MONITORING_KEY = "expo.beacon.is_monitoring"
internal let MAX_DISTANCE_KEY = "expo.beacon.max_distance"
internal let EXIT_DISTANCE_KEY = "expo.beacon.exit_distance"
internal let NOTIFICATION_CONFIG_KEY = "expo.beacon.notification_config"
internal let EVENT_LOGGING_ENABLED_KEY = "expo.beacon.event_logging_enabled"
internal let MIN_RSSI_KEY = "expo.beacon.min_rssi"
internal let EVENT_LEVEL_KEY = "expo.beacon.event_level"
internal let EXIT_TIMEOUT_SECONDS_KEY = "expo.beacon.exit_timeout_seconds"
internal let CARPLAY_MONITORING_ENABLED_KEY = "expo.beacon.carplay_monitoring_enabled"

// MARK: - Tuning thresholds

/// Default minimum RSSI (dBm) below which beacon readings are discarded as unreliable.
internal let DEFAULT_MIN_RSSI: Int = -85
/// Default seconds of silence after last beacon sighting before a disappearance-based exit fires.
internal let DEFAULT_EXIT_TIMEOUT_SECONDS: TimeInterval = 300.0

/// Number of consecutive in-range readings required before an enter event is emitted.
/// IMPORTANT: Keep in sync with BeaconConstants.kt (Android).
internal let ENTER_HYSTERESIS_COUNT = 1
/// Number of consecutive out-of-range readings required before an exit event is emitted.
/// IMPORTANT: Keep in sync with BeaconConstants.kt (Android).
internal let EXIT_HYSTERESIS_COUNT = 3

/// Eddystone monitoring timer interval in seconds.
internal let EDDYSTONE_MONITORING_TICK_INTERVAL: TimeInterval = 2.0
/// Maximum age (in seconds) before a beacon is considered "not recently seen".
/// Set high enough to tolerate iOS background CoreBluetooth throttling which
/// can cause 10-12 s gaps between Eddystone advertisements.
internal let EDDYSTONE_RECENTLY_SEEN_THRESHOLD: TimeInterval = 15.0
/// Minimum interval between consecutive distance event emissions per identifier.
internal let DISTANCE_EVENT_THROTTLE_INTERVAL: TimeInterval = 1.0
/// Seconds of no valid BLE readings before starting the timeout countdown.
/// Acts as a safety net when ranging cycles stop entirely (e.g. Doze mode).
internal let DISTANCE_INACTIVITY_SECONDS: TimeInterval = 60.0
