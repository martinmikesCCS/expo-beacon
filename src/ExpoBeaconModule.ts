import { NativeModule, requireNativeModule } from "expo";

import {
  ExpoBeaconModuleEvents,
  BeaconScanResult,
  EddystoneScanResult,
  PairedBeacon,
  PairedEddystone,
  NotificationConfig,
  MonitoringOptions,
  MonitoringConfig,
  MonitoredDeviceState,
  EventLogQueryOptions,
  EventLogEntry,
  BeaconNotificationConfig,
  BeaconNotificationSettings,
} from "./ExpoBeacon.types";

export declare class ExpoBeaconModule extends NativeModule<ExpoBeaconModuleEvents> {
  /**
   * Start a one-shot iBeacon scan. Resolves with discovered beacons after scanDuration ms.
   *
   * Pass one or more UUIDs to scan for specific beacons (uses CoreLocation on iOS).
   * On iOS, at least one UUID is required — Apple strips iBeacon data from BLE
   * advertisements, making wildcard discovery impossible. When you pass an empty
   * array, the module automatically uses UUIDs from paired beacons.
   * On Android, pass an empty array to discover all nearby iBeacons.
   *
   * @param uuids Proximity UUIDs to filter by. Empty/omitted = use paired UUIDs (iOS) or wildcard (Android).
   * @param scanDuration Duration in ms (default 5000)
   */
  scanForBeaconsAsync(
    uuids?: string[],
    scanDuration?: number,
  ): Promise<BeaconScanResult[]>;

  /**
   * Start a one-shot Eddystone beacon scan using BLE.
   * Discovers Eddystone-UID and Eddystone-URL frames.
   *
   * @param scanDuration Duration in ms (default 5000)
   */
  scanForEddystonesAsync(scanDuration?: number): Promise<EddystoneScanResult[]>;

  /**
   * Register a beacon for persistent region monitoring.
   *
   * Re-pairing an existing iBeacon identifier replaces the previous entry.
   * Throws `DUPLICATE_IDENTIFIER` if the identifier is already used by a
   * paired Eddystone beacon.
   */
  pairBeacon(
    identifier: string,
    uuid: string,
    major: number,
    minor: number,
    name?: string,
    timeoutSeconds?: number,
  ): void;

  /**
   * Remove a previously paired beacon.
   */
  unpairBeacon(identifier: string): void;

  /**
   * Return all currently paired beacons.
   */
  getPairedBeacons(): PairedBeacon[];

  /**
   * Register an Eddystone-UID beacon for persistent monitoring.
   * Namespace and instance are normalized to lowercase before storage.
   *
   * Re-pairing an existing Eddystone identifier replaces the previous entry.
   * Throws `DUPLICATE_IDENTIFIER` if the identifier is already used by a
   * paired iBeacon.
   */
  pairEddystone(
    identifier: string,
    namespace: string,
    instance: string,
    name?: string,
    timeoutSeconds?: number,
  ): void;

  /**
   * Remove a previously paired Eddystone beacon.
   */
  unpairEddystone(identifier: string): void;

  /**
   * Return all currently paired Eddystone beacons.
   */
  getPairedEddystones(): PairedEddystone[];

  /**
   * Set persistent notification configuration. Settings are saved and applied to all
   * subsequent monitoring sessions until explicitly changed.
   */
  setNotificationConfig(config: NotificationConfig): void;

  /**
   * Persist beacon notification settings without replacing other beacon settings.
   * Passing a plain BeaconNotificationConfig is treated as the beacon event config.
   */
  setBeaconNotificationConfig(
    config: BeaconNotificationSettings | BeaconNotificationConfig,
  ): void;

  /**
   * Start background region monitoring for all paired beacons.
   * On Android starts a foreground service.
   * On iOS starts CLLocationManager region monitoring.
   *
   * Accepts a plain number (backward-compatible maxDistance shorthand) or a
   * MonitoringOptions object with maxDistance and/or notification overrides.
   */
  startMonitoring(options?: MonitoringOptions | number): Promise<void>;

  /**
   * Stop background region monitoring. Persisted monitoring options
   * (maxDistance, exitDistance, level, exitTimeoutSeconds, …) are cleared
   * on both platforms.
   */
  stopMonitoring(): Promise<void>;

  /**
   * Start a continuous BLE scan. Fires `onBeaconFound` / `onEddystoneFound`
   * events as beacons are detected. Call stopContinuousScan() to end the scan.
   *
   * iOS only ranges the UUIDs of PAIRED beacons — with no paired beacons no
   * iBeacons are discovered. Android discovers all nearby iBeacons.
   * Eddystone discovery works on both platforms regardless of pairing.
   */
  startContinuousScan(): void;

  /** Stop the continuous scan started by startContinuousScan(). */
  stopContinuousScan(): void;

  /**
   * Cancel any in-progress one-shot scan (iBeacon or Eddystone).
   * The pending promise will be rejected with code "SCAN_CANCELLED".
   */
  cancelScan(): void;

  /**
   * Request the permissions needed for scanning and monitoring.
   *
   * - Android: requests location, Bluetooth (API 31+) and notification
   *   (API 33+) permissions, then background location (API 29+) in a second
   *   prompt; resolves true only when background location is granted.
   * - iOS: requests location When-In-Use authorization and resolves true once
   *   granted — the Always upgrade is requested later by startMonitoring(),
   *   and Bluetooth permission is not prompted here.
   */
  requestPermissionsAsync(): Promise<boolean>;

  /**
   * Check whether the app is exempt from Android battery optimizations.
   * Always returns true on iOS and web (no equivalent concept).
   */
  isBatteryOptimizationExempt(): boolean;

  /**
   * Request exemption from Android battery optimizations.
   * Opens the system dialog asking the user to whitelist this app.
   * Returns true if the dialog was shown (or already exempt), false on failure.
   * Always resolves true on iOS and web.
   */
  requestBatteryOptimizationExemption(): Promise<boolean>;

  /** Enable SQLite event logging. All beacon events will be persisted to a local database. */
  enableEventLogging(): void;

  /** Disable event logging. Previously logged events are retained. */
  disableEventLogging(): void;

  /**
   * Returns whether SQLite event logging is currently enabled.
   * Reads the persisted flag, so this stays accurate across app cold-starts.
   */
  isEventLoggingEnabled(): boolean;

  /**
   * Retrieve logged beacon events from the SQLite database.
   * @param options Optional filters (limit, eventType, sinceTimestamp).
   */
  getEventLogs(options?: EventLogQueryOptions): EventLogEntry[];

  /** Delete all logged events from the database. */
  clearEventLogs(): void;

  /** Delete the entire event log database. Also disables logging. */
  destroyEventLogs(): void;

  /**
   * Configure a remote API endpoint for native event forwarding.
   * Once set, beacon events are POSTed directly from native code,
   * ensuring delivery even when the JS bridge is not active (app backgrounded).
   *
   * @param url The API endpoint URL to POST events to.
   * @param apiKey Optional API key sent as the X-CSFR-Token header
   *   (sic — the header is literally "X-CSFR-Token", not "X-CSRF-Token").
   * @param id Optional identifier appended to every forwarded event payload.
   */
  setApiEndpoint(url: string, apiKey?: string, id?: string): void;

  /**
   * Return the current monitoring configuration and active state.
   * Option fields are undefined if not explicitly set.
   */
  getMonitoringConfig(): MonitoringConfig;

  /**
   * Return the current state snapshot for a paired monitored device.
   * Returns null when no paired device matches the identifier.
   */
  getMonitoredDeviceState(identifier: string): MonitoredDeviceState | null;

  /**
   * Return the current state snapshot for all paired monitored devices.
   */
  getMonitoredDeviceStates(): MonitoredDeviceState[];

  /**
   * Return the current API forwarding configuration.
   * Each field is `null` if not set.
   */
  getApiEndpoint(): {
    url: string | null;
    apiKey: string | null;
    id: string | null;
  };
}

// The scan parameters are genuinely optional: both native implementations
// default an omitted scanDuration to 5000 ms (and an omitted uuid list to the
// paired-UUID fallback on iOS / wildcard on Android), so the optional TS
// signatures are honoured without a JS-side shim.
export default requireNativeModule<ExpoBeaconModule>("ExpoBeacon");
