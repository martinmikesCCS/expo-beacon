import type {
  ExpoBeaconModuleEvents,
  BeaconScanResult,
  PairedBeacon,
} from "./ExpoBeacon.types";

const notSupported = (): never => {
  throw new Error("expo-beacon is not supported on web.");
};

const stub = {
  scanForBeaconsAsync: (_scanDuration?: number): Promise<BeaconScanResult[]> =>
    notSupported(),
  pairBeacon: (
    _identifier: string,
    _uuid: string,
    _major: number,
    _minor: number,
  ): void => notSupported(),
  unpairBeacon: (_identifier: string): void => notSupported(),
  getPairedBeacons: (): PairedBeacon[] => notSupported(),
  startMonitoring: (): Promise<void> => notSupported(),
  stopMonitoring: (): Promise<void> => notSupported(),
  requestPermissionsAsync: (): Promise<boolean> => notSupported(),
  addListener: (_eventName: keyof ExpoBeaconModuleEvents, _listener: any) => ({
    remove: () => {},
  }),
  removeAllListeners: (_eventName: keyof ExpoBeaconModuleEvents) => {},
};

export default stub;
