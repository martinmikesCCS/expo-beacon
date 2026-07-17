[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / MonitoringConfig

# Type Alias: MonitoringConfig

> **MonitoringConfig** = `object`

Defined in: [src/ExpoBeacon.types.ts:137](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L137)

Snapshot of the current monitoring configuration and active state.

## Properties

### exitDistance?

> `optional` **exitDistance?**: `number`

Defined in: [src/ExpoBeacon.types.ts:141](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L141)

***

### exitTimeoutSeconds?

> `optional` **exitTimeoutSeconds?**: `number`

Defined in: [src/ExpoBeacon.types.ts:145](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L145)

Seconds after last beacon sighting before an exit event fires. Default: 300.

***

### isMonitoring

> **isMonitoring**: `boolean`

Defined in: [src/ExpoBeacon.types.ts:139](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L139)

Whether background monitoring is currently active.

***

### level?

> `optional` **level?**: `"all"` \| `"events"`

Defined in: [src/ExpoBeacon.types.ts:143](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L143)

***

### maxDistance?

> `optional` **maxDistance?**: `number`

Defined in: [src/ExpoBeacon.types.ts:140](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L140)

***

### minRssi?

> `optional` **minRssi?**: `number`

Defined in: [src/ExpoBeacon.types.ts:142](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L142)

***

### notifications?

> `optional` **notifications?**: [`NotificationConfig`](NotificationConfig.md)

Defined in: [src/ExpoBeacon.types.ts:146](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L146)
