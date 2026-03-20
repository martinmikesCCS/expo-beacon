import ExpoModulesCore
import CoreLocation
import CoreBluetooth
import UserNotifications

private let PAIRED_BEACONS_KEY = "expo.beacon.paired"
private let PAIRED_EDDYSTONES_KEY = "expo.beacon.paired_eddystones"
private let IS_MONITORING_KEY = "expo.beacon.is_monitoring"
private let MAX_DISTANCE_KEY = "expo.beacon.max_distance"
private let NOTIFICATION_CONFIG_KEY = "expo.beacon.notification_config"

/// Number of consecutive ranging misses before emitting a distance-based exit event.
private let EXIT_MISS_THRESHOLD = 3
/// Number of consecutive readings required to confirm a distance-based enter or exit transition.
private let HYSTERESIS_COUNT = 3

public class ExpoBeaconModule: Module {

    private lazy var locationDelegate = LocationDelegate(module: self)

    private lazy var locationManager: CLLocationManager = {
        let manager = CLLocationManager()
        manager.delegate = locationDelegate
        let backgroundModes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        if backgroundModes.contains("location") {
            manager.allowsBackgroundLocationUpdates = true
        }
        manager.pausesLocationUpdatesAutomatically = false
        return manager
    }()

    // One-shot scan state
    fileprivate var scanPromise: Promise?
    private var scannedBeacons: [CLBeacon] = []
    private var scanConstraints: [CLBeaconIdentityConstraint] = []

    // Monitored regions
    private var monitoredRegions: [CLBeaconRegion] = []

    // Always-on ranging for distance events + distance-based enter/exit (identifier → constraint)
    private var distanceRangingConstraints: [String: CLBeaconIdentityConstraint] = [:]
    // Identifiers currently in "entered" state (used for distance-driven enter/exit)
    private var enteredRegions: Set<String> = []
    // Consecutive miss counter per identifier (for distance-based exit when beacon disappears)
    private var missCounters: [String: Int] = [:]
    // Hysteresis counters: consecutive readings inside/outside threshold per identifier
    private var enterCounters: [String: Int] = [:]
    private var exitCounters: [String: Int] = [:]

    // Continuous scan state
    private var continuousScanActive = false
    // Constraints started exclusively for continuous scan (not shared with distance ranging)
    private var continuousScanOnlyConstraints: [CLBeaconIdentityConstraint] = []

    // Wildcard (CoreBluetooth) scan state
    private lazy var bluetoothDelegate = BluetoothDelegate(module: self)
    private var centralManager: CBCentralManager?
    private var wildcardScannedBeacons: [[String: Any]] = []
    private var wildcardScanTimer: DispatchWorkItem?

    // Eddystone (CoreBluetooth) scan state
    fileprivate var eddystoneScanPromise: Promise?
    private var eddystoneScannedBeacons: [[String: Any]] = []
    private var eddystoneScanTimer: DispatchWorkItem?

    // Eddystone monitoring state
    private var eddystoneMonitoringActive = false
    private var eddystoneMonitoringTimer: Timer?
    private var eddystoneLatestSeen: [String: Date] = [:]
    private var eddystoneEnteredRegions: Set<String> = []
    private var eddystoneMissCounters: [String: Int] = [:]
    private var eddystoneEnterCounters: [String: Int] = [:]
    private var eddystoneExitCounters: [String: Int] = [:]
    private var eddystoneLastDistanceEmit: [String: Date] = [:]

    // Permission callback
    private var permissionCompletion: ((Bool) -> Void)?

    public func definition() -> ModuleDefinition {
        Name("ExpoBeacon")

        Events("onBeaconEnter", "onBeaconExit", "onBeaconDistance", "onBeaconFound", "onEddystoneFound", "onEddystoneEnter", "onEddystoneExit", "onEddystoneDistance")

        // MARK: - Scan

        AsyncFunction("scanForBeaconsAsync") { (uuids: [String], scanDurationMs: Int, promise: Promise) in
            guard self.scanPromise == nil else {
                promise.reject("SCAN_IN_PROGRESS", "A scan is already in progress")
                return
            }

            self.scanPromise = promise

            // Build UUID list — iOS cannot do wildcard iBeacon scans via CoreBluetooth
            // (Apple strips iBeacon data from BLE advertisements). When no UUIDs are
            // provided, fall back to the unique UUIDs of paired beacons.
            var parsedUUIDs: [UUID] = []
            if uuids.isEmpty {
                let paired = self.loadPairedBeaconsRaw()
                var seen = Set<String>()
                for b in paired {
                    guard let uuidStr = b["uuid"] as? String,
                          let uuid = UUID(uuidString: uuidStr) else { continue }
                    let key = uuid.uuidString.uppercased()
                    if !seen.contains(key) {
                        seen.insert(key)
                        parsedUUIDs.append(uuid)
                    }
                }
                if parsedUUIDs.isEmpty {
                    promise.reject("WILDCARD_NOT_SUPPORTED",
                        "iOS does not support wildcard iBeacon scanning. " +
                        "Provide at least one proximity UUID, or pair beacons first.")
                    self.scanPromise = nil
                    return
                }
            } else {
                for uuidStr in uuids {
                    guard let uuid = UUID(uuidString: uuidStr) else {
                        promise.reject("INVALID_UUID", "Invalid UUID: \(uuidStr)")
                        self.scanPromise = nil
                        return
                    }
                    parsedUUIDs.append(uuid)
                }
            }

            self.scannedBeacons = []
            self.scanConstraints = []

            self.requestLocationPermission { granted in
                guard granted else {
                    promise.reject("PERMISSION_DENIED", "Location permission required for beacon scanning")
                    self.scanPromise = nil
                    return
                }

                // Range for each requested UUID simultaneously
                for uuid in parsedUUIDs {
                    let constraint = CLBeaconIdentityConstraint(uuid: uuid)
                    self.scanConstraints.append(constraint)
                    self.locationManager.startRangingBeacons(satisfying: constraint)
                }

                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(scanDurationMs)) {
                    self.stopScanAndResolve()
                }
            }
        }

        // MARK: - Pair

        Function("pairBeacon") { (identifier: String, uuid: String, major: Int, minor: Int) -> Void in
            guard UUID(uuidString: uuid) != nil else {
                throw Exception(name: "INVALID_UUID", description: "Invalid UUID format: \(uuid)")
            }
            guard (0...65535).contains(major) else {
                throw Exception(name: "INVALID_MAJOR", description: "Major must be 0–65535, got \(major)")
            }
            guard (0...65535).contains(minor) else {
                throw Exception(name: "INVALID_MINOR", description: "Minor must be 0–65535, got \(minor)")
            }

            var beacons = self.loadPairedBeaconsRaw()
            beacons.removeAll { ($0["identifier"] as? String) == identifier }
            beacons.append([
                "identifier": identifier,
                "uuid": uuid,
                "major": major,
                "minor": minor
            ])
            UserDefaults.standard.set(beacons, forKey: PAIRED_BEACONS_KEY)
        }

        Function("unpairBeacon") { (identifier: String) in
            var beacons = self.loadPairedBeaconsRaw()
            beacons.removeAll { ($0["identifier"] as? String) == identifier }
            UserDefaults.standard.set(beacons, forKey: PAIRED_BEACONS_KEY)
        }

        Function("getPairedBeacons") { () -> [[String: Any]] in
            return self.loadPairedBeaconsRaw()
        }

        // MARK: - Eddystone Pair

        Function("pairEddystone") { (identifier: String, namespace: String, instance: String) -> Void in
            var eddystones = self.loadPairedEddystonesRaw()
            eddystones.removeAll { ($0["identifier"] as? String) == identifier }
            eddystones.append([
                "identifier": identifier,
                "namespace": namespace,
                "instance": instance
            ])
            UserDefaults.standard.set(eddystones, forKey: PAIRED_EDDYSTONES_KEY)
        }

        Function("unpairEddystone") { (identifier: String) in
            var eddystones = self.loadPairedEddystonesRaw()
            eddystones.removeAll { ($0["identifier"] as? String) == identifier }
            UserDefaults.standard.set(eddystones, forKey: PAIRED_EDDYSTONES_KEY)
        }

        Function("getPairedEddystones") { () -> [[String: Any]] in
            return self.loadPairedEddystonesRaw()
        }

        // MARK: - Notification Config

        Function("setNotificationConfig") { (config: [String: Any]) in
            if let data = try? JSONSerialization.data(withJSONObject: config),
               let json = String(data: data, encoding: .utf8) {
                UserDefaults.standard.set(json, forKey: NOTIFICATION_CONFIG_KEY)
            }
        }

        // MARK: - Monitoring

        AsyncFunction("startMonitoring") { (options: Either<Double, [String: Any]>?, promise: Promise) in
            var maxDistance: Double? = nil
            if let dist: Double = options?.get() {
                maxDistance = dist
            } else if let map: [String: Any] = options?.get() {
                maxDistance = map["maxDistance"] as? Double
                if let notifications = map["notifications"] as? [String: Any],
                   let data = try? JSONSerialization.data(withJSONObject: notifications),
                   let json = String(data: data, encoding: .utf8) {
                    UserDefaults.standard.set(json, forKey: NOTIFICATION_CONFIG_KEY)
                }
            }
            if let dist = maxDistance {
                UserDefaults.standard.set(dist, forKey: MAX_DISTANCE_KEY)
            } else {
                UserDefaults.standard.removeObject(forKey: MAX_DISTANCE_KEY)
            }
            UserDefaults.standard.set(true, forKey: IS_MONITORING_KEY)
            self.requestLocationPermission(requireAlways: true) { granted in
                guard granted else {
                    promise.reject("PERMISSION_DENIED", "Always location permission required for background monitoring")
                    return
                }
                self.requestNotificationPermission()
                self.startRegionMonitoring()
                promise.resolve(nil)
            }
        }

        AsyncFunction("stopMonitoring") { (promise: Promise) in
            UserDefaults.standard.set(false, forKey: IS_MONITORING_KEY)
            UserDefaults.standard.removeObject(forKey: MAX_DISTANCE_KEY)
            self.stopRegionMonitoring()
            promise.resolve(nil)
        }

        AsyncFunction("requestPermissionsAsync") { (promise: Promise) in
            self.requestLocationPermission { granted in
                promise.resolve(granted)
            }
        }

        // MARK: - Continuous Scan

        Function("startContinuousScan") { () -> Void in
            guard !self.continuousScanActive else { return }
            self.continuousScanActive = true
            // Ranging requires location authorization — request it before starting.
            self.requestLocationPermission { granted in
                guard granted, self.continuousScanActive else {
                    self.continuousScanActive = false
                    return
                }
                self.startContinuousScanRanging()
                // Also start BLE scanning for Eddystone beacons
                self.ensureBleScanRunning()
            }
        }

        Function("stopContinuousScan") { () -> Void in
            self.continuousScanActive = false
            for constraint in self.continuousScanOnlyConstraints {
                self.locationManager.stopRangingBeacons(satisfying: constraint)
            }
            self.continuousScanOnlyConstraints.removeAll()
            self.stopBleScanIfUnneeded()
        }

        // MARK: - Eddystone Scan

        AsyncFunction("scanForEddystonesAsync") { (scanDurationMs: Int, promise: Promise) in
            guard self.eddystoneScanPromise == nil else {
                promise.reject("SCAN_IN_PROGRESS", "An Eddystone scan is already in progress")
                return
            }
            self.eddystoneScanPromise = promise
            self.eddystoneScannedBeacons = []
            self.startEddystoneScan(durationMs: scanDurationMs)
        }
    }

    // MARK: - Private Helpers

    private func requestLocationPermission(requireAlways: Bool = false, completion: @escaping (Bool) -> Void) {
        let status = locationManager.authorizationStatus
        switch status {
        case .authorizedAlways:
            completion(true)
        case .authorizedWhenInUse:
            if requireAlways {
                // Already have whenInUse — request upgrade to always
                self.permissionCompletion = { granted in
                    // After the upgrade prompt, only .authorizedAlways counts
                    let nowStatus = self.locationManager.authorizationStatus
                    completion(nowStatus == .authorizedAlways)
                }
                locationManager.requestAlwaysAuthorization()
            } else {
                completion(true)
            }
        case .notDetermined:
            // Two-step flow: first request whenInUse, then upgrade to always
            self.permissionCompletion = { _ in
                let nowStatus = self.locationManager.authorizationStatus
                if requireAlways && nowStatus == .authorizedWhenInUse {
                    // Got provisional whenInUse — request upgrade to always
                    self.permissionCompletion = { _ in
                        let finalStatus = self.locationManager.authorizationStatus
                        completion(finalStatus == .authorizedAlways)
                    }
                    self.locationManager.requestAlwaysAuthorization()
                } else {
                    completion(nowStatus == .authorizedAlways || nowStatus == .authorizedWhenInUse)
                }
            }
            locationManager.requestWhenInUseAuthorization()
        default:
            completion(false)
        }
    }

    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    private func startRegionMonitoring() {
        stopRegionMonitoring()

        let beacons = loadPairedBeaconsRaw()
        for b in beacons {
            guard
                let identifier = b["identifier"] as? String,
                let uuidString = b["uuid"] as? String,
                let uuid = UUID(uuidString: uuidString),
                let major = b["major"] as? Int,
                let minor = b["minor"] as? Int
            else { continue }

            let region = CLBeaconRegion(
                uuid: uuid,
                major: CLBeaconMajorValue(major),
                minor: CLBeaconMinorValue(minor),
                identifier: identifier
            )
            region.notifyOnEntry = true
            region.notifyOnExit = true
            region.notifyEntryStateOnDisplay = true

            monitoredRegions.append(region)
            locationManager.startMonitoring(for: region)

            // Always-on ranging for distance events + distance-driven enter/exit
            let constraint = CLBeaconIdentityConstraint(
                uuid: uuid,
                major: CLBeaconMajorValue(major),
                minor: CLBeaconMinorValue(minor)
            )
            distanceRangingConstraints[identifier] = constraint
            locationManager.startRangingBeacons(satisfying: constraint)
        }

        // Start Eddystone-UID monitoring if any paired Eddystones exist
        let eddystones = loadPairedEddystonesRaw()
        if !eddystones.isEmpty {
            startEddystoneMonitoring()
        }
    }

    private func stopRegionMonitoring() {
        for region in monitoredRegions {
            locationManager.stopMonitoring(for: region)
        }
        monitoredRegions.removeAll()

        for constraint in distanceRangingConstraints.values {
            locationManager.stopRangingBeacons(satisfying: constraint)
        }
        distanceRangingConstraints.removeAll()
        enteredRegions.removeAll()
        missCounters.removeAll()
        enterCounters.removeAll()
        exitCounters.removeAll()

        stopEddystoneMonitoring()
    }

    // Start UUID-only ranging for each unique paired-beacon UUID.
    // UUID-only constraints discover ALL beacons advertising that UUID,
    // not just the specific major/minor that was paired.
    private func startContinuousScanRanging() {
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

    private func stopScanAndResolve() {
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

    // MARK: - Wildcard (CoreBluetooth) scan

    private func startWildcardScan(durationMs: Int) {
        wildcardScannedBeacons = []

        if centralManager == nil {
            // Creating CBCentralManager triggers a Bluetooth power-on check.
            // Once .poweredOn, the delegate calls beginWildcardScanning().
            centralManager = CBCentralManager(delegate: bluetoothDelegate, queue: .main)
        } else if centralManager?.state == .poweredOn {
            beginWildcardScanning()
        }
        // If state is not yet .poweredOn the delegate will call beginWildcardScanning()
        // when centralManagerDidUpdateState fires.

        let timer = DispatchWorkItem { [weak self] in
            self?.stopWildcardScanAndResolve()
        }
        wildcardScanTimer = timer
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(durationMs), execute: timer)
    }

    fileprivate func beginWildcardScanning() {
        guard scanPromise != nil, wildcardScanTimer != nil else { return }
        centralManager?.scanForPeripherals(
            withServices: nil,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
    }

    fileprivate func handleWildcardDiscovery(advertisementData: [String: Any], rssi: NSNumber) {
        guard let mfgData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
              mfgData.count >= 25 else { return }

        // iBeacon format: Apple company ID (0x4C 0x00) + type 0x02 + length 0x15
        guard mfgData[0] == 0x4C, mfgData[1] == 0x00,
              mfgData[2] == 0x02, mfgData[3] == 0x15 else { return }

        // Parse UUID (bytes 4–19)
        let uuidBytes = [UInt8](mfgData[4..<20])
        let uuid = NSUUID(uuidBytes: uuidBytes) as UUID

        // Parse major (bytes 20–21, big-endian) and minor (bytes 22–23, big-endian)
        let major = Int(UInt16(mfgData[20]) << 8 | UInt16(mfgData[21]))
        let minor = Int(UInt16(mfgData[22]) << 8 | UInt16(mfgData[23]))

        // TX power (byte 24, signed)
        let txPower = Int(Int8(bitPattern: mfgData[24]))

        let rssiValue = rssi.intValue
        let distance = Self.calculateDistance(rssi: rssiValue, txPower: txPower)

        let result: [String: Any] = [
            "uuid": uuid.uuidString.uppercased(),
            "major": major,
            "minor": minor,
            "rssi": rssiValue,
            "distance": distance,
            "txPower": txPower
        ]
        wildcardScannedBeacons.append(result)
    }

    private func stopWildcardScanAndResolve() {
        wildcardScanTimer?.cancel()
        wildcardScanTimer = nil
        stopBleScanIfUnneeded()

        // Deduplicate by uuid:major:minor, keeping the last (freshest) reading
        var seen = Set<String>()
        var deduped: [[String: Any]] = []
        for beacon in wildcardScannedBeacons.reversed() {
            let key = "\(beacon["uuid"] ?? ""):\(beacon["major"] ?? ""):\(beacon["minor"] ?? "")"
            guard !seen.contains(key) else { continue }
            seen.insert(key)
            deduped.append(beacon)
        }

        scanPromise?.resolve(deduped)
        scanPromise = nil
        wildcardScannedBeacons = []
    }

    // MARK: - Eddystone Scan

    private func startEddystoneScan(durationMs: Int) {
        ensureBleScanRunning()

        let timer = DispatchWorkItem { [weak self] in
            self?.stopEddystoneScanAndResolve()
        }
        eddystoneScanTimer = timer
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(durationMs), execute: timer)
    }

    private func stopEddystoneScanAndResolve() {
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

    fileprivate func handleEddystoneDiscovery(advertisementData: [String: Any], rssi: NSNumber) {
        let eddystoneServiceUUID = CBUUID(string: "FEAA")
        guard let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data],
              let data = serviceData[eddystoneServiceUUID],
              data.count >= 2 else { return }

        let frameType = data[0]
        let rssiValue = rssi.intValue
        var result: [String: Any]?

        switch frameType {
        case 0x00: // Eddystone-UID
            guard data.count >= 18 else { return }
            let txPower = Int(Int8(bitPattern: data[1]))
            let namespace = data[2..<12].map { String(format: "%02x", $0) }.joined()
            let instance = data[12..<18].map { String(format: "%02x", $0) }.joined()
            let distance = Self.calculateDistance(rssi: rssiValue, txPower: txPower)
            result = [
                "frameType": "uid",
                "namespace": namespace,
                "instance": instance,
                "rssi": rssiValue,
                "distance": distance,
                "txPower": txPower
            ]

        case 0x10: // Eddystone-URL
            guard data.count >= 3 else { return }
            let txPower = Int(Int8(bitPattern: data[1]))
            let url = Self.decodeEddystoneURL(data: data)
            let distance = Self.calculateDistance(rssi: rssiValue, txPower: txPower)
            result = [
                "frameType": "url",
                "url": url,
                "rssi": rssiValue,
                "distance": distance,
                "txPower": txPower
            ]

        default:
            break
        }

        guard let beacon = result else { return }

        if eddystoneScanPromise != nil {
            eddystoneScannedBeacons.append(beacon)
        }

        if continuousScanActive {
            sendEvent("onEddystoneFound", beacon)
        }

        // Eddystone monitoring: match UID frames against paired list
        if eddystoneMonitoringActive, let ns = beacon["namespace"] as? String,
           let inst = beacon["instance"] as? String,
           let distance = beacon["distance"] as? Double {
            let pairedEddystones = loadPairedEddystonesRaw()
            for paired in pairedEddystones {
                guard let identifier = paired["identifier"] as? String,
                      let pns = paired["namespace"] as? String,
                      let pinst = paired["instance"] as? String,
                      pns == ns && pinst == inst else { continue }

                eddystoneLatestSeen[identifier] = Date()
                eddystoneMissCounters[identifier] = 0

                // Throttle distance events to ~1/sec
                let now = Date()
                if let lastEmit = eddystoneLastDistanceEmit[identifier],
                   now.timeIntervalSince(lastEmit) < 1.0 {
                    break
                }
                eddystoneLastDistanceEmit[identifier] = now

                let distParams: [String: Any] = [
                    "identifier": identifier,
                    "namespace": ns,
                    "instance": inst,
                    "distance": distance
                ]
                sendEvent("onEddystoneDistance", distParams)

                // Distance-driven enter/exit with hysteresis
                if let maxDist = UserDefaults.standard.object(forKey: MAX_DISTANCE_KEY) as? Double {
                    if distance <= maxDist {
                        eddystoneExitCounters[identifier] = 0
                        eddystoneEnterCounters[identifier] = (eddystoneEnterCounters[identifier] ?? 0) + 1

                        if !eddystoneEnteredRegions.contains(identifier) && (eddystoneEnterCounters[identifier] ?? 0) >= HYSTERESIS_COUNT {
                            eddystoneEnteredRegions.insert(identifier)
                            eddystoneEnterCounters[identifier] = 0
                            let params: [String: Any] = [
                                "identifier": identifier,
                                "namespace": ns,
                                "instance": inst,
                                "event": "enter",
                                "distance": distance
                            ]
                            sendEvent("onEddystoneEnter", params)
                            postBeaconNotification(identifier: identifier, eventType: "enter")
                        }
                    } else {
                        eddystoneEnterCounters[identifier] = 0
                        eddystoneExitCounters[identifier] = (eddystoneExitCounters[identifier] ?? 0) + 1

                        if eddystoneEnteredRegions.contains(identifier) && (eddystoneExitCounters[identifier] ?? 0) >= HYSTERESIS_COUNT {
                            eddystoneEnteredRegions.remove(identifier)
                            eddystoneExitCounters[identifier] = 0
                            let params: [String: Any] = [
                                "identifier": identifier,
                                "namespace": ns,
                                "instance": inst,
                                "event": "exit",
                                "distance": distance
                            ]
                            sendEvent("onEddystoneExit", params)
                            postBeaconNotification(identifier: identifier, eventType: "exit")
                        }
                    }
                } else {
                    // No maxDistance — enter on first consistent readings
                    eddystoneEnterCounters[identifier] = (eddystoneEnterCounters[identifier] ?? 0) + 1

                    if !eddystoneEnteredRegions.contains(identifier) && (eddystoneEnterCounters[identifier] ?? 0) >= HYSTERESIS_COUNT {
                        eddystoneEnteredRegions.insert(identifier)
                        eddystoneEnterCounters[identifier] = 0
                        let params: [String: Any] = [
                            "identifier": identifier,
                            "namespace": ns,
                            "instance": inst,
                            "event": "enter",
                            "distance": distance
                        ]
                        sendEvent("onEddystoneEnter", params)
                        postBeaconNotification(identifier: identifier, eventType: "enter")
                    }
                }
                break
            }
        }
    }

    fileprivate func ensureBleScanRunning() {
        if centralManager == nil {
            centralManager = CBCentralManager(delegate: bluetoothDelegate, queue: .main)
        } else if centralManager?.state == .poweredOn {
            centralManager?.scanForPeripherals(
                withServices: nil,
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
        }
    }

    private func stopBleScanIfUnneeded() {
        guard wildcardScanTimer == nil && eddystoneScanTimer == nil && !continuousScanActive && !eddystoneMonitoringActive else { return }
        centralManager?.stopScan()
    }

    private static func decodeEddystoneURL(data: Data) -> String {
        guard data.count >= 3 else { return "" }
        let schemes = ["http://www.", "https://www.", "http://", "https://"]
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
    private static func calculateDistance(rssi: Int, txPower: Int) -> Double {
        guard rssi != 0 else { return -1 }
        let ratio = Double(txPower - rssi) / 20.0
        return pow(10.0, ratio)
    }

    private func loadPairedBeaconsRaw() -> [[String: Any]] {
        return UserDefaults.standard.array(forKey: PAIRED_BEACONS_KEY) as? [[String: Any]] ?? []
    }

    private func loadPairedEddystonesRaw() -> [[String: Any]] {
        return UserDefaults.standard.array(forKey: PAIRED_EDDYSTONES_KEY) as? [[String: Any]] ?? []
    }

    // MARK: - Eddystone Monitoring

    private func startEddystoneMonitoring() {
        eddystoneMonitoringActive = true
        ensureBleScanRunning()

        // Timer to detect exit (beacon disappears from BLE advertisements)
        eddystoneMonitoringTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.eddystoneMonitoringTick()
        }
    }

    private func stopEddystoneMonitoring() {
        eddystoneMonitoringActive = false
        eddystoneMonitoringTimer?.invalidate()
        eddystoneMonitoringTimer = nil
        eddystoneLatestSeen.removeAll()
        eddystoneEnteredRegions.removeAll()
        eddystoneMissCounters.removeAll()
        eddystoneEnterCounters.removeAll()
        eddystoneExitCounters.removeAll()
        eddystoneLastDistanceEmit.removeAll()
        stopBleScanIfUnneeded()
    }

    private func eddystoneMonitoringTick() {
        let now = Date()
        let pairedEddystones = loadPairedEddystonesRaw()

        for paired in pairedEddystones {
            guard let identifier = paired["identifier"] as? String else { continue }

            if let lastSeen = eddystoneLatestSeen[identifier], now.timeIntervalSince(lastSeen) < 3.0 {
                // Recently seen — miss counter reset already done in handleEddystoneDiscovery
                continue
            }

            // Not seen recently — increment miss counter
            guard eddystoneEnteredRegions.contains(identifier) else { continue }

            let count = (eddystoneMissCounters[identifier] ?? 0) + 1
            eddystoneMissCounters[identifier] = count

            if count >= EXIT_MISS_THRESHOLD {
                eddystoneEnteredRegions.remove(identifier)
                eddystoneMissCounters[identifier] = 0
                eddystoneEnterCounters[identifier] = 0
                eddystoneExitCounters[identifier] = 0
                eddystoneLatestSeen.removeValue(forKey: identifier)

                let ns = paired["namespace"] as? String ?? ""
                let inst = paired["instance"] as? String ?? ""
                let params: [String: Any] = [
                    "identifier": identifier,
                    "namespace": ns,
                    "instance": inst,
                    "event": "exit",
                    "distance": -1
                ]
                sendEvent("onEddystoneExit", params)
                postBeaconNotification(identifier: identifier, eventType: "exit")
            }
        }
    }

    private func postBeaconNotification(identifier: String, eventType: String) {
        let cfg = loadNotificationConfig()
        let eventsCfg = cfg["beaconEvents"] as? [String: Any]

        // Respect the enabled flag (defaults to true)
        if let enabled = eventsCfg?["enabled"] as? Bool, !enabled { return }

        let defaultTitle = eventType == "enter" ? "Beacon Entered" : "Beacon Exited"
        let title: String
        if eventType == "enter" {
            title = (eventsCfg?["enterTitle"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? defaultTitle
        } else {
            title = (eventsCfg?["exitTitle"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? defaultTitle
        }

        let bodyTemplate = (eventsCfg?["body"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? "{identifier} region {event}ed"
        let body = bodyTemplate
            .replacingOccurrences(of: "{identifier}", with: identifier)
            .replacingOccurrences(of: "{event}", with: eventType)

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body

        let playSound = eventsCfg?["sound"] as? Bool ?? true
        if playSound { content.sound = .default }

        let request = UNNotificationRequest(
            identifier: "beacon_\(eventType)_\(identifier)_\(Date().timeIntervalSince1970)",
            content: content,
            trigger: nil  // deliver immediately
        )
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    private func loadNotificationConfig() -> [String: Any] {
        guard let json = UserDefaults.standard.string(forKey: NOTIFICATION_CONFIG_KEY),
              let data = json.data(using: .utf8),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return [:] }
        return dict
    }

    private func constraintMatches(_ a: CLBeaconIdentityConstraint, _ b: CLBeaconIdentityConstraint) -> Bool {
        return a.uuid == b.uuid && a.major == b.major && a.minor == b.minor
    }

    // MARK: - CLLocationManagerDelegate handlers (called by LocationDelegate)

    fileprivate func handleDidChangeAuthorization(_ status: CLAuthorizationStatus) {
        let granted = (status == .authorizedAlways || status == .authorizedWhenInUse)
        // Nil out BEFORE calling so the closure can set a new permissionCompletion
        // (e.g. the notDetermined → whenInUse → always two-step upgrade flow).
        let completion = permissionCompletion
        permissionCompletion = nil
        completion?(granted)
    }

    fileprivate func handleDidRange(_ beacons: [CLBeacon], satisfying constraint: CLBeaconIdentityConstraint) {
        // 1. One-shot scan mode
        if scanConstraints.contains(where: { constraintMatches($0, constraint) }) {
            scannedBeacons.append(contentsOf: beacons)
            return
        }

        // 2. Distance-ranging for monitored beacons
        if let (identifier, _) = distanceRangingConstraints.first(where: { constraintMatches($0.value, constraint) }) {
            let validBeacon = beacons.first(where: { $0.accuracy >= 0 })

            if let beacon = validBeacon {
                // Got a valid reading — reset miss counter
                missCounters[identifier] = 0

                // Emit distance event every ranging cycle (~1 s)
                let distParams: [String: Any] = [
                    "identifier": identifier,
                    "uuid": beacon.uuid.uuidString.uppercased(),
                    "major": beacon.major.intValue,
                    "minor": beacon.minor.intValue,
                    "distance": beacon.accuracy
                ]
                sendEvent("onBeaconDistance", distParams)

                // Distance-driven enter/exit synthesis with hysteresis
                if let maxDist = UserDefaults.standard.object(forKey: MAX_DISTANCE_KEY) as? Double {
                    if beacon.accuracy <= maxDist {
                        // Reading is inside threshold
                        exitCounters[identifier] = 0
                        enterCounters[identifier] = (enterCounters[identifier] ?? 0) + 1

                        if !enteredRegions.contains(identifier) && (enterCounters[identifier] ?? 0) >= HYSTERESIS_COUNT {
                            enteredRegions.insert(identifier)
                            enterCounters[identifier] = 0
                            let params: [String: Any] = [
                                "identifier": identifier,
                                "uuid": beacon.uuid.uuidString.uppercased(),
                                "major": beacon.major.intValue,
                                "minor": beacon.minor.intValue,
                                "event": "enter",
                                "distance": beacon.accuracy
                            ]
                            sendEvent("onBeaconEnter", params)
                            postBeaconNotification(identifier: identifier, eventType: "enter")
                        }
                    } else {
                        // Reading is outside threshold
                        enterCounters[identifier] = 0
                        exitCounters[identifier] = (exitCounters[identifier] ?? 0) + 1

                        if enteredRegions.contains(identifier) && (exitCounters[identifier] ?? 0) >= HYSTERESIS_COUNT {
                            enteredRegions.remove(identifier)
                            exitCounters[identifier] = 0
                            let params: [String: Any] = [
                                "identifier": identifier,
                                "uuid": beacon.uuid.uuidString.uppercased(),
                                "major": beacon.major.intValue,
                                "minor": beacon.minor.intValue,
                                "event": "exit",
                                "distance": beacon.accuracy
                            ]
                            sendEvent("onBeaconExit", params)
                            postBeaconNotification(identifier: identifier, eventType: "exit")
                        }
                    }
                }

                // Note: onBeaconFound for continuous scan is emitted by the
                // UUID-only constraints in check 3 below, not here, to avoid
                // duplicate events when both monitoring and continuous scan are active.
            } else {
                // No valid beacon reading — beacon may have disappeared
                let count = (missCounters[identifier] ?? 0) + 1
                missCounters[identifier] = count

                if enteredRegions.contains(identifier) && count >= EXIT_MISS_THRESHOLD {
                    enteredRegions.remove(identifier)
                    missCounters[identifier] = 0
                    enterCounters[identifier] = 0
                    exitCounters[identifier] = 0

                    // Look up region info for the exit event payload
                    let region = monitoredRegions.first { $0.identifier == identifier }
                    let params: [String: Any] = [
                        "identifier": identifier,
                        "uuid": region?.uuid.uuidString.uppercased() ?? "",
                        "major": region?.major?.intValue ?? 0,
                        "minor": region?.minor?.intValue ?? 0,
                        "event": "exit",
                        "distance": -1
                    ]
                    sendEvent("onBeaconExit", params)
                    postBeaconNotification(identifier: identifier, eventType: "exit")
                }
            }
            return
        }

        // 3. Continuous-scan-only constraints (monitoring not active)
        if continuousScanActive,
           continuousScanOnlyConstraints.contains(where: { constraintMatches($0, constraint) }) {
            for beacon in beacons where beacon.accuracy >= 0 {
                let params: [String: Any] = [
                    "uuid": beacon.uuid.uuidString.uppercased(),
                    "major": beacon.major.intValue,
                    "minor": beacon.minor.intValue,
                    "rssi": beacon.rssi,
                    "distance": beacon.accuracy,
                    "txPower": 0
                ]
                sendEvent("onBeaconFound", params)
            }
        }
    }

    fileprivate func handleDidEnterRegion(_ region: CLRegion) {
        guard let beaconRegion = region as? CLBeaconRegion else { return }
        let identifier = beaconRegion.identifier

        // If maxDistance is set, distance ranging handles enter/exit — skip region-based emit
        guard UserDefaults.standard.object(forKey: MAX_DISTANCE_KEY) == nil else { return }

        let params: [String: Any] = [
            "identifier": identifier,
            "uuid": beaconRegion.uuid.uuidString.uppercased(),
            "major": beaconRegion.major?.intValue ?? 0,
            "minor": beaconRegion.minor?.intValue ?? 0,
            "event": "enter",
            "distance": -1
        ]
        sendEvent("onBeaconEnter", params)
        postBeaconNotification(identifier: identifier, eventType: "enter")
    }

    fileprivate func handleDidExitRegion(_ region: CLRegion) {
        guard let beaconRegion = region as? CLBeaconRegion else { return }
        let identifier = beaconRegion.identifier

        // If maxDistance is set, distance ranging handles exit; just clean up tracked state
        if UserDefaults.standard.object(forKey: MAX_DISTANCE_KEY) != nil {
            enteredRegions.remove(identifier)
            return
        }

        let params: [String: Any] = [
            "identifier": identifier,
            "uuid": beaconRegion.uuid.uuidString.uppercased(),
            "major": beaconRegion.major?.intValue ?? 0,
            "minor": beaconRegion.minor?.intValue ?? 0,
            "event": "exit",
            "distance": -1
        ]
        sendEvent("onBeaconExit", params)
        postBeaconNotification(identifier: identifier, eventType: "exit")
    }

    fileprivate func handleMonitoringDidFail(for region: CLRegion?, withError error: Error) {
        print("[ExpoBeacon] Monitoring failed for region \(region?.identifier ?? "unknown"): \(error.localizedDescription)")
    }

    fileprivate func handleDidFailRanging(for constraint: CLBeaconIdentityConstraint, error: Error) {
        print("[ExpoBeacon] Ranging failed for constraint \(constraint.uuid): \(error.localizedDescription)")

        // If a one-shot scan is active and this constraint belongs to it, reject the promise
        if scanPromise != nil && scanConstraints.contains(where: { constraintMatches($0, constraint) }) {
            // Stop all scan constraints
            for sc in scanConstraints {
                locationManager.stopRangingBeacons(satisfying: sc)
            }
            scanConstraints.removeAll()
            scannedBeacons.removeAll()
            scanPromise?.reject("RANGING_FAILED", "Beacon ranging failed: \(error.localizedDescription)")
            scanPromise = nil
        }
    }
}

// MARK: - CLLocationManagerDelegate

private class LocationDelegate: NSObject, CLLocationManagerDelegate {
    private weak var module: ExpoBeaconModule?

    init(module: ExpoBeaconModule) {
        self.module = module
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        module?.handleDidChangeAuthorization(manager.authorizationStatus)
    }

    func locationManager(_ manager: CLLocationManager, didRange beacons: [CLBeacon], satisfying constraint: CLBeaconIdentityConstraint) {
        module?.handleDidRange(beacons, satisfying: constraint)
    }

    func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        module?.handleDidEnterRegion(region)
    }

    func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        module?.handleDidExitRegion(region)
    }

    func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        module?.handleMonitoringDidFail(for: region, withError: error)
    }

    func locationManager(_ manager: CLLocationManager, didFailRangingFor beaconConstraint: CLBeaconIdentityConstraint, error: Error) {
        module?.handleDidFailRanging(for: beaconConstraint, error: error)
    }
}

// MARK: - CBCentralManagerDelegate (wildcard iBeacon scanning)

private class BluetoothDelegate: NSObject, CBCentralManagerDelegate {
    private weak var module: ExpoBeaconModule?

    init(module: ExpoBeaconModule) {
        self.module = module
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            module?.ensureBleScanRunning()
        case .unauthorized:
            module?.scanPromise?.reject("BLUETOOTH_UNAUTHORIZED", "Bluetooth permission denied")
            module?.scanPromise = nil
            module?.eddystoneScanPromise?.reject("BLUETOOTH_UNAUTHORIZED", "Bluetooth permission denied")
            module?.eddystoneScanPromise = nil
        case .poweredOff:
            module?.scanPromise?.reject("BLUETOOTH_OFF", "Bluetooth is powered off")
            module?.scanPromise = nil
            module?.eddystoneScanPromise?.reject("BLUETOOTH_OFF", "Bluetooth is powered off")
            module?.eddystoneScanPromise = nil
        default:
            break
        }
    }

    func centralManager(_ central: CBCentralManager,
                         didDiscover peripheral: CBPeripheral,
                         advertisementData: [String: Any],
                         rssi RSSI: NSNumber) {
        module?.handleWildcardDiscovery(advertisementData: advertisementData, rssi: RSSI)
        module?.handleEddystoneDiscovery(advertisementData: advertisementData, rssi: RSSI)
    }
}

