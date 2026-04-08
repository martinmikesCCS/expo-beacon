// Native module (default export)
export { default } from "./ExpoBeaconModule";

// All public types
export type {
  BeaconScanResult,
  PairedBeacon,
  BeaconRegionEvent,
  BeaconDistanceEvent,
  BeaconTimeoutEvent,
  ExpoBeaconModuleEvents,
  NotificationConfig,
  MonitoringOptions,
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
