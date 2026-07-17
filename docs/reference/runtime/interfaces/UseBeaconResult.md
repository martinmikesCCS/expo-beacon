[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / UseBeaconResult

# Interface: UseBeaconResult

Defined in: [src/hooks/useBeacon.ts:79](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L79)

## Properties

### cancelScan

> **cancelScan**: () => `void`

Defined in: [src/hooks/useBeacon.ts:124](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L124)

Cancel any in-progress one-shot scan.

#### Returns

`void`

***

### clearEventLogs

> **clearEventLogs**: () => `void`

Defined in: [src/hooks/useBeacon.ts:151](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L151)

Delete all logged events (keeps logging enabled).

#### Returns

`void`

***

### destroyEventLogs

> **destroyEventLogs**: () => `void`

Defined in: [src/hooks/useBeacon.ts:153](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L153)

Delete the event-log database and disable logging.

#### Returns

`void`

***

### disableEventLogging

> **disableEventLogging**: () => `void`

Defined in: [src/hooks/useBeacon.ts:147](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L147)

Disable SQLite event logging (updates `isEventLoggingEnabled`).

#### Returns

`void`

***

### enableEventLogging

> **enableEventLogging**: () => `void`

Defined in: [src/hooks/useBeacon.ts:145](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L145)

Enable SQLite event logging (updates `isEventLoggingEnabled`).

#### Returns

`void`

***

### getApiEndpoint

> **getApiEndpoint**: () => `object`

Defined in: [src/hooks/useBeacon.ts:158](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L158)

Read the current API forwarding configuration.

#### Returns

`object`

##### apiKey

> **apiKey**: `string` \| `null`

##### id

> **id**: `string` \| `null`

##### url

> **url**: `string` \| `null`

***

### getEventLogs

> **getEventLogs**: (`options?`) => [`EventLogEntry`](../type-aliases/EventLogEntry.md)[]

Defined in: [src/hooks/useBeacon.ts:149](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L149)

Retrieve logged events, optionally filtered.

#### Parameters

##### options?

[`EventLogQueryOptions`](../type-aliases/EventLogQueryOptions.md)

#### Returns

[`EventLogEntry`](../type-aliases/EventLogEntry.md)[]

***

### getMonitoredDeviceState

> **getMonitoredDeviceState**: (`identifier`) => [`MonitoredDeviceState`](../type-aliases/MonitoredDeviceState.md) \| `null`

Defined in: [src/hooks/useBeacon.ts:134](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L134)

State snapshot for one paired device, or `null` when the identifier is unknown.

#### Parameters

##### identifier

`string`

#### Returns

[`MonitoredDeviceState`](../type-aliases/MonitoredDeviceState.md) \| `null`

***

### getMonitoredDeviceStates

> **getMonitoredDeviceStates**: () => [`MonitoredDeviceState`](../type-aliases/MonitoredDeviceState.md)[]

Defined in: [src/hooks/useBeacon.ts:136](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L136)

State snapshot for all paired devices.

#### Returns

[`MonitoredDeviceState`](../type-aliases/MonitoredDeviceState.md)[]

***

### getMonitoringConfig

> **getMonitoringConfig**: () => [`MonitoringConfig`](../type-aliases/MonitoringConfig.md)

Defined in: [src/hooks/useBeacon.ts:132](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L132)

Read the current monitoring configuration and active-state snapshot.

#### Returns

[`MonitoringConfig`](../type-aliases/MonitoringConfig.md)

***

### inRange

> **inRange**: [`InRangeBeacon`](../type-aliases/InRangeBeacon.md)[]

Defined in: [src/hooks/useBeacon.ts:90](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L90)

Paired beacons currently within range, derived live from monitoring
enter / exit / distance / timeout events (empty when `track` is `false`).
Continuous-scan "found" events are delivered via the `onBeaconFound` /
`onEddystoneFound` callbacks instead — they carry no paired identifier.

***

### isBatteryOptimizationExempt

> **isBatteryOptimizationExempt**: () => `boolean`

Defined in: [src/hooks/useBeacon.ts:165](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L165)

Whether the app is exempt from Android battery optimizations (always `true` on iOS / web).

#### Returns

`boolean`

***

### isEventLoggingEnabled

> **isEventLoggingEnabled**: `boolean`

Defined in: [src/hooks/useBeacon.ts:94](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L94)

Whether SQLite event logging is currently enabled.

***

### isMonitoring

> **isMonitoring**: `boolean`

Defined in: [src/hooks/useBeacon.ts:92](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L92)

Whether background region monitoring is currently active.

***

### pairBeacon

> **pairBeacon**: (`identifier`, `uuid`, `major`, `minor`, `name?`, `timeoutSeconds?`) => `void`

Defined in: [src/hooks/useBeacon.ts:98](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L98)

#### Parameters

##### identifier

`string`

##### uuid

`string`

##### major

`number`

##### minor

`number`

##### name?

`string`

##### timeoutSeconds?

`number`

#### Returns

`void`

***

### pairedBeacons

> **pairedBeacons**: [`PairedBeacon`](../type-aliases/PairedBeacon.md)[]

Defined in: [src/hooks/useBeacon.ts:81](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L81)

Paired iBeacons, refreshed by the pairing actions and `refreshPaired`.

***

### pairEddystone

> **pairEddystone**: (`identifier`, `namespace`, `instance`, `name?`, `timeoutSeconds?`) => `void`

Defined in: [src/hooks/useBeacon.ts:107](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L107)

#### Parameters

##### identifier

`string`

##### namespace

`string`

##### instance

`string`

##### name?

`string`

##### timeoutSeconds?

`number`

#### Returns

`void`

***

### pairedEddystones

> **pairedEddystones**: [`PairedEddystone`](../type-aliases/PairedEddystone.md)[]

Defined in: [src/hooks/useBeacon.ts:83](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L83)

Paired Eddystone beacons.

***

### refreshPaired

> **refreshPaired**: () => `void`

Defined in: [src/hooks/useBeacon.ts:97](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L97)

Re-read paired beacons from the native store.

#### Returns

`void`

***

### requestBatteryOptimizationExemption

> **requestBatteryOptimizationExemption**: () => `Promise`\<`boolean`\>

Defined in: [src/hooks/useBeacon.ts:167](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L167)

Request exemption from Android battery optimizations (opens the system dialog).

#### Returns

`Promise`\<`boolean`\>

***

### requestPermissions

> **requestPermissions**: () => `Promise`\<`boolean`\>

Defined in: [src/hooks/useBeacon.ts:170](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L170)

Request the platform permissions needed for scanning and monitoring.

#### Returns

`Promise`\<`boolean`\>

***

### scanForBeacons

> **scanForBeacons**: (`uuids?`, `scanDuration?`) => `Promise`\<[`BeaconScanResult`](../type-aliases/BeaconScanResult.md)[]\>

Defined in: [src/hooks/useBeacon.ts:117](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L117)

One-shot iBeacon scan; resolves with discovered beacons.

#### Parameters

##### uuids?

`string`[]

##### scanDuration?

`number`

#### Returns

`Promise`\<[`BeaconScanResult`](../type-aliases/BeaconScanResult.md)[]\>

***

### scanForEddystones

> **scanForEddystones**: (`scanDuration?`) => `Promise`\<[`EddystoneScanResult`](../type-aliases/EddystoneScanResult.md)[]\>

Defined in: [src/hooks/useBeacon.ts:122](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L122)

One-shot Eddystone scan; resolves with discovered beacons.

#### Parameters

##### scanDuration?

`number`

#### Returns

`Promise`\<[`EddystoneScanResult`](../type-aliases/EddystoneScanResult.md)[]\>

***

### setApiEndpoint

> **setApiEndpoint**: (`url`, `apiKey?`, `id?`) => `void`

Defined in: [src/hooks/useBeacon.ts:156](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L156)

Configure a native API endpoint for background event forwarding.

#### Parameters

##### url

`string`

##### apiKey?

`string`

##### id?

`string`

#### Returns

`void`

***

### setBeaconNotificationConfig

> **setBeaconNotificationConfig**: (`config`) => `void`

Defined in: [src/hooks/useBeacon.ts:141](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L141)

Persist only beacon notification settings.

#### Parameters

##### config

[`BeaconNotificationConfig`](../type-aliases/BeaconNotificationConfig.md) \| [`BeaconNotificationSettings`](../type-aliases/BeaconNotificationSettings.md)

#### Returns

`void`

***

### setNotificationConfig

> **setNotificationConfig**: (`config`) => `void`

Defined in: [src/hooks/useBeacon.ts:139](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L139)

Persist notification configuration applied to subsequent monitoring sessions.

#### Parameters

##### config

[`NotificationConfig`](../type-aliases/NotificationConfig.md)

#### Returns

`void`

***

### startContinuousScan

> **startContinuousScan**: () => `void`

Defined in: [src/hooks/useBeacon.ts:126](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L126)

Start a continuous scan; results stream via `onBeaconFound` / `onEddystoneFound`.

#### Returns

`void`

***

### startMonitoring

> **startMonitoring**: (`options?`) => `Promise`\<`void`\>

Defined in: [src/hooks/useBeacon.ts:129](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L129)

#### Parameters

##### options?

`number` \| [`MonitoringOptions`](../type-aliases/MonitoringOptions.md)

#### Returns

`Promise`\<`void`\>

***

### stopContinuousScan

> **stopContinuousScan**: () => `void`

Defined in: [src/hooks/useBeacon.ts:127](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L127)

#### Returns

`void`

***

### stopMonitoring

> **stopMonitoring**: () => `Promise`\<`void`\>

Defined in: [src/hooks/useBeacon.ts:130](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L130)

#### Returns

`Promise`\<`void`\>

***

### unpairBeacon

> **unpairBeacon**: (`identifier`) => `void`

Defined in: [src/hooks/useBeacon.ts:106](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L106)

#### Parameters

##### identifier

`string`

#### Returns

`void`

***

### unpairEddystone

> **unpairEddystone**: (`identifier`) => `void`

Defined in: [src/hooks/useBeacon.ts:114](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L114)

#### Parameters

##### identifier

`string`

#### Returns

`void`
