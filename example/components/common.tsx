import React from "react";
import { Platform, StyleSheet, Text, View } from "react-native";

export interface EventLogEntry {
  id: string;
  timestamp: string;
  message: string;
  type: "enter" | "exit" | "info";
}

export type AddLog = (
  message: string,
  type?: EventLogEntry["type"],
  occurredAt?: number,
) => void;

export function Section({
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

export const styles = StyleSheet.create({
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
