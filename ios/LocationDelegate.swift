import CoreLocation

// MARK: - CLLocationManagerDelegate

internal final class LocationDelegate: NSObject, CLLocationManagerDelegate {
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


    // Response to requestState(for:) — called by iOS after a background-process
    // restart to confirm whether the device is currently inside a monitored
    // region. Forwarded so the module can log and take action (e.g. emit a
    // synthetic enter if state is .inside and ranging has not yet delivered data).
    func locationManager(_ manager: CLLocationManager, didDetermineState state: CLRegionState, for region: CLRegion) {
        module?.handleDidDetermineState(state, for: region)
    }
}
