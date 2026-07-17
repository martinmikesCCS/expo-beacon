[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / BeaconErrorEvent

# Type Alias: BeaconErrorEvent

> **BeaconErrorEvent** = `object`

Defined in: [src/ExpoBeacon.types.ts:327](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L327)

Payload for native beacon error events (monitoring/ranging failures).

## Properties

### code

> **code**: [`BeaconErrorCode`](BeaconErrorCode.md)

Defined in: [src/ExpoBeacon.types.ts:331](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L331)

Machine-readable error code.

***

### identifier

> **identifier**: `string`

Defined in: [src/ExpoBeacon.types.ts:329](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L329)

Region or constraint identifier, empty string if unavailable.

***

### message

> **message**: `string`

Defined in: [src/ExpoBeacon.types.ts:333](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L333)

Human-readable error message from the native layer.
