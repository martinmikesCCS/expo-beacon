import Foundation

/// Implement this protocol in your app to react to beacon enter/exit events at the native level.
///
/// Register your implementation once, before the Expo module is created:
///   BeaconLifecycleRegistry.register(MyPlugin())
///
/// The registry holds a strong reference — register early (e.g. AppDelegate.didFinishLaunching
/// before super, or in a +load / initialize method).
public protocol BeaconLifecycleDelegate: AnyObject {
    // MARK: iBeacon
    func beaconDidEnter(identifier: String, uuid: String, major: Int, minor: Int, distance: Double)
    func beaconDidExit(identifier: String, uuid: String, major: Int, minor: Int, distance: Double)
    func beaconDidTimeout(identifier: String, uuid: String, major: Int, minor: Int, distance: Double)

    // MARK: Eddystone
    func eddystoneDidEnter(identifier: String, namespace: String, instance: String, distance: Double)
    func eddystoneDidExit(identifier: String, namespace: String, instance: String, distance: Double)
    func eddystoneDidTimeout(identifier: String, namespace: String, instance: String, distance: Double)

}

/// Thread-safe registry for [BeaconLifecycleDelegate] plugins.
/// Mirrors the Android BeaconPluginRegistry pattern so both platforms use the same app-side wiring.
public final class BeaconLifecycleRegistry {
    public static let shared = BeaconLifecycleRegistry()
    private init() {}

    private let lock = NSLock()
    private var plugins: [any BeaconLifecycleDelegate] = []

    public static func register(_ plugin: any BeaconLifecycleDelegate) {
        shared.lock.lock(); defer { shared.lock.unlock() }
        shared.plugins.append(plugin)
    }

    public static func unregister(_ plugin: any BeaconLifecycleDelegate) {
        shared.lock.lock(); defer { shared.lock.unlock() }
        shared.plugins.removeAll { $0 === plugin }
    }

    internal func dispatchEnter(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
        snapshot().forEach { $0.beaconDidEnter(identifier: identifier, uuid: uuid, major: major, minor: minor, distance: distance) }
    }
    internal func dispatchExit(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
        snapshot().forEach { $0.beaconDidExit(identifier: identifier, uuid: uuid, major: major, minor: minor, distance: distance) }
    }
    internal func dispatchEddystoneEnter(identifier: String, namespace: String, instance: String, distance: Double) {
        snapshot().forEach { $0.eddystoneDidEnter(identifier: identifier, namespace: namespace, instance: instance, distance: distance) }
    }
    internal func dispatchEddystoneExit(identifier: String, namespace: String, instance: String, distance: Double) {
        snapshot().forEach { $0.eddystoneDidExit(identifier: identifier, namespace: namespace, instance: instance, distance: distance) }
    }
    internal func dispatchBeaconTimeout(identifier: String, uuid: String, major: Int, minor: Int, distance: Double) {
        snapshot().forEach { $0.beaconDidTimeout(identifier: identifier, uuid: uuid, major: major, minor: minor, distance: distance) }
    }
    internal func dispatchEddystoneTimeout(identifier: String, namespace: String, instance: String, distance: Double) {
        snapshot().forEach { $0.eddystoneDidTimeout(identifier: identifier, namespace: namespace, instance: instance, distance: distance) }
    }
    private func snapshot() -> [any BeaconLifecycleDelegate] {
        lock.lock(); defer { lock.unlock() }
        return plugins
    }
}
