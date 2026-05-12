import Foundation
import UserNotifications

extension ExpoBeaconModule {
    func postBeaconNotification(identifier: String, eventType: String) {
        let cfg = loadNotificationConfig()
        let eventsCfg = cfg["beaconEvents"] as? [String: Any]

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
}
