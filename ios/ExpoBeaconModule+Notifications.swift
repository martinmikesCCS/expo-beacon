import Foundation
import UserNotifications

extension ExpoBeaconModule {
    func postBeaconNotification(identifier: String, eventType: String) {
        let cfg = loadNotificationConfig()
        let eventsCfg = notificationSection(cfg, sectionKey: "beacons", childKey: "events", legacyKey: "beaconEvents")

        // Respect the enabled flag (defaults to true)
        if let enabled = eventsCfg?["enabled"] as? Bool, !enabled { return }

        let defaultTitle: String
        switch eventType {
        case "enter": defaultTitle = "Beacon Entered"
        case "timeout": defaultTitle = "Beacon Timeout"
        default: defaultTitle = "Beacon Exited"
        }
        let title: String
        switch eventType {
        case "enter":
            title = (eventsCfg?["enterTitle"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? defaultTitle
        case "timeout":
            title = (eventsCfg?["timeoutTitle"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? defaultTitle
        default:
            title = (eventsCfg?["exitTitle"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? defaultTitle
        }

        let bodyTemplate = (eventsCfg?["body"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? "{identifier} region {event}ed"
        let body = bodyTemplate
            .replacingOccurrences(of: "{identifier}", with: identifier)
            .replacingOccurrences(of: "{event}", with: eventType)

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body

        let playSound = eventsCfg?["sound"] as? Bool ?? true
        if playSound { content.sound = .default }

        let request = UNNotificationRequest(
            identifier: "beacon_\(eventType)_\(identifier)_\(Date().timeIntervalSince1970)",
            content: content,
            trigger: nil  // deliver immediately
        )
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    /// Post a local notification when a CarPlay session connects or disconnects.
    /// `eventType` is `"connected"` or `"disconnected"`. `transport` is the
    /// transport string from the connect payload (e.g. "wired", "wireless");
    /// pass `nil` on disconnect — the `{transport}` body placeholder is then
    /// substituted with an empty string.
    func postCarPlayNotification(eventType: String, transport: String?) {
        let cfg = loadNotificationConfig()
        let eventsCfg = notificationSection(cfg, sectionKey: "carPlay", childKey: "events", legacyKey: "carPlayEvents")

        // Respect the enabled flag (defaults to true)
        if let enabled = eventsCfg?["enabled"] as? Bool, !enabled { return }

        let defaultTitle = eventType == "connected" ? "CarPlay Connected" : "CarPlay Disconnected"
        let title: String
        switch eventType {
        case "connected":
            title = (eventsCfg?["connectedTitle"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? defaultTitle
        default:
            title = (eventsCfg?["disconnectedTitle"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? defaultTitle
        }

        let bodyTemplate = (eventsCfg?["body"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? "CarPlay session {event}"
        let body = bodyTemplate
            .replacingOccurrences(of: "{event}", with: eventType)
            .replacingOccurrences(of: "{transport}", with: transport ?? "")

        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body

        let playSound = eventsCfg?["sound"] as? Bool ?? true
        if playSound { content.sound = .default }

        let request = UNNotificationRequest(
            identifier: "carplay_\(eventType)_\(Date().timeIntervalSince1970)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }

    func loadNotificationConfig() -> [String: Any] {
        guard let json = self.defaults.string(forKey: NOTIFICATION_CONFIG_KEY),
              let data = json.data(using: .utf8) else { return [:] }
        guard let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            print("[ExpoBeacon] Warning: failed to parse notification config JSON")
            sendLoggedEvent("onBeaconError", ["identifier": "", "code": "CONFIG_PARSE_ERROR", "message": "Failed to parse notification config JSON"])
            return [:]
        }
        return dict
    }

    func saveNotificationConfig(_ config: [String: Any]) {
        if let data = try? JSONSerialization.data(withJSONObject: config),
           let json = String(data: data, encoding: .utf8) {
            self.defaults.set(json, forKey: NOTIFICATION_CONFIG_KEY)
        }
    }

    func updateNotificationSection(_ sectionKey: String, config: [String: Any], nestedKeys: Set<String>) {
        var current = loadNotificationConfig()
        current[sectionKey] = normalizeNotificationSection(config, nestedKeys: nestedKeys)
        saveNotificationConfig(current)
    }

    func mergeNotificationConfig(_ config: [String: Any]) {
        var current = loadNotificationConfig()
        for (key, value) in config {
            current[key] = value
        }
        saveNotificationConfig(current)
    }

    func notificationSection(_ config: [String: Any], sectionKey: String, childKey: String, legacyKey: String) -> [String: Any]? {
        if let section = config[sectionKey] as? [String: Any],
           let child = section[childKey] as? [String: Any] {
            return child
        }
        return config[legacyKey] as? [String: Any]
    }

    private func normalizeNotificationSection(_ config: [String: Any], nestedKeys: Set<String>) -> [String: Any] {
        if config.keys.contains(where: { nestedKeys.contains($0) }) {
            return config
        }
        return ["events": config]
    }
}
