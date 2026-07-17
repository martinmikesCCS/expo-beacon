# Getting started

## 1. Install

```sh
npx expo install expo-beacon
```

This package contains native code. Use an Expo development build or a bare
React Native application; Expo Go is not supported.

## 2. Add the config plugin

```json
{
  "expo": {
    "plugins": ["expo-beacon"]
  }
}
```

The plugin adds the required iOS usage descriptions and background modes.
Android permissions are supplied by the library manifest and merged into the
application manifest.

After adding or changing the plugin, rebuild the native application:

```sh
npx expo prebuild
npx expo run:android
# or: npx expo run:ios
```

## 3. Request permissions and scan

For new code, prefer the named helper because its options include units:

```ts
import { ExpoBeacon, scanForBeacons } from "expo-beacon";

const granted = await ExpoBeacon.requestPermissionsAsync();
if (!granted) {
  throw new Error("Beacon permissions were not granted");
}

const nearby = await scanForBeacons({
  uuids: ["E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"],
  durationMs: 5_000,
});
```

On iOS, the UUID list cannot be omitted unless at least one paired iBeacon can
supply a UUID. Android supports wildcard iBeacon scanning. Eddystone scanning
does not use iBeacon UUID filters.

## Next steps

- [Scanning](scanning.md)
- [Background monitoring](background-monitoring.md)
- [Platform support](platform-support.md)
- [Errors](errors.md)
