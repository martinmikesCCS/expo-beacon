# expo-beacon

An Expo module for scanning, pairing, and monitoring **iBeacons** and **Eddystone** beacons in React Native apps — with full background support on both iOS and Android.

| Feature | Description |
|---|---|
| **Scan** | Discover nearby iBeacons (one-shot or continuous) and Eddystone-UID / Eddystone-URL beacons via BLE |
| **Pair** | Register specific beacons for persistent tracking — survives app restarts |
| **Monitor** | Background enter/exit region detection with distance-based filtering |
| **Distance** | Real-time distance updates (~1/sec) while monitoring |
| **Timeout** | Fire a one-shot event after a beacon has been out of range for a configured duration |
| **Event Logging** | Persist every beacon event to a local SQLite database for diagnostics & replay |
| **Notifications** | Automatic local notifications on region enter/exit, fully customisable |

| Platform | Native Implementation |
|---|---|
| **Android** | [AltBeacon](https://altbeacon.github.io/android-beacon-library/) library + Foreground Service |
| **iOS** | CoreLocation (iBeacon ranging & monitoring) + CoreBluetooth (Eddystone & wildcard BLE) |
| **Web** | Not supported (async methods reject, sync getters return inert defaults, everything else throws) |

---

## Table of Contents

- [Installation](#installation)
- [Platform Setup](#platform-setup)
  - [iOS](#ios)
  - [Android](#android)
- [Quick Start](#quick-start)
- [React Hooks](#react-hooks)
  - [useBeacon()](#usebeacon)
  - [useCarPlay()](#usecarplay)
- [Usage Examples](#usage-examples)
  - [Scanning for iBeacons](#scanning-for-ibeacons)
  - [Scanning for Eddystone Beacons](#scanning-for-eddystone-beacons)
  - [Continuous (Live) Scanning](#continuous-live-scanning)
  - [Pairing & Unpairing Beacons](#pairing--unpairing-beacons)
  - [Background Monitoring](#background-monitoring)
  - [Customizing Notifications](#customizing-notifications)
  - [Beacon Timeout](#beacon-timeout)
  - [Event Logging](#event-logging)
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
  - [getMonitoringConfig()](#getmonitoringconfig)
  - [getMonitoredDeviceState()](#getmonitoreddevicestateidentifier)
  - [getMonitoredDeviceStates()](#getmonitoreddevicestates)
  - [setNotificationConfig()](#setnotificationconfigconfig)
  - [enableEventLogging()](#enableeventlogging)
  - [disableEventLogging()](#disableeventlogging)
  - [getEventLogs()](#geteventlogsoptions)
  - [clearEventLogs()](#cleareventlogs)
  - [destroyEventLogs()](#destroyeventlogs)
  - [setApiEndpoint()](#setapiendpointurl-apikey-id)
- [Events](#events)
- [TypeScript Types](#typescript-types)
- [Native Integrations](#native-integrations)
  - [react-native-background-geolocation](#react-native-background-geolocation)
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

> When the bundled config plugin is installed (`"plugins": ["expo-beacon"]`), `location` is merged into `UIBackgroundModes` automatically on `expo prebuild` — the native module only enables background ranging when this mode is present.

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

## React Hooks

For React / React Native apps the package ships two hooks that wrap the
imperative API, manage event subscriptions (with automatic cleanup), and expose
the relevant state reactively. Import them directly from the package:

```ts
import { useBeacon, useCarPlay } from "expo-beacon";
```

Both hooks accept optional event callbacks. Callbacks are read from a ref, so
passing fresh inline functions on every render does **not** re-subscribe the
underlying native listeners.

### useBeacon()

Manages scanning and background monitoring. It keeps the paired-beacon lists,
the set of beacons currently in range, and the monitoring flag in sync, and
returns stable action wrappers.

```tsx
import { useBeacon } from "expo-beacon";

function BeaconScreen() {
  const {
    inRange,
    isMonitoring,
    pairedBeacons,
    requestPermissions,
    pairBeacon,
    startMonitoring,
    stopMonitoring,
  } = useBeacon({
    onBeaconEnter: (e) => console.log("entered", e.identifier, e.distance),
    onBeaconExit: (e) => console.log("exited", e.identifier),
    onError: (e) => console.warn(`[${e.code}] ${e.message}`),
  });

  return (
    <View>
      <Button title="Grant permissions" onPress={requestPermissions} />
      <Button
        title={isMonitoring ? "Stop monitoring" : "Start monitoring"}
        onPress={() => (isMonitoring ? stopMonitoring() : startMonitoring())}
      />
      {inRange.map((b) => (
        <Text key={b.identifier}>
          {b.identifier} — {b.distance >= 0 ? `${b.distance.toFixed(1)}m` : "n/a"}
        </Text>
      ))}
    </View>
  );
}
```

| Returned value | Description |
| --- | --- |
| `pairedBeacons` / `pairedEddystones` | Reactive lists of paired devices. |
| `inRange` | Paired beacons currently in range, derived live from enter/exit/distance/timeout events (`InRangeBeacon[]`). |
| `isMonitoring` | Whether background monitoring is active. |
| `isEventLoggingEnabled` | Whether SQLite event logging is enabled (kept in sync by the logging actions). |
| `refreshPaired()` | Re-read the paired lists from native. |
| `pairBeacon()` / `unpairBeacon()` | Pair / unpair an iBeacon, then refresh. |
| `pairEddystone()` / `unpairEddystone()` | Pair / unpair an Eddystone, then refresh. |
| `scanForBeacons()` / `scanForEddystones()` | One-shot scans returning a promise. |
| `startContinuousScan()` / `stopContinuousScan()` | Live scan; results arrive via `onBeaconFound` / `onEddystoneFound`. |
| `cancelScan()` | Cancel an in-progress one-shot scan. |
| `startMonitoring()` / `stopMonitoring()` | Start / stop background monitoring. |
| `getMonitoringConfig()` | Read the current monitoring config + active-state snapshot. |
| `getMonitoredDeviceState()` / `getMonitoredDeviceStates()` | Native state snapshot for one / all paired devices. |
| `setNotificationConfig()` | Persist notification configuration for monitoring sessions. |
| `enableEventLogging()` / `disableEventLogging()` | Toggle SQLite logging (updates `isEventLoggingEnabled`). |
| `getEventLogs()` / `clearEventLogs()` / `destroyEventLogs()` | Read / clear / drop the persisted event log. |
| `setApiEndpoint()` / `getApiEndpoint()` | Configure / read the native event-forwarding endpoint. |
| `isBatteryOptimizationExempt()` / `requestBatteryOptimizationExemption()` | Check / request Android battery-optimization exemption. |
| `requestPermissions()` | Request the permissions needed for scanning / monitoring. |

`inRange` reflects **monitored (paired)** beacons only. Continuous-scan results
are delivered through the `onBeaconFound` / `onEddystoneFound` callbacks because
raw scan hits carry no paired identifier. Pass `track: false` to skip `inRange`
bookkeeping when you only need the callbacks.

### useCarPlay()

Observes CarPlay / Android Auto connection state. It initializes from the
persisted native state on mount and tracks live connect / disconnect events.

```tsx
import { useCarPlay } from "expo-beacon";

function CarPlayBadge() {
  const { connected, transport, isMonitoring, startMonitoring, stopMonitoring } =
    useCarPlay({
      onConnected: (e) => console.log("car connected via", e.transport),
      onDisconnected: () => console.log("car disconnected"),
    });

  return (
    <View>
      <Text>{connected ? `Connected (${transport})` : "Not connected"}</Text>
      <Button
        title={isMonitoring ? "Stop" : "Start"}
        onPress={() => (isMonitoring ? stopMonitoring() : startMonitoring())}
      />
    </View>
  );
}
```

| Returned value | Description |
| --- | --- |
| `connected` | Whether a CarPlay / Android Auto session is active. |
| `transport` | Transport of the active session (`CarPlayTransport`), or `null`. |
| `isMonitoring` | Whether persistent monitoring is enabled. |
| `lastConnectedAt` / `lastDisconnectedAt` | Epoch-ms timestamps of the last transitions, or `null`. |
| `startMonitoring()` / `stopMonitoring()` | Enable / disable monitoring. |
| `refresh()` | Re-read the connection + monitoring state from native. |
| `getDiagnostics()` | Fetch detection diagnostics for troubleshooting. |

Pass `autoStart: true` to call `startCarPlayMonitoring()` on mount when it is
not already enabled.

> Both hooks are safe to call on web: the underlying module is a no-op stub
> there, so the hooks simply report empty / disconnected state.

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
// Pass an empty array (or omit the arguments — defaults are uuids = [],
// scanDuration = 5000) to discover ALL nearby iBeacons.
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

### Beacon Timeout

Pair a beacon with `timeoutSeconds` to fire a one-shot event after the beacon has been out of range for that duration. The countdown is armed when the beacon exits range (or when no BLE readings arrive for 60 seconds, e.g. due to Doze mode or background throttling) and is cancelled if the beacon is seen again before it fires.

```tsx
import { useEffect } from "react";
import ExpoBeacon from "expo-beacon";
import type { BeaconTimeoutEvent, EddystoneTimeoutEvent } from "expo-beacon";

// Pair with a 30-second timeout
ExpoBeacon.pairBeacon(
  "lobby-entrance",
  "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
  1,
  100,
  undefined,      // name (optional)
  30,             // timeoutSeconds — fires 30 s after the beacon leaves range
);

// Pair Eddystone with a 60-second timeout
ExpoBeacon.pairEddystone(
  "meeting-room",
  "edd1ebeac04e5defa017",
  "0123456789ab",
  undefined,      // name (optional)
  60,             // timeoutSeconds — fires 60 s after the beacon leaves range
);

// Listen for the timeout events
useEffect(() => {
  const beaconTimeout = ExpoBeacon.addListener(
    "onBeaconTimeout",
    (e: BeaconTimeoutEvent) => {
      console.log(`Beacon "${e.identifier}" out of range for configured duration!`);
    },
  );
  const eddystoneTimeout = ExpoBeacon.addListener(
    "onEddystoneTimeout",
    (e: EddystoneTimeoutEvent) => {
      console.log(`Eddystone "${e.identifier}" out of range for configured duration!`);
    },
  );

  return () => {
    beaconTimeout.remove();
    eddystoneTimeout.remove();
  };
}, []);
```

> **Note**: The timeout fires once per exit. If the beacon re-enters range before the countdown completes, the pending timer is cancelled and re-armed on the next exit.

---

### Event Logging

Enable SQLite-backed event logging to persist every beacon event locally. Useful for diagnostics, debugging, and replaying event history.

```ts
import ExpoBeacon from "expo-beacon";
import type { EventLogEntry, EventLogQueryOptions } from "expo-beacon";

// Enable logging — creates/opens the SQLite database
ExpoBeacon.enableEventLogging();

// ... scanning, monitoring, etc. — all events are now persisted automatically ...

// Query all recent events
const logs: EventLogEntry[] = ExpoBeacon.getEventLogs();
console.log(logs);
// [
//   { id: 42, timestamp: 1712345678000, eventType: "onBeaconEnter",
//     identifier: "lobby", data: { uuid: "E2C5…", major: 1, minor: 100, ... } },
//   ...
// ]

// Filter by event type and time range
const enterLogs = ExpoBeacon.getEventLogs({
  eventType: "onBeaconEnter",
  sinceTimestamp: Date.now() - 3600_000, // last hour
  limit: 100,
});

// Disable logging (retains existing data)
ExpoBeacon.disableEventLogging();

// Clear all logged events (keeps the database)
ExpoBeacon.clearEventLogs();

// Destroy the database entirely (also disables logging)
ExpoBeacon.destroyEventLogs();
```

> **Storage**: Events are stored in a local SQLite database (`expo_beacon_events.db`). No external dependencies are required — Android uses the built-in SQLite, iOS uses the system `libsqlite3`.

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

### CarPlay / Android Auto Detection

Detect when the device connects to a car infotainment system and react in JS — or, when the bundled config plugin is installed, automatically start `react-native-background-geolocation` tracking on connect and stop it on disconnect.

Detection covers both **wired and wireless CarPlay** on iOS and **Android Auto projection / Android Automotive OS** on Android. No special CarPlay entitlement or Android Auto certification is required.

```ts
import ExpoBeacon, {
  CarPlayConnectedEvent,
  CarPlayDisconnectedEvent,
} from "expo-beacon";

// Start observing
await ExpoBeacon.startCarPlayMonitoring();

const connectSub = ExpoBeacon.addListener(
  "onCarPlayConnected",
  (event: CarPlayConnectedEvent) => {
    // event.transport: "wired" | "wireless" | "projection" | "native" | "unknown"
    console.log(`Car connected via ${event.transport}`);
  },
);
const disconnectSub = ExpoBeacon.addListener(
  "onCarPlayDisconnected",
  (_event: CarPlayDisconnectedEvent) => {
    console.log("Car disconnected");
  },
);

// Stop later (e.g. when feature is disabled)
await ExpoBeacon.stopCarPlayMonitoring();
connectSub.remove();
disconnectSub.remove();
```

**How it works**

- **iOS:** observes `AVAudioSession.routeChangeNotification` for output ports of type `.carAudio`. Wired-vs-wireless is reported on a best-effort basis (looking for a coexisting Bluetooth output port).
- **Android:** observes `androidx.car.app.connection.CarConnection` LiveData. `transport` is `"projection"` for phones casting to a head unit, `"native"` for Android Automotive OS.
- Connect/disconnect events flow through the same SQLite event log and remote API forwarder as beacon events.
- When the config plugin is installed, the auto-generated `BeaconGeoPlugin` also calls `BackgroundGeolocation.start()` on connect and `.stop()` on disconnect — no extra wiring required.

**Background detection**

CarPlay observation is **persistent** — the enabled flag is stored in native preferences and the observer is automatically re-attached after app kill or device reboot. `startMonitoring()` also enables CarPlay observation by default; calling `startCarPlayMonitoring()` explicitly is only required if you want CarPlay events without beacon monitoring.

- **Android:** the foreground service hosts the `CarConnection` observer. As long as the service runs (which it does whenever beacon monitoring or CarPlay monitoring is enabled, and is restarted on boot by `BootReceiver`), CarPlay events are captured even after the app process is killed. **Guaranteed background detection.**
- **iOS:** the observer auto-restarts in the module's `OnCreate`, including background-launches triggered by beacon region monitoring. **iOS cannot wake a terminated app on CarPlay alone** — for guaranteed wake-from-suspension, also call `startMonitoring()` with at least one paired beacon (e.g. a beacon left in the vehicle). Region-wake events trigger a CarPlay state resync to reconcile any route changes that happened while the app was suspended.

**Notes**

- `startCarPlayMonitoring()` is idempotent. Calling it twice does not register a duplicate observer.
- `stopCarPlayMonitoring()` clears the persisted flag, so the observer will not auto-restart on next launch.
- The iOS detector does not require the CarPlay entitlement because it only reads the active audio route; you do not need to ship a CarPlay app.
- On iOS, if the JS bundle is suspended in the background, the JS event delivery is deferred until the app resumes, but the native lifecycle delegate (used by the geolocation plugin) fires immediately on connect.
- On Android, when CarPlay monitoring is enabled without beacon monitoring, the foreground service shows a generic "Connected device monitoring active" notification.

**Android Auto registration (automatic via config plugin)**

On Android 14+ — and especially with **wireless Android Auto** — `CarConnection.getType()` silently returns `NOT_CONNECTED` for any app that has not declared itself Android-Auto-aware. The bundled config plugin handles this for you: on the next `expo prebuild` it injects a `com.google.android.gms.car.application` meta-data entry into `AndroidManifest.xml` and writes `res/xml/automotive_app_desc.xml` with `<uses name="template"/>`. No manual native edits are required.

If you need to disable this (e.g. you already ship your own Android Auto template app and don't want a duplicate registration) or you need to declare a different capability than `template`, configure the plugin in your app config:

```json
{
  "expo": {
    "plugins": [
      ["expo-beacon", {
        "android": {
          "androidAuto": {
            "register": true,
            "usesName": "template"
          }
        }
      }]
    ]
  }
}
```

`usesName` accepts any value supported by Android's [`automotiveApp` schema](https://developer.android.com/training/cars/apps#manifest) (`template`, `media`, `notification`, …). Setting `register: false` skips both the meta-data and the resource file.

**Diagnostics**

If `onCarPlayConnected` never fires on Android, call `getCarPlayDiagnostics()` to inspect the native state:

```ts
const d = ExpoBeacon.getCarPlayDiagnostics();
// {
//   isCarAppMetadataPresent: true,   // false → config plugin didn't run; prebuild again
//   isCarProviderQueryable: true,    // false → Android Auto app not installed on device
//   lastRawConnectionType: 2,        // 0=NOT_CONNECTED 1=NATIVE (AAOS) 2=PROJECTION; null = no value yet
//   observerActive: true,
//   serviceAlive: true,
// }
```

A response with `isCarAppMetadataPresent: false` indicates the AA registration didn't make it into the built APK — re-run `expo prebuild --clean` to apply the plugin changes.

---

## Full API Reference

### `requestPermissionsAsync()`

```ts
requestPermissionsAsync(): Promise<boolean>
```

Requests all permissions required for scanning and monitoring.

| Platform | Permissions Requested |
|---|---|
| **Android** | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+), `POST_NOTIFICATIONS` (API 33+), then `ACCESS_BACKGROUND_LOCATION` (API 29+) in a second prompt. Resolves `true` only when background location is granted. |
| **iOS** | `CLLocationManager` "When In Use" authorization — resolves `true` once granted. The "Always" upgrade is requested later by `startMonitoring()`, and Bluetooth permission is not prompted here. |

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

Both parameters are optional — the defaults are applied on the JS side before the native call.

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

The parameter is optional — the default is applied on the JS side before the native call.

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

### `pairBeacon(identifier, uuid, major, minor, name?, timeoutSeconds?)`

```ts
pairBeacon(identifier: string, uuid: string, major: number, minor: number, name?: string, timeoutSeconds?: number): void
```

Registers an iBeacon for persistent monitoring.

| Parameter | Type | Description |
|---|---|---|
| `identifier` | `string` | Unique label (e.g. `"lobby-entrance"`). Re-using an iBeacon identifier replaces the previous entry. |
| `uuid` | `string` | iBeacon proximity UUID (case-insensitive, e.g. `"E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"`) |
| `major` | `number` | Major value: `0`–`65535` |
| `minor` | `number` | Minor value: `0`–`65535` |
| `name` | `string?` | Optional BLE device name for display purposes |
| `timeoutSeconds` | `number?` | Fire `onBeaconTimeout` once, this many seconds after the beacon exits range. Cancelled if the beacon is seen again first. |

**Possible errors**: `INVALID_UUID`, `INVALID_MAJOR`, `INVALID_MINOR`, `DUPLICATE_IDENTIFIER` (identifier already used by a paired Eddystone).

```ts
ExpoBeacon.pairBeacon("main-door", "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", 1, 42);

// With timeout — fires onBeaconTimeout 30 s after the beacon leaves range
ExpoBeacon.pairBeacon("main-door", "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0", 1, 42, undefined, 30);
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

### `pairEddystone(identifier, namespace, instance, name?, timeoutSeconds?)`

```ts
pairEddystone(identifier: string, namespace: string, instance: string, name?: string, timeoutSeconds?: number): void
```

Registers an Eddystone-UID beacon for persistent monitoring. The namespace and instance are normalized to lowercase before storage.

| Parameter | Type | Description |
|---|---|---|
| `identifier` | `string` | Unique label (e.g. `"meeting-room"`). Re-using an Eddystone identifier replaces the previous entry. |
| `namespace` | `string` | 10-byte namespace ID as hex string — must be exactly **20 hex characters** |
| `instance` | `string` | 6-byte instance ID as hex string — must be exactly **12 hex characters** |
| `name` | `string?` | Optional BLE device name for display purposes |
| `timeoutSeconds` | `number?` | Fire `onEddystoneTimeout` once, this many seconds after the beacon exits range. Cancelled if the beacon is seen again first. |

**Possible errors**: `INVALID_NAMESPACE`, `INVALID_INSTANCE`, `DUPLICATE_IDENTIFIER` (identifier already used by a paired iBeacon).

```ts
ExpoBeacon.pairEddystone("meeting-room", "edd1ebeac04e5defa017", "0123456789ab");

// With timeout — fires onEddystoneTimeout 60 s after the beacon leaves range
ExpoBeacon.pairEddystone("meeting-room", "edd1ebeac04e5defa017", "0123456789ab", undefined, 60);
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

Stops all background monitoring. On Android, stops the foreground service. Persisted monitoring options (`maxDistance`, `exitDistance`, `level`, `exitTimeoutSeconds`, …) are cleared on both platforms.

```ts
await ExpoBeacon.stopMonitoring();
```

---

### `getMonitoringConfig()`

```ts
getMonitoringConfig(): MonitoringConfig
```

Returns the current monitoring configuration snapshot, including whether background monitoring is active.

This reads the native monitoring settings currently persisted by the module. Option fields are omitted when they have not been explicitly set.

```ts
const config = ExpoBeacon.getMonitoringConfig();
// {
//   isMonitoring: true,
//   maxDistance: 10,
//   exitDistance: 15,
//   minRssi: -85,
//   level: "all"
// }
```

---

### `getMonitoredDeviceState(identifier)`

```ts
getMonitoredDeviceState(identifier: string): MonitoredDeviceState | null
```

Returns the current monitoring-state snapshot for a paired iBeacon or Eddystone with the matching identifier.

- `state` is `"entered"` or `"exited"`.
- `distance` is `null` when the device is currently exited or there is no live reading yet.
- Returns `null` when no paired device matches the identifier.

Identifiers should be unique across all paired monitored devices.

```ts
const lobby = ExpoBeacon.getMonitoredDeviceState("lobby-entrance");
// {
//   kind: "ibeacon",
//   identifier: "lobby-entrance",
//   uuid: "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
//   major: 1,
//   minor: 100,
//   state: "entered",
//   distance: 2.4
// }
```

---

### `getMonitoredDeviceStates()`

```ts
getMonitoredDeviceStates(): MonitoredDeviceState[]
```

Returns the current monitoring-state snapshots for all paired monitored devices across iBeacon and Eddystone.

```ts
const states = ExpoBeacon.getMonitoredDeviceStates();
// [
//   { kind: "ibeacon", identifier: "lobby-entrance", state: "entered", distance: 2.4, ... },
//   { kind: "eddystone", identifier: "meeting-room", state: "exited", distance: null, ... }
// ]
```

---

### `setNotificationConfig(config)`

```ts
setNotificationConfig(config: NotificationConfig): void
```

Persists notification configuration applied to **all subsequent monitoring sessions**. Survives app restarts.

For one-off overrides, pass `notifications` inside `startMonitoring(options)` instead.

See [`NotificationConfig`](#notificationconfig) for the full shape.

---

### `enableEventLogging()`

```ts
enableEventLogging(): void
```

Creates/opens the local SQLite database and starts persisting **every beacon event** (`onBeaconEnter`, `onBeaconExit`, `onBeaconDistance`, `onBeaconTimeout`, `onBeaconFound`, `onEddystoneEnter`, etc.). Call before `startMonitoring()` or `startContinuousScan()`.

```ts
ExpoBeacon.enableEventLogging();
```

---

### `disableEventLogging()`

```ts
disableEventLogging(): void
```

Stops persisting events. Previously logged data is **retained** — call `clearEventLogs()` or `destroyEventLogs()` to remove it.

```ts
ExpoBeacon.disableEventLogging();
```

---

### `getEventLogs(options?)`

```ts
getEventLogs(options?: EventLogQueryOptions): EventLogEntry[]
```

Retrieves logged events from the SQLite database, newest first.

| Property | Type | Default | Description |
|---|---|---|---|
| `limit` | `number` | `1000` | Max rows to return (capped at 10 000) |
| `eventType` | `string` | `undefined` | Filter by event name (e.g. `"onBeaconEnter"`) |
| `sinceTimestamp` | `number` | `undefined` | Only events with `timestamp >= value` (ms since epoch) |

**Returns**: `EventLogEntry[]`

```ts
const logs = ExpoBeacon.getEventLogs({ eventType: "onBeaconEnter", limit: 50 });
```

---

### `clearEventLogs()`

```ts
clearEventLogs(): void
```

Deletes all rows from the event log table. The database file remains.

```ts
ExpoBeacon.clearEventLogs();
```

---

### `destroyEventLogs()`

```ts
destroyEventLogs(): void
```

Disables logging **and** deletes the entire SQLite database file.

```ts
ExpoBeacon.destroyEventLogs();
```

---

### `setApiEndpoint(url, apiKey?, id?)`

```ts
setApiEndpoint(url: string, apiKey?: string, id?: string): void
```

Configures a remote endpoint to which native code POSTs every beacon event — delivery works even when the JS bridge is not active (app backgrounded). The configuration persists until changed.

| Parameter | Type | Description |
|---|---|---|
| `url` | `string` | The API endpoint URL to POST events to. |
| `apiKey` | `string?` | Sent as the `X-CSFR-Token` header (sic — the header is literally `X-CSFR-Token`, not `X-CSRF-Token`). |
| `id` | `string?` | Identifier appended to every forwarded event payload. |

Use `getApiEndpoint()` to read back the current configuration (each field is `null` if unset).

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
| `onBeaconTimeout` | Paired iBeacon out of range for configured `timeoutSeconds` | `BeaconTimeoutEvent` |
| `onEddystoneTimeout` | Paired Eddystone out of range for configured `timeoutSeconds` | `EddystoneTimeoutEvent` |

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

#### `onBeaconTimeout`

Fired **once**, `timeoutSeconds` after a paired iBeacon exits range (or after BLE readings stop for 60 s). Re-detection cancels the pending timer.

```ts
ExpoBeacon.addListener("onBeaconTimeout", (e) => {
  // e.identifier — "lobby-entrance"
  // e.uuid, e.major, e.minor — beacon identity
  // e.distance — usually –1 (the beacon is out of range when this fires)
  console.log(`Beacon "${e.identifier}" timeout — out of range for configured duration`);
});
```

#### `onEddystoneTimeout`

Fired **once**, `timeoutSeconds` after a paired Eddystone exits range (or after BLE readings stop for 60 s). Re-detection cancels the pending timer.

```ts
ExpoBeacon.addListener("onEddystoneTimeout", (e) => {
  // e.identifier, e.namespace, e.instance — Eddystone identity
  // e.distance — usually –1 (the beacon is out of range when this fires)
  console.log(`Eddystone "${e.identifier}" timeout`);
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
  BeaconTimeoutEvent,
  EddystoneFrameType,
  EddystoneScanResult,
  PairedEddystone,
  EddystoneRegionEvent,
  EddystoneDistanceEvent,
  EddystoneTimeoutEvent,
  BeaconErrorEvent,
  ExpoBeaconModuleEvents,
  MonitoringOptions,
  NotificationConfig,
  BeaconNotificationConfig,
  ForegroundServiceConfig,
  NotificationChannelConfig,
  EventLogQueryOptions,
  EventLogEntry,
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
  distance: number;  // Estimated distance in metres (–1 when unavailable)
  txPower: number;   // Calibrated TX power. Android only — always 0 on iOS (CoreLocation does not expose it)
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
  name?: string;           // Optional BLE device name
  timeoutSeconds?: number; // Fires onBeaconTimeout this many seconds after the beacon exits range
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
  name?: string;           // Optional BLE device name
  timeoutSeconds?: number; // Fires onEddystoneTimeout this many seconds after the beacon exits range
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
  minRssi?: number;
  level?: "all" | "events";
};
```

### `MonitoringConfig`

Returned by `getMonitoringConfig()`.

```ts
type MonitoringConfig = {
  isMonitoring: boolean;
  maxDistance?: number;
  exitDistance?: number;
  minRssi?: number;
  level?: "all" | "events";
  notifications?: NotificationConfig;
};
```

### `MonitoredDeviceState`

Returned by `getMonitoredDeviceState()` and `getMonitoredDeviceStates()`.

```ts
type MonitoredDeviceState =
  | {
      kind: "ibeacon";
      identifier: string;
      uuid: string;
      major: number;
      minor: number;
      state: "entered" | "exited";
      distance: number | null;
    }
  | {
      kind: "eddystone";
      identifier: string;
      namespace: string;
      instance: string;
      state: "entered" | "exited";
      distance: number | null;
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
  timeoutTitle?: string; // Default: "Beacon Timeout"
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

### `BeaconTimeoutEvent`

Payload for `onBeaconTimeout`.

```ts
type BeaconTimeoutEvent = {
  identifier: string;
  uuid: string;
  major: number;
  minor: number;
  distance: number; // Usually –1 (the beacon is out of range when the timeout fires)
};
```

### `EddystoneTimeoutEvent`

Payload for `onEddystoneTimeout`.

```ts
type EddystoneTimeoutEvent = {
  identifier: string;
  namespace: string;
  instance: string;
  distance: number; // Usually –1 (the beacon is out of range when the timeout fires)
};
```

### `EventLogQueryOptions`

Passed to `getEventLogs()`.

```ts
type EventLogQueryOptions = {
  limit?: number;          // Max entries (default: 1000, max: 10000)
  eventType?: string;      // Filter by event name
  sinceTimestamp?: number; // Only events after this ms-epoch timestamp
};
```

### `EventLogEntry`

Returned by `getEventLogs()`.

```ts
type EventLogEntry = {
  id: number;                     // Auto-increment row ID
  timestamp: number;              // Milliseconds since epoch
  eventType: string;              // e.g. "onBeaconEnter"
  identifier?: string;            // Beacon identifier, if available
  data: Record<string, unknown>;  // Full event payload
};
```

---

## Native Integrations

Dispatching work in response to beacon enter/exit events can be done at the native level, before the JS bridge is involved. expo-beacon exposes a plugin registry on both platforms for this purpose.

When a plugin is registered, `onBeaconEnter` / `onBeaconExit` (and their Eddystone equivalents) are called synchronously inside the same choke point that fires the JS event — so the native side-effect is guaranteed even when the JS thread is sleeping.

### react-native-background-geolocation

This integration starts BGLocation when any beacon is entered and stops it when all beacons are exited.

> **Requirement**: bare workflow or `npx expo prebuild`. Does not work with Expo Go.

#### 1. Install packages

```sh
npx expo install expo-beacon react-native-background-geolocation
```

Follow [react-native-background-geolocation's native setup](https://transistorsoft.github.io/react-native-background-geolocation) — it requires extra Gradle / CocoaPods config and a license key.

#### 2. Add the Expo config plugin

In `app.json` (or `app.config.js`), add `expo-beacon` to your plugins list:

```json
{
  "expo": {
    "plugins": [
      "expo-beacon"
    ]
  }
}
```

Then run prebuild to apply the native changes:

```sh
npx expo prebuild --clean
```

The plugin writes `BeaconGeoPlugin.swift` / `BeaconGeoPlugin.kt` into your native project and wires them up in `AppDelegate.swift` and `MainApplication.kt` automatically. It also merges `location` into the iOS `UIBackgroundModes` (required for background ranging) and registers the app as Android-Auto-aware (see [CarPlay / Android Auto Detection](#carplay--android-auto-detection)).

> **Java projects**: the `MainApplication` patch is Kotlin-only. If your project still uses `MainApplication.java`, the plugin skips the patch and you must add `BeaconPluginRegistry.register(BeaconGeoPlugin(this))` manually.

##### The `backgroundGeolocation` prop

The `BeaconGeoPlugin` generation requires `react-native-background-geolocation` to be installed — without it, the generated native files fail to compile. If you don't use that library, disable the integration with the `backgroundGeolocation` prop (default: `true`):

```json
{
  "expo": {
    "plugins": [
      ["expo-beacon", {
        "ios": { "backgroundGeolocation": false },
        "android": { "backgroundGeolocation": false }
      }]
    ]
  }
}
```

Setting it to `false` skips the `BeaconGeoPlugin.swift` / `BeaconGeoPlugin.kt` generation and the `AppDelegate.swift` / `MainApplication.kt` patches. The `UIBackgroundModes` merge and the Android Auto registration are applied regardless of this prop.

#### 3. Configure BGLocation once at JS startup

Call `ready()` once when your app starts, **not** inside a beacon callback:

```ts
import BackgroundGeolocation from 'react-native-background-geolocation';

BackgroundGeolocation.ready({
  desiredAccuracy: BackgroundGeolocation.DESIRED_ACCURACY_HIGH,
  distanceFilter: 10,
  stopOnTerminate: false,
  startOnBoot: true,
  // ...your config
});
```

#### How it works at runtime

```
Beacon region entered (native)
  → BeaconForegroundService / ExpoBeaconModule (expo-beacon)
  → BeaconPluginRegistry / BeaconLifecycleRegistry dispatches to plugins
  → BeaconGeoPlugin.onBeaconEnter / beaconDidEnter
  → BackgroundGeolocation.start()   ← native only, no JS bridge involved
```

---

#### Manual wiring (without the config plugin)

If you prefer not to use `expo prebuild` (e.g. you manage your native project manually), create the following files yourself after each `npx expo prebuild`:

**iOS** — `ios/<AppName>/BeaconGeoPlugin.swift` (add to Xcode target):

```swift
import ExpoBeacon
import TSLocationManager

final class BeaconGeoPlugin: BeaconLifecycleDelegate {
  private func startTracking() {
    BackgroundGeolocation.sharedInstance().start()
    BackgroundGeolocation.sharedInstance().changePace(true)
  }

  private func stopTracking() {
    BackgroundGeolocation.sharedInstance().changePace(false)
    BackgroundGeolocation.sharedInstance().stop()
  }

  func beaconDidEnter(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
    startTracking()
  }
  func beaconDidExit(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
    stopTracking()
  }
  func beaconDidTimeout(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
    stopTracking()
  }
  func eddystoneDidEnter(identifier: String, namespace: String, instance: String, distance: Double) {
    startTracking()
  }
  func eddystoneDidExit(identifier: String, namespace: String, instance: String, distance: Double) {
    stopTracking()
  }
  func eddystoneDidTimeout(identifier: String, namespace: String, instance: String, distance: Double) {
    stopTracking()
  }
  // Start tracking when the device connects to CarPlay (wired or wireless),
  // stop when it disconnects.
  func carPlayDidConnect(transport: String) {
    startTracking()
  }
  func carPlayDidDisconnect() {
    stopTracking()
  }
}
```

Register in `ios/<AppName>/AppDelegate.swift` **before** `super`:

```swift
import ExpoBeacon

// in application(_:didFinishLaunchingWithOptions:):
BeaconLifecycleRegistry.register(BeaconGeoPlugin()) // ← before super
return super.application(application, didFinishLaunchingWithOptions: launchOptions)
```

**Android** — `android/app/src/main/java/<pkg>/BeaconGeoPlugin.kt`:

```kotlin
package com.yourapp

import android.content.Context
import com.transistorsoft.locationmanager.adapter.BackgroundGeolocation
import com.transistorsoft.locationmanager.adapter.callback.TSCallback
import expo.modules.beacon.BeaconEventPlugin

class BeaconGeoPlugin(ctx: Context) : BeaconEventPlugin {
    private val bgGeo = BackgroundGeolocation.getInstance(ctx, null)
    private val noOp = object : TSCallback {
        override fun onSuccess() {}
        override fun onFailure(error: String) {}
    }

    override fun onBeaconEnter(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) =
        bgGeo.start(noOp)
    override fun onBeaconExit(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) =
        bgGeo.stop(noOp)
    override fun onBeaconTimeout(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) =
        bgGeo.stop(noOp)
    override fun onEddystoneEnter(identifier: String, namespace: String, instance: String, distance: Double) =
        bgGeo.start(noOp)
    override fun onEddystoneExit(identifier: String, namespace: String, instance: String, distance: Double) =
        bgGeo.stop(noOp)
    override fun onEddystoneTimeout(identifier: String, namespace: String, instance: String, distance: Double) =
        bgGeo.stop(noOp)
    // Start tracking when the device connects to Android Auto, stop when it disconnects.
    override fun onCarPlayConnected(transport: String) {
        bgGeo.start(noOp)
    }
    override fun onCarPlayDisconnected() {
        bgGeo.stop(noOp)
    }
}
```

Register in `MainApplication.kt` inside `onCreate()` after `super`:

```kotlin
import expo.modules.beacon.BeaconPluginRegistry

override fun onCreate() {
    super.onCreate()
    BeaconPluginRegistry.register(BeaconGeoPlugin(this)) // ← after super
}
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
| Background modes | `allowsBackgroundLocationUpdates` is only enabled when `UIBackgroundModes` contains `location` (the config plugin adds it on prebuild); `pausesLocationUpdatesAutomatically = false` |
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

2. **Two-step location permission**: iOS requires requesting "When In Use" first, then upgrading to "Always". `requestPermissionsAsync()` requests (and resolves `true` with) "When In Use"; the "Always" upgrade prompt is triggered by `startMonitoring()`.

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
| `DUPLICATE_IDENTIFIER` | `pairBeacon`, `pairEddystone` | The identifier is already used by a paired beacon of the other type. |
| `PERMISSION_DENIED` | `scanForBeaconsAsync`, `startMonitoring` | Required permissions were not granted. |
| `WILDCARD_NOT_SUPPORTED` | `scanForBeaconsAsync` | iOS only: no UUIDs provided and no paired beacons exist. |

---

## Contributing

Contributions are welcome! Open an issue or pull request on [GitHub](https://github.com/martinmikesCCS/expo-beacon).

## License

MIT
