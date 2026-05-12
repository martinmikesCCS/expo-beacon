import CoreLocation

extension ExpoBeaconModule {
    // Start UUID-only ranging for each unique paired-beacon UUID.
    // UUID-only constraints discover ALL beacons advertising that UUID,
    // not just the specific major/minor that was paired.
    func startContinuousScanRanging() {
        let beacons = loadPairedBeaconsRaw()
        var seenUUIDs = Set<String>()
        for b in beacons {
            guard
                let uuidString = b["uuid"] as? String,
                let uuid = UUID(uuidString: uuidString)
            else { continue }

            let key = uuid.uuidString.uppercased()
            guard !seenUUIDs.contains(key) else { continue }
            seenUUIDs.insert(key)

            let constraint = CLBeaconIdentityConstraint(uuid: uuid)
            continuousScanOnlyConstraints.append(constraint)
            locationManager.startRangingBeacons(satisfying: constraint)
        }
    }

    func stopScanAndResolve() {
        scanTimer?.cancel()
        scanTimer = nil

        for constraint in scanConstraints {
            locationManager.stopRangingBeacons(satisfying: constraint)
        }
        scanConstraints.removeAll()

        var seen = Set<String>()
        let results: [[String: Any]] = scannedBeacons.compactMap { beacon in
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
