// Native module (default export)
export { default } from "./ExpoBeaconModule.js";

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
} from "./ExpoBeacon.types";
