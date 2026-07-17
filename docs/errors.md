# Errors

Native promise rejections expose a machine-readable code. Monitoring and
ranging failures can also arrive through `onBeaconError`:

```ts
import { ExpoBeacon } from "expo-beacon";
import type { BeaconErrorEvent } from "expo-beacon";

const subscription = ExpoBeacon.addListener(
  "onBeaconError",
  (error: BeaconErrorEvent) => {
    console.error(error.code, error.message, error.identifier);
  },
);
```

`BeaconErrorCode` is exported as a string union for exhaustive application
handling and IDE completion.

## Common corrective actions

| Code                              | Corrective action                                                         |
| --------------------------------- | ------------------------------------------------------------------------- |
| `PERMISSION_DENIED`               | Request beacon permissions and explain the system prompt before retrying. |
| `WILDCARD_NOT_SUPPORTED`          | On iOS, supply one or more iBeacon UUIDs or pair an iBeacon first.        |
| `NO_PAIRED_BEACONS`               | Pair an iBeacon or Eddystone-UID device before monitoring.                |
| `SCAN_IN_PROGRESS`                | Wait for, cancel, or reuse the current scan of the same type.             |
| `SCAN_CANCELLED`                  | Treat as expected control flow after `cancelScan`.                        |
| `INVALID_DURATION`                | Pass a duration greater than zero milliseconds.                           |
| `INVALID_UUID`                    | Pass a standard iBeacon proximity UUID.                                   |
| `INVALID_MAJOR` / `INVALID_MINOR` | Use integer values from 0 through 65535.                                  |
| `INVALID_NAMESPACE`               | Use exactly 20 hexadecimal characters.                                    |
| `INVALID_INSTANCE`                | Use exactly 12 hexadecimal characters.                                    |
| `DUPLICATE_IDENTIFIER`            | Use an identifier not already assigned to the other beacon kind.          |
| `REGION_LIMIT_EXCEEDED`           | Remove another iOS Core Location region or monitor fewer iBeacons.        |
| `BLUETOOTH_OFF`                   | Ask the user to enable Bluetooth before retrying.                         |
| `SERVICE_START_FAILED`            | Check Android permissions, notification permission, and native logs.      |

The complete `BeaconErrorCode` declaration is generated into the runtime API
reference by `npm run docs:api`.
