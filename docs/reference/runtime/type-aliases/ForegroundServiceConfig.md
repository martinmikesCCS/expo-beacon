[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / ForegroundServiceConfig

# Type Alias: ForegroundServiceConfig

> **ForegroundServiceConfig** = `object`

Defined in: [src/ExpoBeacon.types.ts:92](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L92)

Configuration for the Android foreground service notification (persistent status bar entry).

## Properties

### icon?

> `optional` **icon?**: `string`

Defined in: [src/ExpoBeacon.types.ts:98](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L98)

Android drawable resource name for the notification icon.

***

### text?

> `optional` **text?**: `string`

Defined in: [src/ExpoBeacon.types.ts:96](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L96)

Body text of the persistent notification. Default: "Monitoring for iBeacons in the background".

***

### title?

> `optional` **title?**: `string`

Defined in: [src/ExpoBeacon.types.ts:94](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L94)

Title of the persistent notification. Default: "Beacon Monitoring Active".
