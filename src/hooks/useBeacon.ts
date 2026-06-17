import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import ExpoBeacon from "../ExpoBeaconModule.js";
import type {
  BeaconDistanceEvent,
  BeaconErrorEvent,
  BeaconNotificationConfig,
  BeaconNotificationSettings,
  BeaconRegionEvent,
  BeaconScanResult,
  BeaconTimeoutEvent,
  CarPlayNotificationConfig,
  CarPlayNotificationSettings,
  EddystoneDistanceEvent,
  EddystoneRegionEvent,
  EddystoneScanResult,
  EddystoneTimeoutEvent,
  EventLogEntry,
  EventLogQueryOptions,
  MonitoredDeviceState,
  MonitoringConfig,
  MonitoringOptions,
  NotificationConfig,
  PairedBeacon,
  PairedEddystone,
} from "../ExpoBeacon.types";

/**
 * A monitored beacon that is currently within range, as tracked by
 * {@link useBeacon} from live enter / exit / distance / timeout events.
 */
export type InRangeBeacon =
  | {
      kind: "ibeacon";
      identifier: string;
      uuid: string;
      major: number;
      minor: number;
      /** Latest measured distance in metres (-1 when unavailable). */
      distance: number;
      /** Latest RSSI in dBm, when reported by the event. */
      rssi?: number;
      /** Epoch ms of the most recent reading for this beacon. */
      lastSeen: number;
    }
  | {
      kind: "eddystone";
      identifier: string;
      namespace: string;
      instance: string;
      /** Latest measured distance in metres (-1 when unavailable). */
      distance: number;
      /** Latest RSSI in dBm, when reported by the event. */
      rssi?: number;
      /** Epoch ms of the most recent reading for this beacon. */
      lastSeen: number;
    };

export interface UseBeaconOptions {
  onBeaconEnter?: (event: BeaconRegionEvent) => void;
  onBeaconExit?: (event: BeaconRegionEvent) => void;
  onBeaconDistance?: (event: BeaconDistanceEvent) => void;
  onBeaconTimeout?: (event: BeaconTimeoutEvent) => void;
  /** Fired for each iBeacon seen during a continuous scan (see `startContinuousScan`). */
  onBeaconFound?: (event: BeaconScanResult) => void;
  onEddystoneEnter?: (event: EddystoneRegionEvent) => void;
  onEddystoneExit?: (event: EddystoneRegionEvent) => void;
  onEddystoneDistance?: (event: EddystoneDistanceEvent) => void;
  onEddystoneTimeout?: (event: EddystoneTimeoutEvent) => void;
  /** Fired for each Eddystone seen during a continuous scan (see `startContinuousScan`). */
  onEddystoneFound?: (event: EddystoneScanResult) => void;
  /** Fired on native monitoring / ranging failures. */
  onError?: (event: BeaconErrorEvent) => void;
  /**
   * Maintain the reactive `inRange` list from monitoring events. Set to `false`
   * if you only want the event callbacks above. Default: `true`.
   */
  track?: boolean;
}

export interface UseBeaconResult {
  /** Paired iBeacons, refreshed by the pairing actions and `refreshPaired`. */
  pairedBeacons: PairedBeacon[];
  /** Paired Eddystone beacons. */
  pairedEddystones: PairedEddystone[];
  /**
   * Paired beacons currently within range, derived live from monitoring
   * enter / exit / distance / timeout events (empty when `track` is `false`).
   * Continuous-scan "found" events are delivered via the `onBeaconFound` /
   * `onEddystoneFound` callbacks instead — they carry no paired identifier.
   */
  inRange: InRangeBeacon[];
  /** Whether background region monitoring is currently active. */
  isMonitoring: boolean;
  /** Whether SQLite event logging is currently enabled. */
  isEventLoggingEnabled: boolean;

  /** Re-read paired beacons from the native store. */
  refreshPaired: () => void;
  pairBeacon: (
    identifier: string,
    uuid: string,
    major: number,
    minor: number,
    name?: string,
    timeoutSeconds?: number,
  ) => void;
  unpairBeacon: (identifier: string) => void;
  pairEddystone: (
    identifier: string,
    namespace: string,
    instance: string,
    name?: string,
    timeoutSeconds?: number,
  ) => void;
  unpairEddystone: (identifier: string) => void;

  /** One-shot iBeacon scan; resolves with discovered beacons. */
  scanForBeacons: (
    uuids?: string[],
    scanDuration?: number,
  ) => Promise<BeaconScanResult[]>;
  /** One-shot Eddystone scan; resolves with discovered beacons. */
  scanForEddystones: (scanDuration?: number) => Promise<EddystoneScanResult[]>;
  /** Cancel any in-progress one-shot scan. */
  cancelScan: () => void;
  /** Start a continuous scan; results stream via `onBeaconFound` / `onEddystoneFound`. */
  startContinuousScan: () => void;
  stopContinuousScan: () => void;

  startMonitoring: (options?: MonitoringOptions | number) => Promise<void>;
  stopMonitoring: () => Promise<void>;
  /** Read the current monitoring configuration and active-state snapshot. */
  getMonitoringConfig: () => MonitoringConfig;
  /** State snapshot for one paired device, or `null` when the identifier is unknown. */
  getMonitoredDeviceState: (identifier: string) => MonitoredDeviceState | null;
  /** State snapshot for all paired devices. */
  getMonitoredDeviceStates: () => MonitoredDeviceState[];

  /** Persist notification configuration applied to subsequent monitoring sessions. */
  setNotificationConfig: (config: NotificationConfig) => void;
  /** Persist only beacon notification settings. */
  setBeaconNotificationConfig: (
    config: BeaconNotificationSettings | BeaconNotificationConfig,
  ) => void;
  /** Persist only CarPlay / Android Auto notification settings. */
  setCarPlayNotificationConfig: (
    config: CarPlayNotificationSettings | CarPlayNotificationConfig,
  ) => void;

  /** Enable SQLite event logging (updates `isEventLoggingEnabled`). */
  enableEventLogging: () => void;
  /** Disable SQLite event logging (updates `isEventLoggingEnabled`). */
  disableEventLogging: () => void;
  /** Retrieve logged events, optionally filtered. */
  getEventLogs: (options?: EventLogQueryOptions) => EventLogEntry[];
  /** Delete all logged events (keeps logging enabled). */
  clearEventLogs: () => void;
  /** Delete the event-log database and disable logging. */
  destroyEventLogs: () => void;

  /** Configure a native API endpoint for background event forwarding. */
  setApiEndpoint: (url: string, apiKey?: string, id?: string) => void;
  /** Read the current API forwarding configuration. */
  getApiEndpoint: () => {
    url: string | null;
    apiKey: string | null;
    id: string | null;
  };

  /** Whether the app is exempt from Android battery optimizations (always `true` on iOS / web). */
  isBatteryOptimizationExempt: () => boolean;
  /** Request exemption from Android battery optimizations (opens the system dialog). */
  requestBatteryOptimizationExemption: () => Promise<boolean>;

  /** Request the platform permissions needed for scanning and monitoring. */
  requestPermissions: () => Promise<boolean>;
}

/**
 * Manage iBeacon / Eddystone scanning and background monitoring.
 *
 * Subscribes to the native beacon events for the lifetime of the component,
 * keeps `pairedBeacons`, `inRange`, and `isMonitoring` in sync, and exposes
 * stable action wrappers. Event callbacks passed in `options` are always
 * invoked with the latest values without re-subscribing.
 *
 * ```tsx
 * const { inRange, isMonitoring, startMonitoring } = useBeacon({
 *   onBeaconEnter: (e) => console.log("entered", e.identifier),
 * });
 * ```
 */
export function useBeacon(options: UseBeaconOptions = {}): UseBeaconResult {
  // Hold the latest callbacks/flags in a ref so the subscription effect can run
  // once on mount yet always call the current handlers.
  const handlers = useRef(options);
  handlers.current = options;

  const [pairedBeacons, setPairedBeacons] = useState<PairedBeacon[]>([]);
  const [pairedEddystones, setPairedEddystones] = useState<PairedEddystone[]>(
    [],
  );
  const [isMonitoring, setIsMonitoring] = useState(false);
  const [isEventLoggingEnabled, setIsEventLoggingEnabled] = useState(false);
  const [inRangeMap, setInRangeMap] = useState<Record<string, InRangeBeacon>>(
    {},
  );

  const refreshPaired = useCallback(() => {
    setPairedBeacons(ExpoBeacon.getPairedBeacons());
    setPairedEddystones(ExpoBeacon.getPairedEddystones());
  }, []);

  // Seed reactive state from the native snapshot on mount. Wrapped in try/catch
  // because the web stub throws for these getters (unsupported platform).
  useEffect(() => {
    try {
      refreshPaired();
    } catch {
      /* unsupported platform */
    }
    try {
      setIsMonitoring(ExpoBeacon.getMonitoringConfig().isMonitoring);
    } catch {
      /* unsupported platform */
    }
    try {
      setIsEventLoggingEnabled(ExpoBeacon.isEventLoggingEnabled());
    } catch {
      /* unsupported platform */
    }
    if (handlers.current.track === false) return;
    try {
      const now = Date.now();
      const seed: Record<string, InRangeBeacon> = {};
      for (const s of ExpoBeacon.getMonitoredDeviceStates()) {
        if (s.state !== "entered") continue;
        seed[s.identifier] =
          s.kind === "ibeacon"
            ? {
                kind: "ibeacon",
                identifier: s.identifier,
                uuid: s.uuid,
                major: s.major,
                minor: s.minor,
                distance: s.distance ?? -1,
                lastSeen: now,
              }
            : {
                kind: "eddystone",
                identifier: s.identifier,
                namespace: s.namespace,
                instance: s.instance,
                distance: s.distance ?? -1,
                lastSeen: now,
              };
      }
      setInRangeMap(seed);
    } catch {
      /* unsupported platform */
    }
  }, [refreshPaired]);

  // Subscribe to all beacon events once; route to inRange state + callbacks.
  useEffect(() => {
    const tracking = () => handlers.current.track !== false;

    const upsertIBeacon = (e: {
      identifier: string;
      uuid: string;
      major: number;
      minor: number;
      distance: number;
      rssi?: number;
    }) => {
      if (!tracking()) return;
      setInRangeMap((m) => ({
        ...m,
        [e.identifier]: {
          kind: "ibeacon",
          identifier: e.identifier,
          uuid: e.uuid,
          major: e.major,
          minor: e.minor,
          distance: e.distance,
          rssi: e.rssi,
          lastSeen: Date.now(),
        },
      }));
    };
    const upsertEddystone = (e: {
      identifier: string;
      namespace: string;
      instance: string;
      distance: number;
      rssi?: number;
    }) => {
      if (!tracking()) return;
      setInRangeMap((m) => ({
        ...m,
        [e.identifier]: {
          kind: "eddystone",
          identifier: e.identifier,
          namespace: e.namespace,
          instance: e.instance,
          distance: e.distance,
          rssi: e.rssi,
          lastSeen: Date.now(),
        },
      }));
    };
    const remove = (identifier: string) => {
      if (!tracking()) return;
      setInRangeMap((m) => {
        if (!(identifier in m)) return m;
        const next = { ...m };
        delete next[identifier];
        return next;
      });
    };

    const subs = [
      ExpoBeacon.addListener("onBeaconEnter", (e) => {
        upsertIBeacon(e);
        handlers.current.onBeaconEnter?.(e);
      }),
      ExpoBeacon.addListener("onBeaconDistance", (e) => {
        upsertIBeacon(e);
        handlers.current.onBeaconDistance?.(e);
      }),
      ExpoBeacon.addListener("onBeaconExit", (e) => {
        remove(e.identifier);
        handlers.current.onBeaconExit?.(e);
      }),
      ExpoBeacon.addListener("onBeaconTimeout", (e) => {
        remove(e.identifier);
        handlers.current.onBeaconTimeout?.(e);
      }),
      ExpoBeacon.addListener("onEddystoneEnter", (e) => {
        upsertEddystone(e);
        handlers.current.onEddystoneEnter?.(e);
      }),
      ExpoBeacon.addListener("onEddystoneDistance", (e) => {
        upsertEddystone(e);
        handlers.current.onEddystoneDistance?.(e);
      }),
      ExpoBeacon.addListener("onEddystoneExit", (e) => {
        remove(e.identifier);
        handlers.current.onEddystoneExit?.(e);
      }),
      ExpoBeacon.addListener("onEddystoneTimeout", (e) => {
        remove(e.identifier);
        handlers.current.onEddystoneTimeout?.(e);
      }),
      ExpoBeacon.addListener("onBeaconFound", (e) => {
        handlers.current.onBeaconFound?.(e);
      }),
      ExpoBeacon.addListener("onEddystoneFound", (e) => {
        handlers.current.onEddystoneFound?.(e);
      }),
      ExpoBeacon.addListener("onBeaconError", (e) => {
        handlers.current.onError?.(e);
      }),
    ];

    return () => {
      for (const sub of subs) sub.remove();
    };
  }, []);

  const pairBeacon = useCallback<UseBeaconResult["pairBeacon"]>(
    (identifier, uuid, major, minor, name, timeoutSeconds) => {
      ExpoBeacon.pairBeacon(
        identifier,
        uuid,
        major,
        minor,
        name,
        timeoutSeconds,
      );
      refreshPaired();
    },
    [refreshPaired],
  );
  const unpairBeacon = useCallback(
    (identifier: string) => {
      ExpoBeacon.unpairBeacon(identifier);
      refreshPaired();
    },
    [refreshPaired],
  );
  const pairEddystone = useCallback<UseBeaconResult["pairEddystone"]>(
    (identifier, namespace, instance, name, timeoutSeconds) => {
      ExpoBeacon.pairEddystone(
        identifier,
        namespace,
        instance,
        name,
        timeoutSeconds,
      );
      refreshPaired();
    },
    [refreshPaired],
  );
  const unpairEddystone = useCallback(
    (identifier: string) => {
      ExpoBeacon.unpairEddystone(identifier);
      refreshPaired();
    },
    [refreshPaired],
  );

  const scanForBeacons = useCallback(
    (uuids?: string[], scanDuration?: number) =>
      ExpoBeacon.scanForBeaconsAsync(uuids, scanDuration),
    [],
  );
  const scanForEddystones = useCallback(
    (scanDuration?: number) => ExpoBeacon.scanForEddystonesAsync(scanDuration),
    [],
  );
  const cancelScan = useCallback(() => ExpoBeacon.cancelScan(), []);
  const startContinuousScan = useCallback(
    () => ExpoBeacon.startContinuousScan(),
    [],
  );
  const stopContinuousScan = useCallback(
    () => ExpoBeacon.stopContinuousScan(),
    [],
  );

  const startMonitoring = useCallback(
    async (opts?: MonitoringOptions | number) => {
      await ExpoBeacon.startMonitoring(opts);
      setIsMonitoring(true);
    },
    [],
  );
  const stopMonitoring = useCallback(async () => {
    await ExpoBeacon.stopMonitoring();
    setIsMonitoring(false);
  }, []);
  const getMonitoringConfig = useCallback(
    () => ExpoBeacon.getMonitoringConfig(),
    [],
  );
  const getMonitoredDeviceState = useCallback(
    (identifier: string) => ExpoBeacon.getMonitoredDeviceState(identifier),
    [],
  );
  const getMonitoredDeviceStates = useCallback(
    () => ExpoBeacon.getMonitoredDeviceStates(),
    [],
  );

  const setNotificationConfig = useCallback(
    (config: NotificationConfig) => ExpoBeacon.setNotificationConfig(config),
    [],
  );
  const setBeaconNotificationConfig = useCallback(
    (config: BeaconNotificationSettings | BeaconNotificationConfig) =>
      ExpoBeacon.setBeaconNotificationConfig(config),
    [],
  );
  const setCarPlayNotificationConfig = useCallback(
    (config: CarPlayNotificationSettings | CarPlayNotificationConfig) =>
      ExpoBeacon.setCarPlayNotificationConfig(config),
    [],
  );

  const enableEventLogging = useCallback(() => {
    ExpoBeacon.enableEventLogging();
    setIsEventLoggingEnabled(true);
  }, []);
  const disableEventLogging = useCallback(() => {
    ExpoBeacon.disableEventLogging();
    setIsEventLoggingEnabled(false);
  }, []);
  const getEventLogs = useCallback(
    (opts?: EventLogQueryOptions) => ExpoBeacon.getEventLogs(opts),
    [],
  );
  const clearEventLogs = useCallback(() => ExpoBeacon.clearEventLogs(), []);
  const destroyEventLogs = useCallback(() => {
    ExpoBeacon.destroyEventLogs();
    setIsEventLoggingEnabled(false);
  }, []);

  const setApiEndpoint = useCallback(
    (url: string, apiKey?: string, id?: string) =>
      ExpoBeacon.setApiEndpoint(url, apiKey, id),
    [],
  );
  const getApiEndpoint = useCallback(() => ExpoBeacon.getApiEndpoint(), []);

  const isBatteryOptimizationExempt = useCallback(
    () => ExpoBeacon.isBatteryOptimizationExempt(),
    [],
  );
  const requestBatteryOptimizationExemption = useCallback(
    () => ExpoBeacon.requestBatteryOptimizationExemption(),
    [],
  );

  const requestPermissions = useCallback(
    () => ExpoBeacon.requestPermissionsAsync(),
    [],
  );

  const inRange = useMemo(() => Object.values(inRangeMap), [inRangeMap]);

  return {
    pairedBeacons,
    pairedEddystones,
    inRange,
    isMonitoring,
    isEventLoggingEnabled,
    refreshPaired,
    pairBeacon,
    unpairBeacon,
    pairEddystone,
    unpairEddystone,
    scanForBeacons,
    scanForEddystones,
    cancelScan,
    startContinuousScan,
    stopContinuousScan,
    startMonitoring,
    stopMonitoring,
    getMonitoringConfig,
    getMonitoredDeviceState,
    getMonitoredDeviceStates,
    setNotificationConfig,
    setBeaconNotificationConfig,
    setCarPlayNotificationConfig,
    enableEventLogging,
    disableEventLogging,
    getEventLogs,
    clearEventLogs,
    destroyEventLogs,
    setApiEndpoint,
    getApiEndpoint,
    isBatteryOptimizationExempt,
    requestBatteryOptimizationExemption,
    requestPermissions,
  };
}
