[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / UseBeaconOptions

# Interface: UseBeaconOptions

Defined in: [src/hooks/useBeacon.ts:57](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L57)

## Properties

### onBeaconDistance?

> `optional` **onBeaconDistance?**: (`event`) => `void`

Defined in: [src/hooks/useBeacon.ts:60](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L60)

#### Parameters

##### event

[`BeaconDistanceEvent`](../type-aliases/BeaconDistanceEvent.md)

#### Returns

`void`

***

### onBeaconEnter?

> `optional` **onBeaconEnter?**: (`event`) => `void`

Defined in: [src/hooks/useBeacon.ts:58](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L58)

#### Parameters

##### event

[`BeaconRegionEvent`](../type-aliases/BeaconRegionEvent.md)

#### Returns

`void`

***

### onBeaconExit?

> `optional` **onBeaconExit?**: (`event`) => `void`

Defined in: [src/hooks/useBeacon.ts:59](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L59)

#### Parameters

##### event

[`BeaconRegionEvent`](../type-aliases/BeaconRegionEvent.md)

#### Returns

`void`

***

### onBeaconFound?

> `optional` **onBeaconFound?**: (`event`) => `void`

Defined in: [src/hooks/useBeacon.ts:63](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L63)

Fired for each iBeacon seen during a continuous scan (see `startContinuousScan`).

#### Parameters

##### event

[`BeaconScanResult`](../type-aliases/BeaconScanResult.md)

#### Returns

`void`

***

### onBeaconTimeout?

> `optional` **onBeaconTimeout?**: (`event`) => `void`

Defined in: [src/hooks/useBeacon.ts:61](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L61)

#### Parameters

##### event

[`BeaconTimeoutEvent`](../type-aliases/BeaconTimeoutEvent.md)

#### Returns

`void`

***

### onEddystoneDistance?

> `optional` **onEddystoneDistance?**: (`event`) => `void`

Defined in: [src/hooks/useBeacon.ts:66](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L66)

#### Parameters

##### event

[`EddystoneDistanceEvent`](../type-aliases/EddystoneDistanceEvent.md)

#### Returns

`void`

***

### onEddystoneEnter?

> `optional` **onEddystoneEnter?**: (`event`) => `void`

Defined in: [src/hooks/useBeacon.ts:64](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L64)

#### Parameters

##### event

[`EddystoneRegionEvent`](../type-aliases/EddystoneRegionEvent.md)

#### Returns

`void`

***

### onEddystoneExit?

> `optional` **onEddystoneExit?**: (`event`) => `void`

Defined in: [src/hooks/useBeacon.ts:65](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L65)

#### Parameters

##### event

[`EddystoneRegionEvent`](../type-aliases/EddystoneRegionEvent.md)

#### Returns

`void`

***

### onEddystoneFound?

> `optional` **onEddystoneFound?**: (`event`) => `void`

Defined in: [src/hooks/useBeacon.ts:69](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L69)

Fired for each Eddystone seen during a continuous scan (see `startContinuousScan`).

#### Parameters

##### event

[`EddystoneScanResult`](../type-aliases/EddystoneScanResult.md)

#### Returns

`void`

***

### onEddystoneTimeout?

> `optional` **onEddystoneTimeout?**: (`event`) => `void`

Defined in: [src/hooks/useBeacon.ts:67](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L67)

#### Parameters

##### event

[`EddystoneTimeoutEvent`](../type-aliases/EddystoneTimeoutEvent.md)

#### Returns

`void`

***

### onError?

> `optional` **onError?**: (`event`) => `void`

Defined in: [src/hooks/useBeacon.ts:71](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L71)

Fired on native monitoring / ranging failures.

#### Parameters

##### event

[`BeaconErrorEvent`](../type-aliases/BeaconErrorEvent.md)

#### Returns

`void`

***

### track?

> `optional` **track?**: `boolean`

Defined in: [src/hooks/useBeacon.ts:76](https://github.com/martinmikesccs/expo-beacon/blob/master/src/hooks/useBeacon.ts#L76)

Maintain the reactive `inRange` list from monitoring events. Set to `false`
if you only want the event callbacks above. Default: `true`.
