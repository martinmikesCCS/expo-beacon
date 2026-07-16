import Foundation
import ExpoModulesCore

extension ExpoBeaconModule {
    /// Rejects a promise and emits the matching `onBeaconError` event.
    func rejectAndEmit(_ promise: Promise, _ code: String, _ message: String) {
        promise.reject(code, message)
        sendLoggedEvent("onBeaconError", ["identifier": "", "code": code, "message": message])
    }

    /// Sends an event to JS and logs it to SQLite if logging is enabled.
    func sendLoggedEvent(
        _ eventName: String,
        _ params: [String: Any],
        dispatchLifecycle: Bool = true
    ) {
        if isEventLoggingEnabled() {
            let identifier = params["identifier"] as? String
            getOrCreateEventLogger().logEvent(eventType: eventName, identifier: identifier, data: params)
        }
        // Forward all produced events to remote API
        apiForwarder.forwardEvent(params, eventType: eventName)
        // Dispatch enter/exit to registered plugins (e.g. to start/stop BGLocation)
        if dispatchLifecycle {
            dispatchToLifecycleRegistry(eventName: eventName, params: params)
        }
        sendEvent(eventName, params)
    }

    func dispatchToLifecycleRegistry(eventName: String, params: [String: Any]) {
        let r = BeaconLifecycleRegistry.shared
        let identifier = params["identifier"] as? String ?? ""
        let distance = params["distance"] as? Double ?? -1.0
        switch eventName {
        case "onBeaconEnter":
            r.dispatchEnter(
                identifier: identifier,
                uuid: params["uuid"] as? String ?? "",
                major: params["major"] as? Int ?? 0,
                minor: params["minor"] as? Int ?? 0,
                distance: distance
            )
        case "onBeaconExit":
            r.dispatchExit(
                identifier: identifier,
                uuid: params["uuid"] as? String ?? "",
                major: params["major"] as? Int ?? 0,
                minor: params["minor"] as? Int ?? 0,
                distance: distance
            )
        case "onBeaconTimeout":
            r.dispatchBeaconTimeout(
                identifier: identifier,
                uuid: params["uuid"] as? String ?? "",
                major: params["major"] as? Int ?? 0,
                minor: params["minor"] as? Int ?? 0,
                distance: distance
            )
        case "onEddystoneEnter":
            r.dispatchEddystoneEnter(
                identifier: identifier,
                namespace: params["namespace"] as? String ?? "",
                instance: params["instance"] as? String ?? "",
                distance: distance
            )
        case "onEddystoneExit":
            r.dispatchEddystoneExit(
                identifier: identifier,
                namespace: params["namespace"] as? String ?? "",
                instance: params["instance"] as? String ?? "",
                distance: distance
            )
        case "onEddystoneTimeout":
            r.dispatchEddystoneTimeout(
                identifier: identifier,
                namespace: params["namespace"] as? String ?? "",
                instance: params["instance"] as? String ?? "",
                distance: distance
            )
        default:
            break
        }
    }

    func getOrCreateEventLogger() -> BeaconEventLogger {
        if let logger = eventLogger {
            return logger
        }
        let logger = BeaconEventLogger()
        eventLogger = logger
        return logger
    }

    func isEventLoggingEnabled() -> Bool {
        if loggingEnabled {
            return true
        }
        guard defaults.bool(forKey: EVENT_LOGGING_ENABLED_KEY) else {
            return false
        }
        loggingEnabled = true
        if eventLogger == nil {
            eventLogger = BeaconEventLogger()
        }
        return true
    }
}
