import ExpoModulesCore
import CoreLocation
import UserNotifications

private let PAIRED_BEACONS_KEY = "expo.beacon.paired"
private let IS_MONITORING_KEY = "expo.beacon.is_monitoring"

public class ExpoBeaconModule: Module, CLLocationManagerDelegate {

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

    // Permission callback
    private var permissionCompletion: ((Bool) -> Void)?

    public func definition() -> ModuleDefinition {
        Name("ExpoBeacon")

        Events("onBeaconEnter", "onBeaconExit", "onBeaconRanging")

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

                // iOS ranging requires a specific UUID; use a "null" UUID for generic scan
                // In practice, users should scan known UUIDs. This scans with a placeholder.
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

        // MARK: - Monitoring

        AsyncFunction("startMonitoring") { (promise: Promise) in
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
            self.stopRegionMonitoring()
            promise.resolve(nil)
        }

        AsyncFunction("requestPermissionsAsync") { (promise: Promise) in
            self.requestLocationPermission { granted in
                promise.resolve(granted)
            }
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
        }
    }

    private func stopRegionMonitoring() {
        for region in monitoredRegions {
            locationManager.stopMonitoring(for: region)
        }
        monitoredRegions.removeAll()
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
        let content = UNMutableNotificationContent()
        content.title = eventType == "enter" ? "Beacon Entered" : "Beacon Exited"
        content.body = "\(identifier) region \(eventType)ed"
        content.sound = .default

        let request = UNNotificationRequest(
            identifier: "beacon_\(eventType)_\(identifier)_\(Date().timeIntervalSince1970)",
            content: content,
            trigger: nil  // deliver immediately
        )
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    // MARK: - CLLocationManagerDelegate

    public func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        let granted = (status == .authorizedAlways || status == .authorizedWhenInUse)
        permissionCompletion?(granted)
        permissionCompletion = nil
    }

    public func locationManager(_ manager: CLLocationManager, didRangeBeacons beacons: [CLBeacon], satisfying constraint: CLBeaconIdentityConstraint) {
        scannedBeacons.append(contentsOf: beacons)
    }

    public func locationManager(_ manager: CLLocationManager, didEnterRegion region: CLRegion) {
        guard let beaconRegion = region as? CLBeaconRegion else { return }
        let params: [String: Any] = [
            "identifier": beaconRegion.identifier,
            "uuid": beaconRegion.uuid.uuidString.uppercased(),
            "major": beaconRegion.major?.intValue ?? 0,
            "minor": beaconRegion.minor?.intValue ?? 0,
            "event": "enter"
        ]
        sendEvent("onBeaconEnter", params)
        postBeaconNotification(identifier: beaconRegion.identifier, eventType: "enter")
    }

    public func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        guard let beaconRegion = region as? CLBeaconRegion else { return }
        let params: [String: Any] = [
            "identifier": beaconRegion.identifier,
            "uuid": beaconRegion.uuid.uuidString.uppercased(),
            "major": beaconRegion.major?.intValue ?? 0,
            "minor": beaconRegion.minor?.intValue ?? 0,
            "event": "exit"
        ]
        sendEvent("onBeaconExit", params)
        postBeaconNotification(identifier: beaconRegion.identifier, eventType: "exit")
    }

    public func locationManager(_ manager: CLLocationManager, monitoringDidFailFor region: CLRegion?, withError error: Error) {
        print("[ExpoBeacon] Monitoring failed for region \(region?.identifier ?? "unknown"): \(error.localizedDescription)")
    }
}

