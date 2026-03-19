import * as React from 'react';

import { ExpoBeaconViewProps } from './ExpoBeacon.types';

export default function ExpoBeaconView(props: ExpoBeaconViewProps) {
  return (
    <div>
      <iframe
        style={{ flex: 1 }}
        src={props.url}
        onLoad={() => props.onLoad({ nativeEvent: { url: props.url } })}
      />
    </div>
  );
}
