import type {
  ExpoBeaconModuleEvents,
  BeaconScanResult,
  EddystoneScanResult,
  PairedBeacon,
  PairedEddystone,
  NotificationConfig,
  BeaconNotificationConfig,
  BeaconNotificationSettings,
  MonitoredDeviceState,
  EventLogQueryOptions,
  EventLogEntry,
} from "./ExpoBeacon.types";
import type { ExpoBeaconModule } from "./ExpoBeaconModule";

const notSupported = (): never => {
  throw new Error("expo-beacon is not supported on web.");
};

const notSupportedAsync = (): Promise<never> =>
  Promise.reject(new Error("expo-beacon is not supported on web."));

const stub: ExpoBeaconModule = {
  scanForBeaconsAsync: (
    _uuids?: string[],
    _scanDuration?: number,
  ): Promise<BeaconScanResult[]> => notSupportedAsync(),
  scanForEddystonesAsync: (
    _scanDuration?: number,
  ): Promise<EddystoneScanResult[]> => notSupportedAsync(),
  pairBeacon: (
    _identifier: string,
    _uuid: string,
    _major: number,
    _minor: number,
  ): void => notSupported(),
  unpairBeacon: (_identifier: string): void => notSupported(),
  getPairedBeacons: (): PairedBeacon[] => notSupported(),
  pairEddystone: (
    _identifier: string,
    _namespace: string,
    _instance: string,
  ): void => notSupported(),
  unpairEddystone: (_identifier: string): void => notSupported(),
  getPairedEddystones: (): PairedEddystone[] => notSupported(),
  startMonitoring: (): Promise<void> => notSupportedAsync(),
  stopMonitoring: (): Promise<void> => notSupportedAsync(),
  startContinuousScan: (): void => notSupported(),
  stopContinuousScan: (): void => notSupported(),
  cancelScan: (): void => notSupported(),
  setNotificationConfig: (_config: NotificationConfig): void => notSupported(),
  setBeaconNotificationConfig: (
    _config: BeaconNotificationSettings | BeaconNotificationConfig,
  ): void => notSupported(),
  requestPermissionsAsync: (): Promise<boolean> => notSupportedAsync(),
  enableEventLogging: (): void => notSupported(),
  disableEventLogging: (): void => notSupported(),
  isEventLoggingEnabled: (): boolean => false,
  getEventLogs: (_options?: EventLogQueryOptions): EventLogEntry[] =>
    notSupported(),
  clearEventLogs: (): void => notSupported(),
  destroyEventLogs: (): void => notSupported(),
  setApiEndpoint: (_url: string, _apiKey?: string, _id?: string): void => {},
  getMonitoringConfig: () => notSupported(),
  getMonitoredDeviceState: (_identifier: string): MonitoredDeviceState | null =>
    notSupported(),
  getMonitoredDeviceStates: (): MonitoredDeviceState[] => notSupported(),
  getApiEndpoint: (): {
    url: string | null;
    apiKey: string | null;
    id: string | null;
  } => notSupported(),
  isBatteryOptimizationExempt: (): boolean => true,
  requestBatteryOptimizationExemption: (): Promise<boolean> =>
    Promise.resolve(true),
  addListener: (_eventName: keyof ExpoBeaconModuleEvents, _listener: any) => ({
    remove: () => {},
  }),
  removeListener: (
    _eventName: keyof ExpoBeaconModuleEvents,
    _listener: any,
  ) => {},
  removeAllListeners: (_eventName: keyof ExpoBeaconModuleEvents) => {},
  emit: (_eventName: keyof ExpoBeaconModuleEvents, ..._args: unknown[]) => {},
  listenerCount: (_eventName: keyof ExpoBeaconModuleEvents): number => 0,
};

export default stub;
