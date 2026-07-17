# Scanning

## One-shot iBeacon scan

```ts
import { scanForBeacons } from "expo-beacon";

const beacons = await scanForBeacons({
  uuids: ["E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"],
  durationMs: 5_000,
});
```

- `durationMs` defaults to `5000` and must be greater than zero.
- iOS requires at least one UUID, either in this call or from a paired iBeacon.
- Android treats an empty or omitted UUID list as a wildcard scan.
- Call `ExpoBeacon.cancelScan()` to reject an active one-shot scan with
  `SCAN_CANCELLED`.

## One-shot Eddystone scan

```ts
import { scanForEddystones } from "expo-beacon";

const frames = await scanForEddystones({ durationMs: 5_000 });
```

This discovers Eddystone-UID and Eddystone-URL frames. It does not take an
iBeacon UUID filter.

## Continuous scan

```ts
import { ExpoBeacon } from "expo-beacon";

const iBeaconSubscription = ExpoBeacon.addListener(
  "onBeaconFound",
  (beacon) => {
    console.log(beacon.uuid, beacon.major, beacon.minor);
  },
);
const eddystoneSubscription = ExpoBeacon.addListener(
  "onEddystoneFound",
  (frame) => console.log(frame.frameType, frame),
);

ExpoBeacon.startContinuousScan();

// Later:
ExpoBeacon.stopContinuousScan();
iBeaconSubscription.remove();
eddystoneSubscription.remove();
```

On iOS, continuous iBeacon discovery only ranges UUIDs from paired iBeacons.
Eddystone discovery still works without pairing.

## Result units

- `distance` is an estimate in metres and is `-1` when unavailable.
- `rssi` is signal strength in dBm.
- `txPower` is calibrated transmit power; Core Location does not expose it for
  iBeacon on iOS, where the value is `0`.
