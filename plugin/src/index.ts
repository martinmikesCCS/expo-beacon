import { ConfigPlugin, createRunOncePlugin } from '@expo/config-plugins';
import withBeaconAndroid, { BeaconAndroidPluginProps } from './withBeaconAndroid';
import withBeaconIOS, { BeaconIOSPluginProps } from './withBeaconIOS';

export type BeaconPluginProps = {
  ios?: BeaconIOSPluginProps;
  android?: BeaconAndroidPluginProps;
};

const withBeaconBGLocation: ConfigPlugin<BeaconPluginProps | void> = (config, props) => {
  const opts: BeaconPluginProps = props ?? {};

  const platform = config.sdkVersion !== undefined
    ? (config as any)._resolvedLinkedPackages?.platform
    : undefined;

  // Apply iOS plugin only when building for iOS (or when platform is unknown,
  // in which case both are applied — the individual mods are platform-gated).
  if (platform !== 'android') {
    config = withBeaconIOS(config, opts.ios);
  }
  if (platform !== 'ios') {
    config = withBeaconAndroid(config, opts.android);
  }

  return config;
};

export default createRunOncePlugin(
  withBeaconBGLocation,
  'expo-beacon-bglocation',
  '1.0.0',
);
