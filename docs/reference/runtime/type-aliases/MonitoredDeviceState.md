[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / MonitoredDeviceState

# Type Alias: MonitoredDeviceState

> **MonitoredDeviceState** = \{ `distance`: `number` \| `null`; `identifier`: `string`; `kind`: `"ibeacon"`; `major`: `number`; `minor`: `number`; `state`: `"entered"` \| `"exited"`; `uuid`: `string`; \} \| \{ `distance`: `number` \| `null`; `identifier`: `string`; `instance`: `string`; `kind`: `"eddystone"`; `namespace`: `string`; `state`: `"entered"` \| `"exited"`; \}

Defined in: [src/ExpoBeacon.types.ts:150](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L150)

Current state snapshot for a paired monitored device.

## Union Members

### Type Literal

\{ `distance`: `number` \| `null`; `identifier`: `string`; `kind`: `"ibeacon"`; `major`: `number`; `minor`: `number`; `state`: `"entered"` \| `"exited"`; `uuid`: `string`; \}

#### distance

> **distance**: `number` \| `null`

Current distance in metres, or null when exited or no live reading is available.

#### identifier

> **identifier**: `string`

#### kind

> **kind**: `"ibeacon"`

#### major

> **major**: `number`

#### minor

> **minor**: `number`

#### state

> **state**: `"entered"` \| `"exited"`

#### uuid

> **uuid**: `string`

***

### Type Literal

\{ `distance`: `number` \| `null`; `identifier`: `string`; `instance`: `string`; `kind`: `"eddystone"`; `namespace`: `string`; `state`: `"entered"` \| `"exited"`; \}

#### distance

> **distance**: `number` \| `null`

Current distance in metres, or null when exited or no live reading is available.

#### identifier

> **identifier**: `string`

#### instance

> **instance**: `string`

#### kind

> **kind**: `"eddystone"`

#### namespace

> **namespace**: `string`

#### state

> **state**: `"entered"` \| `"exited"`
