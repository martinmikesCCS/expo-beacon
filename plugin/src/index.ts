import { ConfigPlugin, createRunOncePlugin } from "@expo/config-plugins";
import withBeaconAndroid, {
  BeaconAndroidPluginProps,
} from "./withBeaconAndroid";
import withBeaconIOS, { BeaconIOSPluginProps } from "./withBeaconIOS";

const pkg = require("../../package.json");

/** Options accepted by the bundled Expo config plugin. */
export type BeaconPluginProps = {
  /** iOS permission strings and optional native integration settings. */
  ios?: BeaconIOSPluginProps;
  /** Android optional native integration settings. */
  android?: BeaconAndroidPluginProps;
};

export type { BeaconAndroidPluginProps, BeaconIOSPluginProps };

const withBeaconBGLocation: ConfigPlugin<BeaconPluginProps | void> = (
  config,
  props,
) => {
  const opts: BeaconPluginProps = props ?? {};

  // Both sub-plugins are applied unconditionally; their mods are platform-gated.
  config = withBeaconIOS(config, opts.ios);
  config = withBeaconAndroid(config, opts.android);

  return config;
};

export default createRunOncePlugin(
  withBeaconBGLocation,
  "expo-beacon-bglocation",
  pkg.version,
);
