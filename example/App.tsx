import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  SafeAreaView,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  Button,
  TouchableOpacity,
  Platform,
} from "react-native";
import ExpoBeacon from "expo-beacon";
import type {
  BeaconScanResult,
  PairedBeacon,
  BeaconRegionEvent,
  BeaconDistanceEvent,
} from "expo-beacon";

interface EventLogEntry {
  id: string;
  timestamp: string;
  message: string;
  type: "enter" | "exit" | "info";
}

export default function App() {
  const [isScanning, setIsScanning] = useState(false);
  const [isMonitoring, setIsMonitoring] = useState(false);
  const [maxDistance, setMaxDistance] = useState("");
  const [scanResults, setScanResults] = useState<BeaconScanResult[]>([]);
  const [pairedBeacons, setPairedBeacons] = useState<PairedBeacon[]>([]);
  const [eventLog, setEventLog] = useState<EventLogEntry[]>([]);
  const liveScanSubRef = useRef<{ remove: () => void } | null>(null);
  const logIdRef = useRef(0);

  const addLog = useCallback(
    (message: string, type: EventLogEntry["type"] = "info") => {
      const id = String(++logIdRef.current);
      setEventLog((prev) =>
        [
          {
            id,
            timestamp: new Date().toLocaleTimeString(),
            message,
            type,
          },
          ...prev,
        ].slice(0, 50),
      );
    },
    [],
  );

  // Subscribe to enter/exit/distance events
  useEffect(() => {
    const enterSub = ExpoBeacon.addListener(
      "onBeaconEnter",
      (event: BeaconRegionEvent) => {
        addLog(`ENTERED: ${event.identifier} (${event.uuid})`, "enter");
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
        addLog(`DIST: ${event.identifier} → ${event.distance.toFixed(2)}m`, "info");
      },
    );

    return () => {
      enterSub.remove();
      exitSub.remove();
      distSub.remove();
    };
  }, [addLog]);

  // Load paired beacons on mount
  useEffect(() => {
    refreshPairedBeacons();
  }, []);

  const refreshPairedBeacons = () => {
    const paired = ExpoBeacon.getPairedBeacons();
    setPairedBeacons(paired);
  };

  const handleRequestPermissions = async () => {
    try {
      const granted = await ExpoBeacon.requestPermissionsAsync();
      addLog(granted ? "Permissions granted ✓" : "Permissions denied ✗");
    } catch (e: any) {
      addLog(`Permission error: ${e.message}`);
    }
  };

  const handleStartLiveScan = () => {
    setScanResults([]);
    setIsScanning(true);
    addLog("Live scan started...");
    liveScanSubRef.current = ExpoBeacon.addListener(
      "onBeaconFound",
      (beacon: BeaconScanResult) => {
        setScanResults((prev) => {
          const key = `${beacon.uuid}-${beacon.major}-${beacon.minor}`;
          const idx = prev.findIndex(
            (b) => `${b.uuid}-${b.major}-${b.minor}` === key,
          );
          if (idx >= 0) {
            const updated = [...prev];
            updated[idx] = beacon;
            return updated;
          }
          return [...prev, beacon];
        });
      },
    );
    ExpoBeacon.startContinuousScan();
  };

  const handleStopLiveScan = () => {
    ExpoBeacon.stopContinuousScan();
    liveScanSubRef.current?.remove();
    liveScanSubRef.current = null;
    setIsScanning(false);
    addLog("Live scan stopped");
  };

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

  const handleStartMonitoring = async () => {
    try {
      const dist = maxDistance.trim() !== "" ? parseFloat(maxDistance) : undefined;
      await ExpoBeacon.startMonitoring(dist);
      setIsMonitoring(true);
      addLog(
        dist !== undefined
          ? `Background monitoring started ✓ (≤${dist}m)`
          : "Background monitoring started ✓"
      );
    } catch (e: any) {
      addLog(`Monitor error: ${e.message}`);
    }
  };

  const handleStopMonitoring = async () => {
    try {
      await ExpoBeacon.stopMonitoring();
      setIsMonitoring(false);
      addLog("Background monitoring stopped");
    } catch (e: any) {
      addLog(`Stop error: ${e.message}`);
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <Text style={styles.title}>expo-beacon</Text>

        {/* Permissions */}
        <Section title="Permissions">
          <Button
            title="Request Bluetooth & Location"
            onPress={handleRequestPermissions}
          />
        </Section>

        {/* Scan */}
        <Section title="Scan for Beacons">
          <Button
            title={isScanning ? "Stop Live Scan" : "Start Live Scan"}
            onPress={isScanning ? handleStopLiveScan : handleStartLiveScan}
            color={isScanning ? "#c0392b" : undefined}
          />
          {scanResults.length > 0 && (
            <View style={styles.list}>
              {scanResults.map((b, idx) => (
                <View key={idx} style={styles.card}>
                  <Text style={styles.cardTitle}>{b.uuid}</Text>
                  <Text style={styles.cardMeta}>
                    Major: {b.major} · Minor: {b.minor}
                  </Text>
                  <Text style={styles.cardMeta}>
                    RSSI: {b.rssi} dBm · ~{b.distance.toFixed(1)}m
                  </Text>
                  <TouchableOpacity
                    style={styles.pairBtn}
                    onPress={() => handlePair(b)}
                  >
                    <Text style={styles.pairBtnText}>Pair this beacon</Text>
                  </TouchableOpacity>
                </View>
              ))}
            </View>
          )}
          {scanResults.length === 0 && !isScanning && (
            <Text style={styles.empty}>No beacons found yet</Text>
          )}
        </Section>

        {/* Paired Beacons */}
        <Section title={`Paired Beacons (${pairedBeacons.length})`}>
          {pairedBeacons.length === 0 ? (
            <Text style={styles.empty}>No paired beacons</Text>
          ) : (
            pairedBeacons.map((b) => (
              <View key={b.identifier} style={styles.card}>
                <Text style={styles.cardTitle}>{b.identifier}</Text>
                <Text style={styles.cardMeta}>{b.uuid}</Text>
                <Text style={styles.cardMeta}>
                  Major: {b.major} · Minor: {b.minor}
                </Text>
                <TouchableOpacity
                  style={[styles.pairBtn, { backgroundColor: "#c0392b" }]}
                  onPress={() => handleUnpair(b.identifier)}
                >
                  <Text style={styles.pairBtnText}>Unpair</Text>
                </TouchableOpacity>
              </View>
            ))
          )}
        </Section>

        {/* Monitoring */}
        <Section title="Background Monitoring">
          <View style={styles.row}>
            <Text style={styles.label}>Max distance (m):</Text>
            <TextInput
              style={styles.input}
              value={maxDistance}
              onChangeText={setMaxDistance}
              placeholder="no limit"
              keyboardType="decimal-pad"
              editable={!isMonitoring}
            />
          </View>
          {!isMonitoring ? (
            <Button
              title="Start Monitoring"
              onPress={handleStartMonitoring}
              disabled={pairedBeacons.length === 0}
            />
          ) : (
            <Button
              title="Stop Monitoring"
              onPress={handleStopMonitoring}
              color="#c0392b"
            />
          )}
          {isMonitoring && (
            <View style={styles.statusBadge}>
              <Text style={styles.statusText}>● Monitoring Active</Text>
            </View>
          )}
        </Section>

        {/* Event Log */}
        <Section title="Event Log">
          {eventLog.length === 0 ? (
            <Text style={styles.empty}>No events yet</Text>
          ) : (
            eventLog.map((entry) => (
              <View
                key={entry.id}
                style={[
                  styles.logEntry,
                  entry.type === "enter"
                    ? styles.logEnter
                    : entry.type === "exit"
                      ? styles.logExit
                      : styles.logInfo,
                ]}
              >
                <Text style={styles.logTime}>{entry.timestamp}</Text>
                <Text style={styles.logMsg}>{entry.message}</Text>
              </View>
            ))
          )}
        </Section>
      </ScrollView>
    </SafeAreaView>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <View style={styles.section}>
      <Text style={styles.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: "#f5f5f5" },
  scroll: { padding: 16 },
  title: {
    fontSize: 24,
    fontWeight: "bold",
    textAlign: "center",
    marginBottom: 20,
    color: "#2c3e50",
  },
  section: {
    backgroundColor: "#fff",
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    shadowColor: "#000",
    shadowOpacity: 0.05,
    shadowRadius: 4,
    elevation: 2,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: "600",
    marginBottom: 12,
    color: "#34495e",
  },
  list: { marginTop: 12 },
  card: {
    backgroundColor: "#f8f9fa",
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
  },
  cardTitle: {
    fontSize: 12,
    fontFamily: Platform.OS === "ios" ? "Menlo" : "monospace",
    color: "#2c3e50",
  },
  cardMeta: { fontSize: 12, color: "#7f8c8d", marginTop: 2 },
  pairBtn: {
    backgroundColor: "#3498db",
    borderRadius: 6,
    padding: 8,
    marginTop: 8,
    alignItems: "center",
  },
  pairBtnText: { color: "#fff", fontWeight: "600", fontSize: 13 },
  empty: { color: "#95a5a6", textAlign: "center", paddingVertical: 8 },
  statusBadge: {
    backgroundColor: "#27ae60",
    borderRadius: 6,
    padding: 8,
    marginTop: 8,
    alignItems: "center",
  },
  statusText: { color: "#fff", fontWeight: "600" },
  logEntry: {
    borderRadius: 6,
    padding: 8,
    marginBottom: 4,
    flexDirection: "row",
    gap: 8,
  },
  logEnter: { backgroundColor: "#d5f5e3" },
  logExit: { backgroundColor: "#fadbd8" },
  logInfo: { backgroundColor: "#eaf4fb" },
  row: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 12,
  },
  label: { fontSize: 13, color: "#34495e", marginRight: 8 },
  input: {
    flex: 1,
    borderWidth: 1,
    borderColor: "#bdc3c7",
    borderRadius: 6,
    paddingHorizontal: 10,
    paddingVertical: 6,
    fontSize: 13,
    color: "#2c3e50",
  },
  logTime: { fontSize: 11, color: "#7f8c8d", minWidth: 60 },
  logMsg: { fontSize: 12, color: "#2c3e50", flex: 1 },
});
