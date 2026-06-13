import React from "react";
import { Button, Switch, Text, View } from "react-native";
import type { EventLogEntry as NativeEventLogEntry } from "expo-beacon";

import { EventLogEntry, Section, styles } from "./common";

export function EventLogSection({
  eventLog,
  onClearEventLog,
  isLoggingEnabled,
  onToggleLogging,
  nativeEventLogs,
  onGetLogs,
  onClearLogs,
  onDestroyLogs,
}: {
  eventLog: EventLogEntry[];
  onClearEventLog: () => void;
  isLoggingEnabled: boolean;
  onToggleLogging: () => void;
  nativeEventLogs: NativeEventLogEntry[];
  onGetLogs: () => void;
  onClearLogs: () => void;
  onDestroyLogs: () => void;
}) {
  return (
    <>
      {/* ── Event Log ── */}
      <Section title="Event Log">
        {eventLog.length > 0 && (
          <Button title="Clear Log" onPress={onClearEventLog} color="#95a5a6" />
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

      {/* ── SQLite Event Logging ── */}
      <Section title="SQLite Event Logging">
        <Text style={styles.hint}>
          Persist all beacon events to a local SQLite database for diagnostics
        </Text>
        <View style={styles.row}>
          <Text style={styles.label}>Logging:</Text>
          <Switch value={isLoggingEnabled} onValueChange={onToggleLogging} />
        </View>
        {isLoggingEnabled && (
          <View style={styles.statusBadge}>
            <Text style={styles.statusText}>● Logging Active</Text>
          </View>
        )}
        <View style={styles.buttonRow}>
          <View style={styles.buttonFlex}>
            <Button title="Get Logs (50)" onPress={onGetLogs} />
          </View>
          <View style={styles.buttonFlex}>
            <Button title="Clear Logs" onPress={onClearLogs} color="#e67e22" />
          </View>
          <View style={styles.buttonFlex}>
            <Button title="Destroy DB" onPress={onDestroyLogs} color="#c0392b" />
          </View>
        </View>
        {nativeEventLogs.length > 0 && (
          <View style={styles.list}>
            <Text style={styles.label}>
              Showing {nativeEventLogs.length} log(s):
            </Text>
            {nativeEventLogs.map((log) => (
              <View key={log.id} style={styles.card}>
                <Text style={styles.cardTitle}>{log.eventType}</Text>
                <Text style={styles.cardMeta}>
                  {new Date(log.timestamp).toLocaleString()}
                  {log.identifier ? ` — ${log.identifier}` : ""}
                </Text>
                <Text style={styles.cardMeta} numberOfLines={2}>
                  {JSON.stringify(log.data)}
                </Text>
              </View>
            ))}
          </View>
        )}
      </Section>
    </>
  );
}
