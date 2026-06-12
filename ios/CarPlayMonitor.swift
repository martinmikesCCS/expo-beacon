import Foundation
import AVFoundation
import os.log

/// Observes the system audio session for CarPlay route changes and emits
/// connect/disconnect callbacks. Detection works for both wired and wireless
/// CarPlay because both expose an output port of type `.carAudio` on the
/// shared `AVAudioSession`. No CarPlay entitlement is required.
///
/// All calls into this class are dispatched onto the main queue. The owning
/// module is responsible for invoking `start(emit:)` / `stop()` from a
/// thread-safe context (e.g. Expo module callbacks).
final class CarPlayMonitor {

    static let shared = CarPlayMonitor()

    /// Emit callback signature: (eventName, payload).
    typealias Emit = (_ eventName: String, _ payload: [String: Any]) -> Void

    private let log = OSLog(subsystem: "expo.modules.beacon", category: "CarPlayMonitor")
    private let queue = DispatchQueue.main

    /// Cached ISO8601 formatter (UTC, fractional seconds). Reused across emits
    /// to avoid per-event allocation. `ISO8601DateFormatter` is documented as
    /// thread-safe.
    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()

    private var observer: NSObjectProtocol?
    private var emit: Emit?
    private var isConnected: Bool = false
    /// Optional persistence target for last-known connection state. Injected via
    /// `start(emit:defaults:)`. When set, every connect/disconnect emission is
    /// mirrored to `CARPLAY_LAST_CONNECTED_KEY` so a freshly relaunched process
    /// can detect a missed disconnect via `reconcileOnProcessStart()`.
    private var defaults: UserDefaults?
    /// When `true`, an authoritative source (CarPlay scene delegate, granted via the
    /// `com.apple.developer.carplay-driving-task` entitlement) is providing
    /// connect/disconnect events. The audio-session observer becomes a passive
    /// secondary signal only — it will not emit events, to avoid duplicate
    /// connect/disconnect notifications. Reset to `false` on entitled disconnect
    /// so the audio-session fallback can take over for any subsequent events.
    private var isEntitledMode: Bool = false

    private init() {}

    /// Begin observing route changes. Idempotent — calling twice replaces the
    /// previous emit callback but does not register a duplicate observer.
    /// Emits an immediate `onCarPlayConnected` event if a CarPlay route is
    /// already active at the time of the call.
    ///
    /// - Parameter defaults: Optional `UserDefaults` suite used to persist the
    ///   last-known connection state for cross-process reconciliation. When the
    ///   module is recreated in a new process (e.g. after a background-wake)
    ///   the persisted value is used by `reconcileOnProcessStart()` to
    ///   synthesize a missed disconnect. Pass `nil` to disable persistence.
    func start(emit: @escaping Emit, defaults: UserDefaults? = nil) {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.emit = emit
            if defaults != nil { self.defaults = defaults }
            if self.observer == nil {
                self.observer = NotificationCenter.default.addObserver(
                    forName: AVAudioSession.routeChangeNotification,
                    object: nil,
                    queue: .main
                ) { [weak self] note in
                    self?.handleRouteChange(notification: note)
                }
                os_log("CarPlay monitoring started", log: self.log, type: .info)
            }
            // Emit current state if already connected.
            let (connected, transport) = Self.currentCarPlayState()
            self.isConnected = connected
            if connected {
                self.emitConnected(transport: transport)
            }
        }
    }

    /// Stop observing route changes and clear the emit callback. Also clears
    /// the persisted last-known state so a subsequent `start(...)` in a future
    /// process doesn't trigger a spurious reconciliation. Call this only when
    /// the user has explicitly opted out of CarPlay monitoring.
    func stop() {
        queue.async { [weak self] in
            guard let self = self else { return }
            if let token = self.observer {
                NotificationCenter.default.removeObserver(token)
                self.observer = nil
                os_log("CarPlay monitoring stopped", log: self.log, type: .info)
            }
            self.emit = nil
            self.isConnected = false
            self.isEntitledMode = false
            self.persistConnectionState(false)
        }
    }

    /// Re-read the current audio route and emit a connect/disconnect event if
    /// the state has changed since the last observed value. Cheap and idempotent;
    /// safe to call from any background-wake hook (e.g. CLLocationManager region
    /// callbacks) to reconcile CarPlay state changes that occurred while the app
    /// was suspended and `AVAudioSession.routeChangeNotification` was not delivered.
    func resyncIfNeeded() {
        queue.async { [weak self] in
            // Snapshot reads (initial start, region wake, explicit resync) are
            // trusted: they reflect the actual current route, not a transient
            // category/configuration change. Pass `nil` notification to bypass
            // the route-change-reason filter.
            self?.handleRouteChange(notification: nil)
        }
    }

    // MARK: - Entitled (Driving Task) source

    /// Called by `BeaconCarPlaySceneDelegate.templateApplicationScene(_:didConnect:)`.
    /// Marks the entitled path as the authoritative source and emits an immediate
    /// `onCarPlayConnected` event (if not already connected from this source).
    /// Subsequent route-change notifications from `AVAudioSession` are suppressed
    /// for emission purposes to prevent duplicate events.
    func notifyEntitledConnect(transport: String = "carplay-scene") {
        queue.async { [weak self] in
            guard let self = self else { return }
            self.isEntitledMode = true
            os_log("CarPlay scene connected (entitled source)", log: self.log, type: .info)
            if !self.isConnected {
                self.isConnected = true
                self.emitConnected(transport: transport)
            }
        }
    }

    /// Called by `BeaconCarPlaySceneDelegate.templateApplicationScene(_:didDisconnect:)`.
    /// Emits `onCarPlayDisconnected` and clears the entitled-mode flag so the
    /// audio-session fallback path becomes authoritative again until the next
    /// entitled connect. Without this reset, a single missed scene-delegate
    /// disconnect (force-quit, OS reclaim, abrupt cable yank between connect
    /// and disconnect callbacks) would silently suppress the audio-session
    /// fallback for the rest of the process lifetime.
    func notifyEntitledDisconnect() {
        queue.async { [weak self] in
            guard let self = self else { return }
            os_log("CarPlay scene disconnected (entitled source)", log: self.log, type: .info)
            self.isEntitledMode = false
            if self.isConnected {
                self.isConnected = false
                self.emitDisconnected()
            }
        }
    }

    /// Whether an entitled CarPlay scene source has notified us at least once.
    /// Consumers (e.g. SLC/Visit auto-start logic) can skip cheap fallbacks
    /// when the entitled real-time path is active.
    var isUsingEntitledSource: Bool {
        if Thread.isMainThread {
            return isEntitledMode
        }
        return queue.sync { isEntitledMode }
    }

    /// Process a route change. When invoked from a system notification the
    /// reason is checked: only `.newDeviceAvailable` and `.oldDeviceUnavailable`
    /// indicate a real device connect/disconnect. Other reasons (category change,
    /// configuration change, override, etc.) are filtered to avoid spurious
    /// disconnect events when the app suspends/resumes and iOS re-evaluates
    /// the audio session category without the physical CarPlay link changing.
    /// When `notification` is nil (initial start, explicit resync), the snapshot
    /// is always trusted.
    private func handleRouteChange(notification: Notification?) {
        if let userInfo = notification?.userInfo,
           let reasonRaw = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
           let reason = AVAudioSession.RouteChangeReason(rawValue: reasonRaw) {
            switch reason {
            case .newDeviceAvailable, .oldDeviceUnavailable:
                break // real device change — proceed
            default:
                // Category/override/configuration changes etc. don't represent
                // a CarPlay connect/disconnect. Skip to avoid spurious events.
                return
            }
        }
        let (connected, transport) = Self.currentCarPlayState()
        // When an entitled CarPlay scene source is active it is authoritative.
        // The audio-session signal is kept as a redundant secondary check but
        // must NOT emit events — the scene delegate already did, or will.
        // We DO still update the cached `isConnected` snapshot and persist it
        // so that cross-process reconciliation (`reconcileOnProcessStart`) and
        // the post-entitled-disconnect fallback path see an accurate state.
        if isEntitledMode {
            if connected != isConnected {
                isConnected = connected
                persistConnectionState(connected)
            }
            return
        }
        if connected == isConnected { return }
        isConnected = connected
        if connected {
            emitConnected(transport: transport)
        } else {
            emitDisconnected()
        }
    }

    // MARK: - Cross-process reconciliation

    /// Compare persisted last-known state against the current audio route and
    /// emit a synthetic `onCarPlayDisconnected` if persisted=connected but the
    /// route is no longer CarPlay. Use case: the previous process was killed or
    /// suspended-then-OS-reclaimed while CarPlay was connected, and the disconnect
    /// fired off-process. JS listeners attached to the freshly recreated module
    /// would otherwise never learn the session ended.
    ///
    /// Must be called AFTER `start(emit:defaults:)` so the emit callback is
    /// installed and persistence target is known. Idempotent.
    func reconcileOnProcessStart() {
        queue.async { [weak self] in
            guard let self = self else { return }
            guard let defaults = self.defaults else { return }
            let persistedConnected = defaults.bool(forKey: CARPLAY_LAST_CONNECTED_KEY)
            let (currentConnected, _) = Self.currentCarPlayState()
            if persistedConnected && !currentConnected {
                os_log("CarPlay reconcile: persisted=connected, current=disconnected — emitting synthetic disconnect", log: self.log, type: .info)
                self.isConnected = false
                self.emitDisconnected(reason: "reconciled")
            }
        }
    }

    /// Write the current connection state to the injected `UserDefaults` suite
    /// (when available). No-op when persistence wasn't configured.
    private func persistConnectionState(_ connected: Bool) {
        defaults?.set(connected, forKey: CARPLAY_LAST_CONNECTED_KEY)
    }

    private func emitConnected(transport: String) {
        let now = Date()
        let payload: [String: Any] = [
            "transport": transport,
            "timestamp": now.timeIntervalSince1970 * 1000.0,
            "timestampIso": Self.isoFormatter.string(from: now),
        ]
        persistConnectionState(true)
        emit?("onCarPlayConnected", payload)
    }

    /// Emit a disconnect event. When `reason` is non-nil it is included in the
    /// payload so consumers can distinguish real-time disconnects from
    /// post-hoc reconciled ones (currently `"reconciled"` from
    /// `reconcileOnProcessStart`). Additive, non-breaking.
    private func emitDisconnected(reason: String? = nil) {
        let now = Date()
        var payload: [String: Any] = [
            "timestamp": now.timeIntervalSince1970 * 1000.0,
            "timestampIso": Self.isoFormatter.string(from: now),
        ]
        if let reason = reason {
            payload["reason"] = reason
        }
        persistConnectionState(false)
        emit?("onCarPlayDisconnected", payload)
    }

    /// Read the current audio session route and determine whether a CarPlay
    /// output port is active. Best-effort transport classification:
    ///   - If a Bluetooth output port is also present, report `"wireless"`.
    ///   - Else if a CarPlay port is present, report `"wired"`.
    ///   - Otherwise `"unknown"` (also returned when not connected).
    private static func currentCarPlayState() -> (connected: Bool, transport: String) {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        let hasCarPlay = outputs.contains { $0.portType == .carAudio }
        guard hasCarPlay else { return (false, "unknown") }
        let hasBluetooth = outputs.contains {
            $0.portType == .bluetoothA2DP ||
            $0.portType == .bluetoothHFP ||
            $0.portType == .bluetoothLE
        }
        return (true, hasBluetooth ? "wireless" : "wired")
    }
}
