import CoreLocation

extension ExpoBeaconModule {
    /// Starts the shared `CarPlayMonitor` and routes its events through the
    /// standard `sendLoggedEvent` pipeline (JS bridge + SQLite + API forwarder
    /// + lifecycle plugin registry). Idempotent — safe to call multiple times
    /// (see `CarPlayMonitor.start(emit:)` semantics).
    func startCarPlayMonitoringInternal() {
        CarPlayMonitor.shared.start { [weak self] eventName, payload in
            guard let self = self else { return }
            self.sendLoggedEvent(eventName, payload)
            switch eventName {
            case "onCarPlayConnected":
                self.postCarPlayNotification(
                    eventType: "connected",
                    transport: payload["transport"] as? String
                )
            case "onCarPlayDisconnected":
                self.postCarPlayNotification(eventType: "disconnected", transport: nil)
            default:
                break
            }
        }
        // Tier 2 fallback: subscribe to background-wake signals so suspended
        // apps still notice CarPlay route changes that happened off-process.
        // Skipped when the entitled CarPlay scene path is providing real-time
        // events — that source keeps the app awake for the entire CarPlay
        // session and renders SLC/Visit redundant.
        if !CarPlayMonitor.shared.isUsingEntitledSource {
            startCarPlayBackgroundWakes()
        }
    }

    /// Start Significant Location Change + Visit monitoring as background-wake
    /// hooks for CarPlay state reconciliation. Both are extremely low cost
    /// (no continuous GPS) and reuse the existing `CLLocationManager` /
    /// `LocationDelegate`. Idempotent.
    func startCarPlayBackgroundWakes() {
        if !CLLocationManager.significantLocationChangeMonitoringAvailable() {
            return
        }
        locationManager.startMonitoringSignificantLocationChanges()
        locationManager.startMonitoringVisits()
    }

    /// Stop the SLC + Visit hooks. Called from `stopCarPlayMonitoring`.
    func stopCarPlayBackgroundWakes() {
        locationManager.stopMonitoringSignificantLocationChanges()
        locationManager.stopMonitoringVisits()
    }

    /// Forwarded from `LocationDelegate` for SLC and Visit callbacks.
    /// Cheap reconciliation: just snapshot the audio route. The entitled
    /// scene-delegate path (when active) takes precedence in `CarPlayMonitor`
    /// itself.
    func handleBackgroundWakeForCarPlay() {
        if defaults.bool(forKey: CARPLAY_MONITORING_ENABLED_KEY) {
            CarPlayMonitor.shared.resyncIfNeeded()
        }
    }
}
