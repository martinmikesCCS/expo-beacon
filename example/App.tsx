import React, { useState, useEffect, useCallback, useRef } from "react";
import { Button, SafeAreaView, ScrollView, Text } from "react-native";
import ExpoBeacon from "expo-beacon";
import type {
  BeaconScanResult,
  PairedBeacon,
  BeaconRegionEvent,
  BeaconDistanceEvent,
  BeaconTimeoutEvent,
  BeaconErrorEvent,
  EddystoneScanResult,
  PairedEddystone,
  EddystoneRegionEvent,
  EddystoneDistanceEvent,
  EddystoneTimeoutEvent,
  EventLogEntry as NativeEventLogEntry,
  CarPlayConnectedEvent,
  CarPlayDisconnectedEvent,
} from "expo-beacon";

import { CarPlayUAT } from "./CarPlayUAT";
import { EventLogEntry, Section, styles } from "./components/common";
import { ApiSection } from "./components/ApiSection";
import { CarPlaySection } from "./components/CarPlaySection";
import { DiagnosticsSection } from "./components/DiagnosticsSection";
import { EventLogSection } from "./components/EventLogSection";
import { MonitoringSection } from "./components/MonitoringSection";
import { PairingSection } from "./components/PairingSection";
import { PermissionsSection } from "./components/PermissionsSection";
import { ScanSection } from "./components/ScanSection";

const formatCarPlayEventDateTime = (
  timestamp?: number,
  timestampIso?: string,
) => {
  if (typeof timestamp === "number" && Number.isFinite(timestamp)) {
    return new Date(timestamp).toLocaleString();
  }
  if (typeof timestampIso === "string") {
    const parsed = Date.parse(timestampIso);
    if (!Number.isNaN(parsed)) {
      return new Date(parsed).toLocaleString();
    }
  }
  return "unknown time";
};

export default function App() {
  // Paired beacons
  const [pairedBeacons, setPairedBeacons] = useState<PairedBeacon[]>([]);
  const [pairedEddystones, setPairedEddystones] = useState<PairedEddystone[]>(
    [],
  );

  // Event log
  const [eventLog, setEventLog] = useState<EventLogEntry[]>([]);

  // Event logging (SQLite)
  const [isLoggingEnabled, setIsLoggingEnabled] = useState(false);
  const [nativeEventLogs, setNativeEventLogs] = useState<NativeEventLogEntry[]>(
    [],
  );

  // CarPlay / Android Auto monitoring
  const [isCarPlayMonitoring, setIsCarPlayMonitoring] = useState(false);
  const [carPlayState, setCarPlayState] = useState<
    "connected" | "disconnected" | "unknown"
  >("unknown");
  const [carPlayTransport, setCarPlayTransport] = useState<string>("");

  // CarPlay UAT debug panel
  const [showCarPlayUAT, setShowCarPlayUAT] = useState(false);

  const logIdRef = useRef(0);

  const addLog = useCallback(
    (
      message: string,
      type: EventLogEntry["type"] = "info",
      occurredAt?: number,
    ) => {
      const id = String(++logIdRef.current);
      const entryDate =
        typeof occurredAt === "number" && Number.isFinite(occurredAt)
          ? new Date(occurredAt)
          : new Date();
      setEventLog((prev) =>
        [
          {
            id,
            timestamp: entryDate.toLocaleString(),
            message,
            type,
          },
          ...prev,
        ].slice(0, 50),
      );
    },
    [],
  );

  // Subscribe to monitoring enter/exit/distance/error events
  useEffect(() => {
    const enterSub = ExpoBeacon.addListener(
      "onBeaconEnter",
      (event: BeaconRegionEvent) => {
        addLog(
          `ENTERED: ${event.identifier} (${event.uuid}) at ~${event.distance >= 0 ? event.distance.toFixed(1) + "m" : "n/a"}`,
          "enter",
        );
      },
    );
    const exitSub = ExpoBeacon.addListener(
      "onBeaconExit",
      (event: BeaconRegionEvent) => {
        addLog(`EXITED: ${event.identifier} (${event.uuid})`, "exit");
      },
    );
    const distSub = ExpoBeacon.addListener(
      "onBeaconDistance",
      (event: BeaconDistanceEvent) => {
        addLog(
          `DIST: ${event.identifier} → ${event.distance.toFixed(2)}m`,
          "info",
        );
      },
    );

    const eddyEnterSub = ExpoBeacon.addListener(
      "onEddystoneEnter",
      (event: EddystoneRegionEvent) => {
        addLog(
          `EDDY ENTERED: ${event.identifier} (${event.namespace})`,
          "enter",
        );
      },
    );
    const eddyExitSub = ExpoBeacon.addListener(
      "onEddystoneExit",
      (event: EddystoneRegionEvent) => {
        addLog(`EDDY EXITED: ${event.identifier} (${event.namespace})`, "exit");
      },
    );
    const eddyDistSub = ExpoBeacon.addListener(
      "onEddystoneDistance",
      (event: EddystoneDistanceEvent) => {
        addLog(
          `EDDY DIST: ${event.identifier} → ${event.distance.toFixed(2)}m`,
          "info",
        );
      },
    );

    const beaconTimeoutSub = ExpoBeacon.addListener(
      "onBeaconTimeout",
      (event: BeaconTimeoutEvent) => {
        addLog(
          `TIMEOUT: ${event.identifier} (${event.uuid}) in range for configured duration at ~${event.distance >= 0 ? event.distance.toFixed(1) + "m" : "n/a"}`,
          "enter",
        );
      },
    );
    const eddyTimeoutSub = ExpoBeacon.addListener(
      "onEddystoneTimeout",
      (event: EddystoneTimeoutEvent) => {
        addLog(
          `EDDY TIMEOUT: ${event.identifier} (${event.namespace}) in range for configured duration`,
          "enter",
        );
      },
    );

    const errorSub = ExpoBeacon.addListener(
      "onBeaconError",
      (event: BeaconErrorEvent) => {
        addLog(
          `ERROR [${event.code}]${event.identifier ? ` ${event.identifier}:` : ""} ${event.message}`,
          "exit",
        );
      },
    );

    return () => {
      enterSub.remove();
      exitSub.remove();
      distSub.remove();
      eddyEnterSub.remove();
      eddyExitSub.remove();
      eddyDistSub.remove();
      beaconTimeoutSub.remove();
      eddyTimeoutSub.remove();
      errorSub.remove();
    };
  }, [addLog]);

  // Subscribe to CarPlay / Android Auto connection events
  useEffect(() => {
    const connectSub = ExpoBeacon.addListener(
      "onCarPlayConnected",
      (event: CarPlayConnectedEvent) => {
        setCarPlayState("connected");
        setCarPlayTransport(event.transport);
        const eventDateTime = formatCarPlayEventDateTime(
          event.timestamp,
          event.timestampIso,
        );
        addLog(
          `CarPlay CONNECTED (${event.transport}) at ${eventDateTime}`,
          "enter",
          event.timestamp,
        );
      },
    );
    const disconnectSub = ExpoBeacon.addListener(
      "onCarPlayDisconnected",
      (event: CarPlayDisconnectedEvent) => {
        setCarPlayState("disconnected");
        setCarPlayTransport("");
        const eventDateTime = formatCarPlayEventDateTime(
          event.timestamp,
          event.timestampIso,
        );
        const reasonSuffix =
          event.reason === "reconciled" ? " (reconciled)" : "";
        addLog(
          `CarPlay DISCONNECTED${reasonSuffix} at ${eventDateTime}`,
          "exit",
          event.timestamp,
        );
      },
    );
    return () => {
      connectSub.remove();
      disconnectSub.remove();
    };
  }, [addLog]);

  // Load paired beacons on mount
  useEffect(() => {
    refreshPairedBeacons();
  }, []);

  // Rehydrate persisted toggles and the current CarPlay connection state from
  // native on mount, and load existing log history so the user always sees
  // previously recorded events — even when event logging is currently turned
  // off, after an app cold-start, or after the JS bundle was killed.
  useEffect(() => {
    setIsCarPlayMonitoring(ExpoBeacon.isCarPlayMonitoringEnabled());
    setIsLoggingEnabled(ExpoBeacon.isEventLoggingEnabled());
    const status = ExpoBeacon.getCarPlayConnectionStatus();
    setCarPlayState(status.connected ? "connected" : "disconnected");
    setCarPlayTransport(status.connected ? (status.transport ?? "") : "");
    addLog(
      `CarPlay status on launch: ${status.connected ? `connected (${status.transport ?? "unknown"})` : "disconnected"}`,
    );
    setNativeEventLogs(ExpoBeacon.getEventLogs({ limit: 50 }));
  }, [addLog]);

  const refreshPairedBeacons = () => {
    setPairedBeacons(ExpoBeacon.getPairedBeacons());
    setPairedEddystones(ExpoBeacon.getPairedEddystones());
  };

  // ── Pairing ──

  const handlePair = (beacon: BeaconScanResult) => {
    const identifier = `beacon-${beacon.uuid.slice(0, 8)}-${beacon.major}-${beacon.minor}`;
    ExpoBeacon.pairBeacon(identifier, beacon.uuid, beacon.major, beacon.minor);
    refreshPairedBeacons();
    addLog(`Paired: ${identifier}`);
  };

  const handleUnpair = (identifier: string) => {
    ExpoBeacon.unpairBeacon(identifier);
    refreshPairedBeacons();
    addLog(`Unpaired: ${identifier}`);
  };

  const handlePairEddystone = (beacon: EddystoneScanResult) => {
    if (beacon.frameType !== "uid" || !beacon.namespace || !beacon.instance)
      return;
    const identifier = `eddy-${beacon.namespace.slice(0, 8)}-${beacon.instance}`;
    ExpoBeacon.pairEddystone(
      identifier,
      beacon.namespace,
      beacon.instance,
      beacon.name ?? identifier,
      30,
    );
    refreshPairedBeacons();
    addLog(`Paired Eddystone: ${identifier}`);
  };

  const handleUnpairEddystone = (identifier: string) => {
    ExpoBeacon.unpairEddystone(identifier);
    refreshPairedBeacons();
    addLog(`Unpaired Eddystone: ${identifier}`);
  };

  // ── CarPlay / Android Auto ──

  const handleStartCarPlay = async () => {
    try {
      await ExpoBeacon.startCarPlayMonitoring();
      setIsCarPlayMonitoring(true);
      addLog("CarPlay monitoring started ✓");
    } catch (e: any) {
      addLog(`CarPlay start failed: ${e.message}`);
    }
  };

  const handleStopCarPlay = async () => {
    try {
      await ExpoBeacon.stopCarPlayMonitoring();
      setIsCarPlayMonitoring(false);
      setCarPlayState("unknown");
      setCarPlayTransport("");
      addLog("CarPlay monitoring stopped");
    } catch (e: any) {
      addLog(`CarPlay stop failed: ${e.message}`);
    }
  };

  const handleConfigureCarPlayNotifications = () => {
    ExpoBeacon.setCarPlayNotificationConfig({
      events: {
        connectedTitle: "Vehicle connected",
        disconnectedTitle: "Vehicle disconnected",
        body: "CarPlay {event} {transport}",
      },
      foregroundService: {
        title: "expo-beacon example",
        text: "Monitoring CarPlay / Android Auto",
      },
      channel: {
        name: "CarPlay Alerts",
        description: "CarPlay and Android Auto connection notifications",
        importance: "default",
      },
    });
    addLog("CarPlay notification config updated ✓");
  };

  // ── Event Logging (SQLite) ──

  const handleToggleLogging = () => {
    if (isLoggingEnabled) {
      ExpoBeacon.disableEventLogging();
      setIsLoggingEnabled(false);
      addLog("Event logging disabled");
    } else {
      ExpoBeacon.enableEventLogging();
      setIsLoggingEnabled(true);
      addLog("Event logging enabled ✓");
    }
    // Always refresh the persisted log view so history stays visible
    // regardless of whether logging is currently on or off.
    try {
      setNativeEventLogs(ExpoBeacon.getEventLogs({ limit: 50 }));
    } catch {
      /* ignore */
    }
  };

  const handleGetLogs = () => {
    const logs = ExpoBeacon.getEventLogs({ limit: 50 });
    setNativeEventLogs(logs);
    addLog(`Fetched ${logs.length} event log(s)`);
  };

  const handleClearLogs = () => {
    ExpoBeacon.clearEventLogs();
    setNativeEventLogs([]);
    addLog("Event logs cleared");
  };

  const handleDestroyLogs = () => {
    ExpoBeacon.destroyEventLogs();
    setIsLoggingEnabled(false);
    setNativeEventLogs([]);
    addLog("Event log database destroyed");
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <Text style={styles.title}>expo-beacon</Text>

        <PermissionsSection addLog={addLog} />

        <CarPlaySection
          isMonitoring={isCarPlayMonitoring}
          carPlayState={carPlayState}
          carPlayTransport={carPlayTransport}
          onStart={handleStartCarPlay}
          onStop={handleStopCarPlay}
          onConfigureNotifications={handleConfigureCarPlayNotifications}
        />

        <ScanSection
          addLog={addLog}
          onPair={handlePair}
          onPairEddystone={handlePairEddystone}
        />

        <PairingSection
          pairedBeacons={pairedBeacons}
          pairedEddystones={pairedEddystones}
          onUnpair={handleUnpair}
          onUnpairEddystone={handleUnpairEddystone}
        />

        <MonitoringSection
          addLog={addLog}
          pairedCount={pairedBeacons.length + pairedEddystones.length}
        />

        <EventLogSection
          eventLog={eventLog}
          onClearEventLog={() => setEventLog([])}
          isLoggingEnabled={isLoggingEnabled}
          onToggleLogging={handleToggleLogging}
          nativeEventLogs={nativeEventLogs}
          onGetLogs={handleGetLogs}
          onClearLogs={handleClearLogs}
          onDestroyLogs={handleDestroyLogs}
        />

        <DiagnosticsSection addLog={addLog} />

        <ApiSection addLog={addLog} />

        {/* ── CarPlay UAT (debug) ── */}
        <Section title="CarPlay UAT">
          <Button
            title={showCarPlayUAT ? "Hide UAT Panel" : "Show UAT Panel"}
            onPress={() => setShowCarPlayUAT((v) => !v)}
          />
          {showCarPlayUAT && <CarPlayUAT />}
        </Section>
      </ScrollView>
    </SafeAreaView>
  );
}
