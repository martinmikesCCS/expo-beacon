import { NativeModule, requireNativeModule } from 'expo';

import { ExpoBeaconModuleEvents } from './ExpoBeacon.types';

declare class ExpoBeaconModule extends NativeModule<ExpoBeaconModuleEvents> {
  PI: number;
  hello(): string;
  setValueAsync(value: string): Promise<void>;
}

// This call loads the native module object from the JSI.
export default requireNativeModule<ExpoBeaconModule>('ExpoBeacon');
