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

    // Significant Location Change wake — used as a background-wake hook for
    // CarPlay state reconciliation when the app is suspended.
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        module?.handleBackgroundWakeForCarPlay()
    }

    // CLVisit wake — fires on arrival/departure from a place. Same purpose
    // as the SLC wake above; either may fire first depending on the user's
    // movement pattern.
    func locationManager(_ manager: CLLocationManager, didVisit visit: CLVisit) {
        module?.handleBackgroundWakeForCarPlay()
    }
}
