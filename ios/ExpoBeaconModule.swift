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

    /// Created eagerly on the main thread in OnCreate — CLLocationManager
    /// delivers delegate callbacks on the creating thread's run loop, and only
    /// the main thread is guaranteed to have one.
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
    /// all mutable state in this module is accessed exclusively on the main
    /// thread: AsyncFunctions are pinned via .runOnQueue(.main), sync Function
    /// bodies hop via onMainSync, and all delegate callbacks arrive on main.
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

    /// Distance thresholds (meters) cached from UserDefaults in
    /// startRegionMonitoring so per-reading callbacks avoid UserDefaults reads.
    internal var maxDistanceThreshold: Double?
    internal var exitDistanceThreshold: Double?

    /// Event level: "all" emits distance + enter/exit/timeout; "events" suppresses distance.
    internal var eventLevel: String = "all"

    /// Seconds of silence after last valid beacon sighting before a miss-based exit fires.
    internal var exitTimeoutSeconds: TimeInterval = DEFAULT_EXIT_TIMEOUT_SECONDS

    /// Distance smoothing (EMA) state per identifier.
    internal var smoothedDistances: [String: Double] = [:]

    // MARK: - Permissions

    /// Pending completions for in-flight permission requests. Multiple
    /// permission-gated calls can overlap; all are drained on the next
    /// authorization change.
    internal var permissionCompletions: [(Bool) -> Void] = []

    // MARK: - CarPlay

    /// Observer token for `UIApplication.didBecomeActiveNotification` used to
    /// resync CarPlay state on foreground. Owned by the CarPlay extension —
    /// declared here so the module instance can hold it across calls.
    internal var carPlayForegroundObserver: NSObjectProtocol?

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
    internal let defaults: UserDefaults = UserDefaults(suiteName: BEACON_DEFAULTS_SUITE_NAME) ?? .standard

    // MARK: - Main-thread serialization

    /// Runs `block` synchronously on the main thread. Sync `Function` bodies
    /// execute on the JS thread; module state is main-thread-only, so they hop
    /// here before touching it.
    internal func onMainSync<T>(_ block: () throws -> T) rethrows -> T {
        if Thread.isMainThread {
            return try block()
        }
        return try DispatchQueue.main.sync(execute: block)
    }

    /// Runs `block` on the main thread — inline when already there, otherwise
    /// asynchronously. Used by lifecycle listeners, which may fire off-main.
    internal func onMainAsync(_ block: @escaping () -> Void) {
        if Thread.isMainThread {
            block()
        } else {
            DispatchQueue.main.async(execute: block)
        }
    }

    // MARK: - Lifecycle (runs on main — see OnCreate/OnDestroy)

    private func setUpOnCreate() {
        // Touch the lazy CLLocationManager here so it is created on the main
        // thread and its delegate callbacks (incl. permission grants) arrive.
        _ = locationManager
        migrateUserDefaultsIfNeeded()
        // If the user previously enabled CarPlay monitoring, restart it now —
        // the module may have been recreated after the app was killed and
        // background-launched (e.g. via a CLLocationManager region wake).
        // Without this, CarPlay state changes that happened during suspension
        // would be missed until JS calls startCarPlayMonitoring() again.
        if defaults.bool(forKey: CARPLAY_MONITORING_ENABLED_KEY) {
            startCarPlayMonitoringInternal()
            // Cross-process reconciliation: if the previous process recorded
            // CarPlay as connected but the current audio route is no longer
            // CarPlay, emit a synthetic disconnect so JS listeners attached
            // to this freshly-created module learn the session ended.
            CarPlayMonitor.shared.reconcileOnProcessStart()
        }
        // Restart beacon ranging on process recreation (background relaunch via
        // SLC, Visit, or region-entry wake). iOS persists CLBeaconRegion monitoring
        // at the OS level so the app is woken on region boundary crossings, but
        // ranging is per-process and stops when the process is terminated.
        // Without restarting here, handleDidRange callbacks never fire until JS
        // explicitly calls startMonitoring() — causing the observed 2-5 min
        // detection delay when CarPlay is active and the app has been sleeping
        // for days. ENTER_HYSTERESIS_COUNT=1, so the first valid ranging reading
        // fires onBeaconEnter within ~1 s of ranging resuming.
        if defaults.bool(forKey: IS_MONITORING_KEY) {
            let authStatus = locationManager.authorizationStatus
            if authStatus == .authorizedAlways || authStatus == .authorizedWhenInUse {
                startRegionMonitoring()
                // Ask iOS for immediate region-state delivery: if the device is
                // already inside a monitored region, didDetermineState fires with
                // .inside without waiting for the next organic boundary crossing.
                for region in monitoredRegions {
                    locationManager.requestState(for: region)
                }
            }
        }
    }

    private func tearDownOnDestroy() {
        loggingEnabled = false
        eventLogger = nil
        stopRegionMonitoring()
        stopEddystoneMonitoring()
        // Only tear down CarPlay observation when the user has explicitly
        // disabled it. Otherwise leave `CarPlayMonitor.shared` running so
        // that route changes continue to be observed across module recreations
        // (e.g. background-launch wake → module re-init → OnDestroy on suspend).
        if !defaults.bool(forKey: CARPLAY_MONITORING_ENABLED_KEY) {
            CarPlayMonitor.shared.stop()
        }
        // Foreground observer is bound to this module instance — always
        // remove it on destroy to avoid leaking observers across recreations.
        removeAppForegroundObserverForCarPlay()
        centralManager?.stopScan()
        centralManager = nil
        scanTimer?.cancel()
        scanTimer = nil
        eddystoneScanTimer?.cancel()
        eddystoneScanTimer = nil
        for constraint in scanConstraints {
            locationManager.stopRangingBeacons(satisfying: constraint)
        }
        scanConstraints.removeAll()
        for constraint in continuousScanOnlyConstraints {
            locationManager.stopRangingBeacons(satisfying: constraint)
        }
        continuousScanOnlyConstraints.removeAll()
        scanPromise = nil
        eddystoneScanPromise = nil
    }

    // MARK: - Module Definition

    public func definition() -> ModuleDefinition {
        Name("ExpoBeacon")

        OnCreate {
            // Module creation can happen off the main thread (lazy JS-side init),
            // but all mutable state — and the CLLocationManager, whose delegate
            // callbacks require the creating thread's run loop — is main-thread-only.
            self.onMainAsync {
                self.setUpOnCreate()
            }
        }

        Events("onBeaconEnter", "onBeaconExit", "onBeaconDistance", "onBeaconTimeout", "onBeaconFound", "onEddystoneFound", "onEddystoneEnter", "onEddystoneExit", "onEddystoneDistance", "onEddystoneTimeout", "onBeaconError", "onCarPlayConnected", "onCarPlayDisconnected")

        // MARK: - Scan

        AsyncFunction("scanForBeaconsAsync") { (uuids: [String]?, scanDurationMs: Int?, promise: Promise) in
            self.scanForBeacons(uuids: uuids ?? [], durationMs: scanDurationMs ?? DEFAULT_SCAN_DURATION_MS, promise: promise)
        }.runOnQueue(.main)

        Function("cancelScan") { () -> Void in
            self.onMainSync {
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
        }

        // MARK: - Pair

        Function("pairBeacon") { (identifier: String, uuid: String, major: Int, minor: Int, name: String?, timeoutSeconds: Int?) -> Void in
            try self.onMainSync {
                try self.pairBeacon(identifier: identifier, uuid: uuid, major: major, minor: minor, name: name, timeoutSeconds: timeoutSeconds)
            }
        }

        Function("unpairBeacon") { (identifier: String) in
            self.onMainSync {
                var beacons = self.loadPairedBeaconsRaw()
                beacons.removeAll { ($0["identifier"] as? String) == identifier }
                self.defaults.set(beacons, forKey: PAIRED_BEACONS_KEY)
                self.cachedPairedBeacons = nil
            }
        }

        Function("getPairedBeacons") { () -> [[String: Any]] in
            return self.onMainSync { self.loadPairedBeaconsRaw() }
        }

        // MARK: - Eddystone Pair

        Function("pairEddystone") { (identifier: String, namespace: String, instance: String, name: String?, timeoutSeconds: Int?) -> Void in
            try self.onMainSync {
                try self.pairEddystone(identifier: identifier, namespace: namespace, instance: instance, name: name, timeoutSeconds: timeoutSeconds)
            }
        }

        Function("unpairEddystone") { (identifier: String) in
            self.onMainSync {
                var eddystones = self.loadPairedEddystonesRaw()
                eddystones.removeAll { ($0["identifier"] as? String) == identifier }
                self.defaults.set(eddystones, forKey: PAIRED_EDDYSTONES_KEY)
                self.cachedPairedEddystones = nil
            }
        }

        Function("getPairedEddystones") { () -> [[String: Any]] in
            return self.onMainSync { self.loadPairedEddystonesRaw() }
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
            self.startMonitoring(options: options, promise: promise)
        }.runOnQueue(.main)

        AsyncFunction("stopMonitoring") { (promise: Promise) in
            self.defaults.set(false, forKey: IS_MONITORING_KEY)
            self.defaults.removeObject(forKey: MAX_DISTANCE_KEY)
            self.defaults.removeObject(forKey: EXIT_DISTANCE_KEY)
            self.defaults.removeObject(forKey: MIN_RSSI_KEY)
            self.defaults.removeObject(forKey: EVENT_LEVEL_KEY)
            self.defaults.removeObject(forKey: EXIT_TIMEOUT_SECONDS_KEY)
            self.eventLevel = "all"
            self.minRssiThreshold = DEFAULT_MIN_RSSI
            self.exitTimeoutSeconds = DEFAULT_EXIT_TIMEOUT_SECONDS
            self.maxDistanceThreshold = nil
            self.exitDistanceThreshold = nil
            self.lastSeenTimes.removeAll()
            self.stopRegionMonitoring()
            promise.resolve(nil)
        }.runOnQueue(.main)

        AsyncFunction("requestPermissionsAsync") { (promise: Promise) in
            self.requestLocationPermission { granted in
                promise.resolve(granted)
            }
        }.runOnQueue(.main)

        // MARK: - CarPlay

        AsyncFunction("startCarPlayMonitoring") { (promise: Promise) in
            self.defaults.set(true, forKey: CARPLAY_MONITORING_ENABLED_KEY)
            self.startCarPlayMonitoringInternal()
            promise.resolve(nil)
        }.runOnQueue(.main)

        AsyncFunction("stopCarPlayMonitoring") { (promise: Promise) in
            self.defaults.set(false, forKey: CARPLAY_MONITORING_ENABLED_KEY)
            CarPlayMonitor.shared.stop()
            self.stopCarPlayBackgroundWakes()
            self.removeAppForegroundObserverForCarPlay()
            promise.resolve(nil)
        }.runOnQueue(.main)

        Function("isCarPlayMonitoringEnabled") { () -> Bool in
            return self.defaults.bool(forKey: CARPLAY_MONITORING_ENABLED_KEY)
        }

        Function("getCarPlayConnectionStatus") { () -> [String: Any] in
            // Read the state persisted by CarPlayMonitor at connect/disconnect
            // time — transport and timestamp reflect the actual last connect,
            // never a value fabricated at query time.
            let connected = self.defaults.bool(forKey: CARPLAY_LAST_CONNECTED_KEY)
            var out: [String: Any] = ["connected": connected]
            if connected {
                if let transport = self.defaults.string(forKey: CARPLAY_LAST_TRANSPORT_KEY) {
                    out["transport"] = transport
                }
                if let ts = self.defaults.object(forKey: CARPLAY_LAST_CONNECTED_AT_KEY) as? Double {
                    out["timestamp"] = ts
                    out["timestampIso"] = CarPlayMonitor.isoFormatter.string(from: Date(timeIntervalSince1970: ts / 1000.0))
                }
            }
            return out
        }

        Function("getCarPlayDiagnostics") { () -> [String: Any] in
            // iOS detection is via AVAudioSession, not a content provider —
            // most diagnostic fields aren't applicable. Returning constants
            // keeps the cross-platform JS surface uniform.
            return [
                "isCarAppMetadataPresent": true,
                "isCarProviderQueryable": true,
                "lastRawConnectionType": NSNull(),
                "observerActive": self.defaults.bool(forKey: CARPLAY_MONITORING_ENABLED_KEY),
                "serviceAlive": true,
            ]
        }

        // MARK: - Continuous Scan

        Function("startContinuousScan") { () -> Void in
            self.onMainSync {
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
        }

        Function("stopContinuousScan") { () -> Void in
            self.onMainSync {
                self.continuousScanActive = false
                for constraint in self.continuousScanOnlyConstraints {
                    self.locationManager.stopRangingBeacons(satisfying: constraint)
                }
                self.continuousScanOnlyConstraints.removeAll()
                self.stopBleScanIfUnneeded()
            }
        }

        // MARK: - Eddystone Scan

        AsyncFunction("scanForEddystonesAsync") { (scanDurationMs: Int?, promise: Promise) in
            let durationMs = scanDurationMs ?? DEFAULT_SCAN_DURATION_MS
            guard durationMs > 0 else {
                self.rejectAndEmit(promise, "INVALID_DURATION", "Scan duration must be a positive integer")
                return
            }
            guard self.eddystoneScanPromise == nil else {
                self.rejectAndEmit(promise, "SCAN_IN_PROGRESS", "An Eddystone scan is already in progress")
                return
            }
            self.eddystoneScanPromise = promise
            self.eddystoneScannedBeacons = []
            self.startEddystoneScan(durationMs: durationMs)
        }.runOnQueue(.main)

        // MARK: - Event Logging

        Function("enableEventLogging") { () -> Void in
            self.onMainSync {
                if self.eventLogger == nil {
                    self.eventLogger = BeaconEventLogger()
                }
                self.defaults.set(true, forKey: EVENT_LOGGING_ENABLED_KEY)
                self.loggingEnabled = true
            }
        }

        Function("disableEventLogging") { () -> Void in
            self.onMainSync {
                self.defaults.set(false, forKey: EVENT_LOGGING_ENABLED_KEY)
                self.loggingEnabled = false
            }
        }

        Function("isEventLoggingEnabled") { () -> Bool in
            return self.defaults.bool(forKey: EVENT_LOGGING_ENABLED_KEY)
        }

        Function("getEventLogs") { (options: [String: Any]?) -> [[String: Any]] in
            return self.onMainSync { () -> [[String: Any]] in
                let logger = self.getOrCreateEventLogger()
                let limit = (options?["limit"] as? Int) ?? 1000
                let eventType = options?["eventType"] as? String
                let sinceTimestamp: Int64? = (options?["sinceTimestamp"] as? NSNumber)?.int64Value
                return logger.getEvents(limit: limit, eventType: eventType, sinceTimestamp: sinceTimestamp)
            }
        }

        Function("clearEventLogs") { () -> Void in
            self.onMainSync {
                self.getOrCreateEventLogger().clearEvents()
            }
        }

        Function("destroyEventLogs") { () -> Void in
            self.onMainSync {
                self.defaults.set(false, forKey: EVENT_LOGGING_ENABLED_KEY)
                self.loggingEnabled = false
                if let logger = self.eventLogger {
                    logger.destroy()
                } else {
                    BeaconEventLogger.destroyPersistentStore()
                }
                self.eventLogger = nil
            }
        }

        // MARK: - API Forwarding

        Function("setApiEndpoint") { (url: String, apiKey: String?, id: String?) -> Void in
            self.onMainSync {
                self.apiForwarder.configure(url: url, apiKey: apiKey, id: id)
            }
        }

        Function("getApiEndpoint") { () -> [String: String?] in
            return self.onMainSync { self.apiForwarder.getConfig() }
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
            return self.onMainSync { self.buildMonitoredDeviceState(identifier: identifier) }
        }

        Function("getMonitoredDeviceStates") { () -> [[String: Any?]] in
            return self.onMainSync { self.buildMonitoredDeviceStates() }
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
            self.onMainAsync {
                self.tearDownOnDestroy()
            }
        }
    }
}
