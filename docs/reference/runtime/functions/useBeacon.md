[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / useBeacon

# Function: useBeacon()

> **useBeacon**(`options?`): [`UseBeaconResult`](../interfaces/UseBeaconResult.md)

Defined in: [src/hooks/useBeacon.ts:187](https://github.com/martinmikesccs/expo-beacon/blob/be82a50bc9b8174d7f3fb92cc67c52e1c960913f/src/hooks/useBeacon.ts#L187)

Manage iBeacon / Eddystone scanning and background monitoring.

Subscribes to the native beacon events for the lifetime of the component,
keeps `pairedBeacons`, `inRange`, and `isMonitoring` in sync, and exposes
stable action wrappers. Event callbacks passed in `options` are always
invoked with the latest values without re-subscribing.

```tsx
const { inRange, isMonitoring, startMonitoring } = useBeacon({
  onBeaconEnter: (e) => console.log("entered", e.identifier),
});
```

## Parameters

### options?

[`UseBeaconOptions`](../interfaces/UseBeaconOptions.md) = `{}`

## Returns

[`UseBeaconResult`](../interfaces/UseBeaconResult.md)
