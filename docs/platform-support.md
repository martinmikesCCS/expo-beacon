# Platform support

| Capability                      | Android                          | iOS                           | Web            |
| ------------------------------- | -------------------------------- | ----------------------------- | -------------- |
| One-shot iBeacon scan           | Wildcard or UUID-filtered        | UUID-filtered only            | Unsupported    |
| Continuous iBeacon scan         | Wildcard                         | Paired UUIDs only             | Unsupported    |
| Eddystone-UID/URL scan          | Supported                        | Supported                     | Unsupported    |
| Persistent pairing              | Supported                        | Supported                     | Unsupported    |
| Background iBeacon monitoring   | Foreground service and AltBeacon | Core Location regions         | Unsupported    |
| Background Eddystone monitoring | Foreground service and BLE       | Core Bluetooth                | Unsupported    |
| Event logging                   | SQLite                           | SQLite                        | Unsupported    |
| Battery-optimization exemption  | Android system flow              | Returns `true`; no equivalent | Returns `true` |

## iOS constraints

- Wildcard iBeacon discovery is unavailable because iBeacon manufacturer data
  is not exposed through Core Bluetooth. Supply a UUID or pair an iBeacon first.
- Core Location permits at most 20 monitored regions per app. `expo-beacon`
  leaves host-app regions intact and uses only remaining slots.
- Eddystone monitoring does not consume Core Location region slots.
- `requestPermissionsAsync` requests When-In-Use location permission. The
  Always upgrade is requested when monitoring begins.
- Background BLE advertisements may be throttled or coalesced by iOS.

## Android constraints

- Runtime permission requirements depend on the Android API level. Use
  `requestPermissionsAsync` rather than requesting a partial set manually.
- Background monitoring requires a foreground service and persistent
  notification on Android 8 and later.
- Battery optimizations can delay or suppress background work. The package
  exposes methods to inspect and request exemption.

## Web behavior

Web is intentionally unsupported. Async native operations reject, most sync
operations throw, and a few platform-query methods return inert values.
