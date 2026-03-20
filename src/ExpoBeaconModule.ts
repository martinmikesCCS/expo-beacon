import { NativeModule, requireNativeModule } from "expo";

import {
  ExpoBeaconModuleEvents,
  BeaconScanResult,
  EddystoneScanResult,
  PairedBeacon,
  PairedEddystone,
  NotificationConfig,
  MonitoringOptions,
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
}

export default requireNativeModule<ExpoBeaconModule>("ExpoBeacon");
