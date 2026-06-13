/**
 * CarPlayUAT — drop-in test panel for verifying iOS CarPlay
 * connect/disconnect reliability after the reconciliation fixes.
 *
 * Usage: import and render inside [App.tsx](App.tsx) (e.g. in a debug section)
 *   import { CarPlayUAT } from "./CarPlayUAT";
 *   ...
 *   <CarPlayUAT />
 *
 * What it verifies (run each scenario on a real device + CarPlay head unit):
 *
 *   1. WIRED CONNECT/DISCONNECT
 *      - Tap "Start CarPlay Monitoring".
 *      - Plug in CarPlay cable → expect "connected" event within ~2s.
 *      - Unplug cable → expect "disconnected" event within ~5s.
 *      - PASS criteria: every connect has a matching disconnect; counts equal.
 *
 *   2. WIRELESS CONNECT/DISCONNECT
 *      - Same as #1 but via Bluetooth pairing. `transport` should be
 *        "wireless" or "bluetooth".
 *
 *   3. RECONCILED DISCONNECT (entitled mode + force-quit)
 *      - Connect CarPlay.
 *      - Force-quit the app from the multitasking switcher while connected.
 *      - Disconnect CarPlay (cable out / wireless off).
 *      - Reopen the app.
 *      - PASS criteria: a disconnect event arrives within a few seconds of
 *        relaunch, AND `reason === "reconciled"` is logged for that event.
 *
 *   4. LONG-SUSPEND DISCONNECT (audio-session path)
 *      - Connect CarPlay, background the app for 10+ minutes, drive >500m.
 *      - Disconnect CarPlay.
 *      - PASS criteria: disconnect arrives within ~minutes (SLC/Visit wakes
 *        the app and resyncIfNeeded picks up the route change).
 *
 *   5. FOREGROUND RESYNC
 *      - Connect CarPlay, background the app, disconnect CarPlay externally,
 *        then reopen the app (do NOT force-quit).
 *      - PASS criteria: disconnect arrives within ~1s of returning to
 *        foreground (didBecomeActive observer fires resyncIfNeeded).
 *
 *   6. TOGGLE STABILITY
 *      - Tap Start/Stop repeatedly (>10 cycles).
 *      - PASS criteria: no duplicate connect events, no leaked observers,
 *        no crashes.
 *
 * The "Connect/Disconnect parity" indicator turns red when counts diverge —
 * a visual smoke test for the reported "doesn't disconnect" bug.
 */
import React, { useEffect, useMemo, useRef, useState } from "react";
import {
  Button,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from "react-native";
import ExpoBeacon, {
  type CarPlayConnectedEvent,
  type CarPlayDisconnectedEvent,
} from "expo-beacon";

type LogEntry = {
  id: string;
  ts: string;
  kind: "connect" | "disconnect" | "info" | "warn";
  text: string;
};

const fmt = (ms: number) => new Date(ms).toISOString().replace("T", " ").replace("Z", "");

export function CarPlayUAT() {
  const [enabled, setEnabled] = useState(false);
  const [connectCount, setConnectCount] = useState(0);
  const [disconnectCount, setDisconnectCount] = useState(0);
  const [reconciledCount, setReconciledCount] = useState(0);
  const [lastTransport, setLastTransport] = useState<string>("—");
  const [log, setLog] = useState<LogEntry[]>([]);
  const idRef = useRef(0);

  const append = (kind: LogEntry["kind"], text: string) => {
    idRef.current += 1;
    const entry: LogEntry = {
      id: String(idRef.current),
      ts: new Date().toISOString().replace("T", " ").replace("Z", ""),
      kind,
      text,
    };
    setLog((prev) => [entry, ...prev].slice(0, 200));
  };

  // Subscribe to CarPlay events for the lifetime of this component.
  useEffect(() => {
    const subConn = ExpoBeacon.addListener(
      "onCarPlayConnected",
      (e: CarPlayConnectedEvent) => {
        setConnectCount((n) => n + 1);
        setLastTransport(e.transport);
        append(
          "connect",
          `CONNECTED transport=${e.transport} t=${fmt(e.timestamp)}`,
        );
      },
    );
    const subDisc = ExpoBeacon.addListener(
      "onCarPlayDisconnected",
      (e: CarPlayDisconnectedEvent) => {
        setDisconnectCount((n) => n + 1);
        if (e.reason === "reconciled") {
          setReconciledCount((n) => n + 1);
          append(
            "warn",
            `DISCONNECTED (reconciled) t=${fmt(e.timestamp)} — synthesized after process restart`,
          );
        } else {
          append("disconnect", `DISCONNECTED t=${fmt(e.timestamp)}`);
        }
      },
    );
    // Reflect persisted state on mount.
    setEnabled(ExpoBeacon.isCarPlayMonitoringEnabled());
    append("info", `UAT mounted (platform=${Platform.OS})`);
    return () => {
      subConn.remove();
      subDisc.remove();
    };
  }, []);

  const start = async () => {
    try {
      await ExpoBeacon.startCarPlayMonitoring();
      setEnabled(true);
      append("info", "startCarPlayMonitoring() resolved");
    } catch (err) {
      append("warn", `startCarPlayMonitoring failed: ${String(err)}`);
    }
  };

  const stop = async () => {
    try {
      await ExpoBeacon.stopCarPlayMonitoring();
      setEnabled(false);
      append("info", "stopCarPlayMonitoring() resolved");
    } catch (err) {
      append("warn", `stopCarPlayMonitoring failed: ${String(err)}`);
    }
  };

  const reset = () => {
    setConnectCount(0);
    setDisconnectCount(0);
    setReconciledCount(0);
    setLog([]);
    idRef.current = 0;
  };

  const parity = useMemo(() => {
    // Allow the leading edge: connectCount === disconnectCount (idle) or
    // connectCount === disconnectCount + 1 (currently connected).
    const diff = connectCount - disconnectCount;
    if (diff === 0) return { ok: true, label: "balanced (idle)" };
    if (diff === 1) return { ok: true, label: "balanced (one connection live)" };
    return { ok: false, label: `IMBALANCED (Δ=${diff})` };
  }, [connectCount, disconnectCount]);

  return (
    <View style={styles.container}>
      <Text style={styles.h1}>CarPlay UAT</Text>
      <Text style={styles.sub}>
        Monitoring: {enabled ? "ON" : "OFF"} · transport: {lastTransport}
      </Text>

      <View style={styles.row}>
        <Button title="Start" onPress={start} disabled={enabled} />
        <View style={styles.spacer} />
        <Button title="Stop" onPress={stop} disabled={!enabled} />
        <View style={styles.spacer} />
        <Button title="Reset counters" onPress={reset} />
      </View>

      <View style={styles.statsRow}>
        <Stat label="connects" value={connectCount} />
        <Stat label="disconnects" value={disconnectCount} />
        <Stat label="reconciled" value={reconciledCount} highlight />
      </View>

      <Text
        style={[styles.parity, parity.ok ? styles.parityOk : styles.parityBad]}
      >
        Parity: {parity.label}
      </Text>

      <Text style={styles.h2}>Event log (newest first)</Text>
      <ScrollView style={styles.log}>
        {log.map((e) => (
          <Text
            key={e.id}
            style={[
              styles.logLine,
              e.kind === "connect" && styles.logConnect,
              e.kind === "disconnect" && styles.logDisconnect,
              e.kind === "warn" && styles.logWarn,
            ]}
          >
            [{e.ts}] {e.text}
          </Text>
        ))}
        {log.length === 0 && (
          <Text style={styles.logLine}>(no events yet)</Text>
        )}
      </ScrollView>
    </View>
  );
}

function Stat({
  label,
  value,
  highlight,
}: {
  label: string;
  value: number;
  highlight?: boolean;
}) {
  return (
    <View style={styles.stat}>
      <Text style={[styles.statValue, highlight && styles.statHighlight]}>
        {value}
      </Text>
      <Text style={styles.statLabel}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    padding: 12,
    borderWidth: 1,
    borderColor: "#888",
    borderRadius: 8,
    margin: 8,
    backgroundColor: "#fafafa",
  },
  h1: { fontSize: 18, fontWeight: "700", marginBottom: 4 },
  h2: { fontSize: 14, fontWeight: "600", marginTop: 12, marginBottom: 4 },
  sub: { fontSize: 12, color: "#444", marginBottom: 8 },
  row: { flexDirection: "row", alignItems: "center", marginBottom: 8 },
  spacer: { width: 8 },
  statsRow: { flexDirection: "row", justifyContent: "space-around", marginVertical: 8 },
  stat: { alignItems: "center" },
  statValue: { fontSize: 22, fontWeight: "700" },
  statHighlight: { color: "#a05a00" },
  statLabel: { fontSize: 11, color: "#555" },
  parity: { textAlign: "center", fontSize: 13, marginVertical: 4, fontWeight: "600" },
  parityOk: { color: "#0a7d2c" },
  parityBad: { color: "#b00020" },
  log: { maxHeight: 220, backgroundColor: "#111", padding: 6, borderRadius: 4 },
  logLine: { color: "#ddd", fontSize: 11, fontFamily: Platform.OS === "ios" ? "Menlo" : "monospace" },
  logConnect: { color: "#7fff7f" },
  logDisconnect: { color: "#ff9f7f" },
  logWarn: { color: "#ffd86b" },
});
