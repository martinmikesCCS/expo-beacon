import React from "react";
import { Button, Text, View } from "react-native";

import { Section, styles } from "./common";

export function CarPlaySection({
  isMonitoring,
  carPlayState,
  carPlayTransport,
  onStart,
  onStop,
  onConfigureNotifications,
}: {
  isMonitoring: boolean;
  carPlayState: "connected" | "disconnected" | "unknown";
  carPlayTransport: string;
  onStart: () => void;
  onStop: () => void;
  onConfigureNotifications: () => void;
}) {
  return (
    <Section title="CarPlay / Android Auto">
      <Text style={styles.hint}>
        Detect when the device connects to a CarPlay (iOS) or Android Auto
        session. When the config plugin is installed, this also auto-starts
        background-geolocation tracking on connect and stops on disconnect.
      </Text>
      <View style={styles.buttonRow}>
        <View style={styles.buttonFlex}>
          <Button
            title={isMonitoring ? "Monitoring ✓" : "Start"}
            onPress={onStart}
            disabled={isMonitoring}
          />
        </View>
        <View style={styles.buttonFlex}>
          <Button title="Stop" onPress={onStop} disabled={!isMonitoring} />
        </View>
      </View>
      <View style={[styles.buttonRow, { marginTop: 8 }]}>
        <View style={styles.buttonFlex}>
          <Button
            title="Configure Notifications"
            onPress={onConfigureNotifications}
          />
        </View>
      </View>
      <Text style={[styles.hint, { marginTop: 8 }]}>
        State: {carPlayState}
        {carPlayTransport ? ` (${carPlayTransport})` : ""}
      </Text>
    </Section>
  );
}
