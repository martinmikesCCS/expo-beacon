import CoreLocation
import UserNotifications

extension ExpoBeaconModule {
    func requestLocationPermission(requireAlways: Bool = false, completion: @escaping (Bool) -> Void) {
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

    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func handleDidChangeAuthorization(_ status: CLAuthorizationStatus) {
        let granted = (status == .authorizedAlways || status == .authorizedWhenInUse)
        // Nil out BEFORE calling so the closure can set a new permissionCompletion
        // (e.g. the notDetermined → whenInUse → always two-step upgrade flow).
        let completion = permissionCompletion
        permissionCompletion = nil
        completion?(granted)
    }
}
