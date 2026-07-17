[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / ExpoBeaconModule

# Interface: ExpoBeaconModule

Defined in: [src/ExpoBeaconModule.ts:19](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L19)

## Extends

- `NativeModule`\<[`ExpoBeaconModuleEvents`](../type-aliases/ExpoBeaconModuleEvents.md)\>

## Methods

### addListener()

> **addListener**\<`EventName`\>(`eventName`, `listener`): `EventSubscription`

Defined in: node\_modules/expo-modules-core/build/ts-declarations/EventEmitter.d.ts:44

Adds a listener for the given event name.

#### Type Parameters

##### EventName

`EventName` *extends* keyof [`ExpoBeaconModuleEvents`](../type-aliases/ExpoBeaconModuleEvents.md)

#### Parameters

##### eventName

`EventName`

##### listener

[`ExpoBeaconModuleEvents`](../type-aliases/ExpoBeaconModuleEvents.md)\[`EventName`\]

#### Returns

`EventSubscription`

#### Inherited from

`NativeModule.addListener`

***

### cancelScan()

> **cancelScan**(): `void`

Defined in: [src/ExpoBeaconModule.ts:145](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L145)

Cancel any in-progress one-shot scan (iBeacon or Eddystone).
The pending promise will be rejected with code "SCAN_CANCELLED".

#### Returns

`void`

***

### clearEventLogs()

> **clearEventLogs**(): `void`

Defined in: [src/ExpoBeaconModule.ts:192](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L192)

Delete all logged events from the database.

#### Returns

`void`

***

### destroyEventLogs()

> **destroyEventLogs**(): `void`

Defined in: [src/ExpoBeaconModule.ts:195](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L195)

Delete the entire event log database. Also disables logging.

#### Returns

`void`

***

### disableEventLogging()

> **disableEventLogging**(): `void`

Defined in: [src/ExpoBeaconModule.ts:177](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L177)

Disable event logging. Previously logged events are retained.

#### Returns

`void`

***

### emit()

> **emit**\<`EventName`\>(`eventName`, ...`args`): `void`

Defined in: node\_modules/expo-modules-core/build/ts-declarations/EventEmitter.d.ts:57

Synchronously calls all the listeners attached to that specific event.
The event can include any number of arguments that will be passed to the listeners.

#### Type Parameters

##### EventName

`EventName` *extends* keyof [`ExpoBeaconModuleEvents`](../type-aliases/ExpoBeaconModuleEvents.md)

#### Parameters

##### eventName

`EventName`

##### args

...`Parameters`\<[`ExpoBeaconModuleEvents`](../type-aliases/ExpoBeaconModuleEvents.md)\[`EventName`\]\>

#### Returns

`void`

#### Inherited from

`NativeModule.emit`

***

### enableEventLogging()

> **enableEventLogging**(): `void`

Defined in: [src/ExpoBeaconModule.ts:174](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L174)

Enable SQLite event logging. All beacon events will be persisted to a local database.

#### Returns

`void`

***

### getApiEndpoint()

> **getApiEndpoint**(): `object`

Defined in: [src/ExpoBeaconModule.ts:230](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L230)

Return the current API forwarding configuration.
Each field is `null` if not set.

#### Returns

`object`

##### apiKey

> **apiKey**: `string` \| `null`

##### id

> **id**: `string` \| `null`

##### url

> **url**: `string` \| `null`

***

### getEventLogs()

> **getEventLogs**(`options?`): [`EventLogEntry`](../type-aliases/EventLogEntry.md)[]

Defined in: [src/ExpoBeaconModule.ts:189](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L189)

Retrieve logged beacon events from the SQLite database.

#### Parameters

##### options?

[`EventLogQueryOptions`](../type-aliases/EventLogQueryOptions.md)

Optional filters (limit, eventType, sinceTimestamp).

#### Returns

[`EventLogEntry`](../type-aliases/EventLogEntry.md)[]

***

### getMonitoredDeviceState()

> **getMonitoredDeviceState**(`identifier`): [`MonitoredDeviceState`](../type-aliases/MonitoredDeviceState.md) \| `null`

Defined in: [src/ExpoBeaconModule.ts:219](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L219)

Return the current state snapshot for a paired monitored device.
Returns null when no paired device matches the identifier.

#### Parameters

##### identifier

`string`

#### Returns

[`MonitoredDeviceState`](../type-aliases/MonitoredDeviceState.md) \| `null`

***

### getMonitoredDeviceStates()

> **getMonitoredDeviceStates**(): [`MonitoredDeviceState`](../type-aliases/MonitoredDeviceState.md)[]

Defined in: [src/ExpoBeaconModule.ts:224](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L224)

Return the current state snapshot for all paired monitored devices.

#### Returns

[`MonitoredDeviceState`](../type-aliases/MonitoredDeviceState.md)[]

***

### getMonitoringConfig()

> **getMonitoringConfig**(): [`MonitoringConfig`](../type-aliases/MonitoringConfig.md)

Defined in: [src/ExpoBeaconModule.ts:213](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L213)

Return the current monitoring configuration and active state.
Option fields are undefined if not explicitly set.

#### Returns

[`MonitoringConfig`](../type-aliases/MonitoringConfig.md)

***

### getPairedBeacons()

> **getPairedBeacons**(): [`PairedBeacon`](../type-aliases/PairedBeacon.md)[]

Defined in: [src/ExpoBeaconModule.ts:69](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L69)

Return all currently paired beacons.

#### Returns

[`PairedBeacon`](../type-aliases/PairedBeacon.md)[]

***

### getPairedEddystones()

> **getPairedEddystones**(): [`PairedEddystone`](../type-aliases/PairedEddystone.md)[]

Defined in: [src/ExpoBeaconModule.ts:95](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L95)

Return all currently paired Eddystone beacons.

#### Returns

[`PairedEddystone`](../type-aliases/PairedEddystone.md)[]

***

### isBatteryOptimizationExempt()

> **isBatteryOptimizationExempt**(): `boolean`

Defined in: [src/ExpoBeaconModule.ts:163](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L163)

Check whether the app is exempt from Android battery optimizations.
Always returns true on iOS and web (no equivalent concept).

#### Returns

`boolean`

***

### isEventLoggingEnabled()

> **isEventLoggingEnabled**(): `boolean`

Defined in: [src/ExpoBeaconModule.ts:183](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L183)

Returns whether SQLite event logging is currently enabled.
Reads the persisted flag, so this stays accurate across app cold-starts.

#### Returns

`boolean`

***

### listenerCount()

> **listenerCount**\<`EventName`\>(`eventName`): `number`

Defined in: node\_modules/expo-modules-core/build/ts-declarations/EventEmitter.d.ts:61

Returns a number of listeners added to the given event.

#### Type Parameters

##### EventName

`EventName` *extends* keyof [`ExpoBeaconModuleEvents`](../type-aliases/ExpoBeaconModuleEvents.md)

#### Parameters

##### eventName

`EventName`

#### Returns

`number`

#### Inherited from

`NativeModule.listenerCount`

***

### pairBeacon()

> **pairBeacon**(`identifier`, `uuid`, `major`, `minor`, `name?`, `timeoutSeconds?`): `void`

Defined in: [src/ExpoBeaconModule.ts:52](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L52)

Register a beacon for persistent region monitoring.

Re-pairing an existing iBeacon identifier replaces the previous entry.
Throws `DUPLICATE_IDENTIFIER` if the identifier is already used by a
paired Eddystone beacon.

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

### pairEddystone()

> **pairEddystone**(`identifier`, `namespace`, `instance`, `name?`, `timeoutSeconds?`): `void`

Defined in: [src/ExpoBeaconModule.ts:79](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L79)

Register an Eddystone-UID beacon for persistent monitoring.
Namespace and instance are normalized to lowercase before storage.

Re-pairing an existing Eddystone identifier replaces the previous entry.
Throws `DUPLICATE_IDENTIFIER` if the identifier is already used by a
paired iBeacon.

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

### removeAllListeners()

> **removeAllListeners**(`eventName`): `void`

Defined in: node\_modules/expo-modules-core/build/ts-declarations/EventEmitter.d.ts:52

Removes all listeners for the given event name.

#### Parameters

##### eventName

keyof [`ExpoBeaconModuleEvents`](../type-aliases/ExpoBeaconModuleEvents.md)

#### Returns

`void`

#### Inherited from

`NativeModule.removeAllListeners`

***

### removeListener()

> **removeListener**\<`EventName`\>(`eventName`, `listener`): `void`

Defined in: node\_modules/expo-modules-core/build/ts-declarations/EventEmitter.d.ts:48

Removes a listener for the given event name.

#### Type Parameters

##### EventName

`EventName` *extends* keyof [`ExpoBeaconModuleEvents`](../type-aliases/ExpoBeaconModuleEvents.md)

#### Parameters

##### eventName

`EventName`

##### listener

[`ExpoBeaconModuleEvents`](../type-aliases/ExpoBeaconModuleEvents.md)\[`EventName`\]

#### Returns

`void`

#### Inherited from

`NativeModule.removeListener`

***

### requestBatteryOptimizationExemption()

> **requestBatteryOptimizationExemption**(): `Promise`\<`boolean`\>

Defined in: [src/ExpoBeaconModule.ts:171](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L171)

Request exemption from Android battery optimizations.
Opens the system dialog asking the user to whitelist this app.
Returns true if the dialog was shown (or already exempt), false on failure.
Always resolves true on iOS and web.

#### Returns

`Promise`\<`boolean`\>

***

### requestPermissionsAsync()

> **requestPermissionsAsync**(): `Promise`\<`boolean`\>

Defined in: [src/ExpoBeaconModule.ts:157](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L157)

Request the permissions needed for scanning and monitoring.

- Android: requests location, Bluetooth (API 31+) and notification
  (API 33+) permissions, then background location (API 29+) in a second
  prompt; resolves true only when background location is granted.
- iOS: requests location When-In-Use authorization and resolves true once
  granted — the Always upgrade is requested later by startMonitoring(),
  and Bluetooth permission is not prompted here.

#### Returns

`Promise`\<`boolean`\>

***

### scanForBeaconsAsync()

> **scanForBeaconsAsync**(`uuids?`, `scanDuration?`): `Promise`\<[`BeaconScanResult`](../type-aliases/BeaconScanResult.md)[]\>

Defined in: [src/ExpoBeaconModule.ts:32](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L32)

Start a one-shot iBeacon scan. Resolves with discovered beacons after scanDuration ms.

Pass one or more UUIDs to scan for specific beacons (uses CoreLocation on iOS).
On iOS, at least one UUID is required — Apple strips iBeacon data from BLE
advertisements, making wildcard discovery impossible. When you pass an empty
array, the module automatically uses UUIDs from paired beacons.
On Android, pass an empty array to discover all nearby iBeacons.

#### Parameters

##### uuids?

`string`[]

Proximity UUIDs to filter by. Empty/omitted = use paired UUIDs (iOS) or wildcard (Android).

##### scanDuration?

`number`

Duration in ms (default 5000)

#### Returns

`Promise`\<[`BeaconScanResult`](../type-aliases/BeaconScanResult.md)[]\>

***

### scanForEddystonesAsync()

> **scanForEddystonesAsync**(`scanDuration?`): `Promise`\<[`EddystoneScanResult`](../type-aliases/EddystoneScanResult.md)[]\>

Defined in: [src/ExpoBeaconModule.ts:43](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L43)

Start a one-shot Eddystone beacon scan using BLE.
Discovers Eddystone-UID and Eddystone-URL frames.

#### Parameters

##### scanDuration?

`number`

Duration in ms (default 5000)

#### Returns

`Promise`\<[`EddystoneScanResult`](../type-aliases/EddystoneScanResult.md)[]\>

***

### setApiEndpoint()

> **setApiEndpoint**(`url`, `apiKey?`, `id?`): `void`

Defined in: [src/ExpoBeaconModule.ts:207](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L207)

Configure a remote API endpoint for native event forwarding.
Once set, beacon events are POSTed directly from native code,
ensuring delivery even when the JS bridge is not active (app backgrounded).

#### Parameters

##### url

`string`

The API endpoint URL to POST events to.

##### apiKey?

`string`

Optional API key sent as the X-CSFR-Token header
  (sic — the header is literally "X-CSFR-Token", not "X-CSRF-Token").

##### id?

`string`

Optional identifier appended to every forwarded event payload.

#### Returns

`void`

***

### setBeaconNotificationConfig()

> **setBeaconNotificationConfig**(`config`): `void`

Defined in: [src/ExpoBeaconModule.ts:107](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L107)

Persist beacon notification settings without replacing other beacon settings.
Passing a plain BeaconNotificationConfig is treated as the beacon event config.

#### Parameters

##### config

[`BeaconNotificationConfig`](../type-aliases/BeaconNotificationConfig.md) \| [`BeaconNotificationSettings`](../type-aliases/BeaconNotificationSettings.md)

#### Returns

`void`

***

### setNotificationConfig()

> **setNotificationConfig**(`config`): `void`

Defined in: [src/ExpoBeaconModule.ts:101](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L101)

Set persistent notification configuration. Settings are saved and applied to all
subsequent monitoring sessions until explicitly changed.

#### Parameters

##### config

[`NotificationConfig`](../type-aliases/NotificationConfig.md)

#### Returns

`void`

***

### startContinuousScan()

> **startContinuousScan**(): `void`

Defined in: [src/ExpoBeaconModule.ts:136](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L136)

Start a continuous BLE scan. Fires `onBeaconFound` / `onEddystoneFound`
events as beacons are detected. Call stopContinuousScan() to end the scan.

iOS only ranges the UUIDs of PAIRED beacons — with no paired beacons no
iBeacons are discovered. Android discovers all nearby iBeacons.
Eddystone discovery works on both platforms regardless of pairing.

#### Returns

`void`

***

### startMonitoring()

> **startMonitoring**(`options?`): `Promise`\<`void`\>

Defined in: [src/ExpoBeaconModule.ts:119](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L119)

Start background region monitoring for all paired beacons.
On Android starts a foreground service.
On iOS starts CLLocationManager region monitoring.

Accepts a plain number (backward-compatible maxDistance shorthand) or a
MonitoringOptions object with maxDistance and/or notification overrides.

#### Parameters

##### options?

`number` \| [`MonitoringOptions`](../type-aliases/MonitoringOptions.md)

#### Returns

`Promise`\<`void`\>

***

### startObserving()?

> `optional` **startObserving**\<`EventName`\>(`eventName`): `void`

Defined in: node\_modules/expo-modules-core/build/ts-declarations/EventEmitter.d.ts:66

Function that is automatically invoked when the first listener for an event with the given name is added.
Override it in a subclass to perform some additional setup once the event started being observed.

#### Type Parameters

##### EventName

`EventName` *extends* keyof [`ExpoBeaconModuleEvents`](../type-aliases/ExpoBeaconModuleEvents.md)

#### Parameters

##### eventName

`EventName`

#### Returns

`void`

#### Inherited from

`NativeModule.startObserving`

***

### stopContinuousScan()

> **stopContinuousScan**(): `void`

Defined in: [src/ExpoBeaconModule.ts:139](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L139)

Stop the continuous scan started by startContinuousScan().

#### Returns

`void`

***

### stopMonitoring()

> **stopMonitoring**(): `Promise`\<`void`\>

Defined in: [src/ExpoBeaconModule.ts:126](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L126)

Stop background region monitoring. Persisted monitoring options
(maxDistance, exitDistance, level, exitTimeoutSeconds, …) are cleared
on both platforms.

#### Returns

`Promise`\<`void`\>

***

### stopObserving()?

> `optional` **stopObserving**\<`EventName`\>(`eventName`): `void`

Defined in: node\_modules/expo-modules-core/build/ts-declarations/EventEmitter.d.ts:71

Function that is automatically invoked when the last listener for an event with the given name is removed.
Override it in a subclass to perform some additional cleanup once the event is no longer observed.

#### Type Parameters

##### EventName

`EventName` *extends* keyof [`ExpoBeaconModuleEvents`](../type-aliases/ExpoBeaconModuleEvents.md)

#### Parameters

##### eventName

`EventName`

#### Returns

`void`

#### Inherited from

`NativeModule.stopObserving`

***

### unpairBeacon()

> **unpairBeacon**(`identifier`): `void`

Defined in: [src/ExpoBeaconModule.ts:64](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L64)

Remove a previously paired beacon.

#### Parameters

##### identifier

`string`

#### Returns

`void`

***

### unpairEddystone()

> **unpairEddystone**(`identifier`): `void`

Defined in: [src/ExpoBeaconModule.ts:90](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeaconModule.ts#L90)

Remove a previously paired Eddystone beacon.

#### Parameters

##### identifier

`string`

#### Returns

`void`
