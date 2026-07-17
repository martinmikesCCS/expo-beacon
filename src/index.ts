// Native module. The default export is retained for backwards compatibility;
// the named export is easier to discover in generated documentation and IDEs.
export { default, default as ExpoBeacon } from "./ExpoBeaconModule.js";
export type { ExpoBeaconModule } from "./ExpoBeaconModule.js";

// Object-based helpers for APIs whose native contracts use positional arguments.
export {
  pairBeacon,
  pairEddystone,
  scanForBeacons,
  scanForEddystones,
} from "./helpers.js";
export type {
  PairBeaconOptions,
  PairEddystoneOptions,
  ScanForBeaconsOptions,
  ScanForEddystonesOptions,
} from "./helpers.js";

// React hooks
export { useBeacon } from "./hooks/useBeacon.js";
export type {
  UseBeaconOptions,
  UseBeaconResult,
  InRangeBeacon,
} from "./hooks/useBeacon";

// All public types
export type {
  BeaconScanResult,
  PairedBeacon,
  BeaconRegionEvent,
  BeaconDistanceEvent,
  BeaconTimeoutEvent,
  BeaconErrorEvent,
  ExpoBeaconModuleEvents,
  NotificationConfig,
  BeaconNotificationSettings,
  MonitoringOptions,
  MonitoringConfig,
  MonitoredDeviceState,
  BeaconNotificationConfig,
  ForegroundServiceConfig,
  NotificationChannelConfig,
  EddystoneFrameType,
  EddystoneScanResult,
  PairedEddystone,
  EddystoneRegionEvent,
  EddystoneDistanceEvent,
  EddystoneTimeoutEvent,
  EventLogQueryOptions,
  EventLogEntry,
  BeaconErrorCode,
  BeaconEventName,
} from "./ExpoBeacon.types";
