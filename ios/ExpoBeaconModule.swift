import ExpoModulesCore
import CoreLocation
import CoreBluetooth
import UserNotifications

public class ExpoBeaconModule: Module {

    // MARK: - Distance smoothing tuning

    /// EMA weight for new readings. 0.4 balances responsiveness vs noise rejection.
    internal static let DISTANCE_EMA_ALPHA = 0.4
    /// If raw distance differs from smoothed by more than this factor, treat as outlier.
    internal static let DISTANCE_JUMP_FACTOR = 5.0

    // MARK: - CoreLocation

    internal lazy var locationDelegate = LocationDelegate(module: self)

    internal lazy var locationManager: CLLocationManager = {
        let manager = CLLocationManager()
        manager.delegate = locationDelegate
        let backgroundModes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String] ?? []
        if backgroundModes.contains("location") {
            manager.allowsBackgroundLocationUpdates = true
        }
        manager.pausesLocationUpdatesAutomatically = false
        return manager
    }()

    // MARK: - One-shot iBeacon scan state

    internal var scanPromise: Promise?
    internal var scannedBeacons: [CLBeacon] = []
    internal var scanConstraints: [CLBeaconIdentityConstraint] = []
    internal var scanTimer: DispatchWorkItem?

    // MARK: - Monitoring state

    internal var monitoredRegions: [CLBeaconRegion] = []

    /// Always-on ranging for distance events + distance-based enter/exit (identifier → constraint)
    internal var distanceRangingConstraints: [String: CLBeaconIdentityConstraint] = [:]
    /// Identifiers currently in "entered" state (used for distance-driven enter/exit)
    internal var enteredRegions: Set<String> = []
    /// Per-identifier timestamp of the last valid ranging reading (for time-based exit detection)
    internal var lastSeenTimes: [String: Date] = [:]
    /// Hysteresis counters: consecutive readings inside/outside threshold per identifier
    internal var enterCounters: [String: Int] = [:]
    internal var exitCounters: [String: Int] = [:]

    // MARK: - Continuous scan state

    internal var continuousScanActive = false
    /// Constraints started exclusively for continuous scan (not shared with distance ranging)
    internal var continuousScanOnlyConstraints: [CLBeaconIdentityConstraint] = []

    // MARK: - CoreBluetooth (Eddystone) state

    /// CBCentralManager must use queue: .main to preserve thread safety —
    /// all mutable state in this module is accessed exclusively on the main thread.
    internal lazy var bluetoothDelegate = BluetoothDelegate(module: self)
    internal var centralManager: CBCentralManager?

    // Eddystone one-shot scan
    internal var eddystoneScanPromise: Promise?
    internal var eddystoneScannedBeacons: [[String: Any]] = []
    internal var eddystoneScanTimer: DispatchWorkItem?

    // Eddystone monitoring
    internal var eddystoneMonitoringActive = false
    internal var eddystoneMonitoringTimer: Timer?
    internal var eddystoneLatestSeen: [String: Date] = [:]
    internal var eddystoneEnteredRegions: Set<String> = []
    internal var eddystoneEnterCounters: [String: Int] = [:]
    internal var eddystoneExitCounters: [String: Int] = [:]
    internal var eddystoneLastDistanceEmit: [String: Date] = [:]

    // MARK: - Configurable thresholds

    /// Minimum RSSI threshold — readings below this are treated as unreliable.
    internal var minRssiThreshold: Int = DEFAULT_MIN_RSSI

    /// Event level: "all" emits distance + enter/exit/timeout; "events" suppresses distance.
    internal var eventLevel: String = "all"

    /// Seconds of silence after last valid beacon sighting before a miss-based exit fires.
    internal var exitTimeoutSeconds: TimeInterval = DEFAULT_EXIT_TIMEOUT_SECONDS

    /// Distance smoothing (EMA) state per identifier.
    internal var smoothedDistances: [String: Double] = [:]

    // MARK: - Permissions

    internal var permissionCompletion: ((Bool) -> Void)?

    // MARK: - Cached paired data (invalidated on pair/unpair)

    internal var cachedPairedBeacons: [[String: Any]]?
    internal var cachedPairedEddystones: [[String: Any]]?

    // MARK: - SQLite event logger

    internal var eventLogger: BeaconEventLogger?
    internal var loggingEnabled = false

    // MARK: - Native API forwarder (fire-and-forget HTTP)

    internal lazy var apiForwarder = BeaconApiForwarder(defaults: defaults)

    // MARK: - Timers

    /// Timeout timers — fire once after beacon stays in range for configured duration
    internal var beaconTimeoutTimers: [String: DispatchWorkItem] = [:]
    internal var eddystoneTimeoutTimers: [String: DispatchWorkItem] = [:]
    /// Inactivity timers — start timeout countdown when no BLE readings for 60 s
    internal var beaconInactivityTimers: [String: DispatchWorkItem] = [:]
    internal var eddystoneInactivityTimers: [String: DispatchWorkItem] = [:]

    // MARK: - UserDefaults

    /// Custom UserDefaults suite to isolate beacon data from the host app's .standard
    internal lazy var defaults: UserDefaults = {
        UserDefaults(suiteName: "expo.modules.beacon") ?? .standard
    }()

    // MARK: - Module Definition

    public func definition() -> ModuleDefinition {
        Name("ExpoBeacon")

        OnCreate {
            self.migrateUserDefaultsIfNeeded()
            // If the user previously enabled CarPlay monitoring, restart it now —
            // the module may have been recreated after the app was killed and
            // background-launched (e.g. via a CLLocationManager region wake).
            // Without this, CarPlay state changes that happened during suspension
            // would be missed until JS calls startCarPlayMonitoring() again.
            if self.defaults.bool(forKey: CARPLAY_MONITORING_ENABLED_KEY) {
                self.startCarPlayMonitoringInternal()
            }
        }

        Events("onBeaconEnter", "onBeaconExit", "onBeaconDistance", "onBeaconTimeout", "onBeaconFound", "onEddystoneFound", "onEddystoneEnter", "onEddystoneExit", "onEddystoneDistance", "onEddystoneTimeout", "onBeaconError", "onCarPlayConnected", "onCarPlayDisconnected")

        // MARK: - Scan

        AsyncFunction("scanForBeaconsAsync") { (uuids: [String], scanDurationMs: Int, promise: Promise) in
            guard scanDurationMs > 0 else {
                promise.reject("INVALID_DURATION", "Scan duration must be a positive integer")
                self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "INVALID_DURATION", "message": "Scan duration must be a positive integer"])
                return
            }
            guard self.scanPromise == nil else {
                promise.reject("SCAN_IN_PROGRESS", "A scan is already in progress")
                self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "SCAN_IN_PROGRESS", "message": "A scan is already in progress"])
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
                    self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "WILDCARD_NOT_SUPPORTED", "message": "iOS does not support wildcard iBeacon scanning. Provide at least one proximity UUID, or pair beacons first."])
                    self.scanPromise = nil
                    return
                }
            } else {
                for uuidStr in uuids {
                    guard let uuid = UUID(uuidString: uuidStr) else {
                        promise.reject("INVALID_UUID", "Invalid UUID: \(uuidStr)")
                        self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "INVALID_UUID", "message": "Invalid UUID: \(uuidStr)"])
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
                    self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "PERMISSION_DENIED", "message": "Location permission required for beacon scanning"])
                    self.scanPromise = nil
                    return
                }

                // Range for each requested UUID simultaneously
                for uuid in parsedUUIDs {
                    let constraint = CLBeaconIdentityConstraint(uuid: uuid)
                    self.scanConstraints.append(constraint)
                    self.locationManager.startRangingBeacons(satisfying: constraint)
                }

                let timer = DispatchWorkItem { [weak self] in
                    self?.stopScanAndResolve()
                }
                self.scanTimer = timer
                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(scanDurationMs), execute: timer)
            }
        }

        Function("cancelScan") { () -> Void in
            // Cancel iBeacon one-shot scan
            if self.scanPromise != nil {
                self.scanTimer?.cancel()
                self.scanTimer = nil
                for constraint in self.scanConstraints {
                    self.locationManager.stopRangingBeacons(satisfying: constraint)
                }
                self.scanConstraints.removeAll()
                self.scannedBeacons.removeAll()
                self.scanPromise?.reject("SCAN_CANCELLED", "Scan was cancelled")
                self.scanPromise = nil
            }
            // Cancel Eddystone one-shot scan
            if self.eddystoneScanPromise != nil {
                self.eddystoneScanTimer?.cancel()
                self.eddystoneScanTimer = nil
                self.stopBleScanIfUnneeded()
                self.eddystoneScannedBeacons.removeAll()
                self.eddystoneScanPromise?.reject("SCAN_CANCELLED", "Scan was cancelled")
                self.eddystoneScanPromise = nil
            }
        }

        // MARK: - Pair

        Function("pairBeacon") { (identifier: String, uuid: String, major: Int, minor: Int, name: String?, timeoutSeconds: Int?) -> Void in
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
            var entry: [String: Any] = [
                "identifier": identifier,
                "uuid": uuid,
                "major": major,
                "minor": minor
            ]
            if let name = name { entry["name"] = name }
            if let timeoutSeconds = timeoutSeconds { entry["timeoutSeconds"] = timeoutSeconds }
            beacons.append(entry)
            self.defaults.set(beacons, forKey: PAIRED_BEACONS_KEY)
            self.cachedPairedBeacons = nil
        }

        Function("unpairBeacon") { (identifier: String) in
            var beacons = self.loadPairedBeaconsRaw()
            beacons.removeAll { ($0["identifier"] as? String) == identifier }
            self.defaults.set(beacons, forKey: PAIRED_BEACONS_KEY)
            self.cachedPairedBeacons = nil
        }

        Function("getPairedBeacons") { () -> [[String: Any]] in
            return self.loadPairedBeaconsRaw()
        }

        // MARK: - Eddystone Pair

        Function("pairEddystone") { (identifier: String, namespace: String, instance: String, name: String?, timeoutSeconds: Int?) -> Void in
            guard namespace.count == 20, namespace.range(of: "^[0-9a-fA-F]+$", options: .regularExpression) != nil else {
                throw Exception(name: "INVALID_NAMESPACE", description: "Namespace must be 20 hex characters, got: \(namespace)")
            }
            guard instance.count == 12, instance.range(of: "^[0-9a-fA-F]+$", options: .regularExpression) != nil else {
                throw Exception(name: "INVALID_INSTANCE", description: "Instance must be 12 hex characters, got: \(instance)")
            }

            var eddystones = self.loadPairedEddystonesRaw()
            eddystones.removeAll { ($0["identifier"] as? String) == identifier }
            // Normalize hex to lowercase — parseEddystoneFrame produces lowercase,
            // so stored values must match for monitoring comparisons.
            var entry: [String: Any] = [
                "identifier": identifier,
                "namespace": namespace.lowercased(),
                "instance": instance.lowercased()
            ]
            if let name = name { entry["name"] = name }
            if let timeoutSeconds = timeoutSeconds { entry["timeoutSeconds"] = timeoutSeconds }
            eddystones.append(entry)
            self.defaults.set(eddystones, forKey: PAIRED_EDDYSTONES_KEY)
            self.cachedPairedEddystones = nil
        }

        Function("unpairEddystone") { (identifier: String) in
            var eddystones = self.loadPairedEddystonesRaw()
            eddystones.removeAll { ($0["identifier"] as? String) == identifier }
            self.defaults.set(eddystones, forKey: PAIRED_EDDYSTONES_KEY)
            self.cachedPairedEddystones = nil
        }

        Function("getPairedEddystones") { () -> [[String: Any]] in
            return self.loadPairedEddystonesRaw()
        }

        // MARK: - Notification Config

        Function("setNotificationConfig") { (config: [String: Any]) in
            if let data = try? JSONSerialization.data(withJSONObject: config),
               let json = String(data: data, encoding: .utf8) {
                self.defaults.set(json, forKey: NOTIFICATION_CONFIG_KEY)
            }
        }

        // MARK: - Monitoring

        AsyncFunction("startMonitoring") { (options: Either<Double, [String: Any]>?, promise: Promise) in
            var maxDistance: Double? = nil
            var exitDistance: Double? = nil
            var minRssi: Int? = nil
            var exitTimeoutSecs: Double? = nil
            if let dist: Double = options?.get() {
                maxDistance = dist
            } else if let map: [String: Any] = options?.get() {
                maxDistance = map["maxDistance"] as? Double
                exitDistance = map["exitDistance"] as? Double
                minRssi = map["minRssi"] as? Int
                exitTimeoutSecs = map["exitTimeoutSeconds"] as? Double
                if let lvl = map["level"] as? String, lvl == "events" || lvl == "all" {
                    self.eventLevel = lvl
                    self.defaults.set(lvl, forKey: EVENT_LEVEL_KEY)
                } else {
                    self.eventLevel = "all"
                    self.defaults.set("all", forKey: EVENT_LEVEL_KEY)
                }
                if let notifications = map["notifications"] as? [String: Any],
                   let data = try? JSONSerialization.data(withJSONObject: notifications),
                   let json = String(data: data, encoding: .utf8) {
                    self.defaults.set(json, forKey: NOTIFICATION_CONFIG_KEY)
                }
            }
            if let dist = maxDistance, (!dist.isFinite || dist <= 0) {
                promise.reject("INVALID_MAX_DISTANCE", "maxDistance must be a finite number greater than 0")
                self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "INVALID_MAX_DISTANCE", "message": "maxDistance must be a finite number greater than 0"])
                return
            }
            if let exitDist = exitDistance, (!exitDist.isFinite || exitDist <= 0) {
                promise.reject("INVALID_EXIT_DISTANCE", "exitDistance must be a finite number greater than 0")
                self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "INVALID_EXIT_DISTANCE", "message": "exitDistance must be a finite number greater than 0"])
                return
            }
            if exitDistance != nil && maxDistance == nil {
                promise.reject("INVALID_EXIT_DISTANCE", "exitDistance requires maxDistance to be set")
                self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "INVALID_EXIT_DISTANCE", "message": "exitDistance requires maxDistance to be set"])
                return
            }
            if let dist = maxDistance, let exitDist = exitDistance, exitDist < dist {
                promise.reject("INVALID_EXIT_DISTANCE", "exitDistance must be greater than or equal to maxDistance")
                self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "INVALID_EXIT_DISTANCE", "message": "exitDistance must be greater than or equal to maxDistance"])
                return
            }
            if let t = exitTimeoutSecs, (!t.isFinite || t <= 0) {
                promise.reject("INVALID_EXIT_TIMEOUT", "exitTimeoutSeconds must be a finite number greater than 0")
                self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "INVALID_EXIT_TIMEOUT", "message": "exitTimeoutSeconds must be a finite number greater than 0"])
                return
            }
            if let dist = maxDistance {
                self.defaults.set(dist, forKey: MAX_DISTANCE_KEY)
            } else {
                self.defaults.removeObject(forKey: MAX_DISTANCE_KEY)
            }
            if let exitDist = exitDistance {
                self.defaults.set(exitDist, forKey: EXIT_DISTANCE_KEY)
            } else {
                self.defaults.removeObject(forKey: EXIT_DISTANCE_KEY)
            }
            if let rssi = minRssi {
                self.defaults.set(rssi, forKey: MIN_RSSI_KEY)
                self.minRssiThreshold = rssi
            } else {
                self.defaults.removeObject(forKey: MIN_RSSI_KEY)
                self.minRssiThreshold = DEFAULT_MIN_RSSI
            }
            if let t = exitTimeoutSecs {
                self.defaults.set(t, forKey: EXIT_TIMEOUT_SECONDS_KEY)
                self.exitTimeoutSeconds = t
            } else {
                self.defaults.removeObject(forKey: EXIT_TIMEOUT_SECONDS_KEY)
                self.exitTimeoutSeconds = DEFAULT_EXIT_TIMEOUT_SECONDS
            }
            self.defaults.set(true, forKey: IS_MONITORING_KEY)
            self.requestLocationPermission { granted in
                guard granted else {
                    promise.reject("PERMISSION_DENIED", "Location permission required for monitoring")
                    self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "PERMISSION_DENIED", "message": "Location permission required for monitoring"])
                    return
                }
                // Request Always authorization non-blockingly for background support.
                // On iOS 13+ requestAlwaysAuthorization() from WhenInUse may be a
                // no-op if the user already made their choice — don't block on it.
                if self.locationManager.authorizationStatus != .authorizedAlways {
                    self.locationManager.requestAlwaysAuthorization()
                }
                self.requestNotificationPermission()
                self.startRegionMonitoring()
                // Auto-enable CarPlay monitoring when beacon monitoring starts so
                // CarPlay events are captured for the same lifetime as beacons.
                // Users can opt out at any time via stopCarPlayMonitoring().
                self.defaults.set(true, forKey: CARPLAY_MONITORING_ENABLED_KEY)
                self.startCarPlayMonitoringInternal()
                promise.resolve(nil)
            }
        }

        AsyncFunction("stopMonitoring") { (promise: Promise) in
            self.defaults.set(false, forKey: IS_MONITORING_KEY)
            self.defaults.removeObject(forKey: MAX_DISTANCE_KEY)
            self.defaults.removeObject(forKey: EXIT_DISTANCE_KEY)
            self.defaults.removeObject(forKey: EVENT_LEVEL_KEY)
            self.defaults.removeObject(forKey: EXIT_TIMEOUT_SECONDS_KEY)
            self.eventLevel = "all"
            self.exitTimeoutSeconds = DEFAULT_EXIT_TIMEOUT_SECONDS
            self.lastSeenTimes.removeAll()
            self.stopRegionMonitoring()
            promise.resolve(nil)
        }

        AsyncFunction("requestPermissionsAsync") { (promise: Promise) in
            self.requestLocationPermission { granted in
                promise.resolve(granted)
            }
        }

        // MARK: - CarPlay

        AsyncFunction("startCarPlayMonitoring") { (promise: Promise) in
            self.defaults.set(true, forKey: CARPLAY_MONITORING_ENABLED_KEY)
            self.startCarPlayMonitoringInternal()
            promise.resolve(nil)
        }

        AsyncFunction("stopCarPlayMonitoring") { (promise: Promise) in
            self.defaults.set(false, forKey: CARPLAY_MONITORING_ENABLED_KEY)
            CarPlayMonitor.shared.stop()
            self.stopCarPlayBackgroundWakes()
            promise.resolve(nil)
        }

        Function("isCarPlayMonitoringEnabled") { () -> Bool in
            return self.defaults.bool(forKey: CARPLAY_MONITORING_ENABLED_KEY)
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
            guard scanDurationMs > 0 else {
                promise.reject("INVALID_DURATION", "Scan duration must be a positive integer")
                self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "INVALID_DURATION", "message": "Scan duration must be a positive integer"])
                return
            }
            guard self.eddystoneScanPromise == nil else {
                promise.reject("SCAN_IN_PROGRESS", "An Eddystone scan is already in progress")
                self.sendLoggedEvent("onBeaconError", ["identifier": "", "code": "SCAN_IN_PROGRESS", "message": "An Eddystone scan is already in progress"])
                return
            }
            self.eddystoneScanPromise = promise
            self.eddystoneScannedBeacons = []
            self.startEddystoneScan(durationMs: scanDurationMs)
        }

        // MARK: - Event Logging

        Function("enableEventLogging") { () -> Void in
            if self.eventLogger == nil {
                self.eventLogger = BeaconEventLogger()
            }
            self.defaults.set(true, forKey: EVENT_LOGGING_ENABLED_KEY)
            self.loggingEnabled = true
        }

        Function("disableEventLogging") { () -> Void in
            self.defaults.set(false, forKey: EVENT_LOGGING_ENABLED_KEY)
            self.loggingEnabled = false
        }

        Function("isEventLoggingEnabled") { () -> Bool in
            return self.defaults.bool(forKey: EVENT_LOGGING_ENABLED_KEY)
        }

        Function("getEventLogs") { (options: [String: Any]?) -> [[String: Any]] in
            let logger = self.getOrCreateEventLogger()
            let limit = (options?["limit"] as? Int) ?? 1000
            let eventType = options?["eventType"] as? String
            let sinceTimestamp: Int64? = (options?["sinceTimestamp"] as? NSNumber)?.int64Value
            return logger.getEvents(limit: limit, eventType: eventType, sinceTimestamp: sinceTimestamp)
        }

        Function("clearEventLogs") { () -> Void in
            self.getOrCreateEventLogger().clearEvents()
        }

        Function("destroyEventLogs") { () -> Void in
            self.defaults.set(false, forKey: EVENT_LOGGING_ENABLED_KEY)
            self.loggingEnabled = false
            if let logger = self.eventLogger {
                logger.destroy()
            } else {
                BeaconEventLogger.destroyPersistentStore()
            }
            self.eventLogger = nil
        }

        // MARK: - API Forwarding

        Function("setApiEndpoint") { (url: String, apiKey: String?, id: String?) -> Void in
            self.apiForwarder.configure(url: url, apiKey: apiKey, id: id)
        }

        Function("getApiEndpoint") { () -> [String: String?] in
            return self.apiForwarder.getConfig()
        }

        Function("getMonitoringConfig") { () -> [String: Any?] in
            var result: [String: Any?] = [
                "isMonitoring": self.defaults.bool(forKey: IS_MONITORING_KEY)
            ]
            if let maxDist = self.defaults.object(forKey: MAX_DISTANCE_KEY) as? Double {
                result["maxDistance"] = maxDist
            }
            if let exitDist = self.defaults.object(forKey: EXIT_DISTANCE_KEY) as? Double {
                result["exitDistance"] = exitDist
            }
            if let rssi = self.defaults.object(forKey: MIN_RSSI_KEY) as? Int {
                result["minRssi"] = rssi
            }
            if let level = self.defaults.string(forKey: EVENT_LEVEL_KEY) {
                result["level"] = level
            }
            if let t = self.defaults.object(forKey: EXIT_TIMEOUT_SECONDS_KEY) as? Double {
                result["exitTimeoutSeconds"] = t
            }
            if let json = self.defaults.string(forKey: NOTIFICATION_CONFIG_KEY),
               let data = json.data(using: .utf8),
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                result["notifications"] = obj
            }
            return result
        }

        Function("getMonitoredDeviceState") { (identifier: String) -> [String: Any?]? in
            return self.buildMonitoredDeviceState(identifier: identifier)
        }

        Function("getMonitoredDeviceStates") { () -> [[String: Any?]] in
            return self.buildMonitoredDeviceStates()
        }

        // MARK: - Battery Optimization (Android-only; no-op on iOS)

        Function("isBatteryOptimizationExempt") { () -> Bool in
            return true
        }

        AsyncFunction("requestBatteryOptimizationExemption") { (promise: Promise) in
            promise.resolve(true)
        }

        // MARK: - Lifecycle

        OnDestroy {
            self.loggingEnabled = false
            self.eventLogger = nil
            self.stopRegionMonitoring()
            self.stopEddystoneMonitoring()
            // Only tear down CarPlay observation when the user has explicitly
            // disabled it. Otherwise leave `CarPlayMonitor.shared` running so
            // that route changes continue to be observed across module recreations
            // (e.g. background-launch wake → module re-init → OnDestroy on suspend).
            if !self.defaults.bool(forKey: CARPLAY_MONITORING_ENABLED_KEY) {
                CarPlayMonitor.shared.stop()
            }
            self.centralManager?.stopScan()
            self.centralManager = nil
            self.scanTimer?.cancel()
            self.scanTimer = nil
            self.eddystoneScanTimer?.cancel()
            self.eddystoneScanTimer = nil
            for constraint in self.scanConstraints {
                self.locationManager.stopRangingBeacons(satisfying: constraint)
            }
            self.scanConstraints.removeAll()
            for constraint in self.continuousScanOnlyConstraints {
                self.locationManager.stopRangingBeacons(satisfying: constraint)
            }
            self.continuousScanOnlyConstraints.removeAll()
            self.scanPromise = nil
            self.eddystoneScanPromise = nil
        }
    }
}
