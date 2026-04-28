import { ConfigPlugin, createRunOncePlugin } from '@expo/config-plugins';
import withBeaconAndroid from './withBeaconAndroid';
import withBeaconIOS from './withBeaconIOS';

const withBeaconBGLocation: ConfigPlugin = (config) => {
  const platform = config.sdkVersion !== undefined
    ? (config as any)._resolvedLinkedPackages?.platform
    : undefined;

  // Apply iOS plugin only when building for iOS (or when platform is unknown,
  // in which case both are applied — the individual mods are platform-gated).
  if (platform !== 'android') {
    config = withBeaconIOS(config);
  }
  if (platform !== 'ios') {
    config = withBeaconAndroid(config);
  }

  return config;
};

export default createRunOncePlugin(
  withBeaconBGLocation,
  'expo-beacon-bglocation',
  '1.0.0',
);
