import CoreLocation
import ExpoModulesCore

extension ExpoBeaconModule {
    /// Unique proximity UUIDs across the paired-beacon list (order preserved).
    func uniquePairedBeaconUUIDs() -> [UUID] {
        var seen = Set<String>()
        var uuids: [UUID] = []
        for b in loadPairedBeaconsRaw() {
            guard
                let uuidString = b["uuid"] as? String,
                let uuid = UUID(uuidString: uuidString)
            else { continue }

            let key = uuid.uuidString.uppercased()
            if seen.insert(key).inserted {
                uuids.append(uuid)
            }
        }
        return uuids
    }

    // MARK: - One-shot scan (scanForBeaconsAsync)

    func scanForBeacons(uuids: [String], durationMs: Int, promise: Promise) {
        guard durationMs > 0 else {
            rejectAndEmit(promise, "INVALID_DURATION", "Scan duration must be a positive integer")
            return
        }
        guard scanPromise == nil else {
            rejectAndEmit(promise, "SCAN_IN_PROGRESS", "A scan is already in progress")
            return
        }

        scanPromise = promise
        let requestID = UUID()
        scanRequestID = requestID

        // Build UUID list — iOS cannot do wildcard iBeacon scans via CoreBluetooth
        // (Apple strips iBeacon data from BLE advertisements). When no UUIDs are
        // provided, fall back to the unique UUIDs of paired beacons.
        var parsedUUIDs: [UUID] = []
        if uuids.isEmpty {
            parsedUUIDs = uniquePairedBeaconUUIDs()
            if parsedUUIDs.isEmpty {
                rejectAndEmit(promise, "WILDCARD_NOT_SUPPORTED",
                    "iOS does not support wildcard iBeacon scanning. " +
                    "Provide at least one proximity UUID, or pair beacons first.")
                scanPromise = nil
                scanRequestID = nil
                return
            }
        } else {
            var seenUUIDs = Set<UUID>()
            for uuidStr in uuids {
                guard let uuid = UUID(uuidString: uuidStr) else {
                    rejectAndEmit(promise, "INVALID_UUID", "Invalid UUID: \(uuidStr)")
                    scanPromise = nil
                    scanRequestID = nil
                    return
                }
                if seenUUIDs.insert(uuid).inserted {
                    parsedUUIDs.append(uuid)
                }
            }
        }

        scannedBeacons = []
        scanConstraints = []

        requestLocationPermission { [weak self] granted in
            guard let self, !self.isModuleDestroyed else {
                promise.reject("MODULE_DESTROYED", "Beacon module was destroyed while requesting permission")
                return
            }
            // The request may have been cancelled (and another scan started)
            // while the system permission sheet was open.
            guard self.scanRequestID == requestID, self.scanPromise != nil else { return }
            guard granted else {
                self.rejectAndEmit(promise, "PERMISSION_DENIED", "Location permission required for beacon scanning")
                self.scanPromise = nil
                self.scanRequestID = nil
                return
            }

            // Range for each requested UUID simultaneously
            for uuid in parsedUUIDs {
                let constraint = CLBeaconIdentityConstraint(uuid: uuid)
                self.scanConstraints.append(constraint)
                if !self.continuousScanOnlyConstraints.contains(where: { $0 == constraint }) {
                    self.locationManager.startRangingBeacons(satisfying: constraint)
                }
            }

            let timer = DispatchWorkItem { [weak self] in
                self?.stopScanAndResolve()
            }
            self.scanTimer = timer
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(durationMs), execute: timer)
        }
    }

    // Start UUID-only ranging for each unique paired-beacon UUID.
    // UUID-only constraints discover ALL beacons advertising that UUID,
    // not just the specific major/minor that was paired.
    func startContinuousScanRanging(uuids: [UUID]? = nil) {
        for uuid in uuids ?? uniquePairedBeaconUUIDs() {
            let constraint = CLBeaconIdentityConstraint(uuid: uuid)
            guard !continuousScanOnlyConstraints.contains(where: { $0 == constraint }) else { continue }
            continuousScanOnlyConstraints.append(constraint)
            if !scanConstraints.contains(where: { $0 == constraint }) {
                locationManager.startRangingBeacons(satisfying: constraint)
            }
        }
    }

    func stopScanAndResolve() {
        scanTimer?.cancel()
        scanTimer = nil

        for constraint in scanConstraints {
            if !continuousScanOnlyConstraints.contains(where: { $0 == constraint }) {
                locationManager.stopRangingBeacons(satisfying: constraint)
            }
        }
        scanConstraints.removeAll()
        scanRequestID = nil

        var seen = Set<String>()
        let results: [[String: Any]] = scannedBeacons.reversed().compactMap { beacon in
            let key = "\(beacon.uuid):\(beacon.major):\(beacon.minor)"
            guard !seen.contains(key) else { return nil }
            seen.insert(key)
            return [
                "uuid": beacon.uuid.uuidString.uppercased(),
                "major": beacon.major.intValue,
                "minor": beacon.minor.intValue,
                "rssi": beacon.rssi,
                "distance": beacon.accuracy,
                "txPower": 0
            ]
        }

        scanPromise?.resolve(results)
        scanPromise = nil
        scannedBeacons = []
    }
}
