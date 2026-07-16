import React, { useState } from "react";
import { Button, Text, View } from "react-native";
import ExpoBeacon from "expo-beacon";

import { AddLog, Section, styles } from "./common";

export function DiagnosticsSection({ addLog }: { addLog: AddLog }) {
  const [snapshot, setSnapshot] = useState<string | null>(null);

  const handleFetch = () => {
    setSnapshot(
      JSON.stringify(
        {
          monitoringConfig: ExpoBeacon.getMonitoringConfig(),
          monitoredDeviceStates: ExpoBeacon.getMonitoredDeviceStates(),
        },
        null,
        2
      )
    );
    addLog("Diagnostics fetched");
  };

  return (
    <Section title="Diagnostics">
      <Text style={styles.hint}>
        getMonitoringConfig + getMonitoredDeviceStates
      </Text>
      <Button title="Fetch Diagnostics" onPress={handleFetch} />
      {snapshot !== null && (
        <View style={[styles.card, { marginTop: 12 }]}>
          <Text style={styles.cardTitle}>{snapshot}</Text>
        </View>
      )}
    </Section>
  );
}
