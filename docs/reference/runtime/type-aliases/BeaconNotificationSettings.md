[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / BeaconNotificationSettings

# Type Alias: BeaconNotificationSettings

> **BeaconNotificationSettings** = `object`

Defined in: [src/ExpoBeacon.types.ts:115](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L115)

Notification settings for beacon monitoring.

## Properties

### channel?

> `optional` **channel?**: [`NotificationChannelConfig`](NotificationChannelConfig.md)

Defined in: [src/ExpoBeacon.types.ts:121](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L121)

Settings for the Android notification channel used by beacon notifications.

***

### events?

> `optional` **events?**: [`BeaconNotificationConfig`](BeaconNotificationConfig.md)

Defined in: [src/ExpoBeacon.types.ts:117](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L117)

Settings for beacon enter/exit/timeout event notifications.

***

### foregroundService?

> `optional` **foregroundService?**: [`ForegroundServiceConfig`](ForegroundServiceConfig.md)

Defined in: [src/ExpoBeacon.types.ts:119](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L119)

Settings for the persistent Android foreground service notification.
