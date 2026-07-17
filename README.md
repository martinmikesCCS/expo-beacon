# expo-beacon

Expo native module for scanning, pairing, and monitoring iBeacon and Eddystone
devices on Android and iOS, including background monitoring, notifications,
event logging, and optional background-geolocation integration.

| Platform | Implementation                     | Support                       |
| -------- | ---------------------------------- | ----------------------------- |
| Android  | AltBeacon and a foreground service | Scanning and monitoring       |
| iOS      | Core Location and Core Bluetooth   | Scanning and monitoring       |
| Web      | Inert fallback                     | Native operations unsupported |

## Important constraints

- Native code is required. Expo Go is not supported.
- Add the bundled config plugin before creating a development build.
- iOS cannot perform wildcard iBeacon scans; provide a UUID or pair an iBeacon.
- Pair at least one iBeacon or Eddystone-UID device before monitoring.
- Eddystone scanning does not use an iBeacon UUID filter.

## Install

```sh
npx expo install expo-beacon
```

Add the config plugin:

```json
{
  "expo": {
    "plugins": ["expo-beacon"]
  }
}
```

Rebuild the native application after adding or changing the plugin:

```sh
npx expo prebuild
npx expo run:android
# or: npx expo run:ios
```

See [Getting started](docs/getting-started.md) for permission behavior and the
complete installation path.

## Scan for iBeacons

For new code, prefer the named helpers with self-describing options:

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

The backwards-compatible default export still exposes the positional native
API:

```ts
import ExpoBeacon from "expo-beacon";

const nearby = await ExpoBeacon.scanForBeaconsAsync(
  ["E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"],
  5_000,
);
```

See [Scanning](docs/scanning.md) for Eddystone, continuous scanning, result
units, cancellation, and platform differences.

## Monitor a paired beacon

```ts
import { ExpoBeacon, pairBeacon } from "expo-beacon";

pairBeacon({
  identifier: "lobby-door",
  uuid: "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
  major: 1,
  minor: 100,
  timeoutSeconds: 30,
});

const entered = ExpoBeacon.addListener("onBeaconEnter", (event) => {
  console.log("entered", event.identifier, event.distance);
});

await ExpoBeacon.startMonitoring({
  maxDistance: 10,
  exitDistance: 12.5,
  level: "events",
});

// Later:
await ExpoBeacon.stopMonitoring();
entered.remove();
```

See [Background monitoring](docs/background-monitoring.md) for thresholds,
timeouts, Eddystone pairing, notifications, and cleanup.

## React hook

```tsx
import { useBeacon } from "expo-beacon";

function NearbyBeaconCount() {
  const { inRange, isMonitoring, startMonitoring, stopMonitoring } =
    useBeacon();

  return null; // Render these values in your application UI.
}
```

The hook manages native event subscriptions, reactive paired-device state, and
stable action wrappers.

## Config-plugin types

The plugin option types are available through a typed package subpath:

```ts
import type { BeaconPluginProps } from "expo-beacon/plugin";
```

See [Config plugin](docs/config-plugin.md) and
[Background geolocation](docs/background-geolocation.md).

## Documentation

- [Documentation index](docs/index.md)
- [Getting started](docs/getting-started.md)
- [Scanning](docs/scanning.md)
- [Background monitoring](docs/background-monitoring.md)
- [Platform support](docs/platform-support.md)
- [Config plugin](docs/config-plugin.md)
- [Errors](docs/errors.md)
- [Generated runtime API](docs/reference/runtime/README.md)
- [Generated config-plugin API](docs/reference/plugin/README.md)
- [Detailed compatibility reference](docs/full-reference.md)
- [`llms.txt`](llms.txt)

## Contributing

Run the following checks before opening a pull request:

```sh
npm run build
npm test -- --runInBand
npm run test:types
npm run lint
npm run docs:api
npm pack --dry-run
```

Repository-specific guidance for coding agents and contributors is in
[AGENTS.md](AGENTS.md).

## License

MIT
