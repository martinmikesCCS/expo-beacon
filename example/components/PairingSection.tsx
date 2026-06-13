import React from "react";
import { Text, TouchableOpacity, View } from "react-native";
import type { PairedBeacon, PairedEddystone } from "expo-beacon";

import { Section, styles } from "./common";

export function PairingSection({
  pairedBeacons,
  pairedEddystones,
  onUnpair,
  onUnpairEddystone,
}: {
  pairedBeacons: PairedBeacon[];
  pairedEddystones: PairedEddystone[];
  onUnpair: (identifier: string) => void;
  onUnpairEddystone: (identifier: string) => void;
}) {
  return (
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
                onPress={() => onUnpair(b.identifier)}
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
                onPress={() => onUnpairEddystone(e.identifier)}
              >
                <Text style={styles.pairBtnText}>Unpair</Text>
              </TouchableOpacity>
            </View>
          ))}
        </>
      )}
    </Section>
  );
}
