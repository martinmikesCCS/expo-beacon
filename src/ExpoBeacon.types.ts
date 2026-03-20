/** Raw beacon discovered during a scan. */
export type BeaconScanResult = {
  uuid: string; // iBeacon proximity UUID (uppercase, formatted)
  major: number; // iBeacon major value (0–65535)
  minor: number; // iBeacon minor value (0–65535)
  rssi: number; // Signal strength in dBm (negative number)
  distance: number; // Estimated distance in meters
  txPower: number; // Calibrated TX power
};

/** A beacon that has been paired/registered for monitoring. */
export type PairedBeacon = {
  identifier: string; // User-defined label (e.g. "lobby-door")
  uuid: string;
  major: number;
  minor: number;
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
};

/** Payload for periodic distance update events during monitoring. */
export type BeaconDistanceEvent = {
  identifier: string;
  uuid: string;
  major: number;
  minor: number;
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
};

/** An Eddystone-UID beacon that has been paired/registered for monitoring. */
export type PairedEddystone = {
  identifier: string;
  /** 10-byte namespace ID as hex string (20 chars). */
  namespace: string;
  /** 6-byte instance ID as hex string (12 chars). */
  instance: string;
};

/** Payload for Eddystone enter/exit region events. */
export type EddystoneRegionEvent = {
  identifier: string;
  namespace: string;
  instance: string;
  event: "enter" | "exit";
  /** Measured distance in metres at the time of the event (–1 if unavailable). */
  distance: number;
};

/** Payload for periodic Eddystone distance update events during monitoring. */
export type EddystoneDistanceEvent = {
  identifier: string;
  namespace: string;
  instance: string;
  distance: number;
};

/** Module event map. */
export type ExpoBeaconModuleEvents = {
  onBeaconEnter: (params: BeaconRegionEvent) => void;
  onBeaconExit: (params: BeaconRegionEvent) => void;
  onBeaconDistance: (params: BeaconDistanceEvent) => void;
  /** Fired continuously during a live scan as each iBeacon is detected. */
  onBeaconFound: (params: BeaconScanResult) => void;
  /** Fired continuously during a live scan as each Eddystone beacon is detected. */
  onEddystoneFound: (params: EddystoneScanResult) => void;
  onEddystoneEnter: (params: EddystoneRegionEvent) => void;
  onEddystoneExit: (params: EddystoneRegionEvent) => void;
  onEddystoneDistance: (params: EddystoneDistanceEvent) => void;
};
