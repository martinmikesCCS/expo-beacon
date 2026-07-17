[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / PairEddystoneOptions

# Type Alias: PairEddystoneOptions

> **PairEddystoneOptions** = `object`

Defined in: [src/helpers.ts:41](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L41)

An Eddystone-UID registration used for persistent monitoring.

## Properties

### identifier

> **identifier**: `string`

Defined in: [src/helpers.ts:43](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L43)

Stable application-defined identifier, for example `meeting-room`.

***

### instance

> **instance**: `string`

Defined in: [src/helpers.ts:47](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L47)

Six-byte instance ID encoded as exactly 12 hexadecimal characters.

***

### name?

> `optional` **name?**: `string`

Defined in: [src/helpers.ts:49](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L49)

Optional display name stored with the registration.

***

### namespace

> **namespace**: `string`

Defined in: [src/helpers.ts:45](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L45)

Ten-byte namespace ID encoded as exactly 20 hexadecimal characters.

***

### timeoutSeconds?

> `optional` **timeoutSeconds?**: `number`

Defined in: [src/helpers.ts:51](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L51)

Optional out-of-range timeout in seconds. Must be greater than zero.
