[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / ExpoBeaconModuleEvents

# Type Alias: ExpoBeaconModuleEvents

> **ExpoBeaconModuleEvents** = `object`

Defined in: [src/ExpoBeacon.types.ts:337](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L337)

Module event map.

## Properties

### onBeaconDistance

> **onBeaconDistance**: (`params`) => `void`

Defined in: [src/ExpoBeacon.types.ts:340](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L340)

#### Parameters

##### params

[`BeaconDistanceEvent`](BeaconDistanceEvent.md)

#### Returns

`void`

***

### onBeaconEnter

> **onBeaconEnter**: (`params`) => `void`

Defined in: [src/ExpoBeacon.types.ts:338](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L338)

#### Parameters

##### params

[`BeaconRegionEvent`](BeaconRegionEvent.md)

#### Returns

`void`

***

### onBeaconError

> **onBeaconError**: (`params`) => `void`

Defined in: [src/ExpoBeacon.types.ts:353](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L353)

Fired when a native monitoring or ranging failure occurs (logged to DB and forwarded to JS).

#### Parameters

##### params

[`BeaconErrorEvent`](BeaconErrorEvent.md)

#### Returns

`void`

***

### onBeaconExit

> **onBeaconExit**: (`params`) => `void`

Defined in: [src/ExpoBeacon.types.ts:339](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L339)

#### Parameters

##### params

[`BeaconRegionEvent`](BeaconRegionEvent.md)

#### Returns

`void`

***

### onBeaconFound

> **onBeaconFound**: (`params`) => `void`

Defined in: [src/ExpoBeacon.types.ts:344](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L344)

Fired continuously during a live scan as each iBeacon is detected.

#### Parameters

##### params

[`BeaconScanResult`](BeaconScanResult.md)

#### Returns

`void`

***

### onBeaconTimeout

> **onBeaconTimeout**: (`params`) => `void`

Defined in: [src/ExpoBeacon.types.ts:342](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L342)

Fired once `timeoutSeconds` after a paired beacon exits range (cancelled if the beacon is seen again first).

#### Parameters

##### params

[`BeaconTimeoutEvent`](BeaconTimeoutEvent.md)

#### Returns

`void`

***

### onEddystoneDistance

> **onEddystoneDistance**: (`params`) => `void`

Defined in: [src/ExpoBeacon.types.ts:349](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L349)

#### Parameters

##### params

[`EddystoneDistanceEvent`](EddystoneDistanceEvent.md)

#### Returns

`void`

***

### onEddystoneEnter

> **onEddystoneEnter**: (`params`) => `void`

Defined in: [src/ExpoBeacon.types.ts:347](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L347)

#### Parameters

##### params

[`EddystoneRegionEvent`](EddystoneRegionEvent.md)

#### Returns

`void`

***

### onEddystoneExit

> **onEddystoneExit**: (`params`) => `void`

Defined in: [src/ExpoBeacon.types.ts:348](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L348)

#### Parameters

##### params

[`EddystoneRegionEvent`](EddystoneRegionEvent.md)

#### Returns

`void`

***

### onEddystoneFound

> **onEddystoneFound**: (`params`) => `void`

Defined in: [src/ExpoBeacon.types.ts:346](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L346)

Fired continuously during a live scan as each Eddystone beacon is detected.

#### Parameters

##### params

[`EddystoneScanResult`](EddystoneScanResult.md)

#### Returns

`void`

***

### onEddystoneTimeout

> **onEddystoneTimeout**: (`params`) => `void`

Defined in: [src/ExpoBeacon.types.ts:351](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L351)

Fired once `timeoutSeconds` after a paired Eddystone exits range (cancelled if the beacon is seen again first).

#### Parameters

##### params

[`EddystoneTimeoutEvent`](EddystoneTimeoutEvent.md)

#### Returns

`void`
