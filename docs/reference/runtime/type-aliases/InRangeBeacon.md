[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / InRangeBeacon

# Type Alias: InRangeBeacon

> **InRangeBeacon** = \{ `distance`: `number`; `identifier`: `string`; `kind`: `"ibeacon"`; `lastSeen`: `number`; `major`: `number`; `minor`: `number`; `rssi?`: `number`; `uuid`: `string`; \} \| \{ `distance`: `number`; `identifier`: `string`; `instance`: `string`; `kind`: `"eddystone"`; `lastSeen`: `number`; `namespace`: `string`; `rssi?`: `number`; \}

Defined in: [src/hooks/useBeacon.ts:30](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L30)

A monitored beacon that is currently within range, as tracked by
[useBeacon](../functions/useBeacon.md) from live enter / exit / distance / timeout events.

## Union Members

### Type Literal

\{ `distance`: `number`; `identifier`: `string`; `kind`: `"ibeacon"`; `lastSeen`: `number`; `major`: `number`; `minor`: `number`; `rssi?`: `number`; `uuid`: `string`; \}

#### distance

> **distance**: `number`

Latest measured distance in metres (-1 when unavailable).

#### identifier

> **identifier**: `string`

#### kind

> **kind**: `"ibeacon"`

#### lastSeen

> **lastSeen**: `number`

Epoch ms of the most recent reading for this beacon.

#### major

> **major**: `number`

#### minor

> **minor**: `number`

#### rssi?

> `optional` **rssi?**: `number`

Latest RSSI in dBm, when reported by the event.

#### uuid

> **uuid**: `string`

***

### Type Literal

\{ `distance`: `number`; `identifier`: `string`; `instance`: `string`; `kind`: `"eddystone"`; `lastSeen`: `number`; `namespace`: `string`; `rssi?`: `number`; \}

#### distance

> **distance**: `number`

Latest measured distance in metres (-1 when unavailable).

#### identifier

> **identifier**: `string`

#### instance

> **instance**: `string`

#### kind

> **kind**: `"eddystone"`

#### lastSeen

> **lastSeen**: `number`

Epoch ms of the most recent reading for this beacon.

#### namespace

> **namespace**: `string`

#### rssi?

> `optional` **rssi?**: `number`

Latest RSSI in dBm, when reported by the event.
