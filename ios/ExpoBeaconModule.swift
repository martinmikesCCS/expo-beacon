import ExpoModulesCore
import CoreLocation
import UserNotifications

private let PAIRED_BEACONS_KEY = "expo.beacon.paired"
private let IS_MONITORING_KEY = "expo.beacon.is_monitoring"
private let MAX_DISTANCE_KEY = "expo.beacon.max_distance"
private let NOTIFICATION_CONFIG_KEY = "expo.beacon.notification_config"

public class ExpoBeaconModule: NSObject, Module, CLLocationManagerDelegate {

    private lazy var locationManager: CLLocationManager = {
        let manager = CLLocationManager()
        manager.delegate = self
        manager.allowsBackgroundLocationUpdates = true
        manager.pausesLocationUpdatesAutomatically = false
        return manager
    }()

    // One-shot scan state
    private var scanPromise: Promise?
    private var scannedBeacons: [CLBeacon] = []
    private var scanConstraint: CLBeaconIdentityConstraint?
    private var scanRegion: CLBeaconRegion?

    // Monitored regions
    private var monitoredRegions: [CLBeaconRegion] = []

    // Always-on ranging for distance events + distance-based enter/exit (identifier → constraint)
    private var distanceRangingConstraints: [String: CLBeaconIdentityConstraint] = [:]
    // Identifiers currently in "entered" state (used for distance-driven enter/exit)
    private var enteredRegions: Set<String> = []

    // Continuous scan state
    private var continuousScanActive = false
    // Constraints started exclusively for continuous scan (not shared with distance ranging)
    private var continuousScanOnlyConstraints: [CLBeaconIdentityConstraint] = []

    // Permission callback
    private var permissionCompletion: ((Bool) -> Void)?

    public func definition() -> ModuleDefinition {
        Name("ExpoBeacon")

        Events("onBeaconEnter", "onBeaconExit", "onBeaconRanging", "onBeaconDistance", "onBeaconFound")

        // MARK: - Scan

        AsyncFunction("scanForBeaconsAsync") { (scanDurationMs: Int, promise: Promise) in
            guard self.scanPromise == nil else {
                promise.reject("SCAN_IN_PROGRESS", "A scan is already in progress")
                return
            }
            self.scanPromise = promise
            self.scannedBeacons = []

            self.requestLocationPermission { granted in
                guard granted else {
                    promise.reject("PERMISSION_DENIED", "Location permission required for beacon scanning")
                    self.scanPromise = nil
                    return
                }

                // iOS ranging requires a specific UUID; use a placeholder for generic scan
                let placeholderUUID = UUID(uuidString: "00000000-0000-0000-0000-000000000000")!
                let region = CLBeaconRegion(uuid: placeholderUUID, identifier: "scan_wildcard")
                self.scanRegion = region
                self.scanConstraint = CLBeaconIdentityConstraint(uuid: placeholderUUID)
                self.locationManager.startRangingBeacons(satisfying: self.scanConstraint!)

                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(scanDurationMs)) {
                    self.stopScanAndResolve()
                }
            }
        }

        // MARK: - Pair

        Function("pairBeacon") { (identifier: String, uuid: String, major: Int, minor: Int) in
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

        // MARK: - Notification Config

        Function("setNotificationConfig") { (config: [String: Any]) in
            if let data = try? JSONSerialization.data(withJSONObject: config),
               let json = String(data: data, encoding: .utf8) {
                UserDefaults.standard.set(json, forKey: NOTIFICATION_CONFIG_KEY)
            }
        }

        // MARK: - Monitoring

        AsyncFunction("startMonitoring") { (options: Any?, promise: Promise) in
            var maxDistance: Double? = nil
            if let dist = options as? Double {
                maxDistance = dist
            } else if let map = options as? [String: Any] {
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
            self.requestLocationPermission { granted in
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
            self.startContinuousScanRanging()
        }

        Function("stopContinuousScan") { () -> Void in
            self.continuousScanActive = false
            for constraint in self.continuousScanOnlyConstraints {
                self.locationManager.stopRangingBeacons(satisfying: constraint)
            }
            self.continuousScanOnlyConstraints.removeAll()
        }
    }

    // MARK: - Private Helpers

    private func requestLocationPermission(completion: @escaping (Bool) -> Void) {
        let status = locationManager.authorizationStatus
        switch status {
        case .authorizedAlways:
            completion(true)
        case .notDetermined:
            self.permissionCompletion = completion
            locationManager.requestAlwaysAuthorization()
        default:
            // WhenInUse allows foreground ranging but not background region monitoring
            completion(status == .authorizedWhenInUse)
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
    }

    // Start ranging for paired beacons not already covered by distance ranging
    private func startContinuousScanRanging() {
        let beacons = loadPairedBeaconsRaw()
        for b in beacons {
            guard
                let identifier = b["identifier"] as? String,
                let uuidString = b["uuid"] as? String,
                let uuid = UUID(uuidString: uuidString),
                let major = b["major"] as? Int,
                let minor = b["minor"] as? Int
            else { continue }

            // Reuse the existing distance-ranging stream if monitoring is active
            if distanceRangingConstraints[identifier] != nil { continue }

            let constraint = CLBeaconIdentityConstraint(
                uuid: uuid,
                major: CLBeaconMajorValue(major),
                minor: CLBeaconMinorValue(minor)
            )
            continuousScanOnlyConstraints.append(constraint)
            locationManager.startRangingBeacons(satisfying: constraint)
        }
    }

    private func stopScanAndResolve() {
        if let constraint = scanConstraint {
            locationManager.stopRangingBeacons(satisfying: constraint)
            scanConstraint = nil
            scanRegion = nil
        }

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

    private func loadPairedBeaconsRaw() -> [[String: Any]] {
        return UserDefaults.standard.array(forKey: PAIRED_BEACONS_KEY) as? [[String: Any]] ?? []
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

    // MARK: - CLLocationManagerDelegate

    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        let granted = (status == .authorizedAlways || status == .authorizedWhenInUse)
        permissionCompletion?(granted)
        permissionCompletion = nil
    }

    public func locationManager(_ manager: CLLocationManager, didRangeBeacons beacons: [CLBeacon], satisfying constraint: CLBeaconIdentityConstraint) {
        // 1. One-shot scan mode
        if let sc = scanConstraint, constraintMatches(sc, constraint) {
            scannedBeacons.append(contentsOf: beacons)
            return
        }

        // 2. Distance-ranging for monitored beacons
        if let (identifier, _) = distanceRangingConstraints.first(where: { constraintMatches($0.value, constraint) }) {
            guard let beacon = beacons.first(where: { $0.accuracy >= 0 }) else {
                return
            }

            // Emit distance event every ranging cycle (~1 s)
            let distParams: [String: Any] = [
                "identifier": identifier,
                "uuid": beacon.uuid.uuidString.uppercased(),
                "major": beacon.major.intValue,
                "minor": beacon.minor.intValue,
                "distance": beacon.accuracy
            ]
            sendEvent("onBeaconDistance", distParams)
            print("[ExpoBeacon] DIST: \(identifier) → \(String(format: "%.2f", beacon.accuracy))m")

            // Distance-driven enter/exit synthesis
            if let maxDist = UserDefaults.standard.object(forKey: MAX_DISTANCE_KEY) as? Double {
                if !enteredRegions.contains(identifier) && beacon.accuracy <= maxDist {
                    enteredRegions.insert(identifier)
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
                } else if enteredRegions.contains(identifier) && beacon.accuracy > maxDist {
                    enteredRegions.remove(identifier)
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

            // Also emit onBeaconFound if continuous scan is active for this beacon
            if continuousScanActive {
                let foundParams: [String: Any] = [
                    "uuid": beacon.uuid.uuidString.uppercased(),
                    "major": beacon.major.intValue,
                    "minor": beacon.minor.intValue,
                    "rssi": beacon.rssi,
                    "distance": beacon.accuracy,
                    "txPower": 0
                ]
                sendEvent("onBeaconFound", foundParams)
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

    public func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
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

    public func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
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

    public func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        print("[ExpoBeacon] Monitoring failed for region \(region?.identifier ?? "unknown"): \(error.localizedDescription)")
    }
}

