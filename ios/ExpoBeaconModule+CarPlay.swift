import CoreLocation
import UIKit

extension ExpoBeaconModule {
    /// Starts the shared `CarPlayMonitor` and routes its events through the
    /// standard `sendLoggedEvent` pipeline (JS bridge + SQLite + API forwarder
    /// + lifecycle plugin registry). Idempotent — safe to call multiple times
    /// (see `CarPlayMonitor.start(emit:)` semantics).
    func startCarPlayMonitoringInternal() {
        CarPlayMonitor.shared.start(emit: { [weak self] eventName, payload in
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
        }, defaults: defaults)
        // Tier 2 fallback: subscribe to background-wake signals so suspended
        // apps still notice CarPlay route changes that happened off-process.
        // Run this UNCONDITIONALLY (even when the entitled scene-delegate
        // source is active) — the scene delegate can fail to deliver a
        // disconnect callback in edge cases (force-quit, OS reclaim, abrupt
        // cable yank) and SLC + Visit are the only reconciliation safety net.
        // Cost is negligible: no continuous GPS, just opportunistic wakes.
        startCarPlayBackgroundWakes()
        // Foreground reconciliation: when the user returns to the app, snapshot
        // the current audio route and emit a connect/disconnect transition if
        // it diverges from the cached state. Catches missed route-change
        // notifications during long suspensions.
        observeAppForegroundForCarPlay()
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

    /// Register a `UIApplication.didBecomeActiveNotification` observer that
    /// resyncs CarPlay state on foreground. Idempotent — the observer token is
    /// cached on the module instance and re-registration is a no-op.
    func observeAppForegroundForCarPlay() {
        if carPlayForegroundObserver != nil { return }
        carPlayForegroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { _ in
            CarPlayMonitor.shared.resyncIfNeeded()
        }
    }

    /// Tear down the foreground observer. Safe to call when none is registered.
    func removeAppForegroundObserverForCarPlay() {
        if let token = carPlayForegroundObserver {
            NotificationCenter.default.removeObserver(token)
            carPlayForegroundObserver = nil
        }
    }
}
