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

/** Payload for ranging events (beacon proximity update). */
export type BeaconRangingEvent = {
  identifier: string;
  uuid: string;
  major: number;
  minor: number;
  rssi: number;
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

/** Module event map. */
export type ExpoBeaconModuleEvents = {
  onBeaconEnter: (params: BeaconRegionEvent) => void;
  onBeaconExit: (params: BeaconRegionEvent) => void;
  onBeaconRanging: (params: BeaconRangingEvent) => void;
  onBeaconDistance: (params: BeaconDistanceEvent) => void;
  /** Fired continuously during a live scan as each beacon is detected. */
  onBeaconFound: (params: BeaconScanResult) => void;
};
