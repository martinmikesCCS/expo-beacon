package expo.modules.beacon

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe singleton registry for [BeaconEventPlugin] implementations.
 *
 * Register your plugin once from MainApplication.onCreate():
 *   BeaconPluginRegistry.register(MyBeaconPlugin(this))
 *
 * If no plugins are registered, all dispatch calls are no-ops.
 */
object BeaconPluginRegistry {
    private val plugins = CopyOnWriteArrayList<BeaconEventPlugin>()

    fun register(plugin: BeaconEventPlugin) {
        plugins.add(plugin)
    }

    fun unregister(plugin: BeaconEventPlugin) {
        plugins.remove(plugin)
    }

    internal fun dispatchEnter(
        isEddystone: Boolean,
        identifier: String,
        uuid: String,
        major: Int,
        minor: Int,
        namespace: String,
        instance: String,
        distance: Double,
    ) {
        plugins.forEach { plugin ->
            if (isEddystone) {
                plugin.onEddystoneEnter(identifier, namespace, instance, distance)
            } else {
                plugin.onBeaconEnter(identifier, uuid, major, minor, distance)
            }
        }
    }

    internal fun dispatchExit(
        isEddystone: Boolean,
        identifier: String,
        uuid: String,
        major: Int,
        minor: Int,
        namespace: String,
        instance: String,
        distance: Double,
    ) {
        plugins.forEach { plugin ->
            if (isEddystone) {
                plugin.onEddystoneExit(identifier, namespace, instance, distance)
            } else {
                plugin.onBeaconExit(identifier, uuid, major, minor, distance)
            }
        }
    }
}
