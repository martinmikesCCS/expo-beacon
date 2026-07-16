import Foundation
import ExpoModulesCore

extension ExpoBeaconModule {
    // MARK: - Pairing

    func pairBeacon(identifier: String, uuid: String, major: Int, minor: Int, name: String?, timeoutSeconds: Int?) throws {
        guard !identifier.isEmpty else {
            throw Exception(name: "INVALID_IDENTIFIER", description: "Identifier must not be empty")
        }
        guard let parsedUUID = UUID(uuidString: uuid) else {
            throw Exception(name: "INVALID_UUID", description: "Invalid UUID format: \(uuid)")
        }
        guard (0...65535).contains(major) else {
            throw Exception(name: "INVALID_MAJOR", description: "Major must be 0–65535, got \(major)")
        }
        guard (0...65535).contains(minor) else {
            throw Exception(name: "INVALID_MINOR", description: "Minor must be 0–65535, got \(minor)")
        }
        if let timeoutSeconds, timeoutSeconds <= 0 {
            throw Exception(name: "INVALID_TIMEOUT", description: "timeoutSeconds must be greater than 0")
        }
        // Identifiers are shared across both beacon types (state/smoothing maps
        // key on them) — reject if a paired Eddystone already uses this one.
        guard !loadPairedEddystonesRaw().contains(where: { ($0["identifier"] as? String) == identifier }) else {
            throw Exception(name: "DUPLICATE_IDENTIFIER", description: "Identifier '\(identifier)' is already used by a paired Eddystone")
        }

        var beacons = loadPairedBeaconsRaw()
        let normalizedUUID = parsedUUID.uuidString.uppercased()
        guard !beacons.contains(where: {
            let storedMajor = ($0["major"] as? NSNumber)?.intValue ?? ($0["major"] as? Int ?? -1)
            let storedMinor = ($0["minor"] as? NSNumber)?.intValue ?? ($0["minor"] as? Int ?? -1)
            return ($0["identifier"] as? String) != identifier &&
            (($0["uuid"] as? String).flatMap { UUID(uuidString: $0) }?.uuidString.uppercased() == normalizedUUID) &&
            storedMajor == major &&
            storedMinor == minor
        }) else {
            throw Exception(name: "DUPLICATE_BEACON_IDENTITY", description: "This iBeacon UUID/major/minor is already paired under another identifier")
        }
        beacons.removeAll { ($0["identifier"] as? String) == identifier }
        var entry: [String: Any] = [
            "identifier": identifier,
            "uuid": normalizedUUID,
            "major": major,
            "minor": minor
        ]
        if let name = name { entry["name"] = name }
        if let timeoutSeconds = timeoutSeconds { entry["timeoutSeconds"] = timeoutSeconds }
        beacons.append(entry)
        defaults.set(beacons, forKey: PAIRED_BEACONS_KEY)
        cachedPairedBeacons = nil
        if defaults.bool(forKey: IS_MONITORING_KEY) {
            replaceMonitoredIBeacon(identifier: identifier, with: entry)
        }
    }

    func pairEddystone(identifier: String, namespace: String, instance: String, name: String?, timeoutSeconds: Int?) throws {
        guard !identifier.isEmpty else {
            throw Exception(name: "INVALID_IDENTIFIER", description: "Identifier must not be empty")
        }
        guard namespace.count == 20, namespace.range(of: "^[0-9a-fA-F]+$", options: .regularExpression) != nil else {
            throw Exception(name: "INVALID_NAMESPACE", description: "Namespace must be 20 hex characters, got: \(namespace)")
        }
        guard instance.count == 12, instance.range(of: "^[0-9a-fA-F]+$", options: .regularExpression) != nil else {
            throw Exception(name: "INVALID_INSTANCE", description: "Instance must be 12 hex characters, got: \(instance)")
        }
        if let timeoutSeconds, timeoutSeconds <= 0 {
            throw Exception(name: "INVALID_TIMEOUT", description: "timeoutSeconds must be greater than 0")
        }
        // Identifiers are shared across both beacon types (state/smoothing maps
        // key on them) — reject if a paired iBeacon already uses this one.
        guard !loadPairedBeaconsRaw().contains(where: { ($0["identifier"] as? String) == identifier }) else {
            throw Exception(name: "DUPLICATE_IDENTIFIER", description: "Identifier '\(identifier)' is already used by a paired beacon")
        }

        var eddystones = loadPairedEddystonesRaw()
        let normalizedNamespace = namespace.lowercased()
        let normalizedInstance = instance.lowercased()
        let existing = eddystones.first { ($0["identifier"] as? String) == identifier }
        let identityChanged = existing == nil ||
            (existing?["namespace"] as? String)?.lowercased() != normalizedNamespace ||
            (existing?["instance"] as? String)?.lowercased() != normalizedInstance
        guard !eddystones.contains(where: {
            ($0["identifier"] as? String) != identifier &&
            ($0["namespace"] as? String)?.lowercased() == normalizedNamespace &&
            ($0["instance"] as? String)?.lowercased() == normalizedInstance
        }) else {
            throw Exception(name: "DUPLICATE_EDDYSTONE_IDENTITY", description: "This Eddystone namespace/instance is already paired under another identifier")
        }
        eddystones.removeAll { ($0["identifier"] as? String) == identifier }
        // Normalize hex to lowercase — parseEddystoneFrame produces lowercase,
        // so stored values must match for monitoring comparisons.
        var entry: [String: Any] = [
            "identifier": identifier,
            "namespace": normalizedNamespace,
            "instance": normalizedInstance
        ]
        if let name = name { entry["name"] = name }
        if let timeoutSeconds = timeoutSeconds { entry["timeoutSeconds"] = timeoutSeconds }
        eddystones.append(entry)
        defaults.set(eddystones, forKey: PAIRED_EDDYSTONES_KEY)
        cachedPairedEddystones = nil
        if defaults.bool(forKey: IS_MONITORING_KEY) {
            refreshMonitoredEddystone(identifier: identifier, identityChanged: identityChanged)
        }
    }

    func unpairBeacon(identifier: String) {
        var beacons = loadPairedBeaconsRaw()
        guard beacons.contains(where: { ($0["identifier"] as? String) == identifier }) else { return }
        beacons.removeAll { ($0["identifier"] as? String) == identifier }
        defaults.set(beacons, forKey: PAIRED_BEACONS_KEY)
        cachedPairedBeacons = nil
        removeMonitoredIBeacon(identifier: identifier)
        stopBeaconMonitoringIfNoPairedDevices()
    }

    func unpairEddystone(identifier: String) {
        var eddystones = loadPairedEddystonesRaw()
        guard eddystones.contains(where: { ($0["identifier"] as? String) == identifier }) else { return }
        eddystones.removeAll { ($0["identifier"] as? String) == identifier }
        defaults.set(eddystones, forKey: PAIRED_EDDYSTONES_KEY)
        cachedPairedEddystones = nil
        removeMonitoredEddystone(identifier: identifier)
        if defaults.bool(forKey: IS_MONITORING_KEY), eddystones.isEmpty {
            stopEddystoneMonitoring()
        }
        stopBeaconMonitoringIfNoPairedDevices()
    }

    /// Unpairing the final device ends beacon monitoring.
    private func stopBeaconMonitoringIfNoPairedDevices() {
        guard loadPairedBeaconsRaw().isEmpty,
              loadPairedEddystonesRaw().isEmpty,
              defaults.bool(forKey: IS_MONITORING_KEY) else { return }
        defaults.set(false, forKey: IS_MONITORING_KEY)
        stopRegionMonitoring()
    }

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
        guard !defaults.bool(forKey: SUITE_MIGRATION_FLAG_KEY) else { return }
        // Only the keys that predate the suite migration — later keys never lived in .standard.
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
        defaults.set(true, forKey: SUITE_MIGRATION_FLAG_KEY)
    }
}
