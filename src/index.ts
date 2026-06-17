// Native module (default export)
export { default } from "./ExpoBeaconModule.js";

// React hooks
export { useBeacon } from "./hooks/useBeacon.js";
export { useCarPlay } from "./hooks/useCarPlay.js";
export type {
  UseBeaconOptions,
  UseBeaconResult,
  InRangeBeacon,
} from "./hooks/useBeacon";
export type { UseCarPlayOptions, UseCarPlayResult } from "./hooks/useCarPlay";

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
  CarPlayNotificationConfig,
  CarPlayNotificationSettings,
  CarPlayChannelConfig,
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
  CarPlayConnectionStatus,
  CarPlayDiagnostics,
} from "./ExpoBeacon.types";
