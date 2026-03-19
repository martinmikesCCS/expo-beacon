// Reexport the native module. On web, it will be resolved to ExpoBeaconModule.web.ts
// and on native platforms to ExpoBeaconModule.ts
export { default } from './ExpoBeaconModule';
export { default as ExpoBeaconView } from './ExpoBeaconView';
export * from  './ExpoBeacon.types';
