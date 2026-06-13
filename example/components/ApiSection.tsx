import React, { useState } from "react";
import { Button, Text, TextInput, View } from "react-native";
import ExpoBeacon from "expo-beacon";

import { AddLog, Section, styles } from "./common";

export function ApiSection({ addLog }: { addLog: AddLog }) {
  const [url, setUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [forwardId, setForwardId] = useState("");
  const [endpoint, setEndpoint] = useState(() => ExpoBeacon.getApiEndpoint());

  const handleApply = () => {
    ExpoBeacon.setApiEndpoint(
      url.trim(),
      apiKey.trim() || undefined,
      forwardId.trim() || undefined,
    );
    setEndpoint(ExpoBeacon.getApiEndpoint());
    addLog("API endpoint updated ✓");
  };

  return (
    <Section title="API Forwarding">
      <Text style={styles.hint}>
        Events are POSTed directly from native code via setApiEndpoint() —
        delivery works even when the JS bridge is not active
      </Text>
      <View style={styles.row}>
        <Text style={styles.label}>URL:</Text>
        <TextInput
          style={styles.input}
          value={url}
          onChangeText={setUrl}
          placeholder="https://example.com/events"
          autoCapitalize="none"
          keyboardType="url"
        />
      </View>
      <View style={styles.row}>
        <Text style={styles.label}>API key:</Text>
        <TextInput
          style={styles.input}
          value={apiKey}
          onChangeText={setApiKey}
          placeholder="optional"
          autoCapitalize="none"
        />
      </View>
      <View style={styles.row}>
        <Text style={styles.label}>ID:</Text>
        <TextInput
          style={styles.input}
          value={forwardId}
          onChangeText={setForwardId}
          placeholder="optional"
          autoCapitalize="none"
        />
      </View>
      <Button title="Apply API Endpoint" onPress={handleApply} />
      <Text style={[styles.hint, { marginTop: 8 }]}>
        Current: {JSON.stringify(endpoint)}
      </Text>
    </Section>
  );
}
