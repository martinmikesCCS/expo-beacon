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
private const val EDDYSTONE_PREFS_NAME = "expo.beacon.paired_eddystones"
private const val EDDYSTONE_PREFS_KEY = "paired_eddystones"
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
            // Register Eddystone-UID parser
            if (manager.beaconParsers.none { it.layout?.contains("s:0-1=feaa,m:2-2=00") == true }) {
                manager.beaconParsers.add(
                    BeaconParser().setBeaconLayout("s:0-1=feaa,m:2-2=00,p:3-3:-41,i:4-13,i:14-19")
                )
            }
            // Register Eddystone-URL parser
            if (manager.beaconParsers.none { it.layout?.contains("s:0-1=feaa,m:2-2=10") == true }) {
                manager.beaconParsers.add(
                    BeaconParser().setBeaconLayout("s:0-1=feaa,m:2-2=10,p:3-3:-41,i:4-20v")
                )
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        appContext.reactContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val eddystonePrefs: SharedPreferences by lazy {
        appContext.reactContext!!.getSharedPreferences(EDDYSTONE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Coroutine scope tied to module lifecycle
    private val moduleScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // BroadcastReceiver bridge from BeaconForegroundService to JS events
    private var eventReceiver: BeaconEventReceiver? = null

    // Current one-shot scan state
    private var scanPromise: Promise? = null
    private var eddystoneScanPromise: Promise? = null
    private var scanJob: Job? = null
    private val scanResults = mutableListOf<Beacon>()
    private var scanUuidFilter: Set<String> = emptySet()
    private var isBoundForScan = false

    // Continuous scan state
    private var continuousScanActive = false
    private val continuousScanRegion = Region("continuousScanRegion", null, null, null)

    override fun definition() = ModuleDefinition {
        Name("ExpoBeacon")

        Events("onBeaconEnter", "onBeaconExit", "onBeaconDistance", "onBeaconFound", "onEddystoneFound", "onEddystoneEnter", "onEddystoneExit", "onEddystoneDistance")

        AsyncFunction("scanForBeaconsAsync") { uuids: List<String>?, scanDurationMs: Int, promise: Promise ->
            if (scanPromise != null || eddystoneScanPromise != null) {
                promise.reject("SCAN_IN_PROGRESS", "A scan is already running", null)
                return@AsyncFunction
            }
            scanResults.clear()
            scanUuidFilter = uuids?.map { it.lowercase() }?.toSet() ?: emptySet()
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

        AsyncFunction("scanForEddystonesAsync") { scanDurationMs: Int, promise: Promise ->
            if (scanPromise != null || eddystoneScanPromise != null) {
                promise.reject("SCAN_IN_PROGRESS", "A scan is already running", null)
                return@AsyncFunction
            }
            scanResults.clear()
            eddystoneScanPromise = promise

            beaconManager.addRangeNotifier(scanRangeNotifier)

            if (!isBoundForScan) {
                isBoundForScan = true
                beaconManager.bind(this@ExpoBeaconModule)
            } else {
                startScanRanging()
            }

            scanJob = moduleScope.launch {
                delay(scanDurationMs.toLong())
                stopEddystoneScanAndResolve()
            }
        }

        Function("pairBeacon") { identifier: String, uuid: String, major: Int, minor: Int ->
            // Validate UUID format
            try {
                java.util.UUID.fromString(uuid)
            } catch (_: IllegalArgumentException) {
                throw expo.modules.kotlin.exception.CodedException("INVALID_UUID", "Invalid UUID format: $uuid", null)
            }
            if (major !in 0..65535) {
                throw expo.modules.kotlin.exception.CodedException("INVALID_MAJOR", "Major must be 0\u201365535, got $major", null)
            }
            if (minor !in 0..65535) {
                throw expo.modules.kotlin.exception.CodedException("INVALID_MINOR", "Minor must be 0\u201365535, got $minor", null)
            }

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

        Function("pairEddystone") { identifier: String, namespace: String, instance: String ->
            val eddystones = loadPairedEddystonesJson()
            val filtered = (0 until eddystones.length())
                .map { eddystones.getJSONObject(it) }
                .filter { it.getString("identifier") != identifier }

            val arr = JSONArray()
            filtered.forEach { arr.put(it) }
            val newEddystone = JSONObject().apply {
                put("identifier", identifier)
                put("namespace", namespace)
                put("instance", instance)
            }
            arr.put(newEddystone)
            eddystonePrefs.edit().putString(EDDYSTONE_PREFS_KEY, arr.toString()).apply()
        }

        Function("unpairEddystone") { identifier: String ->
            val eddystones = loadPairedEddystonesJson()
            val filtered = (0 until eddystones.length())
                .map { eddystones.getJSONObject(it) }
                .filter { it.getString("identifier") != identifier }
            val arr = JSONArray()
            filtered.forEach { arr.put(it) }
            eddystonePrefs.edit().putString(EDDYSTONE_PREFS_KEY, arr.toString()).apply()
        }

        Function("getPairedEddystones") {
            val eddystones = loadPairedEddystonesJson()
            (0 until eddystones.length()).map { i ->
                val e = eddystones.getJSONObject(i)
                mapOf(
                    "identifier" to e.getString("identifier"),
                    "namespace" to e.getString("namespace"),
                    "instance" to e.getString("instance")
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
            // Verify we have the permissions needed for background monitoring
            val hasLocation = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasBgLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasLocation || !hasBgLocation) {
                promise.reject("PERMISSION_DENIED", "Location permissions required for background monitoring. Call requestPermissionsAsync() first.", null)
                return@AsyncFunction
            }

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
            // Step 1: request foreground permissions
            val foreground = buildList {
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
                val allGranted = foreground.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
                promise.resolve(allGranted)
                return@AsyncFunction
            }

            permissionsManager.askForPermissions({ results ->
                val fgGranted = foreground.all { perm ->
                    results[perm]?.status == PermissionsStatus.GRANTED
                }
                if (!fgGranted) {
                    promise.resolve(false)
                    return@askForPermissions
                }
                // Step 2: request background location (Android 10+)
                // Must be requested separately after foreground location is granted
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    permissionsManager.askForPermissions({ bgResults ->
                        val bgGranted = bgResults[Manifest.permission.ACCESS_BACKGROUND_LOCATION]?.status == PermissionsStatus.GRANTED
                        promise.resolve(bgGranted)
                    }, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    promise.resolve(true)
                }
            }, *foreground.toTypedArray())
        }

        OnDestroy {
            this@ExpoBeaconModule.unregisterEventReceiver()
            this@ExpoBeaconModule.scanJob?.cancel()
            this@ExpoBeaconModule.scanPromise = null
            this@ExpoBeaconModule.eddystoneScanPromise = null
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
        if (scanPromise != null || eddystoneScanPromise != null) startScanRanging()
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
        if (scanUuidFilter.isEmpty()) {
            scanResults.addAll(beacons)
        } else {
            scanResults.addAll(beacons.filter { beacon ->
                scanUuidFilter.contains(beacon.id1.toString().lowercase())
            })
        }
    }

    private val continuousScanRangeNotifier = RangeNotifier { beacons, _ ->
        beacons.forEach { beacon ->
            if (beacon.serviceUuid == 0xfeaa) {
                sendEvent("onEddystoneFound", eddystoneBeaconToMap(beacon))
            } else {
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

        val results = scanResults.filter { it.serviceUuid != 0xfeaa }.distinctBy { "${it.id1}:${it.id2}:${it.id3}" }.map { beacon ->
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
        scanUuidFilter = emptySet()
    }

    private fun stopEddystoneScanAndResolve() {
        scanJob?.cancel()
        try {
            beaconManager.stopRangingBeaconsInRegion(Region("scanRegion", null, null, null))
        } catch (_: RemoteException) {}
        beaconManager.removeRangeNotifier(scanRangeNotifier)

        val results = scanResults
            .filter { it.serviceUuid == 0xfeaa }
            .distinctBy {
                if (it.identifiers.size >= 2) "uid:${it.id1}:${it.id2}"
                else "url:${it.id1}"
            }
            .map { eddystoneBeaconToMap(it) }

        eddystoneScanPromise?.resolve(results)
        eddystoneScanPromise = null
        scanResults.clear()
    }

    private fun eddystoneBeaconToMap(beacon: Beacon): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "rssi" to beacon.rssi,
            "distance" to beacon.distance,
            "txPower" to beacon.txPower
        )
        if (beacon.identifiers.size >= 2) {
            map["frameType"] = "uid"
            map["namespace"] = beacon.id1.toString().removePrefix("0x")
            map["instance"] = beacon.id2.toString().removePrefix("0x")
        } else {
            map["frameType"] = "url"
            map["url"] = decodeEddystoneUrl(beacon.id1.toByteArray())
        }
        return map
    }

    private fun decodeEddystoneUrl(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val schemes = arrayOf("http://www.", "https://www.", "http://", "https://")
        val suffixes = mapOf(
            0x00.toByte() to ".com/", 0x01.toByte() to ".org/",
            0x02.toByte() to ".edu/", 0x03.toByte() to ".net/",
            0x04.toByte() to ".info/", 0x05.toByte() to ".biz/",
            0x06.toByte() to ".gov/",
            0x07.toByte() to ".com", 0x08.toByte() to ".org",
            0x09.toByte() to ".edu", 0x0A.toByte() to ".net",
            0x0B.toByte() to ".info", 0x0C.toByte() to ".biz",
            0x0D.toByte() to ".gov"
        )
        val schemeIndex = bytes[0].toInt() and 0xFF
        if (schemeIndex >= schemes.size) return ""
        val sb = StringBuilder(schemes[schemeIndex])
        for (i in 1 until bytes.size) {
            val b = bytes[i]
            val suffix = suffixes[b]
            if (suffix != null) {
                sb.append(suffix)
            } else {
                val c = b.toInt() and 0xFF
                if (c in 0x20..0x7E) {
                    sb.append(c.toChar())
                }
            }
        }
        return sb.toString()
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

    private fun loadPairedEddystonesJson(): JSONArray {
        val json = eddystonePrefs.getString(EDDYSTONE_PREFS_KEY, "[]") ?: "[]"
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
