[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / BeaconRegionEvent

# Type Alias: BeaconRegionEvent

> **BeaconRegionEvent** = `object`

Defined in: [src/ExpoBeacon.types.ts:37](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L37)

Payload for enter/exit region events.

## Properties

### distance

> **distance**: `number`

Defined in: [src/ExpoBeacon.types.ts:44](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L44)

Measured distance in metres at the time of the event (–1 if unavailable).

***

### event

> **event**: `"enter"` \| `"exit"`

Defined in: [src/ExpoBeacon.types.ts:42](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L42)

***

### identifier

> **identifier**: `string`

Defined in: [src/ExpoBeacon.types.ts:38](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L38)

***

### major

> **major**: `number`

Defined in: [src/ExpoBeacon.types.ts:40](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L40)

***

### minor

> **minor**: `number`

Defined in: [src/ExpoBeacon.types.ts:41](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L41)

***

### rssi?

> `optional` **rssi?**: `number`

Defined in: [src/ExpoBeacon.types.ts:46](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L46)

Signal strength in dBm at the time of the event (0 if unavailable).

***

### uuid

> **uuid**: `string`

Defined in: [src/ExpoBeacon.types.ts:39](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L39)
