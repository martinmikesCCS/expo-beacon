import { useCallback, useEffect, useRef, useState } from "react";

import ExpoBeacon from "../ExpoBeaconModule.js";
import type {
  CarPlayConnectedEvent,
  CarPlayDiagnostics,
  CarPlayDisconnectedEvent,
  CarPlayNotificationConfig,
  CarPlayNotificationSettings,
  CarPlayTransport,
} from "../ExpoBeacon.types";

export interface UseCarPlayOptions {
  onConnected?: (event: CarPlayConnectedEvent) => void;
  onDisconnected?: (event: CarPlayDisconnectedEvent) => void;
  /**
   * Call `startCarPlayMonitoring()` automatically on mount when monitoring is
   * not already enabled. Default: `false`.
   */
  autoStart?: boolean;
}

export interface UseCarPlayResult {
  /** Whether a CarPlay / Android Auto session is currently active. */
  connected: boolean;
  /** Transport of the active session, or `null` when disconnected. */
  transport: CarPlayTransport | null;
  /** Whether persistent CarPlay / Android Auto monitoring is enabled. */
  isMonitoring: boolean;
  /** Epoch ms of the most recent connect, or `null`. */
  lastConnectedAt: number | null;
  /** Epoch ms of the most recent disconnect, or `null`. */
  lastDisconnectedAt: number | null;

  /** Enable persistent CarPlay / Android Auto monitoring. */
  startMonitoring: () => Promise<void>;
  /** Disable CarPlay / Android Auto monitoring. */
  stopMonitoring: () => Promise<void>;
  /** Re-read the current connection + monitoring state from native. */
  refresh: () => void;
  /** Fetch CarPlay / Android Auto detection diagnostics (troubleshooting). */
  getDiagnostics: () => CarPlayDiagnostics;
  /** Persist only CarPlay / Android Auto notification settings. */
  setCarPlayNotificationConfig: (
    config: CarPlayNotificationSettings | CarPlayNotificationConfig,
  ) => void;
}

/**
 * Observe and control CarPlay / Android Auto connection monitoring.
 *
 * Initializes from the persisted native state on mount, subscribes to
 * connect / disconnect events for the lifetime of the component, and keeps
 * `connected`, `transport`, and `isMonitoring` in sync. Callbacks passed in
 * `options` are always invoked with the latest values without re-subscribing.
 *
 * ```tsx
 * const { connected, transport, startMonitoring } = useCarPlay({
 *   onConnected: (e) => console.log("car connected via", e.transport),
 * });
 * ```
 */
export function useCarPlay(options: UseCarPlayOptions = {}): UseCarPlayResult {
  const handlers = useRef(options);
  handlers.current = options;

  const [connected, setConnected] = useState(false);
  const [transport, setTransport] = useState<CarPlayTransport | null>(null);
  const [isMonitoring, setIsMonitoring] = useState(false);
  const [lastConnectedAt, setLastConnectedAt] = useState<number | null>(null);
  const [lastDisconnectedAt, setLastDisconnectedAt] = useState<number | null>(
    null,
  );

  const refresh = useCallback(() => {
    const status = ExpoBeacon.getCarPlayConnectionStatus();
    setConnected(status.connected);
    setTransport(status.connected ? (status.transport ?? null) : null);
    if (status.connected && typeof status.timestamp === "number") {
      setLastConnectedAt(status.timestamp);
    }
    setIsMonitoring(ExpoBeacon.isCarPlayMonitoringEnabled());
  }, []);

  const startMonitoring = useCallback(async () => {
    await ExpoBeacon.startCarPlayMonitoring();
    setIsMonitoring(true);
  }, []);

  const stopMonitoring = useCallback(async () => {
    await ExpoBeacon.stopCarPlayMonitoring();
    setIsMonitoring(false);
    setConnected(false);
    setTransport(null);
  }, []);

  const getDiagnostics = useCallback(
    () => ExpoBeacon.getCarPlayDiagnostics(),
    [],
  );

  const setCarPlayNotificationConfig = useCallback(
    (config: CarPlayNotificationSettings | CarPlayNotificationConfig) =>
      ExpoBeacon.setCarPlayNotificationConfig(config),
    [],
  );

  // Initialize from native state and subscribe to connection events (once).
  useEffect(() => {
    try {
      refresh();
    } catch {
      /* unsupported platform */
    }

    const connectSub = ExpoBeacon.addListener("onCarPlayConnected", (e) => {
      setConnected(true);
      setTransport(e.transport);
      setLastConnectedAt(e.timestamp);
      handlers.current.onConnected?.(e);
    });
    const disconnectSub = ExpoBeacon.addListener(
      "onCarPlayDisconnected",
      (e) => {
        setConnected(false);
        setTransport(null);
        setLastDisconnectedAt(e.timestamp);
        handlers.current.onDisconnected?.(e);
      },
    );

    if (
      handlers.current.autoStart &&
      !ExpoBeacon.isCarPlayMonitoringEnabled()
    ) {
      startMonitoring().catch(() => {
        /* start failure is reported by the native layer */
      });
    }

    return () => {
      connectSub.remove();
      disconnectSub.remove();
    };
  }, [refresh, startMonitoring]);

  return {
    connected,
    transport,
    isMonitoring,
    lastConnectedAt,
    lastDisconnectedAt,
    startMonitoring,
    stopMonitoring,
    refresh,
    getDiagnostics,
    setCarPlayNotificationConfig,
  };
}
