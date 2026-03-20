# expo-beacon

An Expo module for scanning, pairing, and monitoring iBeacons and Eddystone beacons in React Native apps.

- **Scan** for nearby iBeacons and Eddystone beacons via one-shot or continuous BLE scans
- **Pair** specific beacons for persistent tracking across app restarts
- **Monitor** paired beacons in the background with enter/exit callbacks
- **Distance events** fired continuously while a monitored beacon is in range
- **Native notifications** shown automatically on region enter/exit

| Platform | Native implementation                                                                                     |
| -------- | --------------------------------------------------------------------------------------------------------- |
| Android  | [AltBeacon](https://altbeacon.github.io/android-beacon-library/) (`org.altbeacon:android-beacon-library`) |
| iOS      | CoreLocation (UUID-targeted scans & monitoring) + CoreBluetooth (wildcard scanning & Eddystone)           |
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

> iOS limits apps to **20 simultaneously monitored regions** (iBeacon only — Eddystone beacons are monitored via BLE and do not count toward this limit).
>
> **iBeacon scanning on iOS**: Apple strips iBeacon manufacturer data from CoreBluetooth BLE advertisements, so wildcard iBeacon discovery is **not possible** on iOS. You must provide at least one proximity UUID, or pair beacons first (the module will use paired beacon UUIDs automatically). UUID-targeted scans use CoreLocation ranging and work in both foreground and background.
>
> **Eddystone scanning**: Eddystone beacons use standard BLE service data (UUID `0xFEAA`), which iOS does not filter. `scanForEddystonesAsync()` and continuous scanning discover Eddystones on both platforms without restrictions.

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
  EddystoneScanResult,
  EddystoneRegionEvent,
  EddystoneDistanceEvent,
} from "expo-beacon";

export default function App() {
  const [beacons, setBeacons] = useState<BeaconScanResult[]>([]);

  useEffect(() => {
    // 1. Pair a known iBeacon for monitoring
    ExpoBeacon.pairBeacon(
      "lobby-entrance",
      "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
      1,
      100,
    );

    // 1b. Pair a known Eddystone-UID beacon for monitoring
    ExpoBeacon.pairEddystone(
      "meeting-room",
      "edd1ebeac04e5defa017", // 10-byte namespace
      "0123456789ab",         // 6-byte instance
    );

    // 2. Subscribe to iBeacon region events
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

    // 3. Subscribe to Eddystone region events
    const eddyEnterSub = ExpoBeacon.addListener(
      "onEddystoneEnter",
      (e: EddystoneRegionEvent) =>
        console.log(`Eddystone entered ${e.identifier} (${e.namespace})`),
    );
    const eddyExitSub = ExpoBeacon.addListener(
      "onEddystoneExit",
      (e: EddystoneRegionEvent) =>
        console.log(`Eddystone exited ${e.identifier}`),
    );
    const eddyDistSub = ExpoBeacon.addListener(
      "onEddystoneDistance",
      (e: EddystoneDistanceEvent) =>
        console.log(`Eddystone ${e.identifier}: ${e.distance.toFixed(2)} m`),
    );

    // 4. Start background monitoring (fires for both paired iBeacons and Eddystones)
    ExpoBeacon.requestPermissionsAsync().then((granted) => {
      if (granted) ExpoBeacon.startMonitoring(10); // enter events within 10 m
    });

    return () => {
      enterSub.remove();
      exitSub.remove();
      distSub.remove();
      eddyEnterSub.remove();
      eddyExitSub.remove();
      eddyDistSub.remove();
      ExpoBeacon.stopMonitoring();
    };
  }, []);

  async function scan() {
    // Wildcard scan — discovers all nearby iBeacons (no UUID filter)
    const results = await ExpoBeacon.scanForBeaconsAsync([], 5000);
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

### `scanForBeaconsAsync(uuids?, scanDurationMs?)`

```ts
scanForBeaconsAsync(uuids?: string[], scanDurationMs?: number): Promise<BeaconScanResult[]>
```

Starts a **one-shot BLE scan**, waits for `scanDurationMs` milliseconds, then resolves with all beacons discovered during that window.

| Parameter        | Type       | Default | Description                              |
| ---------------- | ---------- | ------- | ---------------------------------------- |
| `uuids`          | `string[]` | `[]`    | Proximity UUIDs to filter by. **iOS**: at least one UUID is required (or have paired beacons). **Android**: pass `[]` for a wildcard scan. |
| `scanDurationMs` | `number`   | `5000`  | How long to scan in milliseconds (1–60 000 recommended) |

Returns an array of [`BeaconScanResult`](#beaconscanresult) objects. Rejects with `SCAN_IN_PROGRESS` if another scan is already running.

**Platform differences**

| | Empty UUIDs (`[]`) | Targeted (`['UUID-1', …]`) |
|---|---|---|
| **Android** | Discovers all iBeacons via AltBeacon | Filters results to matching UUIDs |
| **iOS** | Uses paired beacon UUIDs automatically. Rejects if no UUIDs and no paired beacons. | CoreLocation ranging (works in foreground & background) |

> **iOS limitation**: Apple strips iBeacon manufacturer data from CoreBluetooth BLE advertisements. Wildcard iBeacon scanning (no UUID filter) is not possible on iOS. When you pass an empty `uuids` array, the module automatically uses UUIDs from your paired beacons. If no beacons are paired, the call rejects with `WILDCARD_NOT_SUPPORTED`. For Eddystone scanning, use `scanForEddystonesAsync()` instead — it works without restrictions on both platforms.

```ts
// Scan by UUID — works on both platforms
const filtered = await ExpoBeacon.scanForBeaconsAsync(
  ['E2C56DB5-DFFB-48D2-B060-D0F5A71096E0'],
  8000,
);

// Wildcard scan — Android only. On iOS, uses paired beacon UUIDs.
const all = await ExpoBeacon.scanForBeaconsAsync([], 5000);

filtered.forEach((b) =>
  console.log(`${b.uuid}  major=${b.major}  minor=${b.minor}  dist=${b.distance.toFixed(1)}m  rssi=${b.rssi}dBm`)
);
```

---

### `startContinuousScan()`

```ts
startContinuousScan(): void
```

Begins a **continuous BLE scan** that fires an [`onBeaconFound`](#onbeaconfound) event every time a beacon advertisement is received. Call [`stopContinuousScan()`](#stopcontinuousscan) to end it.

Unlike `scanForBeaconsAsync`, this never resolves — it streams results in real time. Eddystone beacons are also reported via the [`onEddystoneFound`](#oneddystonefound) event.

 **iOS note**: Due to CoreLocation API constraints, `startContinuousScan()` on iOS only ranges iBeacons that have been previously paired with `pairBeacon()`. On Android, all nearby BLE beacons are reported regardless of pairing status. Eddystone beacons are discovered on both platforms via CoreBluetooth / AltBeacon regardless of pairing.

```ts
const sub = ExpoBeacon.addListener("onBeaconFound", (beacon) => {
  console.log("Live:", beacon.uuid, beacon.distance);
});
const eddySub = ExpoBeacon.addListener("onEddystoneFound", (beacon) => {
  console.log("Eddystone:", beacon.frameType, beacon.namespace ?? beacon.url);
});

ExpoBeacon.startContinuousScan();

// later, when done:
ExpoBeacon.stopContinuousScan();
sub.remove();
eddySub.remove();
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

### `scanForEddystonesAsync(scanDurationMs?)`

```ts
scanForEddystonesAsync(scanDurationMs?: number): Promise<EddystoneScanResult[]>
```

Starts a **one-shot BLE scan** for Eddystone beacons, waits for `scanDurationMs` milliseconds, then resolves with all Eddystone beacons discovered.

| Parameter        | Type     | Default | Description                                    |
| ---------------- | -------- | ------- | ---------------------------------------------- |
| `scanDurationMs` | `number` | `5000`  | How long to scan in milliseconds               |

Returns an array of [`EddystoneScanResult`](#eddystonescanresult) objects. Discovers both Eddystone-UID and Eddystone-URL frames.

```ts
const eddystones = await ExpoBeacon.scanForEddystonesAsync(5000);
eddystones.forEach((b) => {
  if (b.frameType === "uid") {
    console.log(`UID: ns=${b.namespace} inst=${b.instance} dist=${b.distance.toFixed(1)}m`);
  } else {
    console.log(`URL: ${b.url} dist=${b.distance.toFixed(1)}m`);
  }
});
```

---

### `pairEddystone(identifier, namespace, instance)`

```ts
pairEddystone(identifier: string, namespace: string, instance: string): void
```

Registers an Eddystone-UID beacon for persistent region monitoring. Paired Eddystones survive app restarts (stored in `SharedPreferences` on Android, `UserDefaults` on iOS). Calling `pairEddystone` with an existing `identifier` replaces that entry.

| Parameter    | Type     | Description                                                      |
| ------------ | -------- | ---------------------------------------------------------------- |
| `identifier` | `string` | Your unique label for this beacon (e.g. `"meeting-room"`)        |
| `namespace`  | `string` | 10-byte namespace ID as a hex string (20 characters)             |
| `instance`   | `string` | 6-byte instance ID as a hex string (12 characters)               |

```ts
ExpoBeacon.pairEddystone(
  "meeting-room",
  "edd1ebeac04e5defa017",
  "0123456789ab",
);
```

---

### `unpairEddystone(identifier)`

```ts
unpairEddystone(identifier: string): void
```

Removes a previously paired Eddystone beacon.

| Parameter    | Type     | Description                        |
| ------------ | -------- | ---------------------------------- |
| `identifier` | `string` | The label used when pairing        |

```ts
ExpoBeacon.unpairEddystone("meeting-room");
```

---

### `getPairedEddystones()`

```ts
getPairedEddystones(): PairedEddystone[]
```

Returns the list of all currently paired Eddystone beacons from persistent storage.

```ts
const paired = ExpoBeacon.getPairedEddystones();
paired.forEach((e) =>
  console.log(e.identifier, e.namespace, e.instance)
);
```

---

### `startMonitoring(options?)`

```ts
startMonitoring(options?: MonitoringOptions | number): Promise<void>
```

Starts background region monitoring for all paired beacons (both iBeacons and Eddystones).

Accepts either a `MonitoringOptions` object or a plain `number` (backward-compatible shorthand for `maxDistance`).

**`MonitoringOptions`**

| Property        | Type                 | Default     | Description                                                                                   |
| --------------- | -------------------- | ----------- | --------------------------------------------------------------------------------------------- |
| `maxDistance`   | `number`             | `undefined` | Optional distance threshold in metres. `onBeaconEnter` is only emitted when the measured distance is ≤ this value. `onBeaconExit` is always emitted. Omit to disable distance filtering. |
| `notifications` | `NotificationConfig` | `undefined` | Notification config overrides applied for this session. Persisted and takes effect immediately. |

**Android**: Launches `BeaconForegroundService` — a persistent foreground service required by Android 8+ for background BLE. Restarts automatically after device reboot.

**iOS**: Activates `CLLocationManager` region monitoring. iOS can wake or relaunch the app when a region boundary is crossed, even if the app is terminated.

```ts
// Backward-compatible shorthand (number = maxDistance)
await ExpoBeacon.startMonitoring(5);

// Full options object
await ExpoBeacon.startMonitoring({
  maxDistance: 5,
  notifications: {
    beaconEvents: {
      enterTitle: "Beacon nearby!",
      body: "{identifier} is within range",
    },
  },
});

// Monitor with no distance limit and no enter/exit notifications
await ExpoBeacon.startMonitoring({
  notifications: { beaconEvents: { enabled: false } },
});
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

### `setNotificationConfig(config)`

```ts
setNotificationConfig(config: NotificationConfig): void
```

Persists notification configuration that is applied to all subsequent monitoring sessions. Values are stored in `SharedPreferences` (Android) / `UserDefaults` (iOS) and survive app restarts.

For one-off overrides tied to a single session, pass `notifications` directly in [`startMonitoring(options)`](#startmonitoringoptions) instead — it calls this function automatically.

```ts
ExpoBeacon.setNotificationConfig({
  // Enter/exit alert notifications
  beaconEvents: {
    enabled: true,                    // false to suppress all enter/exit notifications
    enterTitle: "Beacon nearby",
    exitTitle: "Beacon out of range",
    body: "{identifier} {event}ed",   // {identifier} and {event} are replaced at runtime
    sound: true,                      // iOS only — default true
    icon: "ic_beacon_notification",   // Android only — drawable resource name
  },

  // Persistent status-bar notification while monitoring is active (Android only)
  foregroundService: {
    title: "My App is watching",
    text: "Monitoring for nearby beacons",
    icon: "ic_service",               // Android drawable resource name
  },

  // Android notification channel settings
  channel: {
    name: "Proximity Alerts",
    description: "Alerts when beacons enter or leave range",
    importance: "default",            // "low" | "default" | "high"
  },
});
```

> **Android channel importance note**: Android prevents decreasing channel importance once a user has been notified. Increasing importance always works; decreasing it will have no effect until the user clears the app's notification settings or reinstalls the app.

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

> **Not currently emitted.** This event is declared in the TypeScript types ([`BeaconRangingEvent`](#beaconrangingevent)) but is not fired by either the iOS or Android native implementation. Use [`onBeaconDistance`](#onbeacondistance) for periodic distance updates during monitoring.

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

Fired during a **continuous scan** (started with `startContinuousScan()`) each time an iBeacon advertisement is received.

**Payload**: [`BeaconScanResult`](#beaconscanresult)

```ts
ExpoBeacon.addListener("onBeaconFound", (b) => {
  console.log(`Found ${b.uuid} ${b.major}/${b.minor} at ${b.distance.toFixed(1)} m`);
});
```

---

### `onEddystoneFound`

Fired during a **continuous scan** (started with `startContinuousScan()`) each time an Eddystone advertisement is received.

**Payload**: [`EddystoneScanResult`](#eddystonescanresult)

```ts
ExpoBeacon.addListener("onEddystoneFound", (b) => {
  if (b.frameType === "uid") {
    console.log(`Eddystone-UID: ${b.namespace}/${b.instance} at ${b.distance.toFixed(1)} m`);
  } else {
    console.log(`Eddystone-URL: ${b.url}`);
  }
});
```

---

### `onEddystoneEnter`

Fired when a paired Eddystone-UID beacon enters range during monitoring. If `maxDistance` was set in `startMonitoring`, this only fires when the measured distance is within that threshold.

**Payload**: [`EddystoneRegionEvent`](#eddystoneregionevent)

```ts
ExpoBeacon.addListener("onEddystoneEnter", (e) => {
  console.log(`Eddystone entered "${e.identifier}" (ns: ${e.namespace}) at ~${e.distance.toFixed(1)} m`);
});
```

---

### `onEddystoneExit`

Fired when a paired Eddystone-UID beacon leaves range during monitoring.

**Payload**: [`EddystoneRegionEvent`](#eddystoneregionevent)

```ts
ExpoBeacon.addListener("onEddystoneExit", (e) => {
  console.log(`Eddystone left "${e.identifier}"`);
});
```

---

### `onEddystoneDistance`

Fired continuously during monitoring whenever a distance update is received for a paired Eddystone beacon (~1 update/sec).

**Payload**: [`EddystoneDistanceEvent`](#eddystonedistanceevent)

```ts
ExpoBeacon.addListener("onEddystoneDistance", (e) => {
  console.log(`Eddystone ${e.identifier}: ${e.distance.toFixed(2)} m`);
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

Payload type for the `onBeaconRanging` event. **Declared for future use — this event is not currently emitted by either platform.** Use [`BeaconDistanceEvent`](#beacondistanceevent) and [`onBeaconDistance`](#onbeacondistance) for real-time distance updates.

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

### `EddystoneScanResult`

Returned by `scanForEddystonesAsync` and used in `onEddystoneFound` events.

```ts
type EddystoneScanResult = {
  frameType: "uid" | "url";
  namespace?: string;  // 10-byte hex string (20 chars). Present for UID frames.
  instance?: string;   // 6-byte hex string (12 chars). Present for UID frames.
  url?: string;        // Decoded URL. Present for URL frames.
  rssi: number;        // Signal strength in dBm
  distance: number;    // Estimated distance in metres
  txPower: number;     // Calibrated TX power
};
```

### `PairedEddystone`

Returned by `getPairedEddystones`.

```ts
type PairedEddystone = {
  identifier: string;  // Your label
  namespace: string;   // 10-byte hex string (20 chars)
  instance: string;    // 6-byte hex string (12 chars)
};
```

### `EddystoneRegionEvent`

Payload for `onEddystoneEnter` and `onEddystoneExit`.

```ts
type EddystoneRegionEvent = {
  identifier: string;         // Matches PairedEddystone.identifier
  namespace: string;
  instance: string;
  event: "enter" | "exit";
  distance: number;           // Measured distance in metres at event time; –1 if unavailable
};
```

### `EddystoneDistanceEvent`

Payload for `onEddystoneDistance`.

```ts
type EddystoneDistanceEvent = {
  identifier: string;
  namespace: string;
  instance: string;
  distance: number;  // Estimated distance in metres
};
```

---

### `MonitoringOptions`

Passed to `startMonitoring(options)`.

```ts
type MonitoringOptions = {
  maxDistance?: number;           // Distance threshold in metres for enter events
  notifications?: NotificationConfig; // Notification overrides for this session
};
```

### `NotificationConfig`

Top-level config object accepted by `setNotificationConfig()` and `startMonitoring({ notifications })`.

```ts
type NotificationConfig = {
  beaconEvents?: BeaconNotificationConfig;
  foregroundService?: ForegroundServiceConfig;  // Android only
  channel?: NotificationChannelConfig;          // Android only
};
```

### `BeaconNotificationConfig`

Controls the enter/exit alert notifications.

```ts
type BeaconNotificationConfig = {
  enabled?: boolean;     // false to disable all enter/exit notifications. Default: true
  enterTitle?: string;   // Default: "Beacon Entered"
  exitTitle?: string;    // Default: "Beacon Exited"
  body?: string;         // Template — {identifier} and {event} are replaced at runtime
                         // Default: "{identifier} region {event}ed"
  sound?: boolean;       // iOS only — play notification sound. Default: true
  icon?: string;         // Android only — drawable resource name (e.g. "ic_notification")
};
```

### `ForegroundServiceConfig`

Controls the persistent Android status-bar notification while monitoring is active.

```ts
type ForegroundServiceConfig = {
  title?: string;  // Default: "Beacon Monitoring Active"
  text?: string;   // Default: "Monitoring for iBeacons in the background"
  icon?: string;   // Android drawable resource name
};
```

### `NotificationChannelConfig`

Controls the Android notification channel shown in system settings.

```ts
type NotificationChannelConfig = {
  name?: string;                          // Default: "Beacon Monitoring"
  description?: string;                   // Default: "Used for background iBeacon region monitoring"
  importance?: "low" | "default" | "high"; // Default: "low"
};
```

---

## Background Behaviour

### Android

`startMonitoring()` launches a **foreground service** (`BeaconForegroundService`) with a persistent notification titled _"Beacon Monitoring Active"_. This is required by Android 8+ (Oreo) to keep BLE scanning alive when the app is backgrounded. The service is automatically restarted after device reboot if monitoring was active at shutdown (via `BootReceiver`).

Default scan timing: 1.1 s scan window every 5 s.

### iOS

`startMonitoring()` activates `CLLocationManager` **region monitoring** for paired iBeacons. iOS can wake or relaunch the app when the device crosses a region boundary, even if the app has been force-quit. `allowsBackgroundLocationUpdates` is `true` and `pausesLocationUpdatesAutomatically` is `false`.

For paired Eddystone beacons, iOS uses **CoreBluetooth BLE scanning** during monitoring. BLE scanning works reliably in the foreground and while the app is backgrounded with `Uses Bluetooth LE accessories` background mode enabled. However, BLE scanning may be throttled or stopped by iOS when the app is suspended.

> iOS limits apps to **20 simultaneously monitored regions** (iBeacon only — Eddystone monitoring does not count toward this limit).

---

## Notifications

A local notification is posted for every beacon enter and exit event (both iBeacon and Eddystone). All notification settings can be customised via [`setNotificationConfig()`](#setnotificationconfigconfig) or inline in [`startMonitoring(options)`](#startmonitoringoptions).

### Defaults

| Property                       | Default value                                    |
| ------------------------------ | ------------------------------------------------ |
| Enter title                    | `"Beacon Entered"`                               |
| Exit title                     | `"Beacon Exited"`                                |
| Body                           | `"{identifier} region {event}ed"`                |
| Sound (iOS)                    | `true`                                           |
| Icon (Android)                 | System `ic_dialog_info`                          |
| Foreground service title       | `"Beacon Monitoring Active"`                     |
| Foreground service text        | `"Monitoring for iBeacons in the background"`    |
| Channel name (Android)         | `"Beacon Monitoring"`                            |
| Channel importance (Android)   | `"low"`                                          |

### Channel IDs (Android)

| Channel / type                  | Importance          |
| ------------------------------- | ------------------- |
| Foreground service (Android)    | configurable (default `low`) |
| Enter / exit alerts             | configurable (default `default`) |

Both notifications share the channel id `expo_beacon_channel`. The channel is recreated on each `onStartCommand` so config changes take effect on the next monitoring start.

---

## Contributing

Contributions are welcome! Open an issue or pull request on [GitHub](https://github.com/martinmikesCCS/expo-beacon).

## License

MIT
