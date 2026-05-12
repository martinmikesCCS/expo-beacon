import Foundation

extension ExpoBeaconModule {
    // MARK: - Paired beacon storage

    func loadPairedBeaconsRaw() -> [[String: Any]] {
        if let cached = cachedPairedBeacons { return cached }
        let value = self.defaults.array(forKey: PAIRED_BEACONS_KEY) as? [[String: Any]] ?? []
        cachedPairedBeacons = value
        return value
    }

    func loadPairedEddystonesRaw() -> [[String: Any]] {
        if let cached = cachedPairedEddystones { return cached }
        let value = self.defaults.array(forKey: PAIRED_EDDYSTONES_KEY) as? [[String: Any]] ?? []
        cachedPairedEddystones = value
        return value
    }

    // MARK: - Monitored device state

    func buildMonitoredDeviceState(identifier: String) -> [String: Any?]? {
        if let pairedBeacon = loadPairedBeaconsRaw().first(where: { ($0["identifier"] as? String) == identifier }) {
            return makeMonitoredIBeaconState(from: pairedBeacon)
        }
        if let pairedEddystone = loadPairedEddystonesRaw().first(where: { ($0["identifier"] as? String) == identifier }) {
            return makeMonitoredEddystoneState(from: pairedEddystone)
        }
        return nil
    }

    func buildMonitoredDeviceStates() -> [[String: Any?]] {
        let beaconStates = loadPairedBeaconsRaw().map { makeMonitoredIBeaconState(from: $0) }
        let eddystoneStates = loadPairedEddystonesRaw().map { makeMonitoredEddystoneState(from: $0) }
        return beaconStates + eddystoneStates
    }

    func makeMonitoredIBeaconState(from paired: [String: Any]) -> [String: Any?] {
        let identifier = paired["identifier"] as? String ?? ""
        let isEntered = enteredRegions.contains(identifier)
        let major = (paired["major"] as? Int) ?? (paired["major"] as? NSNumber)?.intValue ?? 0
        let minor = (paired["minor"] as? Int) ?? (paired["minor"] as? NSNumber)?.intValue ?? 0

        return [
            "kind": "ibeacon",
            "identifier": identifier,
            "uuid": paired["uuid"] as? String ?? "",
            "major": major,
            "minor": minor,
            "state": isEntered ? "entered" : "exited",
            "distance": normalizedMonitoringDistance(identifier: identifier, isEntered: isEntered)
        ]
    }

    func makeMonitoredEddystoneState(from paired: [String: Any]) -> [String: Any?] {
        let identifier = paired["identifier"] as? String ?? ""
        let isEntered = eddystoneEnteredRegions.contains(identifier)

        return [
            "kind": "eddystone",
            "identifier": identifier,
            "namespace": paired["namespace"] as? String ?? "",
            "instance": paired["instance"] as? String ?? "",
            "state": isEntered ? "entered" : "exited",
            "distance": normalizedMonitoringDistance(identifier: identifier, isEntered: isEntered)
        ]
    }

    func normalizedMonitoringDistance(identifier: String, isEntered: Bool) -> Double? {
        guard isEntered,
              let distance = smoothedDistances[identifier],
              distance.isFinite,
              distance >= 0 else {
            return nil
        }
        return distance
    }

    // MARK: - UserDefaults migration

    func migrateUserDefaultsIfNeeded() {
        let migrationKey = "expo.beacon.migrated_to_suite_v1"
        guard !defaults.bool(forKey: migrationKey) else { return }
        let keysToMigrate = [
            PAIRED_BEACONS_KEY, PAIRED_EDDYSTONES_KEY,
            IS_MONITORING_KEY, MAX_DISTANCE_KEY, NOTIFICATION_CONFIG_KEY,
            EVENT_LOGGING_ENABLED_KEY
        ]
        for key in keysToMigrate {
            if let value = UserDefaults.standard.object(forKey: key) {
                defaults.set(value, forKey: key)
                UserDefaults.standard.removeObject(forKey: key)
            }
        }
        defaults.set(true, forKey: migrationKey)
    }
}
