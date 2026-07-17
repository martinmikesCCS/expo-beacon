[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / BeaconIOSPluginProps

# Type Alias: BeaconIOSPluginProps

> **BeaconIOSPluginProps** = `object`

Defined in: [withBeaconIOS.ts:11](https://github.com/martinmikesccs/expo-beacon/blob/master/plugin/src/withBeaconIOS.ts#L11)

## Properties

### backgroundGeolocation?

> `optional` **backgroundGeolocation?**: `boolean`

Defined in: [withBeaconIOS.ts:19](https://github.com/martinmikesccs/expo-beacon/blob/master/plugin/src/withBeaconIOS.ts#L19)

Generate and register the optional
`react-native-background-geolocation` lifecycle bridge.

The peer package must be installed in the consuming app.

#### Default Value

```ts
false
```

***

### bluetoothPermission?

> `optional` **bluetoothPermission?**: `string`

Defined in: [withBeaconIOS.ts:25](https://github.com/martinmikesccs/expo-beacon/blob/master/plugin/src/withBeaconIOS.ts#L25)

Value for `NSBluetoothAlwaysUsageDescription`.

***

### locationAlwaysPermission?

> `optional` **locationAlwaysPermission?**: `string`

Defined in: [withBeaconIOS.ts:23](https://github.com/martinmikesccs/expo-beacon/blob/master/plugin/src/withBeaconIOS.ts#L23)

Value for `NSLocationAlwaysAndWhenInUseUsageDescription`.

***

### locationWhenInUsePermission?

> `optional` **locationWhenInUsePermission?**: `string`

Defined in: [withBeaconIOS.ts:21](https://github.com/martinmikesccs/expo-beacon/blob/master/plugin/src/withBeaconIOS.ts#L21)

Value for `NSLocationWhenInUseUsageDescription`.
