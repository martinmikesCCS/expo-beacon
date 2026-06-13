import CoreLocation
import UserNotifications

extension ExpoBeaconModule {
    func requestLocationPermission(completion: @escaping (Bool) -> Void) {
        let status = locationManager.authorizationStatus
        switch status {
        case .authorizedAlways, .authorizedWhenInUse:
            completion(true)
        case .notDetermined:
            permissionCompletions.append(completion)
            locationManager.requestWhenInUseAuthorization()
        default:
            completion(false)
        }
    }

    func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    func handleDidChangeAuthorization(_ status: CLAuthorizationStatus) {
        // The first callback after requestWhenInUseAuthorization() can still be
        // .notDetermined (prompt not yet answered) — keep completions pending
        // until the user makes an actual choice.
        guard status != .notDetermined else { return }
        let granted = (status == .authorizedAlways || status == .authorizedWhenInUse)
        // Drain BEFORE calling so a completion can register a new request.
        let completions = permissionCompletions
        permissionCompletions.removeAll()
        for completion in completions {
            completion(granted)
        }
    }
}
