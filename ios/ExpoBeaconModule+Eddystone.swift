import Foundation
import CoreBluetooth

extension ExpoBeaconModule {
    // MARK: - Eddystone scanning (one-shot)

    func startEddystoneScan(durationMs: Int) {
        ensureBleScanRunning()

        let timer = DispatchWorkItem { [weak self] in
            self?.stopEddystoneScanAndResolve()
        }
        eddystoneScanTimer = timer
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(durationMs), execute: timer)
    }

    func stopEddystoneScanAndResolve() {
        eddystoneScanTimer?.cancel()
        eddystoneScanTimer = nil
        stopBleScanIfUnneeded()

        // Deduplicate: by namespace:instance for UID, by url for URL
        var seen = Set<String>()
        var deduped: [[String: Any]] = []
        for beacon in eddystoneScannedBeacons.reversed() {
            let key: String
            if let ns = beacon["namespace"] as? String, let inst = beacon["instance"] as? String {
                key = "uid:\(ns):\(inst)"
            } else if let url = beacon["url"] as? String {
                key = "url:\(url)"
            } else {
                continue
            }
            guard !seen.contains(key) else { continue }
            seen.insert(key)
            deduped.append(beacon)
        }

        eddystoneScanPromise?.resolve(deduped)
        eddystoneScanPromise = nil
        eddystoneScannedBeacons = []
    }

    // MARK: - Eddystone frame parsing

    static func parseEddystoneFrame(data: Data, rssi: Int) -> [String: Any]? {
        guard data.count >= 2 else { return nil }
        let frameType = data[0]
        switch frameType {
        case 0x00: // Eddystone-UID
            guard data.count >= 18 else { return nil }
            let txPower = Int(Int8(bitPattern: data[1]))
            let namespace = data[2..<12].map { String(format: "%02x", $0) }.joined()
            let instance = data[12..<18].map { String(format: "%02x", $0) }.joined()
            let distance = calculateDistance(rssi: rssi, txPower: txPower)
            return [
                "frameType": "uid",
                "namespace": namespace,
                "instance": instance,
                "rssi": rssi,
                "distance": distance,
                "txPower": txPower
            ]
        case 0x10: // Eddystone-URL
            guard data.count >= 3 else { return nil }
            let txPower = Int(Int8(bitPattern: data[1]))
            let url = decodeEddystoneURL(data: data)
            let distance = calculateDistance(rssi: rssi, txPower: txPower)
            return [
                "frameType": "url",
                "url": url,
                "rssi": rssi,
                "distance": distance,
                "txPower": txPower
            ]
        default:
            return nil
        }
    }

    // Decodes an Eddystone-URL payload from raw CoreBluetooth service data.
    // data[0]=frameType (0x10), data[1]=txPower, data[2]=scheme index.
    // On Android (AltBeacon), the frame-type and txPower bytes are already
    // stripped, so bytes[0] is the scheme — see ExpoBeaconModule.kt decodeEddystoneUrl.
    static func decodeEddystoneURL(data: Data) -> String {
        guard data.count >= 3 else { return "" }
        let schemes = ["http://www.", "https://www.", "http://", "https://"]
        // SYNC: This suffix table must match decodeEddystoneUrl() in ExpoBeaconModule.kt
        let suffixes: [UInt8: String] = [
            0x00: ".com/", 0x01: ".org/", 0x02: ".edu/", 0x03: ".net/",
            0x04: ".info/", 0x05: ".biz/", 0x06: ".gov/",
            0x07: ".com", 0x08: ".org", 0x09: ".edu", 0x0A: ".net",
            0x0B: ".info", 0x0C: ".biz", 0x0D: ".gov"
        ]
        let schemeIndex = Int(data[2])
        guard schemeIndex < schemes.count else { return "" }
        var url = schemes[schemeIndex]
        for i in 3..<data.count {
            let byte = data[i]
            if let suffix = suffixes[byte] {
                url += suffix
            } else if byte >= 0x20 && byte <= 0x7E {
                url += String(UnicodeScalar(byte))
            }
        }
        return url
    }

    /// Log-distance path loss model: distance = 10 ^ ((txPower - rssi) / (10 * n)), n = 2.0
    /// Eddystone txPower is calibrated at 0 m; subtract 41 dB to convert to 1 m reference.
    /// Note: On Android, AltBeacon provides distance via its own model — values may differ slightly.
    static func calculateDistance(rssi: Int, txPower: Int) -> Double {
        guard rssi != 0 else { return -1 }
        let txPowerAt1m = Double(txPower - 41)
        let ratio = (txPowerAt1m - Double(rssi)) / 20.0
        let distance = pow(10.0, ratio)
        // Clamp to a reasonable maximum to avoid infinity/NaN propagation
        if distance.isNaN || distance.isInfinite || distance > 1000.0 {
            return -1
        }
        return distance
    }

    // MARK: - Eddystone discovery (BLE callback)

    func handleEddystoneDiscovery(advertisementData: [String: Any], rssi: NSNumber) {
        guard let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
              let data = serviceData[EDDYSTONE_SERVICE_UUID] else { return }

        let beaconRssi = rssi.intValue
        guard let beacon = ExpoBeaconModule.parseEddystoneFrame(data: data, rssi: beaconRssi) else { return }

        // Augment with the BLE advertising device name if present
        var beaconInfo = beacon
        if let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String {
            beaconInfo["name"] = localName
        }

        if eddystoneScanPromise != nil {
            eddystoneScannedBeacons.append(beaconInfo)
        }

        if continuousScanActive {
            sendLoggedEvent("onEddystoneFound", beaconInfo)
        }

        // Monitoring path only: discard weak signals that produce unreliable
        // distance estimates. One-shot scans and continuous-scan found events
        // are NOT filtered (Android does not filter scans either).
        guard beaconRssi >= minRssiThreshold else { return }

        // Eddystone monitoring: match UID frames against paired list
        guard eddystoneMonitoringActive,
              let ns = beacon["namespace"] as? String,
              let inst = beacon["instance"] as? String,
              let distance = beacon["distance"] as? Double else { return }

        let pairedEddystones = loadPairedEddystonesRaw()
        for paired in pairedEddystones {
            guard let identifier = paired["identifier"] as? String,
                  let pns = paired["namespace"] as? String,
                  let pinst = paired["instance"] as? String,
                  pns.lowercased() == ns && pinst.lowercased() == inst else { continue }

            eddystoneLatestSeen[identifier] = Date()
            // Valid BLE reading — reset inactivity timer.
            rescheduleEddystoneInactivity(identifier: identifier, namespace: ns, instance: inst)

            // Distance-driven enter/exit with hysteresis — evaluated on every
            // BLE callback (not throttled) so the hysteresis counters advance
            // reliably regardless of advertisement rate.
            let maxDist = maxDistanceThreshold
            let exitDist = exitDistanceThreshold
            let hasValidDistance = distance.isFinite && distance >= 0
            if hasValidDistance || maxDist == nil {
                // Apply EMA smoothing; jump resets EMA to the new value
                let effectiveDistance: Double
                if hasValidDistance {
                    effectiveDistance = smoothDistance(identifier: identifier, rawDistance: distance)
                } else {
                    effectiveDistance = distance
                }
                let action = evaluateDistanceHysteresis(
                    identifier: identifier,
                    distance: effectiveDistance,
                    maxDistance: maxDist,
                    exitDistance: exitDist,
                    entered: &eddystoneEnteredRegions,
                    enterCtrs: &eddystoneEnterCounters,
                    exitCtrs: &eddystoneExitCounters
                )
                switch action {
                case .enter:
                    sendLoggedEvent("onEddystoneEnter", [
                        "identifier": identifier,
                        "namespace": ns,
                        "instance": inst,
                        "event": "enter",
                        "distance": distance,
                        "rssi": beaconRssi
                    ])
                    postBeaconNotification(identifier: identifier, eventType: "enter")
                    // Beacon returned — cancel any running timeout timer.
                    cancelEddystoneTimeout(identifier: identifier)
                case .exit:
                    smoothedDistances.removeValue(forKey: identifier)
                    sendLoggedEvent("onEddystoneExit", [
                        "identifier": identifier,
                        "namespace": ns,
                        "instance": inst,
                        "event": "exit",
                        "distance": distance,
                        "rssi": beaconRssi
                    ])
                    postBeaconNotification(identifier: identifier, eventType: "exit")
                    // Beacon left — cancel inactivity timer and start the timeout clock.
                    cancelEddystoneInactivity(identifier: identifier)
                    scheduleEddystoneTimeout(identifier: identifier, namespace: ns, instance: inst)
                case .none:
                    break
                }
            }

            guard hasValidDistance else { break }
            guard self.eventLevel == "all" else { break }

            // Throttle distance events — enter/exit above is evaluated on every
            // callback, but distance events are rate-limited to avoid flooding JS.
            let now = Date()
            if let lastEmit = eddystoneLastDistanceEmit[identifier],
               now.timeIntervalSince(lastEmit) < DISTANCE_EVENT_THROTTLE_INTERVAL {
                break
            }
            eddystoneLastDistanceEmit[identifier] = now

            sendLoggedEvent("onEddystoneDistance", [
                "identifier": identifier,
                "namespace": ns,
                "instance": inst,
                "distance": distance,
                "rssi": beaconRssi
            ])
            break
        }
    }

    // MARK: - BLE scan lifecycle

    func ensureBleScanRunning() {
        if centralManager == nil {
            centralManager = CBCentralManager(
                delegate: bluetoothDelegate,
                queue: .main,
                options: [CBCentralManagerOptionRestoreIdentifierKey: "expo.beacon.eddystone"]
            )
        } else if centralManager?.state == .poweredOn {
            centralManager?.scanForPeripherals(
                withServices: [EDDYSTONE_SERVICE_UUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
        }
    }

    /// Rejects an in-flight one-shot Eddystone scan and disarms its timer so a
    /// stale timer cannot prematurely resolve a future scan. Safe to call when
    /// no scan is active.
    func failEddystoneScan(code: String, message: String) {
        guard eddystoneScanPromise != nil else { return }
        eddystoneScanTimer?.cancel()
        eddystoneScanTimer = nil
        eddystoneScannedBeacons.removeAll()
        eddystoneScanPromise?.reject(code, message)
        eddystoneScanPromise = nil
        stopBleScanIfUnneeded()
    }

    func stopBleScanIfUnneeded() {
        guard eddystoneScanTimer == nil && !continuousScanActive && !eddystoneMonitoringActive else { return }
        centralManager?.stopScan()
        centralManager = nil
    }

    // MARK: - Eddystone monitoring

    func startEddystoneMonitoring() {
        eddystoneMonitoringActive = true
        ensureBleScanRunning()

        // Timer to detect exit (beacon disappears from BLE advertisements).
        // Add it to the main run loop explicitly — Timer.scheduledTimer uses the
        // current thread's run loop, which only exists on the main thread.
        let timer = Timer(timeInterval: EDDYSTONE_MONITORING_TICK_INTERVAL, repeats: true) { [weak self] _ in
            self?.eddystoneMonitoringTick()
        }
        RunLoop.main.add(timer, forMode: .common)
        eddystoneMonitoringTimer = timer
    }

    func stopEddystoneMonitoring() {
        eddystoneMonitoringActive = false
        eddystoneMonitoringTimer?.invalidate()
        eddystoneMonitoringTimer = nil
        eddystoneLatestSeen.removeAll()
        eddystoneEnteredRegions.removeAll()
        eddystoneEnterCounters.removeAll()
        eddystoneExitCounters.removeAll()
        eddystoneLastDistanceEmit.removeAll()
        // Eddystone smoothed distances are in the shared smoothedDistances map;
        // they are cleaned up when stopRegionMonitoring clears the entire map.

        for timer in eddystoneTimeoutTimers.values { timer.cancel() }
        eddystoneTimeoutTimers.removeAll()

        for timer in eddystoneInactivityTimers.values { timer.cancel() }
        eddystoneInactivityTimers.removeAll()

        stopBleScanIfUnneeded()
    }

    func eddystoneMonitoringTick() {
        guard !eddystoneEnteredRegions.isEmpty else { return }

        let now = Date()
        let pairedEddystones = loadPairedEddystonesRaw()

        for paired in pairedEddystones {
            guard let identifier = paired["identifier"] as? String else { continue }

            if let lastSeen = eddystoneLatestSeen[identifier], now.timeIntervalSince(lastSeen) < EDDYSTONE_RECENTLY_SEEN_THRESHOLD {
                continue
            }

            // Not seen recently — reset exit counter but preserve enter counter
            // so that background BLE throttling gaps don't force re-accumulating
            // ENTER_HYSTERESIS_COUNT reads.
            eddystoneExitCounters[identifier] = 0
            guard eddystoneEnteredRegions.contains(identifier) else { continue }

            // Fire exit only after silence exceeds the configured exitTimeoutSeconds.
            let silentLongEnough: Bool
            if let lastSeen = eddystoneLatestSeen[identifier] {
                silentLongEnough = now.timeIntervalSince(lastSeen) >= exitTimeoutSeconds
            } else {
                silentLongEnough = false
            }

            if silentLongEnough {
                eddystoneEnteredRegions.remove(identifier)
                eddystoneEnterCounters[identifier] = 0
                eddystoneExitCounters[identifier] = 0
                eddystoneLatestSeen.removeValue(forKey: identifier)
                smoothedDistances.removeValue(forKey: identifier)

                let ns = paired["namespace"] as? String ?? ""
                let inst = paired["instance"] as? String ?? ""
                let params: [String: Any] = [
                    "identifier": identifier,
                    "namespace": ns,
                    "instance": inst,
                    "event": "exit",
                    "distance": -1
                ]
                sendLoggedEvent("onEddystoneExit", params)
                postBeaconNotification(identifier: identifier, eventType: "exit")
                // Beacon disappeared — cancel inactivity timer and start the timeout clock.
                cancelEddystoneInactivity(identifier: identifier)
                scheduleEddystoneTimeout(identifier: identifier, namespace: ns, instance: inst)
            }
        }
    }

    // MARK: - Bluetooth state errors (called from BluetoothDelegate)

    func handleBluetoothStateError(code: String, message: String) {
        sendLoggedEvent("onBeaconError", [
            "identifier": "",
            "code": code,
            "message": message
        ])
    }
}
