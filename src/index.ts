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
  CarPlayTransport,
  CarPlayConnectedEvent,
  CarPlayDisconnectedEvent,
} from "./ExpoBeacon.types";
