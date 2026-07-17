[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / BeaconNotificationConfig

# Type Alias: BeaconNotificationConfig

> **BeaconNotificationConfig** = `object`

Defined in: [src/ExpoBeacon.types.ts:71](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L71)

Configuration for beacon enter/exit event notifications.

## Properties

### body?

> `optional` **body?**: `string`

Defined in: [src/ExpoBeacon.types.ts:84](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L84)

Notification body template. Supports {identifier} and {event} placeholders.
Default: "{identifier} region {event}ed".

***

### enabled?

> `optional` **enabled?**: `boolean`

Defined in: [src/ExpoBeacon.types.ts:73](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L73)

Whether to show enter/exit notifications. Default: true.

***

### enterTitle?

> `optional` **enterTitle?**: `string`

Defined in: [src/ExpoBeacon.types.ts:75](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L75)

Notification title on beacon enter. Default: "Beacon Entered".

***

### exitTitle?

> `optional` **exitTitle?**: `string`

Defined in: [src/ExpoBeacon.types.ts:77](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L77)

Notification title on beacon exit. Default: "Beacon Exited".

***

### icon?

> `optional` **icon?**: `string`

Defined in: [src/ExpoBeacon.types.ts:88](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L88)

Android drawable resource name for the notification icon (e.g. "ic_notification").

***

### sound?

> `optional` **sound?**: `boolean`

Defined in: [src/ExpoBeacon.types.ts:86](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L86)

Play a sound with the notification (iOS only). Default: true.

***

### timeoutTitle?

> `optional` **timeoutTitle?**: `string`

Defined in: [src/ExpoBeacon.types.ts:79](https://github.com/martinmikesccs/expo-beacon/blob/master/src/ExpoBeacon.types.ts#L79)

Notification title on beacon timeout. Default: "Beacon Timeout".
