import type { BeaconScanResult, EddystoneScanResult } from "./ExpoBeacon.types";
import ExpoBeacon from "./ExpoBeaconModule.js";

/** Options for a one-shot iBeacon scan. */
export type ScanForBeaconsOptions = {
  /**
   * Proximity UUIDs to scan for.
   *
   * On iOS, an empty or omitted list uses UUIDs from paired beacons and throws
   * `WILDCARD_NOT_SUPPORTED` when no paired UUID is available. On Android, an
   * empty or omitted list performs a wildcard scan.
   */
  uuids?: readonly string[];
  /** Scan duration in milliseconds. @defaultValue 5000 */
  durationMs?: number;
};

/** Options for a one-shot Eddystone scan. */
export type ScanForEddystonesOptions = {
  /** Scan duration in milliseconds. @defaultValue 5000 */
  durationMs?: number;
};

/** An iBeacon registration used for persistent monitoring. */
export type PairBeaconOptions = {
  /** Stable application-defined identifier, for example `lobby-door`. */
  identifier: string;
  /** iBeacon proximity UUID. */
  uuid: string;
  /** iBeacon major value from 0 through 65535. */
  major: number;
  /** iBeacon minor value from 0 through 65535. */
  minor: number;
  /** Optional display name stored with the registration. */
  name?: string;
  /** Optional out-of-range timeout in seconds. Must be greater than zero. */
  timeoutSeconds?: number;
};

/** An Eddystone-UID registration used for persistent monitoring. */
export type PairEddystoneOptions = {
  /** Stable application-defined identifier, for example `meeting-room`. */
  identifier: string;
  /** Ten-byte namespace ID encoded as exactly 20 hexadecimal characters. */
  namespace: string;
  /** Six-byte instance ID encoded as exactly 12 hexadecimal characters. */
  instance: string;
  /** Optional display name stored with the registration. */
  name?: string;
  /** Optional out-of-range timeout in seconds. Must be greater than zero. */
  timeoutSeconds?: number;
};

/**
 * Scan once for iBeacons using a self-describing options object.
 *
 * @example
 * ```ts
 * const nearby = await scanForBeacons({
 *   uuids: ["E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"],
 *   durationMs: 5_000,
 * });
 * ```
 */
export function scanForBeacons(
  options: ScanForBeaconsOptions = {},
): Promise<BeaconScanResult[]> {
  const uuids = options.uuids ? [...options.uuids] : undefined;
  return ExpoBeacon.scanForBeaconsAsync(uuids, options.durationMs);
}

/** Scan once for Eddystone-UID and Eddystone-URL frames. */
export function scanForEddystones(
  options: ScanForEddystonesOptions = {},
): Promise<EddystoneScanResult[]> {
  return ExpoBeacon.scanForEddystonesAsync(options.durationMs);
}

/** Register an iBeacon for persistent monitoring. */
export function pairBeacon(options: PairBeaconOptions): void {
  ExpoBeacon.pairBeacon(
    options.identifier,
    options.uuid,
    options.major,
    options.minor,
    options.name,
    options.timeoutSeconds,
  );
}

/** Register an Eddystone-UID beacon for persistent monitoring. */
export function pairEddystone(options: PairEddystoneOptions): void {
  ExpoBeacon.pairEddystone(
    options.identifier,
    options.namespace,
    options.instance,
    options.name,
    options.timeoutSeconds,
  );
}
