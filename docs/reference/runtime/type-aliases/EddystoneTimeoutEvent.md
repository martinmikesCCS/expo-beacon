[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / EddystoneTimeoutEvent

# Type Alias: EddystoneTimeoutEvent

> **EddystoneTimeoutEvent** = `object`

Defined in: [src/ExpoBeacon.types.ts:282](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L282)

Payload for Eddystone timeout events (beacon out of range for the configured duration).

## Properties

### distance

> **distance**: `number`

Defined in: [src/ExpoBeacon.types.ts:287](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L287)

Distance in metres at the time the timeout fired. Usually –1, since the beacon is out of range when this fires.

***

### identifier

> **identifier**: `string`

Defined in: [src/ExpoBeacon.types.ts:283](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L283)

***

### instance

> **instance**: `string`

Defined in: [src/ExpoBeacon.types.ts:285](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L285)

***

### namespace

> **namespace**: `string`

Defined in: [src/ExpoBeacon.types.ts:284](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L284)
