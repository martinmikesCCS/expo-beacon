[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / PairedEddystone

# Type Alias: PairedEddystone

> **PairedEddystone** = `object`

Defined in: [src/ExpoBeacon.types.ts:241](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L241)

An Eddystone-UID beacon that has been paired/registered for monitoring.

Note: Paired beacon data is stored unencrypted in UserDefaults (iOS) /
SharedPreferences (Android) and may be included in device backups.

## Properties

### identifier

> **identifier**: `string`

Defined in: [src/ExpoBeacon.types.ts:242](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L242)

***

### instance

> **instance**: `string`

Defined in: [src/ExpoBeacon.types.ts:246](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L246)

6-byte instance ID as hex string (12 chars).

***

### name?

> `optional` **name?**: `string`

Defined in: [src/ExpoBeacon.types.ts:248](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L248)

BLE advertising device name, if provided at pairing time.

***

### namespace

> **namespace**: `string`

Defined in: [src/ExpoBeacon.types.ts:244](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L244)

10-byte namespace ID as hex string (20 chars).

***

### timeoutSeconds?

> `optional` **timeoutSeconds?**: `number`

Defined in: [src/ExpoBeacon.types.ts:256](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L256)

Timeout in seconds. When set, the module fires `onEddystoneTimeout` once,
this many seconds after the beacon exits range. The countdown is armed on
exit — or when no BLE readings arrive for 60 seconds (e.g. due to Doze
mode or background throttling) — and is cancelled if the beacon is seen
again before it fires.
