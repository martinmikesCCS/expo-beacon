import { NativeModule, requireNativeModule } from "expo";

import {
  ExpoBeaconModuleEvents,
  BeaconScanResult,
  PairedBeacon,
} from "./ExpoBeacon.types";

declare class ExpoBeaconModule extends NativeModule<ExpoBeaconModuleEvents> {
  /**
   * Start a one-shot BLE scan. Resolves with discovered beacons after scanDuration ms.
   * @param scanDuration Duration in ms (default 5000)
   */
  scanForBeaconsAsync(scanDuration?: number): Promise<BeaconScanResult[]>;

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
   * Start background region monitoring for all paired beacons.
   * On Android starts a foreground service.
   * On iOS starts CLLocationManager region monitoring.
   * @param maxDistance Optional distance threshold in metres. Enter events are only
   *   emitted when the beacon is measured to be within this distance.
   *   Exit events are always emitted when the beacon region is lost.
   */
  startMonitoring(maxDistance?: number): Promise<void>;

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

export default requireNativeModule<ExpoBeaconModule>("ExpoBeacon");
