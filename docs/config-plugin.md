# Expo config plugin

The package includes a config plugin. The minimal configuration is:

```json
{
  "expo": {
    "plugins": ["expo-beacon"]
  }
}
```

The plugin's public option types can be imported from `expo-beacon/plugin`:

```ts
import type { BeaconPluginProps } from "expo-beacon/plugin";

const options = {
  ios: {
    locationWhenInUsePermission: "Allow the app to detect nearby beacons.",
    locationAlwaysPermission: "Allow the app to monitor paired beacons.",
    bluetoothPermission: "Allow the app to scan for Bluetooth beacons.",
  },
  android: {},
} satisfies BeaconPluginProps;
```

## Options

| Option                            | Default                         | Purpose                                                       |
| --------------------------------- | ------------------------------- | ------------------------------------------------------------- |
| `ios.locationWhenInUsePermission` | Existing value or built-in text | Sets `NSLocationWhenInUseUsageDescription`.                   |
| `ios.locationAlwaysPermission`    | Existing value or built-in text | Sets `NSLocationAlwaysAndWhenInUseUsageDescription`.          |
| `ios.bluetoothPermission`         | Existing value or built-in text | Sets `NSBluetoothAlwaysUsageDescription`.                     |
| `ios.backgroundGeolocation`       | `false`                         | Generates the optional iOS background-geolocation bridge.     |
| `android.backgroundGeolocation`   | `false`                         | Generates the optional Android background-geolocation bridge. |

Both background-geolocation options require
`react-native-background-geolocation` to be installed in the consuming app.
The plugin fails during prebuild with an actionable error when the option is
enabled but the dependency cannot be resolved.

Rebuild native projects after changing config-plugin options.
