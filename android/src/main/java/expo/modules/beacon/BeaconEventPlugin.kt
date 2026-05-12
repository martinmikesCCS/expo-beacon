package expo.modules.beacon

/**
 * Implement this interface in your app to react to beacon enter/exit events at the native level.
 *
 * Register your implementation via [BeaconPluginRegistry.register] from MainApplication.onCreate().
 * Unregister via [BeaconPluginRegistry.unregister] if the plugin has a scoped lifetime.
 */
interface BeaconEventPlugin {
    // iBeacon
    fun onBeaconEnter(identifier: String, uuid: String, major: Int, minor: Int, distance: Double)
    fun onBeaconExit(identifier: String, uuid: String, major: Int, minor: Int, distance: Double)
    fun onBeaconTimeout(identifier: String, uuid: String, major: Int, minor: Int, distance: Double)

    // Eddystone
    fun onEddystoneEnter(identifier: String, namespace: String, instance: String, distance: Double)
    fun onEddystoneExit(identifier: String, namespace: String, instance: String, distance: Double)
    fun onEddystoneTimeout(identifier: String, namespace: String, instance: String, distance: Double)

    // CarPlay / Android Auto
    /** Called when the device connects to an Android Auto session. Default no-op. */
    fun onCarPlayConnected(transport: String) {}
    /** Called when the device disconnects from an Android Auto session. Default no-op. */
    fun onCarPlayDisconnected() {}
}
