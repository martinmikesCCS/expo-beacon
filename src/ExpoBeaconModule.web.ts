import { registerWebModule, NativeModule } from 'expo';

import { ExpoBeaconModuleEvents } from './ExpoBeacon.types';

class ExpoBeaconModule extends NativeModule<ExpoBeaconModuleEvents> {
  PI = Math.PI;
  async setValueAsync(value: string): Promise<void> {
    this.emit('onChange', { value });
  }
  hello() {
    return 'Hello world! 👋';
  }
}

export default registerWebModule(ExpoBeaconModule, 'ExpoBeaconModule');
