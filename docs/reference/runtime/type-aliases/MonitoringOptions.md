[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / MonitoringOptions

# Type Alias: MonitoringOptions

> **MonitoringOptions** = `object`

Defined in: [src/ExpoBeacon.types.ts:172](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L172)

Options accepted by startMonitoring().

## Properties

### exitDistance?

> `optional` **exitDistance?**: `number`

Defined in: [src/ExpoBeacon.types.ts:186](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L186)

Distance in metres at which exit events fire (must be ≥ maxDistance).
Creates a hysteresis band between enter and exit thresholds to prevent
rapid toggling near the boundary.

Default when omitted: `maxDistance + min(maxDistance × 0.5, 2.5)`.
Only used when `maxDistance` is set.

***

### exitTimeoutSeconds?

> `optional` **exitTimeoutSeconds?**: `number`

Defined in: [src/ExpoBeacon.types.ts:211](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L211)

Seconds after last beacon sighting before an exit event fires when the beacon
disappears without moving outside the exit distance threshold.

Default: 300 (5 minutes). Minimum: 1.

***

### level?

> `optional` **level?**: `"all"` \| `"events"`

Defined in: [src/ExpoBeacon.types.ts:204](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L204)

Controls which event types are emitted, logged, and forwarded to the API.

- `'all'` (default): distance + enter + exit + timeout events.
- `'events'`: enter + exit + timeout only (no distance events).

***

### maxDistance?

> `optional` **maxDistance?**: `number`

Defined in: [src/ExpoBeacon.types.ts:177](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L177)

Maximum distance in metres for distance-based enter events.
Exit events are always emitted when the region is lost.

***

### minRssi?

> `optional` **minRssi?**: `number`

Defined in: [src/ExpoBeacon.types.ts:197](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L197)

Minimum RSSI (dBm) for a beacon reading to be considered valid.
Readings below this threshold are discarded as unreliable, preventing
false detections from reflected or distant signals.

Applies to monitoring readings only — one-shot scan results are not
filtered.

Default: -85. Typical range: -100 (very permissive) to -70 (strict).

***

### notifications?

> `optional` **notifications?**: [`NotificationConfig`](NotificationConfig.md)

Defined in: [src/ExpoBeacon.types.ts:213](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/ExpoBeacon.types.ts#L213)

Notification configuration overrides to apply for this monitoring session.
