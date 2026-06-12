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
  CarPlayConnectionStatus,
  CarPlayDiagnostics,
} from "./ExpoBeacon.types";

declare class ExpoBeaconModule extends NativeModule<ExpoBeaconModuleEvents> {
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
  scanForEddystonesAsync(
    scanDuration?: number,
  ): Promise<EddystoneScanResult[]>;

  /**
   * Register a beacon for persistent region monitoring.
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
   * Start background region monitoring for all paired beacons.
   * On Android starts a foreground service.
   * On iOS starts CLLocationManager region monitoring.
   *
   * Accepts a plain number (backward-compatible maxDistance shorthand) or a
   * MonitoringOptions object with maxDistance and/or notification overrides.
   */
  startMonitoring(options?: MonitoringOptions | number): Promise<void>;

  /**
   * Stop background region monitoring.
   */
  stopMonitoring(): Promise<void>;

  /**
   * Start a continuous BLE scan. Fires `onBeaconFound` events as beacons are detected.
   * Call stopContinuousScan() to end the scan.
   */
  startContinuousScan(): void;

  /** Stop the continuous scan started by startContinuousScan(). */
  stopContinuousScan(): void;

  /**
   * Cancel any in-progress one-shot scan (iBeacon or Eddystone).
   * The pending promise will be rejected with code "SCAN_CANCELLED".
   */
  cancelScan(): void;

  /** Request Bluetooth + Location permissions. Returns true if granted. */
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
   * @param apiKey Optional API key sent as X-CSFR-Token header.
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
  getApiEndpoint(): { url: string | null; apiKey: string | null; id: string | null };

  /**
   * Start observing CarPlay (iOS) / Android Auto (Android) connection state.
   *
   * Emits `onCarPlayConnected` and `onCarPlayDisconnected` JS events. Events are
   * also written to the SQLite event log and forwarded to the configured API
   * endpoint, and dispatched to native lifecycle plugins (used by the auto-
   * generated geolocation plugin to start/stop background-geolocation).
   *
   * **Persistent.** The enabled state is stored in native preferences and
   * survives app kill / device reboot. Call `stopCarPlayMonitoring()` to
   * disable. `startMonitoring()` also enables CarPlay observation
   * automatically — calling this method explicitly is only required if you
   * want CarPlay events without beacon monitoring.
   *
   * **Background behavior:**
   * - **Android**: the foreground service hosts the observer and continues to
   *   receive `CarConnection` events even after the app process is killed and
   *   restarted via `BootReceiver`. Guaranteed background detection.
   * - **iOS**: the observer auto-restarts in `OnCreate` whenever the module
   *   is recreated, including background-launches triggered by beacon region
   *   monitoring. **iOS cannot wake a terminated app on CarPlay alone** — for
   *   guaranteed wake-from-suspension, also call `startMonitoring()` with at
   *   least one paired beacon. Region wake events trigger a CarPlay state
   *   resync to reconcile any route changes that occurred during suspension.
   *
   * - iOS: observes `AVAudioSession.routeChangeNotification` for `.carAudio` ports.
   *   No CarPlay entitlement required.
   * - Android: observes `androidx.car.app.connection.CarConnection` LiveData.
   *   No Android Auto certification required.
   * - Web: no-op (resolves immediately).
   */
  startCarPlayMonitoring(): Promise<void>;

  /**
   * Stop CarPlay / Android Auto connection monitoring and clear the persisted
   * "enabled" flag so the observer is not auto-restarted on next launch / boot.
   *
   * On Android, if no beacon monitoring is active, the foreground service
   * stops itself.
   */
  stopCarPlayMonitoring(): Promise<void>;

  /**
   * Returns whether CarPlay / Android Auto observation is currently enabled.
   * Reads the persisted flag, so the value survives app cold-starts and
   * reflects the native source of truth (foreground service on Android,
   * UserDefaults suite on iOS).
   */
  isCarPlayMonitoringEnabled(): boolean;

  /**
   * Returns a snapshot of the current CarPlay / Android Auto connection state
   * without waiting for an event. Useful on mount to immediately reflect
   * whether a car session is already active.
   *
   * Reads from the live observer if the foreground service is running, or falls
   * back to the persisted last-known state otherwise.
   */
  getCarPlayConnectionStatus(): CarPlayConnectionStatus;

  /**
   * Returns diagnostic information about the CarPlay / Android Auto detection
   * pipeline. Use this when `onCarPlayConnected` never fires despite the head
   * unit being connected — it reveals whether the host app is correctly
   * registered with Gearhead, whether the connection content provider is
   * reachable, and the most recent raw value the observer received.
   *
   * See {@link CarPlayDiagnostics} for field-level guidance.
   */
  getCarPlayDiagnostics(): CarPlayDiagnostics;
}

export default requireNativeModule<ExpoBeaconModule>("ExpoBeacon");
