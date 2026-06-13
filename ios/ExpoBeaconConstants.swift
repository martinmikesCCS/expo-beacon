import Foundation
import CoreBluetooth

// MARK: - UserDefaults suite

/// Custom UserDefaults suite isolating beacon data from the host app's .standard.
internal let BEACON_DEFAULTS_SUITE_NAME = "expo.modules.beacon"

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
/// Persisted last-known CarPlay connection state. Used by `CarPlayMonitor` to
/// reconcile across process recreations (e.g. background-launch wake) and emit
/// a synthetic `onCarPlayDisconnected` if the route is no longer CarPlay.
internal let CARPLAY_LAST_CONNECTED_KEY = "expo.beacon.carplay_last_connected"
/// Persisted transport of the last CarPlay connect (e.g. "wired", "wireless", "unknown").
internal let CARPLAY_LAST_TRANSPORT_KEY = "expo.beacon.carplay_last_transport"
/// Persisted Unix-millisecond timestamp of the last CarPlay connect.
internal let CARPLAY_LAST_CONNECTED_AT_KEY = "expo.beacon.carplay_last_connected_at"
/// Flag marking that values were migrated from UserDefaults.standard to the suite.
internal let SUITE_MIGRATION_FLAG_KEY = "expo.beacon.migrated_to_suite_v1"

// MARK: - API forwarder keys

internal let API_URL_KEY = "expo.beacon.api_url"
internal let API_KEY_KEY = "expo.beacon.api_key"
internal let API_ID_KEY = "expo.beacon.id"

// MARK: - BLE

/// Eddystone service UUID (0xFEAA) — used for scan filtering and frame extraction.
internal let EDDYSTONE_SERVICE_UUID = CBUUID(string: "FEAA")

// MARK: - Tuning thresholds

/// Default minimum RSSI (dBm) below which beacon readings are discarded as unreliable.
internal let DEFAULT_MIN_RSSI: Int = -85
/// Default one-shot scan duration (ms) applied when the JS caller omits it.
internal let DEFAULT_SCAN_DURATION_MS: Int = 5000
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
