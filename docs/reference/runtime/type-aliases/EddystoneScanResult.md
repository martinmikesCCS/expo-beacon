[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / EddystoneScanResult

# Type Alias: EddystoneScanResult

> **EddystoneScanResult** = `object`

Defined in: [src/ExpoBeacon.types.ts:220](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L220)

Raw Eddystone beacon discovered during a scan.

## Properties

### distance

> **distance**: `number`

Defined in: [src/ExpoBeacon.types.ts:229](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L229)

***

### frameType

> **frameType**: [`EddystoneFrameType`](EddystoneFrameType.md)

Defined in: [src/ExpoBeacon.types.ts:221](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L221)

***

### instance?

> `optional` **instance?**: `string`

Defined in: [src/ExpoBeacon.types.ts:225](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L225)

6-byte instance ID as hex string (12 chars). Present for UID frames.

***

### name?

> `optional` **name?**: `string`

Defined in: [src/ExpoBeacon.types.ts:232](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L232)

BLE advertising device name.

***

### namespace?

> `optional` **namespace?**: `string`

Defined in: [src/ExpoBeacon.types.ts:223](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L223)

10-byte namespace ID as hex string (20 chars). Present for UID frames.

***

### rssi

> **rssi**: `number`

Defined in: [src/ExpoBeacon.types.ts:228](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L228)

***

### txPower

> **txPower**: `number`

Defined in: [src/ExpoBeacon.types.ts:230](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L230)

***

### url?

> `optional` **url?**: `string`

Defined in: [src/ExpoBeacon.types.ts:227](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L227)

Decoded URL. Present for URL frames.
