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

/** Configuration for CarPlay / Android Auto connect/disconnect notifications. */
export type CarPlayNotificationConfig = {
  /** Whether to show CarPlay connect/disconnect notifications. Default: true. */
  enabled?: boolean;
  /** Notification title on CarPlay/Android Auto connect. Default: "CarPlay Connected". */
  connectedTitle?: string;
  /** Notification title on CarPlay/Android Auto disconnect. Default: "CarPlay Disconnected". */
  disconnectedTitle?: string;
  /**
   * Notification body template. Supports `{event}` ("connected"/"disconnected") and
   * `{transport}` (e.g. "wired", "wireless", "projection", "native", "unknown") placeholders.
   * Note: `{transport}` is only meaningful for connect events; on disconnect it is replaced with an empty string.
   * Default: "CarPlay session {event}".
   */
  body?: string;
  /** Play a sound with the notification (iOS only). Default: true. */
  sound?: boolean;
  /** Android drawable resource name for the notification icon (e.g. "ic_notification"). */
  icon?: string;
};

/** Configuration for the Android notification channel used for CarPlay events. */
export type CarPlayChannelConfig = {
  /** Channel display name shown in system settings. Default: "CarPlay / Android Auto". */
  name?: string;
  /** Channel description shown in system settings. Default: "CarPlay and Android Auto connect/disconnect notifications". */
  description?: string;
  /**
   * Channel importance level. Default: 'default' (so connect/disconnect events make a sound).
   * Note: Android may ignore decreases in importance after first channel creation until the app is reinstalled.
   */
  importance?: "low" | "default" | "high";
};

/** Combined notification configuration for all notification types. */
export type NotificationConfig = {
  /** Settings for beacon enter/exit event notifications. */
  beaconEvents?: BeaconNotificationConfig;
  /** Settings for CarPlay / Android Auto connect/disconnect notifications. */
  carPlayEvents?: CarPlayNotificationConfig;
  /** Settings for the persistent foreground service notification (Android only). */
  foregroundService?: ForegroundServiceConfig;
  /** Settings for the Android notification channel (Android only). */
  channel?: NotificationChannelConfig;
  /** Settings for the Android notification channel used for CarPlay events (Android only). */
  carPlayChannel?: CarPlayChannelConfig;
};

/** Snapshot of the current monitoring configuration and active state. */
export type MonitoringConfig = {
  /** Whether background monitoring is currently active. */
  isMonitoring: boolean;
  maxDistance?: number;
  exitDistance?: number;
  minRssi?: number;
  level?: 'all' | 'events';
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
  level?: 'all' | 'events';
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

/** Transport reported with CarPlay / Android Auto connection events. */
export type CarPlayTransport =
  | "wired"      // iOS CarPlay over USB / Lightning
  | "wireless"   // iOS CarPlay over Bluetooth + Wi-Fi
  | "projection" // Android Auto projection (phone projecting to head unit)
  | "native"     // Android Automotive OS (running on the head unit)
  | "unknown";

/** Payload fired when the device connects to a CarPlay or Android Auto session. */
export type CarPlayConnectedEvent = {
  transport: CarPlayTransport;
  /** Timestamp in milliseconds since epoch. */
  timestamp: number;
  /** ISO 8601 UTC representation of {@link timestamp} (e.g. "2026-05-12T14:23:45.678Z"). */
  timestampIso?: string;
};

/** Payload fired when the device disconnects from a CarPlay or Android Auto session. */
export type CarPlayDisconnectedEvent = {
  /** Timestamp in milliseconds since epoch. */
  timestamp: number;
  /** ISO 8601 UTC representation of {@link timestamp} (e.g. "2026-05-12T14:23:45.678Z"). */
  timestampIso?: string;
  /**
   * Reason this disconnect was emitted. Absent for normal real-time disconnects.
   * `"reconciled"` indicates the disconnect was synthesized after the module was
   * recreated in a new process and detected that the previously persisted CarPlay
   * state no longer matches the current connection — i.e. the disconnect happened
   * off-process (force-quit, OS reclaim, abrupt cable yank) and is being delivered
   * post-hoc. Emitted on both iOS and Android.
   */
  reason?: "reconciled";
};

/**
 * Snapshot of the current CarPlay / Android Auto connection state, returned by
 * {@link ExpoBeaconModule.getCarPlayConnectionStatus}.
 */
export type CarPlayConnectionStatus = {
  /** `true` if a CarPlay or Android Auto session is currently active. */
  connected: boolean;
  /**
   * Connection transport type. Present only when `connected` is `true`.
   * See {@link CarPlayTransport} for possible values.
   */
  transport?: CarPlayTransport;
  /** Unix-millisecond timestamp of last connect. Present only when `connected` is `true`. */
  timestamp?: number;
  /** ISO 8601 UTC timestamp of last connect. Present only when `connected` is `true`. */
  timestampIso?: string;
};

/**
 * Diagnostic snapshot for troubleshooting CarPlay / Android Auto detection.
 * Returned by {@link ExpoBeaconModule.getCarPlayDiagnostics}.
 *
 * The most common failure mode on Android is that the host app is not
 * registered as an Android Auto-aware app (missing
 * `com.google.android.gms.car.application` meta-data + `automotive_app_desc.xml`
 * resource). When that happens, Gearhead silently reports
 * `CONNECTION_TYPE_NOT_CONNECTED` to the app regardless of `<queries>`
 * declarations, so `onCarPlayConnected` events never fire.
 *
 * Inspect this object after calling `startCarPlayMonitoring()` and confirming
 * Android Auto is connected on the head unit. If `isCarAppMetadataPresent` or
 * `isCarProviderQueryable` is `false`, the consumer app needs to enable the
 * config plugin's `android.androidAuto.register` option (default in recent
 * versions) and re-run `expo prebuild`.
 *
 * On iOS the diagnostic is a best-effort stub — most fields are not applicable
 * because CarPlay detection uses `AVAudioSession` rather than a content
 * provider.
 */
export type CarPlayDiagnostics = {
  /**
   * Android: `true` if the host app's manifest declares the
   * `com.google.android.gms.car.application` meta-data tag (required for
   * Gearhead to expose connection state to the app). iOS: always `true`.
   */
  isCarAppMetadataPresent: boolean;
  /**
   * Android: `true` if the system can resolve at least one provider for the
   * `androidx.car.app.connection.action.CAR_PROVIDER` intent (requires the
   * `<queries>` declaration shipped by this library AND a compatible Gearhead
   * / AAOS install on the device). iOS: always `true`.
   */
  isCarProviderQueryable: boolean;
  /**
   * Android: most recent raw value read from `CarConnection.getType()`.
   * `0` = `NOT_CONNECTED`, `1` = `NATIVE` (AAOS), `2` = `PROJECTION`.
   * `null` if the observer has not yet received any value (or is not running).
   * iOS: `null`.
   */
  lastRawConnectionType: number | null;
  /**
   * `true` if the underlying connection observer is currently active.
   * On Android, equivalent to "the foreground service is running AND
   * `CarPlayMonitor.start()` succeeded". On iOS, the audio-session observer
   * is registered.
   */
  observerActive: boolean;
  /**
   * Android: `true` if the foreground service hosting the observer is alive.
   * iOS: always `true` (no background service involved).
   */
  serviceAlive: boolean;
};

/** Payload for native beacon error events (monitoring/ranging failures). */
export type BeaconErrorEvent = {
  /** Region or constraint identifier, empty string if unavailable. */
  identifier: string;
  /** Machine-readable error code (e.g. "MONITORING_FAILED", "RANGING_FAILED", "SECURITY_EXCEPTION"). */
  code: string;
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
  /** Fired when the device connects to a CarPlay (iOS) or Android Auto (Android) session. */
  onCarPlayConnected: (params: CarPlayConnectedEvent) => void;
  /** Fired when the device disconnects from a CarPlay (iOS) or Android Auto (Android) session. */
  onCarPlayDisconnected: (params: CarPlayDisconnectedEvent) => void;
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
