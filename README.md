# expo-beacon

An Expo module for scanning, pairing, and monitoring iBeacons in React Native apps.

- **Scan** for nearby iBeacons via BLE
- **Pair** specific beacons for persistent tracking
- **Monitor** paired beacons in the background (foreground service on Android, CLLocationManager on iOS)
- **Enter/Exit callbacks** delivered as React Native events
- **Native notifications** shown automatically on region enter/exit

| Platform | Implementation                                                                                            |
| -------- | --------------------------------------------------------------------------------------------------------- |
| Android  | [AltBeacon](https://altbeacon.github.io/android-beacon-library/) (`org.altbeacon:android-beacon-library`) |
| iOS      | CoreLocation / `CLLocationManager` (iBeacon native API)                                                   |

---

## Installation

```sh
npx expo install expo-beacon
```

> This module contains native code and cannot be used with Expo Go. Use a [development build](https://docs.expo.dev/develop/development-builds/introduction/).

---

## Setup

### iOS

Add the following keys to your app's `Info.plist`:

```xml
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>This app monitors iBeacons in the background.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>This app uses location to detect nearby beacons.</string>
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to scan for iBeacons.</string>
```

In Xcode, under **Signing & Capabilities**, enable:

- **Background Modes → Location updates**
- **Background Modes → Uses Bluetooth LE accessories**

### Android

All required permissions are declared automatically by the module's `AndroidManifest.xml` and merged into your app. You still need to request runtime permissions before scanning or monitoring:

```ts
import { PermissionsAndroid } from "react-native";

await PermissionsAndroid.requestMultiple([
  PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
  PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
  PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
  PermissionsAndroid.PERMISSIONS.ACCESS_BACKGROUND_LOCATION,
]);
```

Or use the module's convenience helper:

```ts
await ExpoBeacon.requestPermissionsAsync();
```

---

## Usage

```ts
import ExpoBeacon from "expo-beacon";
import type {
  BeaconScanResult,
  PairedBeacon,
  BeaconRegionEvent,
} from "expo-beacon";
```

### 1. Request permissions

```ts
const granted = await ExpoBeacon.requestPermissionsAsync();
```

### 2. Scan for nearby beacons

Performs a one-shot BLE scan and resolves with all discovered beacons after the given duration.

```ts
const beacons: BeaconScanResult[] = await ExpoBeacon.scanForBeaconsAsync(5000); // 5 second scan

beacons.forEach((b) => {
  console.log(b.uuid, b.major, b.minor, b.rssi, b.distance);
});
```

### 3. Pair beacons

Register a beacon for persistent monitoring using a human-readable identifier:

```ts
ExpoBeacon.pairBeacon(
  "lobby-entrance", // identifier (your label)
  "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", // UUID
  1, // major
  100, // minor
);
```

Paired beacons survive app restarts (stored in SharedPreferences on Android, UserDefaults on iOS).

### 4. List / remove paired beacons

```ts
const paired: PairedBeacon[] = ExpoBeacon.getPairedBeacons();

ExpoBeacon.unpairBeacon("lobby-entrance");
```

### 5. Start background monitoring

Starts monitoring all paired beacon regions. On Android this launches a foreground service; on iOS it activates `CLLocationManager` region monitoring.

```ts
await ExpoBeacon.startMonitoring();
await ExpoBeacon.stopMonitoring();
```

### 6. Listen for enter/exit events

```ts
import { useEffect } from "react";

useEffect(() => {
  const enterSub = ExpoBeacon.addListener(
    "onBeaconEnter",
    (event: BeaconRegionEvent) => {
      console.log("Entered:", event.identifier, event.uuid);
    },
  );

  const exitSub = ExpoBeacon.addListener(
    "onBeaconExit",
    (event: BeaconRegionEvent) => {
      console.log("Exited:", event.identifier, event.uuid);
    },
  );

  return () => {
    enterSub.remove();
    exitSub.remove();
  };
}, []);
```

Events are also delivered when the app is in the background — a native notification is shown automatically for each enter/exit.

---

## API Reference

### Methods

| Method                    | Signature                                              | Description                              |
| ------------------------- | ------------------------------------------------------ | ---------------------------------------- |
| `requestPermissionsAsync` | `() => Promise<boolean>`                               | Request Bluetooth + Location permissions |
| `scanForBeaconsAsync`     | `(durationMs?: number) => Promise<BeaconScanResult[]>` | One-shot BLE scan (default 5000 ms)      |
| `pairBeacon`              | `(identifier, uuid, major, minor) => void`             | Persist a beacon for monitoring          |
| `unpairBeacon`            | `(identifier) => void`                                 | Remove a paired beacon                   |
| `getPairedBeacons`        | `() => PairedBeacon[]`                                 | Return all paired beacons                |
| `startMonitoring`         | `() => Promise<void>`                                  | Start background region monitoring       |
| `stopMonitoring`          | `() => Promise<void>`                                  | Stop background region monitoring        |

### Events

| Event             | Payload              | Description                           |
| ----------------- | -------------------- | ------------------------------------- |
| `onBeaconEnter`   | `BeaconRegionEvent`  | Device entered a paired beacon region |
| `onBeaconExit`    | `BeaconRegionEvent`  | Device exited a paired beacon region  |
| `onBeaconRanging` | `BeaconRangingEvent` | Proximity update for a ranged beacon  |

### Types

```ts
type BeaconScanResult = {
  uuid: string; // iBeacon proximity UUID (uppercase)
  major: number; // iBeacon major value (0–65535)
  minor: number; // iBeacon minor value (0–65535)
  rssi: number; // Signal strength in dBm
  distance: number; // Estimated distance in meters
  txPower: number; // Calibrated TX power
};

type PairedBeacon = {
  identifier: string; // Your label
  uuid: string;
  major: number;
  minor: number;
};

type BeaconRegionEvent = {
  identifier: string;
  uuid: string;
  major: number;
  minor: number;
  event: "enter" | "exit";
};

type BeaconRangingEvent = {
  identifier: string;
  uuid: string;
  major: number;
  minor: number;
  rssi: number;
  distance: number;
};
```

---

## Background Behaviour

### Android

`startMonitoring()` launches a **foreground service** (`BeaconForegroundService`) with a persistent notification titled _"Beacon Monitoring Active"_. This is required by Android 8+ to keep BLE scanning alive when the app is backgrounded. The service restarts automatically after device reboot if monitoring was active (via `BootReceiver`).

Scan intervals in background: 1.1 s scan window every 5 s.

### iOS

`startMonitoring()` activates **CLLocationManager region monitoring**. iOS wakes the app (or delivers a notification) when the device crosses a region boundary, even if the app has been terminated. `allowsBackgroundLocationUpdates` is set to `true` and `pausesLocationUpdatesAutomatically` to `false`.

> iOS limits apps to 20 simultaneously monitored regions.

---

## Notifications

A local notification is shown on every enter and exit event using the same notification channel (`expo_beacon_channel`). The foreground service persistent notification uses `IMPORTANCE_LOW`; enter/exit alerts use `IMPORTANCE_DEFAULT`.

---

## Contributing

Contributions are welcome! Please refer to the [contributing guide](https://github.com/expo/expo#contributing).

## License

MIT
