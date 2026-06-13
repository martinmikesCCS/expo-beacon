import Foundation
import os.log

private let MAX_RETRIES = 3

/// Fire-and-forget HTTP event forwarder for beacon events.
/// Sends enter/exit/timeout events to a configured API endpoint from native code,
/// ensuring delivery even when the JS bridge is not active (app backgrounded).
final class BeaconApiForwarder {

    private let defaults: UserDefaults
    private let session: URLSession

    init(defaults: UserDefaults) {
        self.defaults = defaults
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 10
        config.timeoutIntervalForResource = 30
        // Use background-safe waitsForConnectivity so requests survive brief connectivity gaps
        config.waitsForConnectivity = true
        self.session = URLSession(configuration: config)
    }

    func configure(url: String, apiKey: String?, id: String? = nil) {
        defaults.set(url, forKey: API_URL_KEY)
        if let key = apiKey {
            defaults.set(key, forKey: API_KEY_KEY)
        } else {
            defaults.removeObject(forKey: API_KEY_KEY)
        }
        if let id = id {
            defaults.set(id, forKey: API_ID_KEY)
        } else {
            defaults.removeObject(forKey: API_ID_KEY)
        }
    }

    func getConfig() -> [String: String?] {
        return [
            "url": defaults.string(forKey: API_URL_KEY),
            "apiKey": defaults.string(forKey: API_KEY_KEY),
            "id": defaults.string(forKey: API_ID_KEY)
        ]
    }

    /// Send a beacon event to the configured API endpoint.
    /// Fire-and-forget with simple retry (3 attempts, exponential backoff).
    /// No-op if no endpoint is configured.
    func forwardEvent(_ params: [String: Any]) {
        guard let urlString = defaults.string(forKey: API_URL_KEY),
              !urlString.isEmpty,
              let url = URL(string: urlString) else { return }

        let apiKey = defaults.string(forKey: API_KEY_KEY)

        var payload = params
        if let id = defaults.string(forKey: API_ID_KEY), !id.isEmpty {
            payload["id"] = id
        }
        payload["timestamp"] = Int64(Date().timeIntervalSince1970 * 1000)
        payload["platform"] = "ios"
        payload["sdkVersion"] = ProcessInfo.processInfo.operatingSystemVersion.majorVersion

        guard let body = try? JSONSerialization.data(withJSONObject: payload) else {
            os_log(.error, "BeaconApiForwarder: failed to serialize payload")
            return
        }

        sendWithRetry(url: url, body: body, apiKey: apiKey, attempt: 1)
    }

    private func sendWithRetry(url: URL, body: Data, apiKey: String?, attempt: Int) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if let key = apiKey {
            request.setValue(key, forHTTPHeaderField: "X-CSFR-Token")
        }
        request.httpBody = body

        let task = session.dataTask(with: request) { [weak self] _, response, error in
            let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0

            if statusCode >= 200 && statusCode < 300 {
                return // Success
            }

            // 4xx client errors — don't retry
            if statusCode >= 400 && statusCode < 500 {
                os_log(.error, "BeaconApiForwarder: HTTP %{public}d — not retrying", statusCode)
                return
            }

            if attempt < MAX_RETRIES {
                let delay = pow(2.0, Double(attempt - 1)) // 1s, 2s, 4s
                DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + delay) {
                    self?.sendWithRetry(url: url, body: body, apiKey: apiKey, attempt: attempt + 1)
                }
            } else {
                let msg = error?.localizedDescription ?? "HTTP \(statusCode)"
                os_log(.error, "BeaconApiForwarder: failed after %d attempts: %{public}@", MAX_RETRIES, msg)
            }
        }
        task.resume()
    }
}
