[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / EddystoneRegionEvent

# Type Alias: EddystoneRegionEvent

> **EddystoneRegionEvent** = `object`

Defined in: [src/ExpoBeacon.types.ts:260](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L260)

Payload for Eddystone enter/exit region events.

## Properties

### distance

> **distance**: `number`

Defined in: [src/ExpoBeacon.types.ts:266](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L266)

Measured distance in metres at the time of the event (–1 if unavailable).

***

### event

> **event**: `"enter"` \| `"exit"`

Defined in: [src/ExpoBeacon.types.ts:264](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L264)

***

### identifier

> **identifier**: `string`

Defined in: [src/ExpoBeacon.types.ts:261](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L261)

***

### instance

> **instance**: `string`

Defined in: [src/ExpoBeacon.types.ts:263](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L263)

***

### namespace

> **namespace**: `string`

Defined in: [src/ExpoBeacon.types.ts:262](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L262)

***

### rssi?

> `optional` **rssi?**: `number`

Defined in: [src/ExpoBeacon.types.ts:268](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L268)

Signal strength in dBm at the time of the event (0 if unavailable).
