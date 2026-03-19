import { requireNativeView } from 'expo';
import * as React from 'react';

import { ExpoBeaconViewProps } from './ExpoBeacon.types';

const NativeView: React.ComponentType<ExpoBeaconViewProps> =
  requireNativeView('ExpoBeacon');

export default function ExpoBeaconView(props: ExpoBeaconViewProps) {
  return <NativeView {...props} />;
}
