import type {
  ExpoBeaconModuleEvents,
  BeaconScanResult,
  EddystoneScanResult,
  PairedBeacon,
  PairedEddystone,
  MonitoredDeviceState,
  EventLogQueryOptions,
  EventLogEntry,
} from "./ExpoBeacon.types.js";

const notSupported = (): never => {
  throw new Error("expo-beacon is not supported on web.");
};

const stub = {
  scanForBeaconsAsync: (
    _uuids: string[],
    _scanDuration?: number,
  ): Promise<BeaconScanResult[]> => notSupported(),
  scanForEddystonesAsync: (
    _scanDuration?: number,
  ): Promise<EddystoneScanResult[]> => notSupported(),
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
  startMonitoring: (): Promise<void> => notSupported(),
  stopMonitoring: (): Promise<void> => notSupported(),
  startContinuousScan: (): void => notSupported(),
  stopContinuousScan: (): void => notSupported(),
  cancelScan: (): void => notSupported(),
  setNotificationConfig: (_config: Record<string, unknown>): void => notSupported(),
  requestPermissionsAsync: (): Promise<boolean> => notSupported(),
  enableEventLogging: (): void => notSupported(),
  disableEventLogging: (): void => notSupported(),
  getEventLogs: (_options?: EventLogQueryOptions): EventLogEntry[] => notSupported(),
  clearEventLogs: (): void => notSupported(),
  destroyEventLogs: (): void => notSupported(),
  getMonitoringConfig: () => notSupported(),
  getMonitoredDeviceState: (_identifier: string): MonitoredDeviceState | null => notSupported(),
  getMonitoredDeviceStates: (): MonitoredDeviceState[] => notSupported(),
  getApiEndpoint: (): { url: string | null; apiKey: string | null; id: string | null } => notSupported(),
  isBatteryOptimizationExempt: (): boolean => true,
  requestBatteryOptimizationExemption: (): Promise<boolean> => Promise.resolve(true),
  addListener: (_eventName: keyof ExpoBeaconModuleEvents, _listener: any) => ({
    remove: () => {},
  }),
  removeAllListeners: (_eventName: keyof ExpoBeaconModuleEvents) => {},
};

export default stub;
