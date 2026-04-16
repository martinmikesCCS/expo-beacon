/** Raw beacon discovered during a scan. */
export type BeaconScanResult = {
  uuid: string; // iBeacon proximity UUID (uppercase, formatted)
  major: number; // iBeacon major value (0–65535)
  minor: number; // iBeacon minor value (0–65535)
  rssi: number; // Signal strength in dBm (negative number)
  distance: number; // Estimated distance in meters
  txPower: number; // Calibrated TX power
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
   * Timeout in seconds. When set, the module fires `onBeaconTimeout` once
   * after the beacon has been continuously in range for this duration.
   * The timer resets if the beacon exits and re-enters range.
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

/** Payload for beacon timeout events (beacon in range for configured duration). */
export type BeaconTimeoutEvent = {
  identifier: string;
  uuid: string;
  major: number;
  minor: number;
  /** Current distance in metres at the time the timeout fired. */
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

/** Combined notification configuration for all notification types. */
export type NotificationConfig = {
  /** Settings for beacon enter/exit event notifications. */
  beaconEvents?: BeaconNotificationConfig;
  /** Settings for the persistent foreground service notification (Android only). */
  foregroundService?: ForegroundServiceConfig;
  /** Settings for the Android notification channel (Android only). */
  channel?: NotificationChannelConfig;
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
   * Default: -85. Typical range: -100 (very permissive) to -70 (strict).
   */
  minRssi?: number;
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
   * Timeout in seconds. When set, the module fires `onEddystoneTimeout` once
   * after the beacon has been continuously in range for this duration.
   * The timer resets if the beacon exits and re-enters range.
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

/** Payload for Eddystone timeout events (beacon in range for configured duration). */
export type EddystoneTimeoutEvent = {
  identifier: string;
  namespace: string;
  instance: string;
  /** Current distance in metres at the time the timeout fired. */
  distance: number;
};

/** Module event map. */
export type ExpoBeaconModuleEvents = {
  onBeaconEnter: (params: BeaconRegionEvent) => void;
  onBeaconExit: (params: BeaconRegionEvent) => void;
  onBeaconDistance: (params: BeaconDistanceEvent) => void;
  /** Fired once after a paired beacon has been continuously in range for its configured `timeoutSeconds`. */
  onBeaconTimeout: (params: BeaconTimeoutEvent) => void;
  /** Fired continuously during a live scan as each iBeacon is detected. */
  onBeaconFound: (params: BeaconScanResult) => void;
  /** Fired continuously during a live scan as each Eddystone beacon is detected. */
  onEddystoneFound: (params: EddystoneScanResult) => void;
  onEddystoneEnter: (params: EddystoneRegionEvent) => void;
  onEddystoneExit: (params: EddystoneRegionEvent) => void;
  onEddystoneDistance: (params: EddystoneDistanceEvent) => void;
  /** Fired once after a paired Eddystone has been continuously in range for its configured `timeoutSeconds`. */
  onEddystoneTimeout: (params: EddystoneTimeoutEvent) => void;
};

/** Options for filtering event logs. */
export type EventLogQueryOptions = {
  /** Maximum number of log entries to return (default: 1000, max: 10000). */
  limit?: number;
  /** Filter by event type (e.g. "onBeaconEnter", "onBeaconExit"). */
  eventType?: string;
  /** Only return events with timestamp >= this value (ms since epoch). */
  sinceTimestamp?: number;
};

/** A single logged beacon event entry. */
export type EventLogEntry = {
  id: number;
  /** Timestamp in milliseconds since epoch. */
  timestamp: number;
  /** The event type that was logged (e.g. "onBeaconEnter"). */
  eventType: string;
  /** Beacon identifier, if available. */
  identifier?: string;
  /** The full event payload that was sent to JS. */
  data: Record<string, unknown>;
};
