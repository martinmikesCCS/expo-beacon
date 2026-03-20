import { NativeModule, requireNativeModule } from "expo";

import {
  ExpoBeaconModuleEvents,
  BeaconScanResult,
  PairedBeacon,
  NotificationConfig,
  MonitoringOptions,
} from "./ExpoBeacon.types";

declare class ExpoBeaconModule extends NativeModule<ExpoBeaconModuleEvents> {
  /**
   * Start a one-shot iBeacon scan. Resolves with discovered beacons after scanDuration ms.
   *
   * Pass one or more UUIDs to scan for specific beacons (uses CoreLocation on iOS).
   * Pass an empty array or omit to perform a wildcard scan that discovers all nearby
   * iBeacons (uses CoreBluetooth on iOS — foreground only).
   *
   * @param uuids Proximity UUIDs to filter by. Empty/omitted = wildcard scan.
   * @param scanDuration Duration in ms (default 5000)
   */
  scanForBeaconsAsync(
    uuids?: string[],
    scanDuration?: number,
  ): Promise<BeaconScanResult[]>;

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

  /** Request Bluetooth + Location permissions. Returns true if granted. */
  requestPermissionsAsync(): Promise<boolean>;
}

try {
  // eslint-disable-next-line import/no-mutable-exports
  var module = requireNativeModule<ExpoBeaconModule>("ExpoBeacon");
} catch {
  throw new Error(
    "expo-beacon: native module not found. Make sure you are using a development build " +
      "(not Expo Go) and have run `npx expo prebuild` followed by a native rebuild.",
  );
}

export default module;
