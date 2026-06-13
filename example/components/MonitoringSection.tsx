import React, { useState } from "react";
import { Button, Switch, Text, TextInput, View } from "react-native";
import ExpoBeacon from "expo-beacon";

import { AddLog, Section, styles } from "./common";

export function MonitoringSection({
  addLog,
  pairedCount,
}: {
  addLog: AddLog;
  pairedCount: number;
}) {
  // Monitoring state
  const [isMonitoring, setIsMonitoring] = useState(false);
  const [maxDistance, setMaxDistance] = useState("");
  const [exitDistanceInput, setExitDistanceInput] = useState("");
  const [notificationsEnabled, setNotificationsEnabled] = useState(true);
  const [enterTitle, setEnterTitle] = useState("Beacon nearby!");
  const [exitTitle, setExitTitle] = useState("Beacon out of range");

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
    <>
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
            disabled={pairedCount === 0}
          />
        ) : (
          <Button
            title="Stop Monitoring"
            onPress={handleStopMonitoring}
            color="#c0392b"
          />
        )}
        {pairedCount === 0 && (
          <Text style={styles.hint}>Pair at least one beacon first</Text>
        )}
        {isMonitoring && (
          <View style={styles.statusBadge}>
            <Text style={styles.statusText}>● Monitoring Active</Text>
          </View>
        )}
      </Section>
    </>
  );
}
