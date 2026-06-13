import React, { useEffect, useRef, useState } from "react";
import { Button, Text, TextInput, TouchableOpacity, View } from "react-native";
import ExpoBeacon from "expo-beacon";
import type { BeaconScanResult, EddystoneScanResult } from "expo-beacon";

import { AddLog, Section, styles } from "./common";

function upsertByKey<T>(prev: T[], item: T, keyFn: (entry: T) => string): T[] {
  const key = keyFn(item);
  const idx = prev.findIndex((entry) => keyFn(entry) === key);
  if (idx >= 0) {
    const updated = [...prev];
    updated[idx] = item;
    return updated;
  }
  return [...prev, item];
}

export function ScanSection({
  addLog,
  onPair,
  onPairEddystone,
}: {
  addLog: AddLog;
  onPair: (beacon: BeaconScanResult) => void;
  onPairEddystone: (beacon: EddystoneScanResult) => void;
}) {
  // Scan state
  const [isLiveScanning, setIsLiveScanning] = useState(false);
  const [isOneShotScanning, setIsOneShotScanning] = useState(false);
  const [scanResults, setScanResults] = useState<BeaconScanResult[]>([]);
  const [eddystoneResults, setEddystoneResults] = useState<EddystoneScanResult[]>([]);
  const [scanUuid, setScanUuid] = useState("");
  const [scanDuration, setScanDuration] = useState("5000");

  // Refs for continuous scan subscriptions
  const liveScanSubRef = useRef<{ remove: () => void } | null>(null);
  const eddystoneScanSubRef = useRef<{ remove: () => void } | null>(null);

  // Dev-reload hygiene: drop live-scan subscriptions and stop any running
  // continuous scan when this component unmounts.
  useEffect(() => {
    return () => {
      liveScanSubRef.current?.remove();
      liveScanSubRef.current = null;
      eddystoneScanSubRef.current?.remove();
      eddystoneScanSubRef.current = null;
      ExpoBeacon.stopContinuousScan();
    };
  }, []);

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
        setScanResults((prev) =>
          upsertByKey(prev, beacon, (b) => `${b.uuid}-${b.major}-${b.minor}`),
        );
      },
    );

    // Subscribe to onEddystoneFound for Eddystone advertisements
    eddystoneScanSubRef.current = ExpoBeacon.addListener(
      "onEddystoneFound",
      (beacon: EddystoneScanResult) => {
        setEddystoneResults((prev) =>
          upsertByKey(prev, beacon, (b) =>
            b.frameType === "uid"
              ? `${b.namespace}-${b.instance}`
              : `url-${b.url}`,
          ),
        );
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

  return (
    <>
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
      <Section title={`iBeacon Results (${scanResults.length})`}>
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
                  onPress={() => onPair(b)}
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
      <Section title={`Eddystone Results (${eddystoneResults.length})`}>
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
                    onPress={() => onPairEddystone(b)}
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
    </>
  );
}
