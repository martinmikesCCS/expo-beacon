[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / BeaconTimeoutEvent

# Type Alias: BeaconTimeoutEvent

> **BeaconTimeoutEvent** = `object`

Defined in: [src/ExpoBeacon.types.ts:61](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L61)

Payload for beacon timeout events (beacon out of range for the configured duration).

## Properties

### distance

> **distance**: `number`

Defined in: [src/ExpoBeacon.types.ts:67](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L67)

Distance in metres at the time the timeout fired. Usually –1, since the beacon is out of range when this fires.

***

### identifier

> **identifier**: `string`

Defined in: [src/ExpoBeacon.types.ts:62](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L62)

***

### major

> **major**: `number`

Defined in: [src/ExpoBeacon.types.ts:64](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L64)

***

### minor

> **minor**: `number`

Defined in: [src/ExpoBeacon.types.ts:65](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L65)

***

### uuid

> **uuid**: `string`

Defined in: [src/ExpoBeacon.types.ts:63](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L63)
