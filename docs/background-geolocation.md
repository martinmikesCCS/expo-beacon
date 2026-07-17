# Background geolocation integration

The optional config-plugin bridge starts
`react-native-background-geolocation` while at least one paired beacon is
active. After the final exit or timeout, it waits 30 seconds, requests and
persists a final position, changes to stationary mode, synchronizes pending
locations, and stops tracking.

## Install and enable

Install and configure the Transistorsoft package according to its own license
and setup instructions, then enable the bridge for each target platform:

```json
{
  "expo": {
    "plugins": [
      [
        "expo-beacon",
        {
          "ios": { "backgroundGeolocation": true },
          "android": { "backgroundGeolocation": true }
        }
      ]
    ]
  }
}
```

Run a native prebuild after changing the option. Enabling it generates
`BeaconGeoPlugin.swift` on iOS and `BeaconGeoPlugin.kt` on Android and registers
the generated lifecycle bridge in the host application.

## Important boundaries

- `expo-beacon` does not configure the background-geolocation SDK settings,
  endpoint, license, or authorization; the application owns that setup.
- The integration is disabled by default.
- Disabling the option removes generated registration code and source files on
  the next prebuild.
- Repeated prebuilds are expected to be idempotent.
