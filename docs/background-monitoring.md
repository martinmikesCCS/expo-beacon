# Background monitoring

Monitoring operates on paired devices. Pair first, subscribe to events, then
start monitoring.

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
const exited = ExpoBeacon.addListener("onBeaconExit", (event) => {
  console.log("exited", event.identifier);
});

await ExpoBeacon.startMonitoring({
  maxDistance: 10,
  exitDistance: 12.5,
  minRssi: -85,
  level: "events",
  exitTimeoutSeconds: 300,
});

// When monitoring is no longer needed:
await ExpoBeacon.stopMonitoring();
entered.remove();
exited.remove();
```

Use `pairEddystone` for Eddystone-UID devices. Application-defined identifiers
must be unique across both beacon kinds.

## Monitoring options

- `maxDistance`: enter threshold in metres.
- `exitDistance`: exit threshold in metres; requires `maxDistance` and cannot
  be smaller. A larger value creates a hysteresis band.
- `minRssi`: weakest accepted monitoring reading in dBm. Default: `-85`.
- `level`: `all` emits distance and lifecycle events; `events` omits periodic
  distance events.
- `exitTimeoutSeconds`: disappearance timeout. Default: `300`.
- `notifications`: overrides persisted notification settings for this session.

Android uses a foreground service with a persistent notification. iOS uses
Core Location region monitoring for iBeacon and Core Bluetooth for Eddystone.
iOS permits at most 20 monitored Core Location regions across the entire app;
Eddystone devices do not consume those slots.
