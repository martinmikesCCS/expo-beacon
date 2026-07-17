# expo-beacon documentation

`expo-beacon` scans and monitors iBeacon and Eddystone transmitters from Expo
development builds and bare React Native applications.

## Choose a task

- New installation: [Getting started](getting-started.md)
- Find nearby devices: [Scanning](scanning.md)
- React to devices in the background: [Background monitoring](background-monitoring.md)
- Configure native permissions or integrations: [Config plugin](config-plugin.md)
- Connect beacon presence to location tracking: [Background geolocation](background-geolocation.md)
- Compare platform behavior: [Platform support](platform-support.md)
- Handle failures: [Errors](errors.md)

Generated references are under `reference/runtime` and `reference/plugin` after
running `npm run docs:api`. The [full reference](full-reference.md) retains the
detailed compatibility documentation for existing positional APIs.

## Rules that prevent most integration mistakes

- This package contains native code and does not work in Expo Go.
- Add the package to the Expo `plugins` array before making a development build.
- Request permissions before scanning or monitoring.
- Pair at least one device before calling `startMonitoring`.
- iOS iBeacon scans require a proximity UUID or a previously paired iBeacon.
- Eddystone discovery uses BLE service data and does not require an iBeacon UUID.
- `react-native-background-geolocation` is optional and must be installed only
  when its config-plugin option is enabled.
