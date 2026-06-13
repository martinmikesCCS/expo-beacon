import CoreLocation

extension ExpoBeaconModule {
    // MARK: - Shared timer plumbing

    /// Shared timeout scheduling for the beacon/eddystone variants, which differ
    /// only in the timer dictionary, paired list and fire-time cleanup + payload.
    /// `onFire` receives the module so wrappers don't capture `self` strongly
    /// (a pending timer must not keep the module alive).
    private func scheduleTimeout(
        identifier: String,
        timers: ReferenceWritableKeyPath<ExpoBeaconModule, [String: DispatchWorkItem]>,
        pairedList: [[String: Any]],
        onFire: @escaping (ExpoBeaconModule) -> Void
    ) {
        // Cancel any existing timer so each exit resets the clock.
        self[keyPath: timers].removeValue(forKey: identifier)?.cancel()

        let paired = pairedList.first { ($0["identifier"] as? String) == identifier }
        guard let seconds = paired?["timeoutSeconds"] as? Int, seconds > 0 else { return }

        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self[keyPath: timers].removeValue(forKey: identifier)
            onFire(self)
        }
        self[keyPath: timers][identifier] = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(seconds), execute: work)
    }

    /// Shared inactivity rescheduling for the beacon/eddystone variants.
    private func rescheduleInactivity(
        identifier: String,
        timers: ReferenceWritableKeyPath<ExpoBeaconModule, [String: DispatchWorkItem]>,
        timeoutTimers: ReferenceWritableKeyPath<ExpoBeaconModule, [String: DispatchWorkItem]>,
        pairedList: [[String: Any]],
        onFire: @escaping (ExpoBeaconModule) -> Void
    ) {
        self[keyPath: timers].removeValue(forKey: identifier)?.cancel()
        // A fresh valid BLE reading means the beacon is present; discard any
        // already-armed timeout so it cannot fire while the device is in range.
        self[keyPath: timeoutTimers].removeValue(forKey: identifier)?.cancel()

        let paired = pairedList.first { ($0["identifier"] as? String) == identifier }
        guard let seconds = paired?["timeoutSeconds"] as? Int, seconds > 0 else { return }

        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self[keyPath: timers].removeValue(forKey: identifier)
            // No BLE readings for 60 s — start the configured timeout countdown.
            onFire(self)
        }
        self[keyPath: timers][identifier] = work
        DispatchQueue.main.asyncAfter(deadline: .now() + DISTANCE_INACTIVITY_SECONDS, execute: work)
    }

    // MARK: - Timeout timers (fire after exit)

    func scheduleBeaconTimeout(identifier: String, beacon: CLBeacon? = nil, region: CLBeaconRegion? = nil) {
        scheduleTimeout(
            identifier: identifier,
            timers: \.beaconTimeoutTimers,
            pairedList: loadPairedBeaconsRaw()
        ) { module in
            module.enteredRegions.remove(identifier)
            module.enterCounters.removeValue(forKey: identifier)
            module.exitCounters.removeValue(forKey: identifier)
            module.lastSeenTimes.removeValue(forKey: identifier)
            module.smoothedDistances.removeValue(forKey: identifier)
            module.sendLoggedEvent("onBeaconTimeout", module.makeBeaconEventParams(identifier: identifier, beacon: beacon, region: region))
            module.postBeaconNotification(identifier: identifier, eventType: "timeout")
        }
    }

    func cancelBeaconTimeout(identifier: String) {
        beaconTimeoutTimers.removeValue(forKey: identifier)?.cancel()
    }

    func scheduleEddystoneTimeout(identifier: String, namespace: String, instance: String) {
        scheduleTimeout(
            identifier: identifier,
            timers: \.eddystoneTimeoutTimers,
            pairedList: loadPairedEddystonesRaw()
        ) { module in
            module.eddystoneEnteredRegions.remove(identifier)
            module.eddystoneEnterCounters.removeValue(forKey: identifier)
            module.eddystoneExitCounters.removeValue(forKey: identifier)
            module.eddystoneLatestSeen.removeValue(forKey: identifier)
            module.smoothedDistances.removeValue(forKey: identifier)
            module.sendLoggedEvent("onEddystoneTimeout", [
                "identifier": identifier,
                "namespace": namespace,
                "instance": instance,
                "distance": -1
            ])
            module.postBeaconNotification(identifier: identifier, eventType: "timeout")
        }
    }

    func cancelEddystoneTimeout(identifier: String) {
        eddystoneTimeoutTimers.removeValue(forKey: identifier)?.cancel()
    }

    // MARK: - Inactivity timers (no BLE readings → start timeout countdown)

    func rescheduleBeaconInactivity(identifier: String, beacon: CLBeacon? = nil, region: CLBeaconRegion? = nil) {
        rescheduleInactivity(
            identifier: identifier,
            timers: \.beaconInactivityTimers,
            timeoutTimers: \.beaconTimeoutTimers,
            pairedList: loadPairedBeaconsRaw()
        ) { module in
            module.scheduleBeaconTimeout(identifier: identifier, beacon: beacon, region: region)
        }
    }

    func rescheduleEddystoneInactivity(identifier: String, namespace: String, instance: String) {
        rescheduleInactivity(
            identifier: identifier,
            timers: \.eddystoneInactivityTimers,
            timeoutTimers: \.eddystoneTimeoutTimers,
            pairedList: loadPairedEddystonesRaw()
        ) { module in
            module.scheduleEddystoneTimeout(identifier: identifier, namespace: namespace, instance: instance)
        }
    }

    func cancelBeaconInactivity(identifier: String) {
        beaconInactivityTimers.removeValue(forKey: identifier)?.cancel()
    }

    func cancelEddystoneInactivity(identifier: String) {
        eddystoneInactivityTimers.removeValue(forKey: identifier)?.cancel()
    }
}
