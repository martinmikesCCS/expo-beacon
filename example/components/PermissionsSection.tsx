import React, { useState } from "react";
import { Button, Platform, Text, View } from "react-native";
import ExpoBeacon from "expo-beacon";

import { AddLog, Section, styles } from "./common";

export function PermissionsSection({ addLog }: { addLog: AddLog }) {
  // Battery optimization (Android)
  const [batteryOptExempt, setBatteryOptExempt] = useState<boolean | null>(null);

  const handleRequestPermissions = async () => {
    try {
      const granted = await ExpoBeacon.requestPermissionsAsync();
      addLog(granted ? "Permissions granted ✓" : "Permissions denied ✗");
    } catch (e: any) {
      addLog(`Permission error: ${e.message}`);
    }
  };

  const handleCheckBatteryOpt = () => {
    const exempt = ExpoBeacon.isBatteryOptimizationExempt();
    setBatteryOptExempt(exempt);
    addLog(exempt ? "Battery optimization: EXEMPT ✓" : "Battery optimization: NOT exempt");
  };

  const handleRequestBatteryOpt = async () => {
    try {
      const result = await ExpoBeacon.requestBatteryOptimizationExemption();
      addLog(result ? "Battery opt dialog shown" : "Battery opt request failed");
      // Re-check after a short delay (user may still be interacting with dialog)
      setTimeout(() => handleCheckBatteryOpt(), 1000);
    } catch (e: any) {
      addLog(`Battery opt error: ${e.message}`);
    }
  };

  return (
    <Section title="Permissions">
      <Button
        title="Request Bluetooth & Location"
        onPress={handleRequestPermissions}
      />
      {Platform.OS === "android" && (
        <View style={{ marginTop: 12 }}>
          <Text style={styles.hint}>
            Samsung and other OEMs may throttle BLE scanning unless battery
            optimization is disabled for this app.
          </Text>
          <View style={styles.buttonRow}>
            <View style={styles.buttonFlex}>
              <Button
                title="Check Battery Opt"
                onPress={handleCheckBatteryOpt}
              />
            </View>
            <View style={styles.buttonFlex}>
              <Button
                title="Request Exemption"
                onPress={handleRequestBatteryOpt}
              />
            </View>
          </View>
          {batteryOptExempt !== null && (
            <View
              style={[
                styles.statusBadge,
                !batteryOptExempt && { backgroundColor: "#e67e22" },
              ]}
            >
              <Text style={styles.statusText}>
                {batteryOptExempt
                  ? "● Battery Opt Exempt"
                  : "● Battery Opt Active (may throttle BLE)"}
              </Text>
            </View>
          )}
        </View>
      )}
    </Section>
  );
}
