[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / PairBeaconOptions

# Type Alias: PairBeaconOptions

> **PairBeaconOptions** = `object`

Defined in: [src/helpers.ts:25](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L25)

An iBeacon registration used for persistent monitoring.

## Properties

### identifier

> **identifier**: `string`

Defined in: [src/helpers.ts:27](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L27)

Stable application-defined identifier, for example `lobby-door`.

***

### major

> **major**: `number`

Defined in: [src/helpers.ts:31](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L31)

iBeacon major value from 0 through 65535.

***

### minor

> **minor**: `number`

Defined in: [src/helpers.ts:33](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L33)

iBeacon minor value from 0 through 65535.

***

### name?

> `optional` **name?**: `string`

Defined in: [src/helpers.ts:35](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L35)

Optional display name stored with the registration.

***

### timeoutSeconds?

> `optional` **timeoutSeconds?**: `number`

Defined in: [src/helpers.ts:37](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L37)

Optional out-of-range timeout in seconds. Must be greater than zero.

***

### uuid

> **uuid**: `string`

Defined in: [src/helpers.ts:29](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L29)

iBeacon proximity UUID.
