# expo-beacon

An Expo module for scanning, pairing, and monitoring iBeacons in React Native apps.

- **Scan** for nearby iBeacons via a one-shot or continuous BLE scan
- **Pair** specific beacons for persistent tracking across app restarts
- **Monitor** paired beacons in the background with enter/exit callbacks
- **Distance events** fired continuously while a monitored beacon is in range
- **Native notifications** shown automatically on region enter/exit

| Platform | Native implementation                                                                                     |
| -------- | --------------------------------------------------------------------------------------------------------- |
| Android  | [AltBeacon](https://altbeacon.github.io/android-beacon-library/) (`org.altbeacon:android-beacon-library`) |
| iOS      | CoreLocation / `CLLocationManager` (iBeacon native API)                                                   |
| Web      | Not supported (throws on all calls)                                                                        |

---

## Installation

```sh
npx expo install expo-beacon
```

> This module contains native code and **cannot** be used with Expo Go. Use a [development build](https://docs.expo.dev/develop/development-builds/introduction/).

---

## Platform Setup

### iOS

Add the following keys to your `Info.plist`:

```xml
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>This app monitors iBeacons in the background.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>This app uses location to detect nearby beacons.</string>
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to scan for iBeacons.</string>
```

In Xcode under **Signing & Capabilities**, enable:

- **Background Modes → Location updates**
- **Background Modes → Uses Bluetooth LE accessories**

> iOS limits apps to **20 simultaneously monitored regions**.

### Android

All required permissions are declared by the module's `AndroidManifest.xml` and merged into your app automatically. You must still request runtime permissions before scanning or monitoring — the easiest way is:

```ts
await ExpoBeacon.requestPermissionsAsync();
```

---

## Quick-start example

```tsx
import { useEffect, useState } from "react";
import { Button, FlatList, Text, View } from "react-native";
import ExpoBeacon from "expo-beacon";
import type {
  BeaconScanResult,
  BeaconRegionEvent,
  BeaconDistanceEvent,
} from "expo-beacon";

export default function App() {
  const [beacons, setBeacons] = useState<BeaconScanResult[]>([]);

  useEffect(() => {
    // 1. Pair a known beacon for monitoring
    ExpoBeacon.pairBeacon(
      "lobby-entrance",
      "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
      1,
      100,
    );

    // 2. Subscribe to region events
    const enterSub = ExpoBeacon.addListener(
      "onBeaconEnter",
      (e: BeaconRegionEvent) =>
        console.log(`Entered ${e.identifier} at ${e.distance.toFixed(1)} m`),
    );
    const exitSub = ExpoBeacon.addListener("onBeaconExit", (e: BeaconRegionEvent) =>
      console.log(`Exited ${e.identifier}`),
    );
    const distSub = ExpoBeacon.addListener(
      "onBeaconDistance",
      (e: BeaconDistanceEvent) =>
        console.log(`${e.identifier}: ${e.distance.toFixed(2)} m`),
    );

    // 3. Start background monitoring (only fires for paired beacons)
    ExpoBeacon.requestPermissionsAsync().then((granted) => {
      if (granted) ExpoBeacon.startMonitoring(10); // enter events within 10 m
    });

    return () => {
      enterSub.remove();
      exitSub.remove();
      distSub.remove();
      ExpoBeacon.stopMonitoring();
    };
  }, []);

  async function scan() {
    const results = await ExpoBeacon.scanForBeaconsAsync(5000);
    setBeacons(results);
  }

  return (
    <View>
      <Button title="Scan 5 s" onPress={scan} />
      <FlatList
        data={beacons}
        keyExtractor={(b) => `${b.uuid}-${b.major}-${b.minor}`}
        renderItem={({ item: b }) => (
          <Text>
            {b.uuid} {b.major}/{b.minor} — {b.distance.toFixed(1)} m
          </Text>
        )}
      />
    </View>
  );
}
```

---

## API Reference

### `requestPermissionsAsync()`

```ts
requestPermissionsAsync(): Promise<boolean>
```

Requests all permissions required for scanning and monitoring:

- **Android**: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS` (API 33+)
- **iOS**: `CLLocationManager` "Always" authorization

Returns `true` if all permissions were granted, `false` otherwise.

```ts
const granted = await ExpoBeacon.requestPermissionsAsync();
if (!granted) {
  console.warn("Permissions not granted — scanning and monitoring will fail.");
}
```

---

### `scanForBeaconsAsync(scanDurationMs?)`

```ts
scanForBeaconsAsync(scanDurationMs?: number): Promise<BeaconScanResult[]>
```

Starts a **one-shot BLE scan**, waits for `scanDurationMs` milliseconds, then resolves with all beacons discovered during that window.

| Parameter       | Type     | Default | Description                              |
| --------------- | -------- | ------- | ---------------------------------------- |
| `scanDurationMs`| `number` | `5000`  | How long to scan in milliseconds (1–60 000 recommended) |

Returns an array of [`BeaconScanResult`](#beaconscanresult) objects. Rejects with `SCAN_IN_PROGRESS` if another scan is already running.

```ts
const beacons = await ExpoBeacon.scanForBeaconsAsync(8000); // 8-second scan
beacons.forEach((b) =>
  console.log(`${b.uuid}  major=${b.major}  minor=${b.minor}  dist=${b.distance.toFixed(1)}m  rssi=${b.rssi}dBm`)
);
```

---

### `startContinuousScan()`

```ts
startContinuousScan(): void
```

Begins a **continuous BLE scan** that fires an [`onBeaconFound`](#onbeaconfound) event every time a beacon advertisement is received. Call [`stopContinuousScan()`](#stopcontinuousscan) to end it.

Unlike `scanForBeaconsAsync`, this never resolves — it streams results in real time.

```ts
const sub = ExpoBeacon.addListener("onBeaconFound", (beacon) => {
  console.log("Live:", beacon.uuid, beacon.distance);
});

ExpoBeacon.startContinuousScan();

// later, when done:
ExpoBeacon.stopContinuousScan();
sub.remove();
```

---

### `stopContinuousScan()`

```ts
stopContinuousScan(): void
```

Stops the continuous scan started by `startContinuousScan()`. No-op if no scan is running.

---

### `pairBeacon(identifier, uuid, major, minor)`

```ts
pairBeacon(identifier: string, uuid: string, major: number, minor: number): void
```

Registers a beacon for persistent region monitoring. Paired beacons survive app restarts (stored in `SharedPreferences` on Android, `UserDefaults` on iOS). Calling `pairBeacon` with an existing `identifier` replaces that entry.

| Parameter    | Type     | Description                                                   |
| ------------ | -------- | ------------------------------------------------------------- |
| `identifier` | `string` | Your unique label for this beacon (e.g. `"lobby-entrance"`)   |
| `uuid`       | `string` | iBeacon proximity UUID (case-insensitive, standard format)    |
| `major`      | `number` | iBeacon major value (0 – 65535)                               |
| `minor`      | `number` | iBeacon minor value (0 – 65535)                               |

```ts
ExpoBeacon.pairBeacon(
  "main-door",
  "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
  1,
  42,
);
```

---

### `unpairBeacon(identifier)`

```ts
unpairBeacon(identifier: string): void
```

Removes a previously paired beacon. If monitoring is active, the region for this beacon stops being monitored immediately.

| Parameter    | Type     | Description                        |
| ------------ | -------- | ---------------------------------- |
| `identifier` | `string` | The label used when pairing        |

```ts
ExpoBeacon.unpairBeacon("main-door");
```

---

### `getPairedBeacons()`

```ts
getPairedBeacons(): PairedBeacon[]
```

Returns the list of all currently paired beacons from persistent storage.

```ts
const paired = ExpoBeacon.getPairedBeacons();
paired.forEach((b) =>
  console.log(b.identifier, b.uuid, b.major, b.minor)
);
```

---

### `startMonitoring(maxDistance?)`

```ts
startMonitoring(maxDistance?: number): Promise<void>
```

Starts background region monitoring for all paired beacons.

| Parameter     | Type     | Default      | Description                                                                                   |
| ------------- | -------- | ------------ | --------------------------------------------------------------------------------------------- |
| `maxDistance` | `number` | `undefined`  | Optional distance threshold in metres. `onBeaconEnter` is only emitted when the measured distance is ≤ this value. `onBeaconExit` is always emitted. Omit to disable distance filtering. |

**Android**: Launches `BeaconForegroundService` — a persistent foreground service required by Android 8+ for background BLE. Restarts automatically after device reboot.

**iOS**: Activates `CLLocationManager` region monitoring. iOS can wake or relaunch the app when a region boundary is crossed, even if the app is terminated.

```ts
// Monitor with no distance limit
await ExpoBeacon.startMonitoring();

// Only fire enter events when within 5 metres
await ExpoBeacon.startMonitoring(5);
```

> Call `requestPermissionsAsync()` before `startMonitoring()`.

---

### `stopMonitoring()`

```ts
stopMonitoring(): Promise<void>
```

Stops background monitoring and removes all active region subscriptions. On Android, stops the foreground service.

```ts
await ExpoBeacon.stopMonitoring();
```

---

## Events

Subscribe to events using `ExpoBeacon.addListener(eventName, handler)`. Always call `.remove()` on the subscription when your component unmounts.

```ts
const sub = ExpoBeacon.addListener("onBeaconEnter", handler);
// cleanup:
sub.remove();
```

---

### `onBeaconEnter`

Fired when the device enters the region of a paired (monitored) beacon. If `maxDistance` was set in `startMonitoring`, this only fires when the measured distance at the time of entry is within that threshold.

**Payload**: [`BeaconRegionEvent`](#beaconregionevent)

```ts
ExpoBeacon.addListener("onBeaconEnter", (e) => {
  console.log(`Entered "${e.identifier}" (${e.uuid}) at ~${e.distance.toFixed(1)} m`);
});
```

---

### `onBeaconExit`

Fired when the device leaves the region of a monitored beacon. Always fired regardless of distance filtering.

**Payload**: [`BeaconRegionEvent`](#beaconregionevent)

```ts
ExpoBeacon.addListener("onBeaconExit", (e) => {
  console.log(`Left "${e.identifier}"`);
});
```

---

### `onBeaconRanging`

Fired periodically during active monitoring with the latest ranging measurement for a paired beacon.

**Payload**: [`BeaconRangingEvent`](#beaconrangingevent)

```ts
ExpoBeacon.addListener("onBeaconRanging", (e) => {
  console.log(`Ranging ${e.identifier}: ${e.distance.toFixed(2)} m  rssi=${e.rssi}`);
});
```

---

### `onBeaconDistance`

Fired continuously during monitoring whenever a distance update is received for a paired beacon. Useful for real-time proximity UI.

**Payload**: [`BeaconDistanceEvent`](#beacondistanceevent)

```ts
ExpoBeacon.addListener("onBeaconDistance", (e) => {
  setProximity(e.distance);
});
```

---

### `onBeaconFound`

Fired during a **continuous scan** (started with `startContinuousScan()`) each time a beacon advertisement is received.

**Payload**: [`BeaconScanResult`](#beaconscanresult)

```ts
ExpoBeacon.addListener("onBeaconFound", (b) => {
  console.log(`Found ${b.uuid} ${b.major}/${b.minor} at ${b.distance.toFixed(1)} m`);
});
```

---

## TypeScript Types

### `BeaconScanResult`

Returned by `scanForBeaconsAsync` and used in `onBeaconFound` events.

```ts
type BeaconScanResult = {
  uuid: string;      // iBeacon proximity UUID (uppercase, formatted)
  major: number;     // iBeacon major value (0–65535)
  minor: number;     // iBeacon minor value (0–65535)
  rssi: number;      // Signal strength in dBm (negative integer, e.g. –65)
  distance: number;  // Estimated distance in metres (calculated from RSSI + txPower)
  txPower: number;   // Calibrated TX power from the beacon advertisement
};
```

### `PairedBeacon`

Returned by `getPairedBeacons`.

```ts
type PairedBeacon = {
  identifier: string; // Your label
  uuid: string;
  major: number;
  minor: number;
};
```

### `BeaconRegionEvent`

Payload for `onBeaconEnter` and `onBeaconExit`.

```ts
type BeaconRegionEvent = {
  identifier: string;        // Matches PairedBeacon.identifier
  uuid: string;
  major: number;
  minor: number;
  event: "enter" | "exit";
  distance: number;          // Measured distance in metres at event time; –1 if unavailable
};
```

### `BeaconRangingEvent`

Payload for `onBeaconRanging`.

```ts
type BeaconRangingEvent = {
  identifier: string;
  uuid: string;
  major: number;
  minor: number;
  rssi: number;      // Signal strength in dBm
  distance: number;  // Estimated distance in metres
};
```

### `BeaconDistanceEvent`

Payload for `onBeaconDistance`.

```ts
type BeaconDistanceEvent = {
  identifier: string;
  uuid: string;
  major: number;
  minor: number;
  distance: number;  // Estimated distance in metres
};
```

---

## Background Behaviour

### Android

`startMonitoring()` launches a **foreground service** (`BeaconForegroundService`) with a persistent notification titled _"Beacon Monitoring Active"_. This is required by Android 8+ (Oreo) to keep BLE scanning alive when the app is backgrounded. The service is automatically restarted after device reboot if monitoring was active at shutdown (via `BootReceiver`).

Default scan timing: 1.1 s scan window every 5 s.

### iOS

`startMonitoring()` activates `CLLocationManager` **region monitoring**. iOS can wake or relaunch the app when the device crosses a region boundary, even if the app has been force-quit. `allowsBackgroundLocationUpdates` is `true` and `pausesLocationUpdatesAutomatically` is `false`.

> iOS limits apps to **20 simultaneously monitored regions**.

---

## Notifications

A local notification is posted for every `onBeaconEnter` and `onBeaconExit` event.

| Channel / type                  | Importance          |
| ------------------------------- | ------------------- |
| Foreground service (Android)    | `IMPORTANCE_LOW`    |
| Enter / exit alerts             | `IMPORTANCE_DEFAULT`|

Both channels share the id `expo_beacon_channel`.

---

## Contributing

Contributions are welcome! Please refer to the [contributing guide](https://github.com/expo/expo#contributing).

## License

MIT
