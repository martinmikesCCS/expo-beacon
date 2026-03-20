# expo-beacon

An Expo module for scanning, pairing, and monitoring **iBeacons** and **Eddystone** beacons in React Native apps — with full background support on both iOS and Android.

| Feature | Description |
|---|---|
| **Scan** | Discover nearby iBeacons (one-shot or continuous) and Eddystone-UID / Eddystone-URL beacons via BLE |
| **Pair** | Register specific beacons for persistent tracking — survives app restarts |
| **Monitor** | Background enter/exit region detection with distance-based filtering |
| **Distance** | Real-time distance updates (~1/sec) while monitoring |
| **Notifications** | Automatic local notifications on region enter/exit, fully customisable |

| Platform | Native Implementation |
|---|---|
| **Android** | [AltBeacon](https://altbeacon.github.io/android-beacon-library/) library + Foreground Service |
| **iOS** | CoreLocation (iBeacon ranging & monitoring) + CoreBluetooth (Eddystone & wildcard BLE) |
| **Web** | Not supported (throws on all calls) |

---

## Table of Contents

- [Installation](#installation)
- [Platform Setup](#platform-setup)
  - [iOS](#ios)
  - [Android](#android)
- [Quick Start](#quick-start)
- [Usage Examples](#usage-examples)
  - [Scanning for iBeacons](#scanning-for-ibeacons)
  - [Scanning for Eddystone Beacons](#scanning-for-eddystone-beacons)
  - [Continuous (Live) Scanning](#continuous-live-scanning)
  - [Pairing & Unpairing Beacons](#pairing--unpairing-beacons)
  - [Background Monitoring](#background-monitoring)
  - [Customizing Notifications](#customizing-notifications)
  - [Cancelling a Scan](#cancelling-a-scan)
- [Full API Reference](#full-api-reference)
  - [requestPermissionsAsync()](#requestpermissionsasync)
  - [scanForBeaconsAsync()](#scanforbeaconsasyncuuids-scandurationms)
  - [scanForEddystonesAsync()](#scanforeddystonesasyncscanDurationms)
  - [startContinuousScan()](#startcontinuousscan)
  - [stopContinuousScan()](#stopcontinuousscan)
  - [cancelScan()](#cancelscan)
  - [pairBeacon()](#pairbeaconidentifier-uuid-major-minor)
  - [unpairBeacon()](#unpairbeaconidentifier)
  - [getPairedBeacons()](#getpairedbeacons)
  - [pairEddystone()](#paireddystoneidentifier-namespace-instance)
  - [unpairEddystone()](#unpaireddystoneidentifier)
  - [getPairedEddystones()](#getpairededdystones)
  - [startMonitoring()](#startmonitoringoptions)
  - [stopMonitoring()](#stopmonitoring)
  - [setNotificationConfig()](#setnotificationconfigconfig)
- [Events](#events)
- [TypeScript Types](#typescript-types)
- [Background Behaviour](#background-behaviour)
- [Notifications](#notifications)
- [Platform-Specific Notes & Gotchas](#platform-specific-notes--gotchas)
- [Troubleshooting](#troubleshooting)
- [Error Codes](#error-codes)
- [Contributing](#contributing)
- [License](#license)

---

## Installation

```sh
npx expo install expo-beacon
```

> **Important**: This module contains native code and **cannot** be used with Expo Go. You must use a [development build](https://docs.expo.dev/develop/development-builds/introduction/) or a bare workflow.

---

## Platform Setup

### iOS

#### 1. Info.plist Keys

Add the following keys to your `Info.plist` (or use an Expo config plugin):

```xml
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>This app monitors iBeacons in the background.</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>This app uses location to detect nearby beacons.</string>
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to scan for iBeacons.</string>
```

#### 2. Background Modes

In Xcode under **Signing & Capabilities**, enable:

- **Background Modes → Location updates**
- **Background Modes → Uses Bluetooth LE accessories**

#### Key iOS Constraints

- **20 monitored regions max**: iOS limits `CLLocationManager` to 20 simultaneously monitored beacon regions. If you pair more than 20 iBeacons, only the first 20 are monitored. Eddystone beacons use BLE scanning and do **not** count toward this limit.
- **No wildcard iBeacon scanning**: Apple strips iBeacon manufacturer data from CoreBluetooth advertisements. You **must** supply at least one proximity UUID when scanning, or have paired beacons (the module auto-uses their UUIDs).
- **Eddystone works unrestricted**: Eddystone uses standard BLE service data (`0xFEAA`), which iOS does not strip. Both `scanForEddystonesAsync()` and continuous scanning discover Eddystones without restrictions.

### Android

All required permissions are declared in the module's `AndroidManifest.xml` and merged automatically. You must still request **runtime permissions** before scanning or monitoring:

```ts
const granted = await ExpoBeacon.requestPermissionsAsync();
```

The module requests: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, and `POST_NOTIFICATIONS` (API 33+).

---

## Quick Start

A minimal example that pairs one iBeacon and one Eddystone, starts monitoring, and scans for nearby beacons:

```tsx
import { useEffect, useState } from "react";
import { Button, FlatList, Text, View } from "react-native";
import ExpoBeacon from "expo-beacon";
import type { BeaconScanResult, BeaconRegionEvent } from "expo-beacon";

export default function App() {
  const [beacons, setBeacons] = useState<BeaconScanResult[]>([]);

  useEffect(() => {
    // 1. Pair beacons you want to monitor
    ExpoBeacon.pairBeacon(
      "lobby-entrance",
      "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
      1,
      100,
    );

    // 2. Listen for enter/exit events
    const enterSub = ExpoBeacon.addListener("onBeaconEnter", (e: BeaconRegionEvent) => {
      console.log(`Entered ${e.identifier} at ${e.distance.toFixed(1)} m`);
    });
    const exitSub = ExpoBeacon.addListener("onBeaconExit", (e: BeaconRegionEvent) => {
      console.log(`Exited ${e.identifier}`);
    });

    // 3. Request permissions and start monitoring
    ExpoBeacon.requestPermissionsAsync().then((granted) => {
      if (granted) ExpoBeacon.startMonitoring(10); // enter within 10 m
    });

    return () => {
      enterSub.remove();
      exitSub.remove();
      ExpoBeacon.stopMonitoring();
    };
  }, []);

  async function scan() {
    const results = await ExpoBeacon.scanForBeaconsAsync(
      ["E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"],
      5000
    );
    setBeacons(results);
  }

  return (
    <View style={{ flex: 1, padding: 20, paddingTop: 60 }}>
      <Button title="Scan 5 s" onPress={scan} />
      <FlatList
        data={beacons}
        keyExtractor={(b) => `${b.uuid}-${b.major}-${b.minor}`}
        renderItem={({ item: b }) => (
          <Text>{b.uuid} {b.major}/{b.minor} — {b.distance.toFixed(1)} m</Text>
        )}
      />
    </View>
  );
}
```

---

## Usage Examples

### Scanning for iBeacons

#### One-shot scan with UUID filter (both platforms)

```ts
import ExpoBeacon from "expo-beacon";

// Scan for 8 seconds, filtering by a specific UUID
const beacons = await ExpoBeacon.scanForBeaconsAsync(
  ["E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"],
  8000,
);

beacons.forEach((b) => {
  console.log(
    `UUID: ${b.uuid}  Major: ${b.major}  Minor: ${b.minor}  ` +
    `Distance: ${b.distance.toFixed(1)}m  RSSI: ${b.rssi}dBm`
  );
});
```

#### Wildcard scan (Android only)

```ts
// Pass an empty array to discover ALL nearby iBeacons
// On iOS, this auto-uses UUIDs from paired beacons
const beacons = await ExpoBeacon.scanForBeaconsAsync([], 5000);
```

#### Multiple UUID scan

```ts
// Scan for beacons from two different manufacturers/deployments
const beacons = await ExpoBeacon.scanForBeaconsAsync(
  [
    "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
    "FDA50693-A4E2-4FB1-AFCF-C6EB07647825",
  ],
  10000,
);
```

---

### Scanning for Eddystone Beacons

```ts
import ExpoBeacon from "expo-beacon";

// Discover both Eddystone-UID and Eddystone-URL frames
const eddystones = await ExpoBeacon.scanForEddystonesAsync(5000);

eddystones.forEach((b) => {
  if (b.frameType === "uid") {
    console.log(`UID: namespace=${b.namespace} instance=${b.instance} dist=${b.distance.toFixed(1)}m`);
  } else if (b.frameType === "url") {
    console.log(`URL: ${b.url} dist=${b.distance.toFixed(1)}m`);
  }
});
```

> Eddystone scanning works identically on both iOS and Android — no UUID filter required.

---

### Continuous (Live) Scanning

Use continuous scanning when you need real-time beacon updates (e.g., a live radar UI). This fires events continuously rather than resolving a single promise.

```tsx
import { useEffect, useRef, useState } from "react";
import { FlatList, Text, Button, View } from "react-native";
import ExpoBeacon from "expo-beacon";
import type { BeaconScanResult, EddystoneScanResult } from "expo-beacon";

export default function LiveScanner() {
  const [ibeacons, setIbeacons] = useState<BeaconScanResult[]>([]);
  const [eddystones, setEddystones] = useState<EddystoneScanResult[]>([]);
  const [scanning, setScanning] = useState(false);
  const subs = useRef<Array<{ remove: () => void }>>([]);

  const startScan = () => {
    setScanning(true);

    // iBeacon advertisements
    subs.current.push(
      ExpoBeacon.addListener("onBeaconFound", (beacon) => {
        setIbeacons((prev) => {
          const key = `${beacon.uuid}-${beacon.major}-${beacon.minor}`;
          const idx = prev.findIndex(
            (b) => `${b.uuid}-${b.major}-${b.minor}` === key,
          );
          if (idx >= 0) {
            const copy = [...prev];
            copy[idx] = beacon; // Update distance/RSSI
            return copy;
          }
          return [...prev, beacon];
        });
      }),
    );

    // Eddystone advertisements
    subs.current.push(
      ExpoBeacon.addListener("onEddystoneFound", (beacon) => {
        setEddystones((prev) => {
          const key = beacon.frameType === "uid"
            ? `${beacon.namespace}-${beacon.instance}`
            : `url-${beacon.url}`;
          const idx = prev.findIndex((b) => {
            const k = b.frameType === "uid"
              ? `${b.namespace}-${b.instance}`
              : `url-${b.url}`;
            return k === key;
          });
          if (idx >= 0) {
            const copy = [...prev];
            copy[idx] = beacon;
            return copy;
          }
          return [...prev, beacon];
        });
      }),
    );

    ExpoBeacon.startContinuousScan();
  };

  const stopScan = () => {
    ExpoBeacon.stopContinuousScan();
    subs.current.forEach((s) => s.remove());
    subs.current = [];
    setScanning(false);
  };

  useEffect(() => {
    return () => stopScan(); // Cleanup on unmount
  }, []);

  return (
    <View style={{ flex: 1, padding: 20 }}>
      <Button
        title={scanning ? "Stop Scan" : "Start Live Scan"}
        onPress={scanning ? stopScan : startScan}
      />
      <Text style={{ fontWeight: "bold", marginTop: 10 }}>
        iBeacons ({ibeacons.length})
      </Text>
      <FlatList
        data={ibeacons}
        keyExtractor={(b) => `${b.uuid}-${b.major}-${b.minor}`}
        renderItem={({ item: b }) => (
          <Text>
            {b.uuid.slice(0, 8)}… {b.major}/{b.minor} — {b.distance.toFixed(1)}m (RSSI: {b.rssi})
          </Text>
        )}
      />
      <Text style={{ fontWeight: "bold", marginTop: 10 }}>
        Eddystones ({eddystones.length})
      </Text>
      <FlatList
        data={eddystones}
        keyExtractor={(b, i) => `eddy-${i}`}
        renderItem={({ item: b }) => (
          <Text>
            {b.frameType === "uid"
              ? `UID: ${b.namespace?.slice(0, 8)}… / ${b.instance}`
              : `URL: ${b.url}`} — {b.distance.toFixed(1)}m
          </Text>
        )}
      />
    </View>
  );
}
```

> **iOS note**: Continuous iBeacon scanning on iOS only discovers beacons whose UUID has been registered via `pairBeacon()`. On Android, all nearby BLE beacons are reported. Eddystone discovery works on both platforms regardless of pairing.

---

### Pairing & Unpairing Beacons

Pairing registers a beacon for persistent monitoring. Paired beacons survive app restarts — they are stored in `UserDefaults` (iOS) / `SharedPreferences` (Android).

```ts
import ExpoBeacon from "expo-beacon";

// ── iBeacon ──

// Pair an iBeacon (identifier must be unique)
ExpoBeacon.pairBeacon(
  "lobby-entrance",                           // your label
  "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",    // proximity UUID
  1,                                          // major (0–65535)
  100,                                        // minor (0–65535)
);

// Re-pairing with the same identifier replaces the previous entry
ExpoBeacon.pairBeacon(
  "lobby-entrance",
  "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
  1,
  200, // updated minor
);

// List all paired iBeacons
const paired = ExpoBeacon.getPairedBeacons();
console.log(paired);
// → [{ identifier: "lobby-entrance", uuid: "E2C5…", major: 1, minor: 200 }]

// Remove a beacon
ExpoBeacon.unpairBeacon("lobby-entrance");

// ── Eddystone-UID ──

// Pair an Eddystone-UID beacon
ExpoBeacon.pairEddystone(
  "meeting-room",                              // your label
  "edd1ebeac04e5defa017",                      // 10-byte namespace (20 hex chars)
  "0123456789ab",                              // 6-byte instance  (12 hex chars)
);

// List all paired Eddystones
const pairedEddy = ExpoBeacon.getPairedEddystones();
console.log(pairedEddy);
// → [{ identifier: "meeting-room", namespace: "edd1…", instance: "0123…" }]

// Remove an Eddystone
ExpoBeacon.unpairEddystone("meeting-room");
```

---

### Background Monitoring

Monitoring watches all paired beacons (iBeacon + Eddystone) in the background and fires events when the device enters or exits a beacon region.

```tsx
import { useEffect, useRef } from "react";
import ExpoBeacon from "expo-beacon";
import type {
  BeaconRegionEvent,
  BeaconDistanceEvent,
  EddystoneRegionEvent,
  EddystoneDistanceEvent,
} from "expo-beacon";

export function useBeaconMonitoring() {
  const subs = useRef<Array<{ remove: () => void }>>([]);

  useEffect(() => {
    async function start() {
      const granted = await ExpoBeacon.requestPermissionsAsync();
      if (!granted) {
        console.warn("Beacon permissions denied");
        return;
      }

      // Subscribe to iBeacon events
      subs.current.push(
        ExpoBeacon.addListener("onBeaconEnter", (e: BeaconRegionEvent) => {
          console.log(`[iBeacon] Entered "${e.identifier}" at ~${e.distance.toFixed(1)}m`);
        }),
        ExpoBeacon.addListener("onBeaconExit", (e: BeaconRegionEvent) => {
          console.log(`[iBeacon] Exited "${e.identifier}"`);
        }),
        ExpoBeacon.addListener("onBeaconDistance", (e: BeaconDistanceEvent) => {
          console.log(`[iBeacon] "${e.identifier}" → ${e.distance.toFixed(2)}m`);
        }),
      );

      // Subscribe to Eddystone events
      subs.current.push(
        ExpoBeacon.addListener("onEddystoneEnter", (e: EddystoneRegionEvent) => {
          console.log(`[Eddystone] Entered "${e.identifier}"`);
        }),
        ExpoBeacon.addListener("onEddystoneExit", (e: EddystoneRegionEvent) => {
          console.log(`[Eddystone] Exited "${e.identifier}"`);
        }),
        ExpoBeacon.addListener("onEddystoneDistance", (e: EddystoneDistanceEvent) => {
          console.log(`[Eddystone] "${e.identifier}" → ${e.distance.toFixed(2)}m`);
        }),
      );

      // Start with distance threshold
      await ExpoBeacon.startMonitoring({
        maxDistance: 10, // Only fire "enter" within 10 metres
        notifications: {
          beaconEvents: {
            enterTitle: "You're near a beacon!",
            exitTitle: "Beacon out of range",
            body: "{identifier} {event}ed",
          },
        },
      });
    }

    start();

    return () => {
      subs.current.forEach((s) => s.remove());
      subs.current = [];
      ExpoBeacon.stopMonitoring();
    };
  }, []);
}
```

#### Simple shorthand (number = maxDistance)

```ts
// Equivalent to { maxDistance: 5 }
await ExpoBeacon.startMonitoring(5);
```

#### Monitor with no distance filter

```ts
// Monitor without distance limit — enter fires as soon as the region is detected
await ExpoBeacon.startMonitoring();
```

---

### Customizing Notifications

#### Persistent configuration (survives app restarts)

```ts
ExpoBeacon.setNotificationConfig({
  // Enter/exit alert notifications (both platforms)
  beaconEvents: {
    enabled: true,                        // Set false to suppress notifications entirely
    enterTitle: "Beacon nearby",
    exitTitle: "Beacon out of range",
    body: "{identifier} {event}ed",       // Placeholders: {identifier}, {event}
    sound: true,                          // iOS only
    icon: "ic_beacon_notification",       // Android only — drawable resource name
  },

  // Persistent status-bar notification (Android only)
  foregroundService: {
    title: "My App — Monitoring",
    text: "Watching for nearby beacons",
    icon: "ic_service",
  },

  // Android notification channel
  channel: {
    name: "Proximity Alerts",
    description: "Alerts when beacons enter or leave range",
    importance: "default",                // "low" | "default" | "high"
  },
});
```

#### One-off session configuration (inline with startMonitoring)

```ts
await ExpoBeacon.startMonitoring({
  maxDistance: 5,
  notifications: {
    beaconEvents: { enabled: false }, // Silent monitoring — no user-facing alerts
  },
});
```

---

### Cancelling a Scan

Cancel any in-progress one-shot scan (iBeacon or Eddystone). The pending promise will reject with error code `SCAN_CANCELLED`.

```ts
// Start a long scan
const scanPromise = ExpoBeacon.scanForBeaconsAsync(
  ["E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"],
  30000,
);

// Cancel it after 2 seconds
setTimeout(() => ExpoBeacon.cancelScan(), 2000);

try {
  const results = await scanPromise;
} catch (e) {
  if (e.code === "SCAN_CANCELLED") {
    console.log("Scan was cancelled by user");
  }
}
```

---

## Full API Reference

### `requestPermissionsAsync()`

```ts
requestPermissionsAsync(): Promise<boolean>
```

Requests all permissions required for scanning and monitoring.

| Platform | Permissions Requested |
|---|---|
| **Android** | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS` (API 33+) |
| **iOS** | `CLLocationManager` "When In Use" → "Always" authorization (two-step prompt) |

**Returns**: `true` if all required permissions were granted.

```ts
const granted = await ExpoBeacon.requestPermissionsAsync();
if (!granted) {
  console.warn("Permissions not granted — scanning and monitoring will fail.");
}
```

> **Tip**: Call this before `scanForBeaconsAsync()` or `startMonitoring()`. If you call `startMonitoring()` without prior authorization, it requests "Always" permission automatically, but explicit control gives a better UX.

---

### `scanForBeaconsAsync(uuids?, scanDurationMs?)`

```ts
scanForBeaconsAsync(uuids?: string[], scanDurationMs?: number): Promise<BeaconScanResult[]>
```

Performs a **one-shot iBeacon scan**. Waits for the specified duration, then resolves with all discovered beacons.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `uuids` | `string[]` | `[]` | Proximity UUIDs to filter by. See platform differences below. |
| `scanDurationMs` | `number` | `5000` | Scan duration in milliseconds (must be > 0). |

**Returns**: `BeaconScanResult[]` — deduplicated by UUID + major + minor.

| Behaviour | Android | iOS |
|---|---|---|
| Empty `uuids` (`[]`) | Wildcard — discovers all nearby iBeacons | Auto-uses paired beacon UUIDs. Rejects with `WILDCARD_NOT_SUPPORTED` if none are paired. |
| Targeted (`["UUID-1"]`) | Filters scan results to matching UUIDs | CoreLocation ranging for those UUIDs |

**Possible errors**:

| Code | Reason |
|---|---|
| `SCAN_IN_PROGRESS` | Another scan is already running |
| `INVALID_UUID` | One of the UUID strings is malformed |
| `INVALID_DURATION` | Duration ≤ 0 |
| `PERMISSION_DENIED` | Location permission not granted |
| `WILDCARD_NOT_SUPPORTED` | iOS: empty UUIDs with no paired beacons |
| `SCAN_CANCELLED` | `cancelScan()` was called |

```ts
const beacons = await ExpoBeacon.scanForBeaconsAsync(
  ["E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"],
  8000,
);
```

---

### `scanForEddystonesAsync(scanDurationMs?)`

```ts
scanForEddystonesAsync(scanDurationMs?: number): Promise<EddystoneScanResult[]>
```

Performs a **one-shot Eddystone scan** using BLE. Discovers both Eddystone-UID and Eddystone-URL frames.

| Parameter | Type | Default | Description |
|---|---|---|---|
| `scanDurationMs` | `number` | `5000` | Scan duration in milliseconds (must be > 0). |

**Returns**: `EddystoneScanResult[]` — deduplicated by namespace:instance (UID) or url (URL).

**Possible errors**:

| Code | Reason |
|---|---|
| `SCAN_IN_PROGRESS` | Another Eddystone scan is already running |
| `INVALID_DURATION` | Duration ≤ 0 |
| `SCAN_CANCELLED` | `cancelScan()` was called |

```ts
const eddystones = await ExpoBeacon.scanForEddystonesAsync(5000);
```

---

### `startContinuousScan()`

```ts
startContinuousScan(): void
```

Begins a **continuous BLE scan** that streams beacon discoveries via events:
- `onBeaconFound` — iBeacon advertisements
- `onEddystoneFound` — Eddystone advertisements

Does not return results directly — subscribe to events before calling. Call `stopContinuousScan()` to end.

> **iOS**: Only reports iBeacons whose UUID is registered via `pairBeacon()`. Eddystones are reported regardless of pairing.

---

### `stopContinuousScan()`

```ts
stopContinuousScan(): void
```

Stops the continuous scan. No-op if no scan is running.

---

### `cancelScan()`

```ts
cancelScan(): void
```

Cancels any in-progress one-shot scan (iBeacon or Eddystone). The pending promise rejects with code `SCAN_CANCELLED`.

---

### `pairBeacon(identifier, uuid, major, minor)`

```ts
pairBeacon(identifier: string, uuid: string, major: number, minor: number): void
```

Registers an iBeacon for persistent monitoring.

| Parameter | Type | Description |
|---|---|---|
| `identifier` | `string` | Unique label (e.g. `"lobby-entrance"`). Re-using an identifier replaces the previous entry. |
| `uuid` | `string` | iBeacon proximity UUID (case-insensitive, e.g. `"E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"`) |
| `major` | `number` | Major value: `0`–`65535` |
| `minor` | `number` | Minor value: `0`–`65535` |

**Possible errors**: `INVALID_UUID`, `INVALID_MAJOR`, `INVALID_MINOR`.

```ts
ExpoBeacon.pairBeacon("main-door", "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", 1, 42);
```

---

### `unpairBeacon(identifier)`

```ts
unpairBeacon(identifier: string): void
```

Removes a paired iBeacon. If monitoring is active, the region stops being tracked immediately.

| Parameter | Type | Description |
|---|---|---|
| `identifier` | `string` | The label used when pairing |

```ts
ExpoBeacon.unpairBeacon("main-door");
```

---

### `getPairedBeacons()`

```ts
getPairedBeacons(): PairedBeacon[]
```

Returns all currently paired iBeacons from persistent storage.

```ts
const paired = ExpoBeacon.getPairedBeacons();
// [{ identifier: "main-door", uuid: "E2C5…", major: 1, minor: 42 }]
```

---

### `pairEddystone(identifier, namespace, instance)`

```ts
pairEddystone(identifier: string, namespace: string, instance: string): void
```

Registers an Eddystone-UID beacon for persistent monitoring.

| Parameter | Type | Description |
|---|---|---|
| `identifier` | `string` | Unique label (e.g. `"meeting-room"`) |
| `namespace` | `string` | 10-byte namespace ID as hex string — must be exactly **20 hex characters** |
| `instance` | `string` | 6-byte instance ID as hex string — must be exactly **12 hex characters** |

**Possible errors**: `INVALID_NAMESPACE`, `INVALID_INSTANCE`.

```ts
ExpoBeacon.pairEddystone("meeting-room", "edd1ebeac04e5defa017", "0123456789ab");
```

---

### `unpairEddystone(identifier)`

```ts
unpairEddystone(identifier: string): void
```

Removes a paired Eddystone beacon.

| Parameter | Type | Description |
|---|---|---|
| `identifier` | `string` | The label used when pairing |

```ts
ExpoBeacon.unpairEddystone("meeting-room");
```

---

### `getPairedEddystones()`

```ts
getPairedEddystones(): PairedEddystone[]
```

Returns all currently paired Eddystone beacons from persistent storage.

```ts
const paired = ExpoBeacon.getPairedEddystones();
// [{ identifier: "meeting-room", namespace: "edd1…", instance: "0123…" }]
```

---

### `startMonitoring(options?)`

```ts
startMonitoring(options?: MonitoringOptions | number): Promise<void>
```

Starts background region monitoring for **all paired beacons** (iBeacon + Eddystone).

Accepts a `MonitoringOptions` object, a plain `number` (shorthand for `maxDistance`), or nothing.

| Property | Type | Default | Description |
|---|---|---|---|
| `maxDistance` | `number` | `undefined` | Distance threshold in metres. `onBeaconEnter` / `onEddystoneEnter` only fires when measured distance ≤ this value. `onBeaconExit` / `onEddystoneExit` always fires. Omit to disable filtering. |
| `exitDistance` | `number` | `maxDistance + min(maxDistance × 0.5, 2.5)` | Distance in metres at which exit events fire. Must be ≥ `maxDistance`. Creates a hysteresis band between enter and exit thresholds to prevent rapid toggling near the boundary. Only used when `maxDistance` is set. |
| `notifications` | `NotificationConfig` | `undefined` | Notification overrides for this session (persisted). |

**What happens on each platform**:

| Platform | Mechanism |
|---|---|
| **Android** | Starts `BeaconForegroundService` (persistent notification). Survives app backgrounding. Auto-restarts after device reboot via `BootReceiver`. Scan timing: 1.1 s every 5 s. |
| **iOS** | Activates `CLLocationManager` region monitoring (iBeacon) + CoreBluetooth BLE scanning (Eddystone). iOS can wake/relaunch the app on region boundary crossings, even if force-quit. |

**Possible errors**: `PERMISSION_DENIED` (Always authorization required on iOS).

```ts
// Shorthand — just a distance threshold
await ExpoBeacon.startMonitoring(5);

// Full options with custom exit threshold
await ExpoBeacon.startMonitoring({
  maxDistance: 10,
  exitDistance: 15, // Exit fires when distance exceeds 15m
  notifications: {
    beaconEvents: {
      enterTitle: "Welcome!",
      body: "{identifier} is nearby",
    },
  },
});

// No distance filter, silent
await ExpoBeacon.startMonitoring({
  notifications: { beaconEvents: { enabled: false } },
});

// No options at all — monitor all paired beacons, no distance filter, default notifications
await ExpoBeacon.startMonitoring();
```

---

### `stopMonitoring()`

```ts
stopMonitoring(): Promise<void>
```

Stops all background monitoring. On Android, stops the foreground service.

```ts
await ExpoBeacon.stopMonitoring();
```

---

### `setNotificationConfig(config)`

```ts
setNotificationConfig(config: NotificationConfig): void
```

Persists notification configuration applied to **all subsequent monitoring sessions**. Survives app restarts.

For one-off overrides, pass `notifications` inside `startMonitoring(options)` instead.

See [`NotificationConfig`](#notificationconfig) for the full shape.

```ts
ExpoBeacon.setNotificationConfig({
  beaconEvents: {
    enabled: true,
    enterTitle: "Nearby",
    exitTitle: "Gone",
    body: "{identifier} {event}ed",
    sound: true,
    icon: "ic_notification",
  },
  foregroundService: {
    title: "Monitoring Active",
    text: "Scanning for beacons",
    icon: "ic_service",
  },
  channel: {
    name: "Beacons",
    description: "Beacon proximity alerts",
    importance: "default",
  },
});
```

---

## Events

Subscribe with `ExpoBeacon.addListener(eventName, handler)`. Always call `.remove()` on the returned subscription during cleanup.

```ts
const sub = ExpoBeacon.addListener("onBeaconEnter", handler);
// Later:
sub.remove();
```

### Event Summary

| Event | Trigger | Payload Type |
|---|---|---|
| `onBeaconEnter` | Paired iBeacon enters range (respects `maxDistance`) | `BeaconRegionEvent` |
| `onBeaconExit` | Paired iBeacon leaves range (always fires) | `BeaconRegionEvent` |
| `onBeaconDistance` | Periodic distance update during monitoring (~1/sec) | `BeaconDistanceEvent` |
| `onBeaconFound` | iBeacon detected during continuous scan | `BeaconScanResult` |
| `onEddystoneFound` | Eddystone detected during continuous scan | `EddystoneScanResult` |
| `onEddystoneEnter` | Paired Eddystone enters range (respects `maxDistance`) | `EddystoneRegionEvent` |
| `onEddystoneExit` | Paired Eddystone leaves range (always fires) | `EddystoneRegionEvent` |
| `onEddystoneDistance` | Periodic Eddystone distance update during monitoring | `EddystoneDistanceEvent` |

### Event Detail

#### `onBeaconEnter`

Fired when the device enters the region of a paired iBeacon. If `maxDistance` was set, only fires when the measured distance is within the threshold.

```ts
ExpoBeacon.addListener("onBeaconEnter", (e) => {
  // e.identifier — "lobby-entrance"
  // e.uuid       — "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"
  // e.major      — 1
  // e.minor      — 100
  // e.event      — "enter"
  // e.distance   — 3.2 (metres, or –1 if unavailable)
  console.log(`Entered "${e.identifier}" at ~${e.distance.toFixed(1)}m`);
});
```

#### `onBeaconExit`

Fired when the device leaves the region. **Always fires** regardless of `maxDistance` setting.

```ts
ExpoBeacon.addListener("onBeaconExit", (e) => {
  console.log(`Left "${e.identifier}"`);
});
```

#### `onBeaconDistance`

Fired continuously during monitoring with the latest distance reading. Useful for proximity-based UI.

```ts
ExpoBeacon.addListener("onBeaconDistance", (e) => {
  // e.identifier, e.uuid, e.major, e.minor, e.distance
  updateProximityBar(e.identifier, e.distance);
});
```

#### `onBeaconFound`

Fired during `startContinuousScan()` each time an iBeacon advertisement is received.

```ts
ExpoBeacon.addListener("onBeaconFound", (b) => {
  console.log(`${b.uuid} ${b.major}/${b.minor} — ${b.distance.toFixed(1)}m RSSI: ${b.rssi}`);
});
```

#### `onEddystoneFound`

Fired during `startContinuousScan()` each time an Eddystone advertisement is received.

```ts
ExpoBeacon.addListener("onEddystoneFound", (b) => {
  if (b.frameType === "uid") {
    console.log(`UID: ${b.namespace}/${b.instance} — ${b.distance.toFixed(1)}m`);
  } else {
    console.log(`URL: ${b.url} — ${b.distance.toFixed(1)}m`);
  }
});
```

#### `onEddystoneEnter`

Fired when a paired Eddystone-UID beacon enters range during monitoring.

```ts
ExpoBeacon.addListener("onEddystoneEnter", (e) => {
  console.log(`Eddystone "${e.identifier}" entered (ns: ${e.namespace})`);
});
```

#### `onEddystoneExit`

Fired when a paired Eddystone-UID beacon leaves range.

```ts
ExpoBeacon.addListener("onEddystoneExit", (e) => {
  console.log(`Eddystone "${e.identifier}" exited`);
});
```

#### `onEddystoneDistance`

Fired continuously during monitoring with the latest Eddystone distance reading.

```ts
ExpoBeacon.addListener("onEddystoneDistance", (e) => {
  console.log(`Eddystone "${e.identifier}" → ${e.distance.toFixed(2)}m`);
});
```

---

## TypeScript Types

All types are exported from the package:

```ts
import type {
  BeaconScanResult,
  PairedBeacon,
  BeaconRegionEvent,
  BeaconDistanceEvent,
  EddystoneFrameType,
  EddystoneScanResult,
  PairedEddystone,
  EddystoneRegionEvent,
  EddystoneDistanceEvent,
  ExpoBeaconModuleEvents,
  MonitoringOptions,
  NotificationConfig,
  BeaconNotificationConfig,
  ForegroundServiceConfig,
  NotificationChannelConfig,
} from "expo-beacon";
```

### `BeaconScanResult`

Returned by `scanForBeaconsAsync()` and `onBeaconFound`.

```ts
type BeaconScanResult = {
  uuid: string;      // Proximity UUID, uppercase (e.g. "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0")
  major: number;     // 0–65535
  minor: number;     // 0–65535
  rssi: number;      // Signal strength in dBm (negative, e.g. –65)
  distance: number;  // Estimated distance in metres
  txPower: number;   // Calibrated TX power from the advertisement
};
```

### `PairedBeacon`

Returned by `getPairedBeacons()`.

```ts
type PairedBeacon = {
  identifier: string; // Your label
  uuid: string;
  major: number;
  minor: number;
};
```

### `BeaconRegionEvent`

Payload for `onBeaconEnter` / `onBeaconExit`.

```ts
type BeaconRegionEvent = {
  identifier: string;        // Matches PairedBeacon.identifier
  uuid: string;
  major: number;
  minor: number;
  event: "enter" | "exit";
  distance: number;          // Metres at event time; –1 if unavailable
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

Returned by `scanForEddystonesAsync()` and `onEddystoneFound`.

```ts
type EddystoneScanResult = {
  frameType: "uid" | "url";
  namespace?: string;  // 20 hex chars. Present for UID frames.
  instance?: string;   // 12 hex chars. Present for UID frames.
  url?: string;        // Decoded URL. Present for URL frames.
  rssi: number;
  distance: number;
  txPower: number;
};
```

### `PairedEddystone`

Returned by `getPairedEddystones()`.

```ts
type PairedEddystone = {
  identifier: string;
  namespace: string;   // 20 hex chars
  instance: string;    // 12 hex chars
};
```

### `EddystoneRegionEvent`

Payload for `onEddystoneEnter` / `onEddystoneExit`.

```ts
type EddystoneRegionEvent = {
  identifier: string;
  namespace: string;
  instance: string;
  event: "enter" | "exit";
  distance: number;           // Metres; –1 if unavailable
};
```

### `EddystoneDistanceEvent`

Payload for `onEddystoneDistance`.

```ts
type EddystoneDistanceEvent = {
  identifier: string;
  namespace: string;
  instance: string;
  distance: number;
};
```

### `MonitoringOptions`

Passed to `startMonitoring()`.

```ts
type MonitoringOptions = {
  maxDistance?: number;
  exitDistance?: number;
  notifications?: NotificationConfig;
};
```

### `NotificationConfig`

Top-level notification configuration.

```ts
type NotificationConfig = {
  beaconEvents?: BeaconNotificationConfig;     // Enter/exit alerts
  foregroundService?: ForegroundServiceConfig;  // Android only — persistent status bar
  channel?: NotificationChannelConfig;          // Android only — channel settings
};
```

### `BeaconNotificationConfig`

```ts
type BeaconNotificationConfig = {
  enabled?: boolean;     // Default: true. Set false to suppress.
  enterTitle?: string;   // Default: "Beacon Entered"
  exitTitle?: string;    // Default: "Beacon Exited"
  body?: string;         // Default: "{identifier} region {event}ed"
                         // Supports {identifier} and {event} placeholders.
  sound?: boolean;       // iOS only. Default: true
  icon?: string;         // Android only. Drawable resource name.
};
```

### `ForegroundServiceConfig`

```ts
type ForegroundServiceConfig = {
  title?: string;  // Default: "Beacon Monitoring Active"
  text?: string;   // Default: "Monitoring for iBeacons in the background"
  icon?: string;   // Android drawable resource name
};
```

### `NotificationChannelConfig`

```ts
type NotificationChannelConfig = {
  name?: string;                           // Default: "Beacon Monitoring"
  description?: string;                    // Default: "Used for background iBeacon region monitoring"
  importance?: "low" | "default" | "high"; // Default: "low"
};
```

---

## Background Behaviour

### Android

`startMonitoring()` launches a **foreground service** (`BeaconForegroundService`) with a persistent notification. This is required by Android 8+ (Oreo) to keep BLE scanning alive in the background.

| Behaviour | Detail |
|---|---|
| Foreground service | Required for background BLE on Android 8+. Shows persistent notification. |
| Reboot survival | `BootReceiver` auto-restarts monitoring after device reboot. |
| Scan timing | 1.1 s scan window every 5 s (AltBeacon default). |
| Battery | Low impact due to duty-cycled scanning. |

### iOS

`startMonitoring()` activates `CLLocationManager` region monitoring for iBeacons and CoreBluetooth BLE scanning for Eddystones.

| Behaviour | Detail |
|---|---|
| Region monitoring | iOS wakes/relaunches the app on region boundary crossings — even if force-quit. |
| BLE scanning | Eddystones are monitored via CoreBluetooth. Works reliably in foreground; may be throttled when the app is suspended. |
| Background modes | `allowsBackgroundLocationUpdates = true`, `pausesLocationUpdatesAutomatically = false` |
| Region limit | 20 simultaneous `CLBeaconRegion` registrations max. Eddystones don't count. |

---

## Notifications

A local notification is posted automatically for every beacon enter/exit event (both iBeacon and Eddystone) during monitoring.

### Default Values

| Property | Default |
|---|---|
| Enter title | `"Beacon Entered"` |
| Exit title | `"Beacon Exited"` |
| Body | `"{identifier} region {event}ed"` |
| Sound (iOS) | `true` |
| Icon (Android) | System `ic_dialog_info` |
| Foreground service title | `"Beacon Monitoring Active"` |
| Foreground service text | `"Monitoring for iBeacons in the background"` |
| Channel name (Android) | `"Beacon Monitoring"` |
| Channel importance (Android) | `"low"` |

### Android Channel

Both the foreground service and enter/exit alerts share the channel ID `expo_beacon_channel`. The channel is recreated on each `onStartCommand`, so config changes take effect on the next monitoring start.

> **Android channel importance note**: Android prevents decreasing channel importance after the first notification. Increasing works; decreasing has no effect until the user clears notification settings or reinstalls the app.

---

## Platform-Specific Notes & Gotchas

### iOS Native Insights (CoreLocation + CoreBluetooth)

1. **iBeacon scanning requires UUIDs**: Apple's CoreBluetooth strips iBeacon manufacturer data from BLE advertisements. The module uses `CLLocationManager` ranging with `CLBeaconIdentityConstraint`, which requires known UUIDs. Wildcard iBeacon discovery is architecturally impossible on iOS.

2. **Two-step location permission**: iOS requires requesting "When In Use" first, then upgrading to "Always". The module handles this automatically via a two-step flow in `requestPermissionsAsync()`.

3. **20 region limit**: `CLLocationManager` enforces a hard limit of 20 monitored `CLBeaconRegion` regions across all apps. If your app pairs more than 20 iBeacons, only the first 20 will be actively monitored. Plan your beacon deployment accordingly.

4. **Region monitoring vs. ranging**: Region monitoring (enter/exit) works indefinitely in the background. Ranging (distance updates) requires the app to be in the foreground or have an active background task. The module keeps ranging alive when background location mode is enabled.

5. **Eddystone background limitations**: Eddystone monitoring uses CoreBluetooth, which iOS throttles in the background (longer scan intervals, delayed discovery). For critical Eddystone use cases, consider using significant location changes to periodically wake the app.

6. **Hysteresis**: The module requires 3 consecutive readings inside/outside the distance threshold before emitting enter/exit events. This prevents jitter from RSSI fluctuations.

### Android Native Insights (AltBeacon + Foreground Service)

1. **Foreground service is mandatory**: Android 8+ kills background BLE scans. The module uses `BeaconForegroundService` with a persistent notification. Users will see this notification while monitoring is active.

2. **Doze mode**: Android Doze can delay BLE scan callbacks. The foreground service mitigates this, but very aggressive OEM battery optimization (Xiaomi, Huawei, Samsung) may still interfere. Direct users to disable battery optimization for your app.

3. **Boot receiver**: Monitoring auto-restarts after reboot via `BootReceiver` reading the `is_monitoring` flag from `SharedPreferences`.

4. **Runtime permissions**: Android 12+ requires `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` in addition to location. Android 13+ requires `POST_NOTIFICATIONS` for the foreground service notification. `requestPermissionsAsync()` handles all of these.

5. **Notification channel immutability**: Once Android creates a notification channel with a given importance level, decreasing the importance has no effect. The only workaround is uninstalling and reinstalling the app.

---

## Troubleshooting

### "WILDCARD_NOT_SUPPORTED" error on iOS

You called `scanForBeaconsAsync([])` with no paired beacons. Either:
- Pass at least one UUID: `scanForBeaconsAsync(["YOUR-UUID"])`
- Or pair beacons first with `pairBeacon()` — the module will auto-use their UUIDs

### Scanning returns empty results

1. Verify Bluetooth is enabled on the device
2. Ensure you called `requestPermissionsAsync()` and got `true`
3. On iOS, confirm you passed a valid UUID or have paired beacons
4. The beacon must be powered on, advertising, and within BLE range (~30–70 m typical)
5. Try a longer scan duration (10000 ms)

### Monitoring events not firing

1. Ensure beacons are paired **before** calling `startMonitoring()`
2. Check that permissions returned `true` (iOS needs "Always" authorization for background monitoring)
3. On iOS, verify Background Modes are enabled in Xcode
4. On Android, check that battery optimization is disabled for your app
5. If using `maxDistance`, the beacon may be too far — try removing the distance filter

### Distance values are inaccurate

BLE distance estimation is inherently imprecise. RSSI fluctuates due to:
- Physical obstacles (walls, furniture, the user's body)
- Multipath interference
- Device orientation
- Other 2.4 GHz interference (Wi-Fi, microwaves)

Use distance values as approximate zones (immediate/near/far) rather than precise measurements. For best accuracy, calibrate `txPower` on your beacons at 1 metre.

### Android foreground notification won't go away

The persistent notification is required by Android 8+ for background BLE scanning. It disappears when you call `stopMonitoring()`. You can customize its appearance via `setNotificationConfig()`.

### `onBeaconEnter` fires repeatedly

The module uses hysteresis (3 consecutive readings) to prevent jitter. If you're still seeing repeated events, it may be because the beacon is at the boundary of `maxDistance`. Consider adding a margin to your distance threshold.

---

## Error Codes

| Code | Method | Description |
|---|---|---|
| `SCAN_IN_PROGRESS` | `scanForBeaconsAsync`, `scanForEddystonesAsync` | A scan is already running. Wait for it to complete or call `cancelScan()`. |
| `SCAN_CANCELLED` | `scanForBeaconsAsync`, `scanForEddystonesAsync` | The scan was cancelled via `cancelScan()`. |
| `INVALID_UUID` | `scanForBeaconsAsync`, `pairBeacon` | Malformed UUID string. |
| `INVALID_DURATION` | `scanForBeaconsAsync`, `scanForEddystonesAsync` | Scan duration must be > 0. |
| `INVALID_MAJOR` | `pairBeacon` | Major value not in range 0–65535. |
| `INVALID_MINOR` | `pairBeacon` | Minor value not in range 0–65535. |
| `INVALID_NAMESPACE` | `pairEddystone` | Namespace must be exactly 20 hex characters. |
| `INVALID_INSTANCE` | `pairEddystone` | Instance must be exactly 12 hex characters. |
| `PERMISSION_DENIED` | `scanForBeaconsAsync`, `startMonitoring` | Required permissions were not granted. |
| `WILDCARD_NOT_SUPPORTED` | `scanForBeaconsAsync` | iOS only: no UUIDs provided and no paired beacons exist. |

---

## Contributing

Contributions are welcome! Open an issue or pull request on [GitHub](https://github.com/martinmikesCCS/expo-beacon).

## License

MIT
