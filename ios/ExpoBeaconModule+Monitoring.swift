import CoreLocation
import ExpoModulesCore
import os

extension ExpoBeaconModule {
    // MARK: - Module-owned Core Location regions

    func nativeRegionIdentifier(for identifier: String) -> String {
        return BEACON_REGION_IDENTIFIER_PREFIX + identifier
    }

    func publicRegionIdentifier(for region: CLRegion) -> String? {
        guard region.identifier.hasPrefix(BEACON_REGION_IDENTIFIER_PREFIX) else { return nil }
        return String(region.identifier.dropFirst(BEACON_REGION_IDENTIFIER_PREFIX.count))
    }

    func isModuleOwnedRegion(_ region: CLRegion) -> Bool {
        return region.identifier.hasPrefix(BEACON_REGION_IDENTIFIER_PREFIX)
    }

    private func storedInt(_ value: Any?) -> Int? {
        if let value = value as? Int { return value }
        return (value as? NSNumber)?.intValue
    }

    func makeIdentityConstraint(from entry: [String: Any]) -> CLBeaconIdentityConstraint? {
        guard let uuidString = entry["uuid"] as? String,
              let uuid = UUID(uuidString: uuidString),
              let major = storedInt(entry["major"]),
              let minor = storedInt(entry["minor"]) else {
            return nil
        }
        return CLBeaconIdentityConstraint(
            uuid: uuid,
            major: CLBeaconMajorValue(major),
            minor: CLBeaconMinorValue(minor)
        )
    }

    @discardableResult
    func addMonitoredIBeacon(from entry: [String: Any], enforceCapacity: Bool = true) -> Bool {
        guard let identifier = entry["identifier"] as? String,
              let constraint = makeIdentityConstraint(from: entry) else { return false }

        if enforceCapacity {
            let hostRegionCount = locationManager.monitoredRegions.filter { !isModuleOwnedRegion($0) }.count
            let newNativeIdentifier = nativeRegionIdentifier(for: identifier)
            var ownedIdentifiers = Set(
                locationManager.monitoredRegions
                    .filter { isModuleOwnedRegion($0) && $0.identifier != newNativeIdentifier }
                    .map(\.identifier)
            )
            ownedIdentifiers.formUnion(
                monitoredRegions
                    .filter { $0.identifier != newNativeIdentifier }
                    .map(\.identifier)
            )
            guard hostRegionCount + ownedIdentifiers.count < 20 else {
                sendLoggedEvent("onBeaconError", [
                    "identifier": identifier,
                    "code": "REGION_LIMIT_EXCEEDED",
                    "message": "No Core Location region slot is available for this beacon"
                ])
                return false
            }
        }

        let region = CLBeaconRegion(
            beaconIdentityConstraint: constraint,
            identifier: nativeRegionIdentifier(for: identifier)
        )
        region.notifyOnEntry = true
        region.notifyOnExit = true
        region.notifyEntryStateOnDisplay = true
        monitoredRegions.append(region)
        distanceRangingConstraints[identifier] = constraint
        locationManager.startMonitoring(for: region)
        locationManager.startRangingBeacons(satisfying: constraint)
        return true
    }

    func clearIBeaconRuntimeState(identifier: String) {
        enteredRegions.remove(identifier)
        lastSeenTimes.removeValue(forKey: identifier)
        enterCounters.removeValue(forKey: identifier)
        exitCounters.removeValue(forKey: identifier)
        smoothedDistances.removeValue(forKey: identifier)
        cancelBeaconTimeout(identifier: identifier)
        cancelBeaconInactivity(identifier: identifier)
    }

    func removeMonitoredIBeacon(identifier: String) {
        let nativeIdentifier = nativeRegionIdentifier(for: identifier)
        let localMatches = monitoredRegions.filter { $0.identifier == nativeIdentifier }
        for region in localMatches { locationManager.stopMonitoring(for: region) }
        monitoredRegions.removeAll { $0.identifier == nativeIdentifier }

        // Also remove a restored module-owned region that predates this module
        // instance and therefore is not present in `monitoredRegions`.
        for region in locationManager.monitoredRegions where region.identifier == nativeIdentifier {
            locationManager.stopMonitoring(for: region)
        }
        if let constraint = distanceRangingConstraints.removeValue(forKey: identifier) {
            locationManager.stopRangingBeacons(satisfying: constraint)
        }
        clearIBeaconRuntimeState(identifier: identifier)
    }

    func replaceMonitoredIBeacon(identifier: String, with entry: [String: Any]) {
        guard defaults.bool(forKey: IS_MONITORING_KEY) else { return }
        if let newConstraint = makeIdentityConstraint(from: entry),
           let current = distanceRangingConstraints[identifier],
           current == newConstraint {
            // Identity did not change; retain enter state but discard timers that
            // were configured from the previous pairing metadata.
            cancelBeaconTimeout(identifier: identifier)
            cancelBeaconInactivity(identifier: identifier)
            return
        }
        removeMonitoredIBeacon(identifier: identifier)
        _ = addMonitoredIBeacon(from: entry)
    }

    func removeMonitoredEddystone(identifier: String) {
        eddystoneLatestSeen.removeValue(forKey: identifier)
        eddystoneEnteredRegions.remove(identifier)
        eddystoneEnterCounters.removeValue(forKey: identifier)
        eddystoneExitCounters.removeValue(forKey: identifier)
        eddystoneLastDistanceEmit.removeValue(forKey: identifier)
        smoothedDistances.removeValue(forKey: identifier)
        cancelEddystoneTimeout(identifier: identifier)
        cancelEddystoneInactivity(identifier: identifier)
    }

    func refreshMonitoredEddystone(identifier: String, identityChanged: Bool) {
        guard defaults.bool(forKey: IS_MONITORING_KEY) else { return }
        if identityChanged {
            removeMonitoredEddystone(identifier: identifier)
        } else {
            cancelEddystoneTimeout(identifier: identifier)
            cancelEddystoneInactivity(identifier: identifier)
        }
        if !eddystoneMonitoringActive {
            startEddystoneMonitoring()
        }
    }

    // MARK: - startMonitoring (option parsing + validation)

    func startMonitoring(options: Either<Double, [String: Any]>?, promise: Promise) {
        guard !loadPairedBeaconsRaw().isEmpty || !loadPairedEddystonesRaw().isEmpty else {
            rejectAndEmit(promise, "NO_PAIRED_BEACONS", "Pair at least one iBeacon or Eddystone before starting monitoring")
            return
        }
        var maxDistance: Double? = nil
        var exitDistance: Double? = nil
        var minRssi: Int? = nil
        var exitTimeoutSecs: Double? = nil
        var level = "all"
        var notificationsConfig: [String: Any]? = nil
        if let dist: Double = options?.get() {
            maxDistance = dist
        } else if let map: [String: Any] = options?.get() {
            maxDistance = map["maxDistance"] as? Double
            exitDistance = map["exitDistance"] as? Double
            minRssi = map["minRssi"] as? Int
            exitTimeoutSecs = map["exitTimeoutSeconds"] as? Double
            if let lvl = map["level"] as? String, lvl == "events" || lvl == "all" {
                level = lvl
            }
            if let notifications = map["notifications"] as? [String: Any] {
                notificationsConfig = notifications
            }
        }
        if let dist = maxDistance, (!dist.isFinite || dist <= 0) {
            rejectAndEmit(promise, "INVALID_MAX_DISTANCE", "maxDistance must be a finite number greater than 0")
            return
        }
        if let exitDist = exitDistance, (!exitDist.isFinite || exitDist <= 0) {
            rejectAndEmit(promise, "INVALID_EXIT_DISTANCE", "exitDistance must be a finite number greater than 0")
            return
        }
        if exitDistance != nil && maxDistance == nil {
            rejectAndEmit(promise, "INVALID_EXIT_DISTANCE", "exitDistance requires maxDistance to be set")
            return
        }
        if let dist = maxDistance, let exitDist = exitDistance, exitDist < dist {
            rejectAndEmit(promise, "INVALID_EXIT_DISTANCE", "exitDistance must be greater than or equal to maxDistance")
            return
        }
        if let t = exitTimeoutSecs, (!t.isFinite || t <= 0) {
            rejectAndEmit(promise, "INVALID_EXIT_TIMEOUT", "exitTimeoutSeconds must be a finite number greater than 0")
            return
        }
        // Persist only after validation passes. Both call shapes (legacy number
        // and options map) reset the same keys so they leave consistent state.
        self.eventLevel = level
        defaults.set(level, forKey: EVENT_LEVEL_KEY)
        if let notificationsConfig {
            mergeNotificationConfig(notificationsConfig)
        }
        if let dist = maxDistance {
            defaults.set(dist, forKey: MAX_DISTANCE_KEY)
        } else {
            defaults.removeObject(forKey: MAX_DISTANCE_KEY)
        }
        if let exitDist = exitDistance {
            defaults.set(exitDist, forKey: EXIT_DISTANCE_KEY)
        } else {
            defaults.removeObject(forKey: EXIT_DISTANCE_KEY)
        }
        if let rssi = minRssi {
            defaults.set(rssi, forKey: MIN_RSSI_KEY)
            minRssiThreshold = rssi
        } else {
            defaults.removeObject(forKey: MIN_RSSI_KEY)
            minRssiThreshold = DEFAULT_MIN_RSSI
        }
        if let t = exitTimeoutSecs {
            defaults.set(t, forKey: EXIT_TIMEOUT_SECONDS_KEY)
            exitTimeoutSeconds = t
        } else {
            defaults.removeObject(forKey: EXIT_TIMEOUT_SECONDS_KEY)
            exitTimeoutSeconds = DEFAULT_EXIT_TIMEOUT_SECONDS
        }
        // An Eddystone-only configuration uses CoreBluetooth and should not be
        // blocked by an unrelated CoreLocation denial.
        if loadPairedBeaconsRaw().isEmpty {
            activateMonitoring(promise: promise)
            return
        }

        requestLocationPermission { [weak self] granted in
            guard let self, !self.isModuleDestroyed else {
                promise.reject("MODULE_DESTROYED", "Beacon module was destroyed while requesting permission")
                return
            }
            guard granted else {
                self.rejectAndEmit(promise, "PERMISSION_DENIED", "Location permission required for monitoring")
                return
            }
            // Request Always authorization non-blockingly for background support.
            // On iOS 13+ requestAlwaysAuthorization() from WhenInUse may be a
            // no-op if the user already made their choice — don't block on it.
            if self.locationManager.authorizationStatus != .authorizedAlways {
                self.locationManager.requestAlwaysAuthorization()
            }
            self.activateMonitoring(promise: promise)
        }
    }

    private func activateMonitoring(promise: Promise) {
        guard !isModuleDestroyed else {
            promise.reject("MODULE_DESTROYED", "Beacon module was destroyed before monitoring could start")
            return
        }
        requestNotificationPermission()
        startRegionMonitoring()
        defaults.set(true, forKey: IS_MONITORING_KEY)
        promise.resolve(nil)
    }

    // MARK: - Region monitoring lifecycle

    func startRegionMonitoring() {
        stopRegionMonitoring()

        // Restore persisted min RSSI threshold (survives app restarts)
        let storedRssi = defaults.object(forKey: MIN_RSSI_KEY) as? Int
        minRssiThreshold = storedRssi ?? DEFAULT_MIN_RSSI

        // Restore persisted event level (survives app restarts)
        eventLevel = defaults.string(forKey: EVENT_LEVEL_KEY) ?? "all"

        // Restore persisted exit timeout (survives app restarts)
        if let t = defaults.object(forKey: EXIT_TIMEOUT_SECONDS_KEY) as? Double, t > 0 {
            exitTimeoutSeconds = t
        } else {
            exitTimeoutSeconds = DEFAULT_EXIT_TIMEOUT_SECONDS
        }

        // Cache distance thresholds so ranging callbacks and BLE advertisements
        // don't re-read UserDefaults on every reading
        maxDistanceThreshold = defaults.object(forKey: MAX_DISTANCE_KEY) as? Double
        exitDistanceThreshold = defaults.object(forKey: EXIT_DISTANCE_KEY) as? Double

        let beacons = loadPairedBeaconsRaw()

        // CLLocationManager supports a maximum of 20 monitored regions.
        // Log a warning if we exceed this — extra regions will silently fail.
        let maxRegions = 20
        let hostRegionCount = locationManager.monitoredRegions.filter { !isModuleOwnedRegion($0) }.count
        let availableRegionSlots = max(0, maxRegions - hostRegionCount)
        if beacons.count > availableRegionSlots {
            let message = "\(beacons.count) paired beacons exceeds the \(availableRegionSlots) Core Location slots available after accounting for \(hostRegionCount) host-app regions."
            print("[ExpoBeacon] Warning: \(message)")
            sendLoggedEvent("onBeaconError", ["identifier": "", "code": "REGION_LIMIT_EXCEEDED", "message": message])
        }

        for beacon in beacons.prefix(availableRegionSlots) {
            _ = addMonitoredIBeacon(from: beacon, enforceCapacity: false)
        }

        // Start Eddystone-UID monitoring if any paired Eddystones exist
        let eddystones = loadPairedEddystonesRaw()
        if !eddystones.isEmpty {
            startEddystoneMonitoring()
        }
    }

    func stopRegionMonitoring() {
        var ownedRegions = Array(locationManager.monitoredRegions.filter { isModuleOwnedRegion($0) })
        for region in monitoredRegions where !ownedRegions.contains(where: { $0.identifier == region.identifier }) {
            ownedRegions.append(region)
        }
        for region in ownedRegions {
            locationManager.stopMonitoring(for: region)
        }
        monitoredRegions.removeAll()

        for constraint in distanceRangingConstraints.values {
            locationManager.stopRangingBeacons(satisfying: constraint)
        }
        distanceRangingConstraints.removeAll()
        enteredRegions.removeAll()
        lastSeenTimes.removeAll()
        enterCounters.removeAll()
        exitCounters.removeAll()
        smoothedDistances.removeAll()

        for timer in beaconTimeoutTimers.values { timer.cancel() }
        beaconTimeoutTimers.removeAll()

        for timer in beaconInactivityTimers.values { timer.cancel() }
        beaconInactivityTimers.removeAll()

        stopEddystoneMonitoring()
    }

    // MARK: - Distance smoothing + enter/exit hysteresis

    /// Apply exponential moving average (EMA) smoothing to a raw distance reading.
    /// If the reading is a large jump (> DISTANCE_JUMP_FACTOR), resets the EMA to the new
    /// value instead of rejecting it — this ensures distance events keep flowing when the
    /// user moves away from a beacon, rather than freezing because the EMA is stuck at the
    /// old close-range value and every new far-range reading is rejected.
    func smoothDistance(identifier: String, rawDistance: Double) -> Double {
        guard let prev = smoothedDistances[identifier] else {
            smoothedDistances[identifier] = rawDistance
            return rawDistance
        }
        // Jump guard: if the raw value is wildly different, reset EMA to the new reading
        // so the hysteresis pipeline keeps receiving data and can fire the exit event.
        let ratio = prev > 0.001 ? rawDistance / prev : rawDistance
        if ratio > ExpoBeaconModule.DISTANCE_JUMP_FACTOR || (ratio > 0 && ratio < 1.0 / ExpoBeaconModule.DISTANCE_JUMP_FACTOR) {
            smoothedDistances[identifier] = rawDistance
            return rawDistance
        }
        let smoothed = ExpoBeaconModule.DISTANCE_EMA_ALPHA * rawDistance + (1 - ExpoBeaconModule.DISTANCE_EMA_ALPHA) * prev
        smoothedDistances[identifier] = smoothed
        return smoothed
    }

    enum HysteresisAction {
        case none, enter, exit
    }

    /// Computes the effective exit distance from maxDistance and an optional explicit exitDistance.
    /// Default: maxDistance + min(maxDistance × 0.5, 2.5).
    static func effectiveExitDistance(maxDistance: Double, exitDistance: Double?) -> Double {
        if let explicit = exitDistance { return explicit }
        return maxDistance + min(maxDistance * 0.5, 2.5)
    }

    /// Shared distance-based enter/exit evaluation with hysteresis.
    /// Used by both iBeacon (handleDidRange) and Eddystone (handleEddystoneDiscovery) paths.
    func evaluateDistanceHysteresis(
        identifier: String,
        distance: Double,
        maxDistance: Double?,
        exitDistance: Double?,
        entered: inout Set<String>,
        enterCtrs: inout [String: Int],
        exitCtrs: inout [String: Int]
    ) -> HysteresisAction {
        if let maxDist = maxDistance {
            let exitDist = ExpoBeaconModule.effectiveExitDistance(maxDistance: maxDist, exitDistance: exitDistance)
            if distance <= maxDist {
                exitCtrs[identifier] = 0
                enterCtrs[identifier] = (enterCtrs[identifier] ?? 0) + 1
                if !entered.contains(identifier) && (enterCtrs[identifier] ?? 0) >= ENTER_HYSTERESIS_COUNT {
                    entered.insert(identifier)
                    enterCtrs[identifier] = 0
                    return .enter
                }
            } else if distance > exitDist {
                enterCtrs[identifier] = 0
                exitCtrs[identifier] = (exitCtrs[identifier] ?? 0) + 1
                if entered.contains(identifier) && (exitCtrs[identifier] ?? 0) >= EXIT_HYSTERESIS_COUNT {
                    entered.remove(identifier)
                    exitCtrs[identifier] = 0
                    return .exit
                }
            } else {
                // In the hysteresis band (maxDist < distance <= exitDist) — do nothing
                enterCtrs[identifier] = 0
                exitCtrs[identifier] = 0
            }
        } else {
            enterCtrs[identifier] = (enterCtrs[identifier] ?? 0) + 1
            if !entered.contains(identifier) && (enterCtrs[identifier] ?? 0) >= ENTER_HYSTERESIS_COUNT {
                entered.insert(identifier)
                enterCtrs[identifier] = 0
                return .enter
            }
        }
        return .none
    }

    /// Constructs a standard iBeacon event payload dictionary.
    /// Use `beacon` for live ranging data, or `region` for region-based events.
    func makeBeaconEventParams(
        identifier: String,
        beacon: CLBeacon? = nil,
        region: CLBeaconRegion? = nil,
        event: String? = nil,
        distance: Double = -1
    ) -> [String: Any] {
        var params: [String: Any] = [
            "identifier": identifier,
            "uuid": (beacon?.uuid ?? region?.uuid)?.uuidString.uppercased() ?? "",
            "major": beacon?.major.intValue ?? region?.major?.intValue ?? 0,
            "minor": beacon?.minor.intValue ?? region?.minor?.intValue ?? 0,
            "distance": beacon != nil ? beacon!.accuracy : distance,
            "rssi": beacon?.rssi ?? 0
        ]
        if let event = event {
            params["event"] = event
        }
        return params
    }

    // MARK: - CLLocationManagerDelegate handlers (called by LocationDelegate)

    func handleDidRange(_ beacons: [CLBeacon], satisfying constraint: CLBeaconIdentityConstraint) {
        // Three mutually exclusive ranging paths (checked in order, early-return):
        // 1. One-shot scan constraints — collecting beacons for scanForBeaconsAsync
        // 2. Distance-ranging constraints from monitoring — distance events + enter/exit
        // 3. Continuous-scan-only constraints (UUID-only) — discovery via onBeaconFound
        // The early returns prevent duplicate events when monitoring and continuous
        // scan are both active.

        // 1. One-shot scan mode
        if scanConstraints.contains(where: { $0 == constraint }) {
            // Keep only the latest sample for each physical beacon so long
            // scans remain bounded and an initial accuracy=-1 sample does not
            // hide a later valid reading.
            for beacon in beacons {
                if let index = scannedBeacons.firstIndex(where: {
                    $0.uuid == beacon.uuid && $0.major == beacon.major && $0.minor == beacon.minor
                }) {
                    scannedBeacons[index] = beacon
                } else {
                    scannedBeacons.append(beacon)
                }
            }
        }

        // 2. Distance-ranging for monitored beacons
        if let (identifier, _) = distanceRangingConstraints.first(where: { $0.value == constraint }) {
            let validBeacon = beacons.first(where: { $0.accuracy >= 0 && $0.rssi >= minRssiThreshold })

            if let beacon = validBeacon {
                // Got a valid reading — record sighting time
                lastSeenTimes[identifier] = Date()
                // Cancel an inactivity-armed timeout only while this beacon is
                // still logically entered. Once a distance exit occurs, merely
                // seeing it beyond the exit threshold must not cancel timeout.
                if enteredRegions.contains(identifier) {
                    cancelBeaconTimeout(identifier: identifier)
                }

                // Emit distance event every ranging cycle (~1 s) if level allows
                if self.eventLevel == "all" {
                    sendLoggedEvent("onBeaconDistance", makeBeaconEventParams(identifier: identifier, beacon: beacon))
                }

                // Enter/exit synthesis with hysteresis — always applied.
                // When maxDistance is set, distance thresholds control transitions.
                // When maxDistance is nil, pure presence-based hysteresis is used
                // (ENTER_HYSTERESIS_COUNT consecutive readings to confirm enter).
                let maxDist = maxDistanceThreshold
                let exitDist = exitDistanceThreshold

                // Apply EMA smoothing; jump resets EMA to the new value
                let smoothed = smoothDistance(identifier: identifier, rawDistance: beacon.accuracy)

                let action = evaluateDistanceHysteresis(
                    identifier: identifier,
                    distance: smoothed,
                    maxDistance: maxDist,
                    exitDistance: exitDist,
                    entered: &enteredRegions,
                    enterCtrs: &enterCounters,
                    exitCtrs: &exitCounters
                )
                switch action {
                case .enter:
                    sendLoggedEvent("onBeaconEnter", makeBeaconEventParams(identifier: identifier, beacon: beacon, event: "enter"))
                    postBeaconNotification(identifier: identifier, eventType: "enter")
                    // Beacon returned — cancel any running timeout timer.
                    cancelBeaconTimeout(identifier: identifier)
                case .exit:
                    smoothedDistances.removeValue(forKey: identifier)
                    sendLoggedEvent("onBeaconExit", makeBeaconEventParams(identifier: identifier, beacon: beacon, event: "exit"))
                    postBeaconNotification(identifier: identifier, eventType: "exit")
                    // Beacon left — cancel inactivity timer and start the timeout clock.
                    cancelBeaconInactivity(identifier: identifier)
                    scheduleBeaconTimeout(identifier: identifier, beacon: beacon)
                case .none:
                    break
                }

                // Inactivity only applies while logically entered. Scheduling
                // it for far-but-visible beacons would later reset an exit timeout.
                if enteredRegions.contains(identifier) {
                    rescheduleBeaconInactivity(identifier: identifier, beacon: beacon)
                }

                // Note: onBeaconFound for continuous scan is emitted by the
                // UUID-only constraints in check 3 below, not here, to avoid
                // duplicate events when both monitoring and continuous scan are active.
            } else {
                // No valid beacon reading — beacon may have disappeared.
                // Preserve enter counter so background accuracy=-1 gaps don't
                // force re-accumulating ENTER_HYSTERESIS_COUNT reads from scratch.
                exitCounters[identifier] = 0

                if enteredRegions.contains(identifier),
                   let lastSeen = lastSeenTimes[identifier],
                   Date().timeIntervalSince(lastSeen) >= exitTimeoutSeconds {
                    enteredRegions.remove(identifier)
                    enterCounters[identifier] = 0
                    exitCounters[identifier] = 0
                    lastSeenTimes.removeValue(forKey: identifier)
                    smoothedDistances.removeValue(forKey: identifier)

                    // Look up region info for the exit event payload
                    let nativeIdentifier = nativeRegionIdentifier(for: identifier)
                    let region = monitoredRegions.first { $0.identifier == nativeIdentifier }
                    sendLoggedEvent("onBeaconExit", makeBeaconEventParams(identifier: identifier, region: region, event: "exit"))
                    postBeaconNotification(identifier: identifier, eventType: "exit")
                    // Beacon disappeared — cancel inactivity timer and start the timeout clock.
                    cancelBeaconInactivity(identifier: identifier)
                    scheduleBeaconTimeout(identifier: identifier, region: region)
                }
            }
            return
        }

        // 3. Continuous-scan-only constraints (monitoring not active)
        if continuousScanActive,
           continuousScanOnlyConstraints.contains(where: { $0 == constraint }) {
            for beacon in beacons where beacon.accuracy >= 0 {
                let params: [String: Any] = [
                    "uuid": beacon.uuid.uuidString.uppercased(),
                    "major": beacon.major.intValue,
                    "minor": beacon.minor.intValue,
                    "rssi": beacon.rssi,
                    "distance": beacon.accuracy,
                    "txPower": 0
                ]
                sendLoggedEvent("onBeaconFound", params)
            }
        }
    }

    func handleDidEnterRegion(_ region: CLRegion) {
        // Region callbacks are suppressed — all enter/exit logic goes through
        // ranging-based hysteresis in handleDidRange for consistent behaviour
        // with ENTER_HYSTERESIS_COUNT / EXIT_HYSTERESIS_COUNT, regardless of whether maxDistance is set.
    }

    func handleDidExitRegion(_ region: CLRegion) {
        guard let beaconRegion = region as? CLBeaconRegion else { return }
        guard let identifier = publicRegionIdentifier(for: beaconRegion) else { return }

        // Ranging-based hysteresis (handleDidRange miss counter) handles exit
        // in most cases. However, when the OS fires didExitRegion, ranging may
        // have already stopped delivering callbacks for this beacon. If the
        // beacon was in "entered" state, emit the exit event as a safety net.
        let wasEntered = enteredRegions.remove(identifier) != nil
        enterCounters.removeValue(forKey: identifier)
        exitCounters.removeValue(forKey: identifier)
        smoothedDistances.removeValue(forKey: identifier)
        if wasEntered {
            sendLoggedEvent("onBeaconExit", makeBeaconEventParams(identifier: identifier, region: beaconRegion, event: "exit"))
            postBeaconNotification(identifier: identifier, eventType: "exit")
            // OS-level exit safety net — cancel inactivity timer and start the timeout clock.
            cancelBeaconInactivity(identifier: identifier)
            scheduleBeaconTimeout(identifier: identifier, region: beaconRegion)
        }
    }

    func handleMonitoringDidFail(for region: CLRegion?, withError error: Error) {
        let id = region.flatMap { publicRegionIdentifier(for: $0) } ?? region?.identifier ?? "unknown"
        // Qualified — ExpoModulesCore also exports a `Logger` type.
        os.Logger(subsystem: "expo.modules.beacon", category: "monitoring")
            .error("Monitoring failed for region \(id, privacy: .public): \(error.localizedDescription, privacy: .public)")
        sendLoggedEvent("onBeaconError", [
            "identifier": id,
            "code": "MONITORING_FAILED",
            "message": error.localizedDescription
        ])
    }

    func handleDidFailRanging(for constraint: CLBeaconIdentityConstraint, error: Error) {
        print("[ExpoBeacon] Ranging failed for constraint \(constraint.uuid): \(error.localizedDescription)")
        sendLoggedEvent("onBeaconError", [
            "identifier": constraint.uuid.uuidString,
            "code": "RANGING_FAILED",
            "message": error.localizedDescription
        ])

        // If a one-shot scan is active and this constraint belongs to it, reject the promise
        if scanPromise != nil && scanConstraints.contains(where: { $0 == constraint }) {
            // Disarm the scan timer so it cannot prematurely resolve a future scan
            scanTimer?.cancel()
            scanTimer = nil
            // Stop all scan constraints
            for sc in scanConstraints {
                if !continuousScanOnlyConstraints.contains(where: { $0 == sc }) {
                    locationManager.stopRangingBeacons(satisfying: sc)
                }
            }
            scanConstraints.removeAll()
            scanRequestID = nil
            scannedBeacons.removeAll()
            scanPromise?.reject("RANGING_FAILED", "Beacon ranging failed: \(error.localizedDescription)")
            scanPromise = nil
        }
    }

    /// Response to `locationManager.requestState(for:)` called during `OnCreate`
    /// after a background process restart. When iOS confirms `.inside`, seed the
    /// enter counter to ENTER_HYSTERESIS_COUNT − 1 so the very first valid ranging
    /// reading immediately fires `onBeaconEnter` without accumulating additional
    /// cycles. When `.outside`, make sure the beacon is not stuck in `enteredRegions`
    /// from a stale prior session.
    func handleDidDetermineState(_ state: CLRegionState, for region: CLRegion) {
        guard let beaconRegion = region as? CLBeaconRegion else { return }
        guard let identifier = publicRegionIdentifier(for: beaconRegion) else { return }
        switch state {
        case .inside:
            // Pre-seed enter counter so the next ranging cycle completes the enter.
            // Only set it when we have NOT already entered (avoid re-emitting enter).
            if !enteredRegions.contains(identifier) {
                enterCounters[identifier] = max(enterCounters[identifier] ?? 0, ENTER_HYSTERESIS_COUNT - 1)
            }
        case .outside:
            // If a stale session left the beacon in entered state, clean it up.
            if enteredRegions.remove(identifier) != nil {
                enterCounters.removeValue(forKey: identifier)
                exitCounters.removeValue(forKey: identifier)
                smoothedDistances.removeValue(forKey: identifier)
                sendLoggedEvent("onBeaconExit", makeBeaconEventParams(identifier: identifier, region: beaconRegion, event: "exit"))
                postBeaconNotification(identifier: identifier, eventType: "exit")
            }
        case .unknown:
            break
        @unknown default:
            break
        }
    }
}
