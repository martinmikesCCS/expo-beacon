[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / EventLogEntry

# Type Alias: EventLogEntry

> **EventLogEntry** = `object`

Defined in: [src/ExpoBeacon.types.ts:370](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L370)

A single logged beacon event entry.

## Properties

### data

> **data**: `Record`\<`string`, `unknown`\>

Defined in: [src/ExpoBeacon.types.ts:379](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L379)

The full event payload that was sent to JS.

***

### eventType

> **eventType**: [`BeaconEventName`](BeaconEventName.md)

Defined in: [src/ExpoBeacon.types.ts:375](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L375)

The event type that was logged.

***

### id

> **id**: `number`

Defined in: [src/ExpoBeacon.types.ts:371](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L371)

***

### identifier?

> `optional` **identifier?**: `string`

Defined in: [src/ExpoBeacon.types.ts:377](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L377)

Beacon identifier, if available.

***

### timestamp

> **timestamp**: `number`

Defined in: [src/ExpoBeacon.types.ts:373](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L373)

Timestamp in milliseconds since epoch.
