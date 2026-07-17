[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / NotificationChannelConfig

# Type Alias: NotificationChannelConfig

> **NotificationChannelConfig** = `object`

Defined in: [src/ExpoBeacon.types.ts:102](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L102)

Configuration for the Android notification channel.

## Properties

### description?

> `optional` **description?**: `string`

Defined in: [src/ExpoBeacon.types.ts:106](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L106)

Channel description shown in system settings. Default: "Used for background iBeacon region monitoring".

***

### importance?

> `optional` **importance?**: `"low"` \| `"default"` \| `"high"`

Defined in: [src/ExpoBeacon.types.ts:111](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L111)

Channel importance level. Default: 'low'.
Note: Android may ignore decreases in importance after first channel creation until the app is reinstalled.

***

### name?

> `optional` **name?**: `string`

Defined in: [src/ExpoBeacon.types.ts:104](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L104)

Channel display name shown in system settings. Default: "Beacon Monitoring".
