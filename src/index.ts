// Native module (default export)
export { default } from "./ExpoBeaconModule.js";

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
} from "./ExpoBeacon.types.js";
