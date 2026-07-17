[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / PairedBeacon

# Type Alias: PairedBeacon

> **PairedBeacon** = `object`

Defined in: [src/ExpoBeacon.types.ts:19](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L19)

A beacon that has been paired/registered for monitoring.

Note: Paired beacon data is stored unencrypted in UserDefaults (iOS) /
SharedPreferences (Android) and may be included in device backups.

## Properties

### identifier

> **identifier**: `string`

Defined in: [src/ExpoBeacon.types.ts:20](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L20)

***

### major

> **major**: `number`

Defined in: [src/ExpoBeacon.types.ts:22](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L22)

***

### minor

> **minor**: `number`

Defined in: [src/ExpoBeacon.types.ts:23](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L23)

***

### name?

> `optional` **name?**: `string`

Defined in: [src/ExpoBeacon.types.ts:25](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L25)

BLE advertising device name, if provided at pairing time.

***

### timeoutSeconds?

> `optional` **timeoutSeconds?**: `number`

Defined in: [src/ExpoBeacon.types.ts:33](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L33)

Timeout in seconds. When set, the module fires `onBeaconTimeout` once,
this many seconds after the beacon exits range. The countdown is armed on
exit — or when no BLE readings arrive for 60 seconds (e.g. due to Doze
mode or background throttling) — and is cancelled if the beacon is seen
again before it fires.

***

### uuid

> **uuid**: `string`

Defined in: [src/ExpoBeacon.types.ts:21](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L21)
