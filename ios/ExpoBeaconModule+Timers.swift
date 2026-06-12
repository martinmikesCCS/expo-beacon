import CoreLocation

extension ExpoBeaconModule {
    // MARK: - Timeout timers (fire after exit)

    func scheduleBeaconTimeout(identifier: String, beacon: CLBeacon? = nil, region: CLBeaconRegion? = nil) {
        // Cancel any existing timer so each exit resets the clock.
        cancelBeaconTimeout(identifier: identifier)

        let paired = loadPairedBeaconsRaw().first { ($0["identifier"] as? String) == identifier }
        guard let seconds = paired?["timeoutSeconds"] as? Int, seconds > 0 else { return }

        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.beaconTimeoutTimers.removeValue(forKey: identifier)
            self.enteredRegions.remove(identifier)
            self.enterCounters.removeValue(forKey: identifier)
            self.exitCounters.removeValue(forKey: identifier)
            self.lastSeenTimes.removeValue(forKey: identifier)
            self.smoothedDistances.removeValue(forKey: identifier)
            self.sendLoggedEvent("onBeaconTimeout", self.makeBeaconEventParams(identifier: identifier, beacon: beacon, region: region))
            self.postBeaconNotification(identifier: identifier, eventType: "timeout")
        }
        beaconTimeoutTimers[identifier] = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(seconds), execute: work)
    }

    func cancelBeaconTimeout(identifier: String) {
        beaconTimeoutTimers.removeValue(forKey: identifier)?.cancel()
    }

    func scheduleEddystoneTimeout(identifier: String, namespace: String, instance: String) {
        // Cancel any existing timer so each exit resets the clock.
        cancelEddystoneTimeout(identifier: identifier)

        let paired = loadPairedEddystonesRaw().first { ($0["identifier"] as? String) == identifier }
        guard let seconds = paired?["timeoutSeconds"] as? Int, seconds > 0 else { return }

        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.eddystoneTimeoutTimers.removeValue(forKey: identifier)
            self.eddystoneEnteredRegions.remove(identifier)
            self.eddystoneEnterCounters.removeValue(forKey: identifier)
            self.eddystoneExitCounters.removeValue(forKey: identifier)
            self.eddystoneLatestSeen.removeValue(forKey: identifier)
            self.smoothedDistances.removeValue(forKey: identifier)
            self.sendLoggedEvent("onEddystoneTimeout", [
                "identifier": identifier,
                "namespace": namespace,
                "instance": instance,
                "distance": -1
            ])
            self.postBeaconNotification(identifier: identifier, eventType: "timeout")
        }
        eddystoneTimeoutTimers[identifier] = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(seconds), execute: work)
    }

    func cancelEddystoneTimeout(identifier: String) {
        eddystoneTimeoutTimers.removeValue(forKey: identifier)?.cancel()
    }

    // MARK: - Inactivity timers (no BLE readings → start timeout countdown)

    func rescheduleBeaconInactivity(identifier: String, beacon: CLBeacon? = nil, region: CLBeaconRegion? = nil) {
        cancelBeaconInactivity(identifier: identifier)
        // A fresh valid BLE reading means the beacon is present; discard any
        // already-armed timeout so it cannot fire while the device is in range.
        cancelBeaconTimeout(identifier: identifier)

        let paired = loadPairedBeaconsRaw().first { ($0["identifier"] as? String) == identifier }
        guard let seconds = paired?["timeoutSeconds"] as? Int, seconds > 0 else { return }

        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.beaconInactivityTimers.removeValue(forKey: identifier)
            // No BLE readings for 60 s — start the configured timeout countdown.
            self.scheduleBeaconTimeout(identifier: identifier, beacon: beacon, region: region)
        }
        beaconInactivityTimers[identifier] = work
        DispatchQueue.main.asyncAfter(deadline: .now() + DISTANCE_INACTIVITY_SECONDS, execute: work)
    }

    func rescheduleEddystoneInactivity(identifier: String, namespace: String, instance: String) {
        cancelEddystoneInactivity(identifier: identifier)
        // A fresh valid BLE reading means the beacon is present; discard any
        // already-armed timeout so it cannot fire while the device is in range.
        cancelEddystoneTimeout(identifier: identifier)

        let paired = loadPairedEddystonesRaw().first { ($0["identifier"] as? String) == identifier }
        guard let seconds = paired?["timeoutSeconds"] as? Int, seconds > 0 else { return }

        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.eddystoneInactivityTimers.removeValue(forKey: identifier)
            // No BLE readings for 60 s — start the configured timeout countdown.
            self.scheduleEddystoneTimeout(identifier: identifier, namespace: namespace, instance: instance)
        }
        eddystoneInactivityTimers[identifier] = work
        DispatchQueue.main.asyncAfter(deadline: .now() + DISTANCE_INACTIVITY_SECONDS, execute: work)
    }

    func cancelBeaconInactivity(identifier: String) {
        beaconInactivityTimers.removeValue(forKey: identifier)?.cancel()
    }

    func cancelEddystoneInactivity(identifier: String) {
        eddystoneInactivityTimers.removeValue(forKey: identifier)?.cancel()
    }
}
