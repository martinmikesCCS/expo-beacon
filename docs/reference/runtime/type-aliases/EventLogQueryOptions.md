[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / EventLogQueryOptions

# Type Alias: EventLogQueryOptions

> **EventLogQueryOptions** = `object`

Defined in: [src/ExpoBeacon.types.ts:360](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L360)

Options for filtering event logs.

## Properties

### eventType?

> `optional` **eventType?**: [`BeaconEventName`](BeaconEventName.md)

Defined in: [src/ExpoBeacon.types.ts:364](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L364)

Filter by an emitted event type.

***

### limit?

> `optional` **limit?**: `number`

Defined in: [src/ExpoBeacon.types.ts:362](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L362)

Maximum number of log entries to return (default: 1000, max: 10000).

***

### sinceTimestamp?

> `optional` **sinceTimestamp?**: `number`

Defined in: [src/ExpoBeacon.types.ts:366](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L366)

Only return events with timestamp >= this value (ms since epoch).
