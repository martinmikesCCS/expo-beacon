[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / BeaconScanResult

# Type Alias: BeaconScanResult

> **BeaconScanResult** = `object`

Defined in: [src/ExpoBeacon.types.ts:2](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L2)

Raw beacon discovered during a scan.

## Properties

### distance

> **distance**: `number`

Defined in: [src/ExpoBeacon.types.ts:7](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L7)

***

### major

> **major**: `number`

Defined in: [src/ExpoBeacon.types.ts:4](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L4)

***

### minor

> **minor**: `number`

Defined in: [src/ExpoBeacon.types.ts:5](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L5)

***

### name?

> `optional` **name?**: `string`

Defined in: [src/ExpoBeacon.types.ts:10](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L10)

BLE advertising device name. May be undefined on iOS (CoreLocation does not expose it for iBeacon).

***

### rssi

> **rssi**: `number`

Defined in: [src/ExpoBeacon.types.ts:6](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L6)

***

### txPower

> **txPower**: `number`

Defined in: [src/ExpoBeacon.types.ts:8](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L8)

***

### uuid

> **uuid**: `string`

Defined in: [src/ExpoBeacon.types.ts:3](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L3)
