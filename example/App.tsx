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
  Switch,
  Platform,
} from "react-native";
import ExpoBeacon from "expo-beacon";
import type {
  BeaconScanResult,
  PairedBeacon,
  BeaconRegionEvent,
  BeaconDistanceEvent,
  EddystoneScanResult,
  PairedEddystone,
  EddystoneRegionEvent,
  EddystoneDistanceEvent,
} from "expo-beacon";

interface EventLogEntry {
  id: string;
  timestamp: string;
  message: string;
  type: "enter" | "exit" | "info";
}

export default function App() {
  // Scan state
  const [isLiveScanning, setIsLiveScanning] = useState(false);
  const [isOneShotScanning, setIsOneShotScanning] = useState(false);
  const [scanResults, setScanResults] = useState<BeaconScanResult[]>([]);
  const [eddystoneResults, setEddystoneResults] = useState<EddystoneScanResult[]>([]);
  const [scanUuid, setScanUuid] = useState("");
  const [scanDuration, setScanDuration] = useState("5000");

  // Monitoring state
  const [isMonitoring, setIsMonitoring] = useState(false);
  const [maxDistance, setMaxDistance] = useState("");
  const [exitDistanceInput, setExitDistanceInput] = useState("");
  const [notificationsEnabled, setNotificationsEnabled] = useState(true);
  const [enterTitle, setEnterTitle] = useState("Beacon nearby!");
  const [exitTitle, setExitTitle] = useState("Beacon out of range");

  // Paired beacons
  const [pairedBeacons, setPairedBeacons] = useState<PairedBeacon[]>([]);
  const [pairedEddystones, setPairedEddystones] = useState<PairedEddystone[]>([]);

  // Event log
  const [eventLog, setEventLog] = useState<EventLogEntry[]>([]);

  // Refs for continuous scan subscriptions
  const liveScanSubRef = useRef<{ remove: () => void } | null>(null);
  const eddystoneScanSubRef = useRef<{ remove: () => void } | null>(null);
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

  // Subscribe to monitoring enter/exit/distance events
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
        addLog(
          `EDDY EXITED: ${event.identifier} (${event.namespace})`,
          "exit",
        );
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

    return () => {
      enterSub.remove();
      exitSub.remove();
      distSub.remove();
      eddyEnterSub.remove();
      eddyExitSub.remove();
      eddyDistSub.remove();
    };
  }, [addLog]);

  // Load paired beacons on mount
  useEffect(() => {
    refreshPairedBeacons();
  }, []);

  const refreshPairedBeacons = () => {
    setPairedBeacons(ExpoBeacon.getPairedBeacons());
    setPairedEddystones(ExpoBeacon.getPairedEddystones());
  };

  // ── Permissions ──

  const handleRequestPermissions = async () => {
    try {
      const granted = await ExpoBeacon.requestPermissionsAsync();
      addLog(granted ? "Permissions granted ✓" : "Permissions denied ✗");
    } catch (e: any) {
      addLog(`Permission error: ${e.message}`);
    }
  };

  // ── One-shot Scan (scanForBeaconsAsync / scanForEddystonesAsync) ──

  const handleOneShotScan = async () => {
    const durationMs = parseInt(scanDuration, 10) || 5000;
    const uuids = scanUuid.trim() ? [scanUuid.trim()] : [];

    setIsOneShotScanning(true);
    setScanResults([]);
    setEddystoneResults([]);
    addLog(
      `One-shot scan started (${durationMs}ms)` +
        (uuids.length > 0 ? ` UUID: ${uuids[0].slice(0, 8)}…` : " (wildcard)"),
    );

    try {
      // Run iBeacon and Eddystone scans in parallel
      const [beacons, eddystones] = await Promise.all([
        ExpoBeacon.scanForBeaconsAsync(uuids, durationMs),
        ExpoBeacon.scanForEddystonesAsync(durationMs),
      ]);

      setScanResults(beacons);
      setEddystoneResults(eddystones);
      addLog(
        `One-shot scan complete: ${beacons.length} iBeacon(s), ${eddystones.length} Eddystone(s)`,
      );
    } catch (e: any) {
      if (e.code === "SCAN_CANCELLED") {
        addLog("Scan cancelled");
      } else {
        addLog(`Scan error: ${e.message}`);
      }
    } finally {
      setIsOneShotScanning(false);
    }
  };

  const handleCancelScan = () => {
    ExpoBeacon.cancelScan();
    addLog("Cancelling scan...");
  };

  // ── Continuous (Live) Scan ──

  const handleStartLiveScan = () => {
    setScanResults([]);
    setEddystoneResults([]);
    setIsLiveScanning(true);
    addLog("Live scan started...");

    // Subscribe to onBeaconFound for iBeacon advertisements
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

    // Subscribe to onEddystoneFound for Eddystone advertisements
    eddystoneScanSubRef.current = ExpoBeacon.addListener(
      "onEddystoneFound",
      (beacon: EddystoneScanResult) => {
        setEddystoneResults((prev) => {
          const key =
            beacon.frameType === "uid"
              ? `${beacon.namespace}-${beacon.instance}`
              : `url-${beacon.url}`;
          const idx = prev.findIndex((b) => {
            const k =
              b.frameType === "uid"
                ? `${b.namespace}-${b.instance}`
                : `url-${b.url}`;
            return k === key;
          });
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
    eddystoneScanSubRef.current?.remove();
    eddystoneScanSubRef.current = null;
    setIsLiveScanning(false);
    addLog("Live scan stopped");
  };

  // ── Pairing ──

  const handlePair = (beacon: BeaconScanResult) => {
    const identifier = `beacon-${beacon.uuid.slice(0, 8)}-${beacon.major}-${beacon.minor}`;
    ExpoBeacon.pairBeacon(
      identifier,
      beacon.uuid,
      beacon.major,
      beacon.minor,
    );
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
    ExpoBeacon.pairEddystone(identifier, beacon.namespace, beacon.instance);
    refreshPairedBeacons();
    addLog(`Paired Eddystone: ${identifier}`);
  };

  const handleUnpairEddystone = (identifier: string) => {
    ExpoBeacon.unpairEddystone(identifier);
    refreshPairedBeacons();
    addLog(`Unpaired Eddystone: ${identifier}`);
  };

  // ── Monitoring with MonitoringOptions ──

  const handleStartMonitoring = async () => {
    try {
      const dist =
        maxDistance.trim() !== "" ? parseFloat(maxDistance) : undefined;
      const exitDist =
        exitDistanceInput.trim() !== "" ? parseFloat(exitDistanceInput) : undefined;

      // Use the full MonitoringOptions object as documented in the README
      await ExpoBeacon.startMonitoring({
        maxDistance: dist,
        exitDistance: exitDist,
        notifications: {
          beaconEvents: {
            enabled: notificationsEnabled,
            enterTitle,
            exitTitle,
            body: "{identifier} {event}ed",
          },
          foregroundService: {
            title: "expo-beacon example",
            text: "Monitoring for nearby beacons",
          },
          channel: {
            name: "Beacon Alerts",
            description: "Beacon enter/exit notifications",
            importance: "default",
          },
        },
      });

      setIsMonitoring(true);
      addLog(
        `Monitoring started ✓` +
          (dist !== undefined ? ` (enter ≤${dist}m` +
            (exitDist !== undefined ? `, exit >${exitDist}m)` : `)`) : "") +
          (notificationsEnabled ? "" : " (notifications off)"),
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

  // ── Notification Config (persistent) ──

  const handleApplyNotificationConfig = () => {
    ExpoBeacon.setNotificationConfig({
      beaconEvents: {
        enabled: notificationsEnabled,
        enterTitle,
        exitTitle,
        body: "{identifier} {event}ed",
      },
    });
    addLog("Notification config updated ✓");
  };

  return (
    <SafeAreaView style={styles.container}>
      <ScrollView contentContainerStyle={styles.scroll}>
        <Text style={styles.title}>expo-beacon</Text>

        {/* ── Permissions ── */}
        <Section title="Permissions">
          <Button
            title="Request Bluetooth & Location"
            onPress={handleRequestPermissions}
          />
        </Section>

        {/* ── One-Shot Scan ── */}
        <Section title="One-Shot Scan">
          <Text style={styles.hint}>
            Runs scanForBeaconsAsync + scanForEddystonesAsync in parallel
          </Text>
          <View style={styles.row}>
            <Text style={styles.label}>UUID filter:</Text>
            <TextInput
              style={styles.input}
              value={scanUuid}
              onChangeText={setScanUuid}
              placeholder="empty = wildcard (Android) / paired UUIDs (iOS)"
              autoCapitalize="characters"
              editable={!isOneShotScanning}
            />
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Duration (ms):</Text>
            <TextInput
              style={[styles.input, { maxWidth: 100 }]}
              value={scanDuration}
              onChangeText={setScanDuration}
              placeholder="5000"
              keyboardType="number-pad"
              editable={!isOneShotScanning}
            />
          </View>
          <View style={styles.buttonRow}>
            <View style={styles.buttonFlex}>
              <Button
                title={isOneShotScanning ? "Scanning…" : "Start One-Shot Scan"}
                onPress={handleOneShotScan}
                disabled={isOneShotScanning || isLiveScanning}
              />
            </View>
            {isOneShotScanning && (
              <View style={styles.buttonFlex}>
                <Button
                  title="Cancel"
                  onPress={handleCancelScan}
                  color="#c0392b"
                />
              </View>
            )}
          </View>
        </Section>

        {/* ── Continuous (Live) Scan ── */}
        <Section title="Continuous (Live) Scan">
          <Text style={styles.hint}>
            Streams onBeaconFound & onEddystoneFound events in real time
          </Text>
          <Button
            title={isLiveScanning ? "Stop Live Scan" : "Start Live Scan"}
            onPress={isLiveScanning ? handleStopLiveScan : handleStartLiveScan}
            color={isLiveScanning ? "#c0392b" : undefined}
            disabled={isOneShotScanning}
          />
        </Section>

        {/* ── iBeacon Results ── */}
        <Section
          title={`iBeacon Results (${scanResults.length})`}
        >
          {scanResults.length > 0 ? (
            <View style={styles.list}>
              {scanResults.map((b, idx) => (
                <View key={idx} style={styles.card}>
                  <Text style={styles.cardTitle}>{b.uuid}</Text>
                  <Text style={styles.cardMeta}>
                    Major: {b.major} · Minor: {b.minor}
                  </Text>
                  <Text style={styles.cardMeta}>
                    RSSI: {b.rssi} dBm · ~{b.distance.toFixed(1)}m · TX:{" "}
                    {b.txPower}
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
          ) : (
            <Text style={styles.empty}>No iBeacons found yet</Text>
          )}
        </Section>

        {/* ── Eddystone Results ── */}
        <Section
          title={`Eddystone Results (${eddystoneResults.length})`}
        >
          {eddystoneResults.length > 0 ? (
            <View style={styles.list}>
              {eddystoneResults.map((b, idx) => (
                <View key={idx} style={styles.card}>
                  <Text style={styles.cardTitle}>
                    {b.frameType === "uid" ? "Eddystone-UID" : "Eddystone-URL"}
                  </Text>
                  {b.frameType === "uid" ? (
                    <>
                      <Text style={styles.cardMeta}>NS: {b.namespace}</Text>
                      <Text style={styles.cardMeta}>
                        Instance: {b.instance}
                      </Text>
                    </>
                  ) : (
                    <Text style={styles.cardMeta}>URL: {b.url}</Text>
                  )}
                  <Text style={styles.cardMeta}>
                    RSSI: {b.rssi} dBm · ~{b.distance.toFixed(1)}m · TX:{" "}
                    {b.txPower}
                  </Text>
                  {b.frameType === "uid" && (
                    <TouchableOpacity
                      style={styles.pairBtn}
                      onPress={() => handlePairEddystone(b)}
                    >
                      <Text style={styles.pairBtnText}>
                        Pair this Eddystone
                      </Text>
                    </TouchableOpacity>
                  )}
                </View>
              ))}
            </View>
          ) : (
            <Text style={styles.empty}>No Eddystone beacons found yet</Text>
          )}
        </Section>

        {/* ── Paired Beacons ── */}
        <Section
          title={`Paired Beacons (${pairedBeacons.length + pairedEddystones.length})`}
        >
          {pairedBeacons.length === 0 && pairedEddystones.length === 0 ? (
            <Text style={styles.empty}>
              No paired beacons — scan and tap "Pair" to add
            </Text>
          ) : (
            <>
              {pairedBeacons.map((b) => (
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
              ))}
              {pairedEddystones.map((e) => (
                <View key={e.identifier} style={styles.card}>
                  <Text style={styles.cardTitle}>{e.identifier}</Text>
                  <Text style={styles.cardMeta}>NS: {e.namespace}</Text>
                  <Text style={styles.cardMeta}>Instance: {e.instance}</Text>
                  <TouchableOpacity
                    style={[styles.pairBtn, { backgroundColor: "#c0392b" }]}
                    onPress={() => handleUnpairEddystone(e.identifier)}
                  >
                    <Text style={styles.pairBtnText}>Unpair</Text>
                  </TouchableOpacity>
                </View>
              ))}
            </>
          )}
        </Section>

        {/* ── Notification Config ── */}
        <Section title="Notification Config">
          <Text style={styles.hint}>
            Persisted via setNotificationConfig() — survives app restarts
          </Text>
          <View style={styles.row}>
            <Text style={styles.label}>Enabled:</Text>
            <Switch
              value={notificationsEnabled}
              onValueChange={setNotificationsEnabled}
            />
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Enter title:</Text>
            <TextInput
              style={styles.input}
              value={enterTitle}
              onChangeText={setEnterTitle}
              placeholder="Beacon nearby!"
            />
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Exit title:</Text>
            <TextInput
              style={styles.input}
              value={exitTitle}
              onChangeText={setExitTitle}
              placeholder="Beacon out of range"
            />
          </View>
          <Button
            title="Apply Notification Config"
            onPress={handleApplyNotificationConfig}
          />
        </Section>

        {/* ── Background Monitoring ── */}
        <Section title="Background Monitoring">
          <Text style={styles.hint}>
            Monitors all paired beacons (iBeacon + Eddystone) using
            MonitoringOptions
          </Text>
          <View style={styles.row}>
            <Text style={styles.label}>Max distance (m):</Text>
            <TextInput
              style={[styles.input, { maxWidth: 100 }]}
              value={maxDistance}
              onChangeText={setMaxDistance}
              placeholder="no limit"
              keyboardType="decimal-pad"
              editable={!isMonitoring}
            />
          </View>
          <View style={styles.row}>
            <Text style={styles.label}>Exit distance (m):</Text>
            <TextInput
              style={[styles.input, { maxWidth: 100 }]}
              value={exitDistanceInput}
              onChangeText={setExitDistanceInput}
              placeholder="auto"
              keyboardType="decimal-pad"
              editable={!isMonitoring}
            />
          </View>
          {!isMonitoring ? (
            <Button
              title="Start Monitoring"
              onPress={handleStartMonitoring}
              disabled={
                pairedBeacons.length === 0 && pairedEddystones.length === 0
              }
            />
          ) : (
            <Button
              title="Stop Monitoring"
              onPress={handleStopMonitoring}
              color="#c0392b"
            />
          )}
          {pairedBeacons.length === 0 && pairedEddystones.length === 0 && (
            <Text style={styles.hint}>Pair at least one beacon first</Text>
          )}
          {isMonitoring && (
            <View style={styles.statusBadge}>
              <Text style={styles.statusText}>● Monitoring Active</Text>
            </View>
          )}
        </Section>

        {/* ── Event Log ── */}
        <Section title="Event Log">
          {eventLog.length > 0 && (
            <Button
              title="Clear Log"
              onPress={() => setEventLog([])}
              color="#95a5a6"
            />
          )}
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
  scroll: { padding: 16, paddingBottom: 40 },
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
  hint: {
    fontSize: 12,
    color: "#95a5a6",
    marginBottom: 10,
    fontStyle: "italic",
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
  buttonRow: {
    flexDirection: "row",
    gap: 8,
  },
  buttonFlex: { flex: 1 },
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
