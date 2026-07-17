/** Raw beacon discovered during a scan. */
export type BeaconScanResult = {
  uuid: string; // iBeacon proximity UUID (uppercase, formatted)
  major: number; // iBeacon major value (0–65535)
  minor: number; // iBeacon minor value (0–65535)
  rssi: number; // Signal strength in dBm (negative number)
  distance: number; // Estimated distance in meters (-1 when unavailable)
  txPower: number; // Calibrated TX power. Android only — always 0 on iOS (CoreLocation does not expose it)
  /** BLE advertising device name. May be undefined on iOS (CoreLocation does not expose it for iBeacon). */
  name?: string;
};

/**
 * A beacon that has been paired/registered for monitoring.
 *
 * Note: Paired beacon data is stored unencrypted in UserDefaults (iOS) /
 * SharedPreferences (Android) and may be included in device backups.
 */
export type PairedBeacon = {
  identifier: string; // User-defined label (e.g. "lobby-door")
  uuid: string;
  major: number;
  minor: number;
  /** BLE advertising device name, if provided at pairing time. */
  name?: string;
  /**
   * Timeout in seconds. When set, the module fires `onBeaconTimeout` once,
   * this many seconds after the beacon exits range. The countdown is armed on
   * exit — or when no BLE readings arrive for 60 seconds (e.g. due to Doze
   * mode or background throttling) — and is cancelled if the beacon is seen
   * again before it fires.
   */
  timeoutSeconds?: number;
};

/** Payload for enter/exit region events. */
export type BeaconRegionEvent = {
  identifier: string; // Matches PairedBeacon.identifier
  uuid: string;
  major: number;
  minor: number;
  event: "enter" | "exit";
  /** Measured distance in metres at the time of the event (–1 if unavailable). */
  distance: number;
  /** Signal strength in dBm at the time of the event (0 if unavailable). */
  rssi?: number;
};

/** Payload for periodic distance update events during monitoring. */
export type BeaconDistanceEvent = {
  identifier: string;
  uuid: string;
  major: number;
  minor: number;
  distance: number;
  /** Signal strength in dBm (0 if unavailable). */
  rssi?: number;
};

/** Payload for beacon timeout events (beacon out of range for the configured duration). */
export type BeaconTimeoutEvent = {
  identifier: string;
  uuid: string;
  major: number;
  minor: number;
  /** Distance in metres at the time the timeout fired. Usually –1, since the beacon is out of range when this fires. */
  distance: number;
};

/** Configuration for beacon enter/exit event notifications. */
export type BeaconNotificationConfig = {
  /** Whether to show enter/exit notifications. Default: true. */
  enabled?: boolean;
  /** Notification title on beacon enter. Default: "Beacon Entered". */
  enterTitle?: string;
  /** Notification title on beacon exit. Default: "Beacon Exited". */
  exitTitle?: string;
  /** Notification title on beacon timeout. Default: "Beacon Timeout". */
  timeoutTitle?: string;
  /**
   * Notification body template. Supports {identifier} and {event} placeholders.
   * Default: "{identifier} region {event}ed".
   */
  body?: string;
  /** Play a sound with the notification (iOS only). Default: true. */
  sound?: boolean;
  /** Android drawable resource name for the notification icon (e.g. "ic_notification"). */
  icon?: string;
};

/** Configuration for the Android foreground service notification (persistent status bar entry). */
export type ForegroundServiceConfig = {
  /** Title of the persistent notification. Default: "Beacon Monitoring Active". */
  title?: string;
  /** Body text of the persistent notification. Default: "Monitoring for iBeacons in the background". */
  text?: string;
  /** Android drawable resource name for the notification icon. */
  icon?: string;
};

/** Configuration for the Android notification channel. */
export type NotificationChannelConfig = {
  /** Channel display name shown in system settings. Default: "Beacon Monitoring". */
  name?: string;
  /** Channel description shown in system settings. Default: "Used for background iBeacon region monitoring". */
  description?: string;
  /**
   * Channel importance level. Default: 'low'.
   * Note: Android may ignore decreases in importance after first channel creation until the app is reinstalled.
   */
  importance?: "low" | "default" | "high";
};

/** Notification settings for beacon monitoring. */
export type BeaconNotificationSettings = {
  /** Settings for beacon enter/exit/timeout event notifications. */
  events?: BeaconNotificationConfig;
  /** Settings for the persistent Android foreground service notification. */
  foregroundService?: ForegroundServiceConfig;
  /** Settings for the Android notification channel used by beacon notifications. */
  channel?: NotificationChannelConfig;
};

/** Beacon notification configuration, including legacy flat keys. */
export type NotificationConfig = {
  /** Settings for beacon monitoring notifications. */
  beacons?: BeaconNotificationSettings;
  /** @deprecated Use `beacons.events` instead. */
  beaconEvents?: BeaconNotificationConfig;
  /** @deprecated Use `beacons.foregroundService` instead. */
  foregroundService?: ForegroundServiceConfig;
  /** @deprecated Use `beacons.channel` instead. */
  channel?: NotificationChannelConfig;
};

/** Snapshot of the current monitoring configuration and active state. */
export type MonitoringConfig = {
  /** Whether background monitoring is currently active. */
  isMonitoring: boolean;
  maxDistance?: number;
  exitDistance?: number;
  minRssi?: number;
  level?: "all" | "events";
  /** Seconds after last beacon sighting before an exit event fires. Default: 300. */
  exitTimeoutSeconds?: number;
  notifications?: NotificationConfig;
};

/** Current state snapshot for a paired monitored device. */
export type MonitoredDeviceState =
  | {
      kind: "ibeacon";
      identifier: string;
      uuid: string;
      major: number;
      minor: number;
      state: "entered" | "exited";
      /** Current distance in metres, or null when exited or no live reading is available. */
      distance: number | null;
    }
  | {
      kind: "eddystone";
      identifier: string;
      namespace: string;
      instance: string;
      state: "entered" | "exited";
      /** Current distance in metres, or null when exited or no live reading is available. */
      distance: number | null;
    };

/** Options accepted by startMonitoring(). */
export type MonitoringOptions = {
  /**
   * Maximum distance in metres for distance-based enter events.
   * Exit events are always emitted when the region is lost.
   */
  maxDistance?: number;
  /**
   * Distance in metres at which exit events fire (must be ≥ maxDistance).
   * Creates a hysteresis band between enter and exit thresholds to prevent
   * rapid toggling near the boundary.
   *
   * Default when omitted: `maxDistance + min(maxDistance × 0.5, 2.5)`.
   * Only used when `maxDistance` is set.
   */
  exitDistance?: number;
  /**
   * Minimum RSSI (dBm) for a beacon reading to be considered valid.
   * Readings below this threshold are discarded as unreliable, preventing
   * false detections from reflected or distant signals.
   *
   * Applies to monitoring readings only — one-shot scan results are not
   * filtered.
   *
   * Default: -85. Typical range: -100 (very permissive) to -70 (strict).
   */
  minRssi?: number;
  /**
   * Controls which event types are emitted, logged, and forwarded to the API.
   *
   * - `'all'` (default): distance + enter + exit + timeout events.
   * - `'events'`: enter + exit + timeout only (no distance events).
   */
  level?: "all" | "events";
  /**
   * Seconds after last beacon sighting before an exit event fires when the beacon
   * disappears without moving outside the exit distance threshold.
   *
   * Default: 300 (5 minutes). Minimum: 1.
   */
  exitTimeoutSeconds?: number;
  /** Notification configuration overrides to apply for this monitoring session. */
  notifications?: NotificationConfig;
};

/** Eddystone frame type. */
export type EddystoneFrameType = "uid" | "url";

/** Raw Eddystone beacon discovered during a scan. */
export type EddystoneScanResult = {
  frameType: EddystoneFrameType;
  /** 10-byte namespace ID as hex string (20 chars). Present for UID frames. */
  namespace?: string;
  /** 6-byte instance ID as hex string (12 chars). Present for UID frames. */
  instance?: string;
  /** Decoded URL. Present for URL frames. */
  url?: string;
  rssi: number;
  distance: number;
  txPower: number;
  /** BLE advertising device name. */
  name?: string;
};

/**
 * An Eddystone-UID beacon that has been paired/registered for monitoring.
 *
 * Note: Paired beacon data is stored unencrypted in UserDefaults (iOS) /
 * SharedPreferences (Android) and may be included in device backups.
 */
export type PairedEddystone = {
  identifier: string;
  /** 10-byte namespace ID as hex string (20 chars). */
  namespace: string;
  /** 6-byte instance ID as hex string (12 chars). */
  instance: string;
  /** BLE advertising device name, if provided at pairing time. */
  name?: string;
  /**
   * Timeout in seconds. When set, the module fires `onEddystoneTimeout` once,
   * this many seconds after the beacon exits range. The countdown is armed on
   * exit — or when no BLE readings arrive for 60 seconds (e.g. due to Doze
   * mode or background throttling) — and is cancelled if the beacon is seen
   * again before it fires.
   */
  timeoutSeconds?: number;
};

/** Payload for Eddystone enter/exit region events. */
export type EddystoneRegionEvent = {
  identifier: string;
  namespace: string;
  instance: string;
  event: "enter" | "exit";
  /** Measured distance in metres at the time of the event (–1 if unavailable). */
  distance: number;
  /** Signal strength in dBm at the time of the event (0 if unavailable). */
  rssi?: number;
};

/** Payload for periodic Eddystone distance update events during monitoring. */
export type EddystoneDistanceEvent = {
  identifier: string;
  namespace: string;
  instance: string;
  distance: number;
  /** Signal strength in dBm (0 if unavailable). */
  rssi?: number;
};

/** Payload for Eddystone timeout events (beacon out of range for the configured duration). */
export type EddystoneTimeoutEvent = {
  identifier: string;
  namespace: string;
  instance: string;
  /** Distance in metres at the time the timeout fired. Usually –1, since the beacon is out of range when this fires. */
  distance: number;
};

/** Machine-readable errors emitted or rejected by the native module. */
export type BeaconErrorCode =
  | "BATTERY_OPT_ERROR"
  | "BLUETOOTH_OFF"
  | "BLUETOOTH_UNAUTHORIZED"
  | "BLUETOOTH_UNSUPPORTED"
  | "CONFIG_PARSE_ERROR"
  | "DUPLICATE_BEACON_IDENTITY"
  | "DUPLICATE_EDDYSTONE_IDENTITY"
  | "DUPLICATE_IDENTIFIER"
  | "INVALID_DURATION"
  | "INVALID_EXIT_DISTANCE"
  | "INVALID_EXIT_TIMEOUT"
  | "INVALID_IDENTIFIER"
  | "INVALID_INSTANCE"
  | "INVALID_MAJOR"
  | "INVALID_MAX_DISTANCE"
  | "INVALID_MINOR"
  | "INVALID_NAMESPACE"
  | "INVALID_TIMEOUT"
  | "INVALID_UUID"
  | "MODULE_DESTROYED"
  | "MONITORING_FAILED"
  | "NO_CONTEXT"
  | "NO_PAIRED_BEACONS"
  | "PERMISSION_DENIED"
  | "RANGING_FAILED"
  | "RECEIVER_REGISTRATION_FAILED"
  | "REGION_LIMIT_EXCEEDED"
  | "SCAN_CANCELLED"
  | "SCAN_ERROR"
  | "SCAN_IN_PROGRESS"
  | "SECURITY_EXCEPTION"
  | "SERVICE_START_FAILED"
  | "WILDCARD_NOT_SUPPORTED";

/** Payload for native beacon error events (monitoring/ranging failures). */
export type BeaconErrorEvent = {
  /** Region or constraint identifier, empty string if unavailable. */
  identifier: string;
  /** Machine-readable error code. */
  code: BeaconErrorCode;
  /** Human-readable error message from the native layer. */
  message: string;
};

/** Module event map. */
export type ExpoBeaconModuleEvents = {
  onBeaconEnter: (params: BeaconRegionEvent) => void;
  onBeaconExit: (params: BeaconRegionEvent) => void;
  onBeaconDistance: (params: BeaconDistanceEvent) => void;
  /** Fired once `timeoutSeconds` after a paired beacon exits range (cancelled if the beacon is seen again first). */
  onBeaconTimeout: (params: BeaconTimeoutEvent) => void;
  /** Fired continuously during a live scan as each iBeacon is detected. */
  onBeaconFound: (params: BeaconScanResult) => void;
  /** Fired continuously during a live scan as each Eddystone beacon is detected. */
  onEddystoneFound: (params: EddystoneScanResult) => void;
  onEddystoneEnter: (params: EddystoneRegionEvent) => void;
  onEddystoneExit: (params: EddystoneRegionEvent) => void;
  onEddystoneDistance: (params: EddystoneDistanceEvent) => void;
  /** Fired once `timeoutSeconds` after a paired Eddystone exits range (cancelled if the beacon is seen again first). */
  onEddystoneTimeout: (params: EddystoneTimeoutEvent) => void;
  /** Fired when a native monitoring or ranging failure occurs (logged to DB and forwarded to JS). */
  onBeaconError: (params: BeaconErrorEvent) => void;
};

/** Every event name emitted by the module. */
export type BeaconEventName = keyof ExpoBeaconModuleEvents;

/** Options for filtering event logs. */
export type EventLogQueryOptions = {
  /** Maximum number of log entries to return (default: 1000, max: 10000). */
  limit?: number;
  /** Filter by an emitted event type. */
  eventType?: BeaconEventName;
  /** Only return events with timestamp >= this value (ms since epoch). */
  sinceTimestamp?: number;
};

/** A single logged beacon event entry. */
export type EventLogEntry = {
  id: number;
  /** Timestamp in milliseconds since epoch. */
  timestamp: number;
  /** The event type that was logged. */
  eventType: BeaconEventName;
  /** Beacon identifier, if available. */
  identifier?: string;
  /** The full event payload that was sent to JS. */
  data: Record<string, unknown>;
};
