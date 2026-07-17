[**expo-beacon**](../README.md)

***

[expo-beacon](../README.md) / scanForBeacons

# Function: scanForBeacons()

> **scanForBeacons**(`options?`): `Promise`\<[`BeaconScanResult`](../type-aliases/BeaconScanResult.md)[]\>

Defined in: src/helpers.ts:65

Scan once for iBeacons using a self-describing options object.

## Parameters

### options?

[`ScanForBeaconsOptions`](../type-aliases/ScanForBeaconsOptions.md) = `{}`

## Returns

`Promise`\<[`BeaconScanResult`](../type-aliases/BeaconScanResult.md)[]\>

## Example

```ts
const nearby = await scanForBeacons({
  uuids: ["E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"],
  durationMs: 5_000,
});
```
