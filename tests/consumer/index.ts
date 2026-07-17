import DefaultExpoBeacon, {
  ExpoBeacon,
  pairBeacon,
  pairEddystone,
  scanForBeacons,
  scanForEddystones,
  useBeacon,
} from "expo-beacon";
import type {
  BeaconErrorCode,
  BeaconEventName,
  PairBeaconOptions,
  ScanForBeaconsOptions,
} from "expo-beacon";
import type { BeaconPluginProps } from "expo-beacon/plugin";

const scanOptions: ScanForBeaconsOptions = {
  uuids: ["E2C56DB5-DFFB-48D2-B060-D0F5A71096E0"],
  durationMs: 5_000,
};
const pairing: PairBeaconOptions = {
  identifier: "lobby-door",
  uuid: "E2C56DB5-DFFB-48D2-B060-D0F5A71096E0",
  major: 1,
  minor: 100,
  timeoutSeconds: 30,
};
const pluginOptions: BeaconPluginProps = {
  ios: {
    locationWhenInUsePermission: "Allow beacon discovery.",
    backgroundGeolocation: false,
  },
  android: { backgroundGeolocation: false },
};
const eventName: BeaconEventName = "onBeaconEnter";
const errorCode: BeaconErrorCode = "WILDCARD_NOT_SUPPORTED";

void DefaultExpoBeacon;
void ExpoBeacon;
void useBeacon;
void pluginOptions;
void eventName;
void errorCode;

void scanForBeacons(scanOptions);
void scanForEddystones({ durationMs: 5_000 });
pairBeacon(pairing);
pairEddystone({
  identifier: "meeting-room",
  namespace: "edd1ebeac04e5defa017",
  instance: "0123456789ab",
});
