package expo.modules.beacon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.RemoteException
import androidx.core.content.ContextCompat
import expo.modules.interfaces.permissions.PermissionsStatus
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.Promise
import org.altbeacon.beacon.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "expo.beacon.paired"
private const val PREFS_KEY = "paired_beacons"
private const val NOTIFICATION_CONFIG_PREFS = "expo.beacon.notification_config"

class ExpoBeaconModule : Module(), BeaconConsumer {

    private val beaconManager: BeaconManager by lazy {
        BeaconManager.getInstanceForApplication(appContext.reactContext!!).also { manager ->
            // Must be configured before any bind/ranging starts
            manager.setEnableScheduledScanJobs(false)
            // Register iBeacon layout parser
            if (manager.beaconParsers.none { it.layout?.contains("0215") == true }) {
                manager.beaconParsers.add(
                    BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25")
                )
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        appContext.reactContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Coroutine scope tied to module lifecycle
    private val moduleScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // BroadcastReceiver bridge from BeaconForegroundService to JS events
    private var eventReceiver: BeaconEventReceiver? = null

    // Current one-shot scan state
    private var scanPromise: Promise? = null
    private var scanJob: Job? = null
    private val scanResults = mutableListOf<Beacon>()
    private var isBoundForScan = false

    // Continuous scan state
    private var continuousScanActive = false
    private val continuousScanRegion = Region("continuousScanRegion", null, null, null)

    override fun definition() = ModuleDefinition {
        Name("ExpoBeacon")

        Events("onBeaconEnter", "onBeaconExit", "onBeaconRanging", "onBeaconDistance", "onBeaconFound")

        AsyncFunction("scanForBeaconsAsync") { scanDurationMs: Int, promise: Promise ->
            if (scanPromise != null) {
                promise.reject("SCAN_IN_PROGRESS", "A scan is already running", null)
                return@AsyncFunction
            }
            scanResults.clear()
            scanPromise = promise

            beaconManager.addRangeNotifier(scanRangeNotifier)

            if (!isBoundForScan) {
                isBoundForScan = true
                beaconManager.bind(this@ExpoBeaconModule)
            } else {
                startScanRanging()
            }

            // Resolve after duration
            scanJob = moduleScope.launch {
                delay(scanDurationMs.toLong())
                stopScanAndResolve()
            }
        }

        Function("startContinuousScan") {
            if (!continuousScanActive) {
                continuousScanActive = true
                beaconManager.addRangeNotifier(continuousScanRangeNotifier)
                if (!isBoundForScan) {
                    isBoundForScan = true
                    beaconManager.bind(this@ExpoBeaconModule)
                } else {
                    startContinuousRanging()
                }
            }
            null
        }

        Function("stopContinuousScan") {
            if (continuousScanActive) {
                continuousScanActive = false
                try {
                    beaconManager.stopRangingBeaconsInRegion(continuousScanRegion)
                } catch (_: RemoteException) {}
                beaconManager.removeRangeNotifier(continuousScanRangeNotifier)
            }
            null
        }

        Function("pairBeacon") { identifier: String, uuid: String, major: Int, minor: Int ->
            val beacons = loadPairedBeaconsJson()
            // Remove duplicate if exists
            val filtered = (0 until beacons.length())
                .map { beacons.getJSONObject(it) }
                .filter { it.getString("identifier") != identifier }

            val arr = JSONArray()
            filtered.forEach { arr.put(it) }
            val newBeacon = JSONObject().apply {
                put("identifier", identifier)
                put("uuid", uuid)
                put("major", major)
                put("minor", minor)
            }
            arr.put(newBeacon)
            prefs.edit().putString(PREFS_KEY, arr.toString()).apply()
        }

        Function("unpairBeacon") { identifier: String ->
            val beacons = loadPairedBeaconsJson()
            val filtered = (0 until beacons.length())
                .map { beacons.getJSONObject(it) }
                .filter { it.getString("identifier") != identifier }
            val arr = JSONArray()
            filtered.forEach { arr.put(it) }
            prefs.edit().putString(PREFS_KEY, arr.toString()).apply()
        }

        Function("getPairedBeacons") {
            val beacons = loadPairedBeaconsJson()
            (0 until beacons.length()).map { i ->
                val b = beacons.getJSONObject(i)
                mapOf(
                    "identifier" to b.getString("identifier"),
                    "uuid" to b.getString("uuid"),
                    "major" to b.getInt("major"),
                    "minor" to b.getInt("minor")
                )
            }
        }

        AsyncFunction("startMonitoring") { options: Any?, promise: Promise ->
            val ctx = appContext.reactContext!!
            var maxDistance: Double? = null
            when (options) {
                is Double -> maxDistance = options
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = options as Map<String, Any?>
                    maxDistance = (map["maxDistance"] as? Number)?.toDouble()
                    val notifications = map["notifications"]
                    if (notifications is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        ctx.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                            .edit().putString("config", mapToJson(notifications as Map<String, Any?>).toString()).apply()
                    }
                }
            }
            ctx.getSharedPreferences("expo.beacon.monitoring_options", Context.MODE_PRIVATE)
                .edit().apply {
                    if (maxDistance != null) putFloat("max_distance", maxDistance.toFloat())
                    else remove("max_distance")
                }.apply()
            registerEventReceiver()
            BeaconForegroundService.start(ctx)
            promise.resolve(null)
        }

        Function("setNotificationConfig") { config: Map<String, Any?> ->
            val ctx = appContext.reactContext!!
            ctx.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                .edit().putString("config", mapToJson(config).toString()).apply()
            null
        }

        AsyncFunction("stopMonitoring") { promise: Promise ->
            BeaconForegroundService.stop(appContext.reactContext!!)
            unregisterEventReceiver()
            promise.resolve(null)
        }

        AsyncFunction("requestPermissionsAsync") { promise: Promise ->
            val required = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(Manifest.permission.BLUETOOTH_SCAN)
                    add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            val permissionsManager = appContext.permissions
            if (permissionsManager == null) {
                val context = appContext.reactContext ?: run {
                    promise.resolve(false)
                    return@AsyncFunction
                }
                val allGranted = required.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                promise.resolve(allGranted)
                return@AsyncFunction
            }

            permissionsManager.askForPermissions({ results ->
                val allGranted = required.all { perm ->
                    results[perm]?.status == PermissionsStatus.GRANTED
                }
                promise.resolve(allGranted)
            }, *required.toTypedArray())
        }

        OnDestroy {
            this@ExpoBeaconModule.unregisterEventReceiver()
            this@ExpoBeaconModule.scanJob?.cancel()
            this@ExpoBeaconModule.moduleScope.cancel()
            if (this@ExpoBeaconModule.continuousScanActive) {
                this@ExpoBeaconModule.continuousScanActive = false
                try { this@ExpoBeaconModule.beaconManager.stopRangingBeaconsInRegion(this@ExpoBeaconModule.continuousScanRegion) } catch (_: RemoteException) {}
                this@ExpoBeaconModule.beaconManager.removeRangeNotifier(this@ExpoBeaconModule.continuousScanRangeNotifier)
            }
            if (this@ExpoBeaconModule.isBoundForScan) {
                this@ExpoBeaconModule.beaconManager.unbind(this@ExpoBeaconModule)
                this@ExpoBeaconModule.isBoundForScan = false
            }
        }
    }

    // --- BeaconConsumer (for scan binding) ---

    override fun onBeaconServiceConnect() {
        if (scanPromise != null) startScanRanging()
        if (continuousScanActive) startContinuousRanging()
    }

    override fun getApplicationContext(): Context {
        return appContext.reactContext!!
    }

    private fun startScanRanging() {
        try {
            beaconManager.startRangingBeaconsInRegion(
                Region("scanRegion", null, null, null)
            )
        } catch (e: RemoteException) {
            scanPromise?.reject("SCAN_ERROR", e.message, e)
            scanPromise = null
        }
    }

    private val scanRangeNotifier = RangeNotifier { beacons, _ ->
        scanResults.addAll(beacons)
    }

    private val continuousScanRangeNotifier = RangeNotifier { beacons, _ ->
        beacons.forEach { beacon ->
            sendEvent("onBeaconFound", mapOf(
                "uuid" to beacon.id1.toString().uppercase(),
                "major" to beacon.id2.toInt(),
                "minor" to beacon.id3.toInt(),
                "rssi" to beacon.rssi,
                "distance" to beacon.distance,
                "txPower" to beacon.txPower
            ))
        }
    }

    private fun startContinuousRanging() {
        try {
            beaconManager.startRangingBeaconsInRegion(continuousScanRegion)
        } catch (e: RemoteException) {
            continuousScanActive = false
            beaconManager.removeRangeNotifier(continuousScanRangeNotifier)
        }
    }

    private fun stopScanAndResolve() {
        try {
            beaconManager.stopRangingBeaconsInRegion(Region("scanRegion", null, null, null))
        } catch (_: RemoteException) {}
        beaconManager.removeRangeNotifier(scanRangeNotifier)

        val results = scanResults.distinctBy { "${it.id1}:${it.id2}:${it.id3}" }.map { beacon ->
            mapOf(
                "uuid" to beacon.id1.toString().uppercase(),
                "major" to beacon.id2.toInt(),
                "minor" to beacon.id3.toInt(),
                "rssi" to beacon.rssi,
                "distance" to beacon.distance,
                "txPower" to beacon.txPower
            )
        }
        scanPromise?.resolve(results)
        scanPromise = null
    }

    // --- Notification config helpers ---

    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        map.forEach { (key, value) ->
            when (value) {
                null -> json.put(key, JSONObject.NULL)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    json.put(key, mapToJson(value as Map<String, Any?>))
                }
                else -> json.put(key, value)
            }
        }
        return json
    }

    // --- Shared Preferences helpers ---

    private fun loadPairedBeaconsJson(): JSONArray {
        val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        return try { JSONArray(json) } catch (_: Exception) { JSONArray() }
    }

    // --- Event receiver registration ---

    private fun registerEventReceiver() {
        if (eventReceiver != null) return
        val receiver = BeaconEventReceiver { eventName, params ->
            sendEvent(eventName, params)
        }
        eventReceiver = receiver
        val context = appContext.reactContext ?: return
        val filter = IntentFilter(ACTION_BEACON_EVENT)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterEventReceiver() {
        val receiver = eventReceiver ?: return
        try {
            appContext.reactContext?.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {}
        eventReceiver = null
    }

    // --- BeaconConsumer binding delegation ---

    override fun bindService(intent: Intent, connection: ServiceConnection, mode: Int): Boolean {
        return appContext.reactContext!!.bindService(intent, connection, mode)
    }

    override fun unbindService(connection: ServiceConnection) {
        appContext.reactContext!!.unbindService(connection)
    }
}
