[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / ScanForBeaconsOptions

# Type Alias: ScanForBeaconsOptions

> **ScanForBeaconsOptions** = `object`

Defined in: [src/helpers.ts:5](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L5)

Options for a one-shot iBeacon scan.

## Properties

### durationMs?

> `optional` **durationMs?**: `number`

Defined in: [src/helpers.ts:15](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L15)

Scan duration in milliseconds.

#### Default Value

```ts
5000
```

***

### uuids?

> `optional` **uuids?**: readonly `string`[]

Defined in: [src/helpers.ts:13](https://github.com/martinmikesccs/expo-beacon/blob/master/src/helpers.ts#L13)

Proximity UUIDs to scan for.

On iOS, an empty or omitted list uses UUIDs from paired beacons and throws
`WILDCARD_NOT_SUPPORTED` when no paired UUID is available. On Android, an
empty or omitted list performs a wildcard scan.
