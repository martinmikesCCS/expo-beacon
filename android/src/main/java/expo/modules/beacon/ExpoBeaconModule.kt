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
import java.util.Collections

class ExpoBeaconModule : Module(), BeaconConsumer {

    companion object {
        private val SCAN_REGION = Region("scanRegion", null, null, null)
        private val NAMESPACE_REGEX = Regex("^[0-9a-fA-F]{20}$")
        private val INSTANCE_REGEX = Regex("^[0-9a-fA-F]{12}$")
    }

    private val beaconManager: BeaconManager by lazy {
        val ctx = appContext.reactContext
            ?: throw IllegalStateException("React context is not available")
        BeaconManager.getInstanceForApplication(ctx).also { manager ->
            manager.setEnableScheduledScanJobs(false)
            BeaconParsers.ensureRegistered(manager)
        }
    }

    private val prefs: SharedPreferences by lazy {
        val ctx = appContext.reactContext
            ?: throw IllegalStateException("React context is not available")
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val eddystonePrefs: SharedPreferences by lazy {
        val ctx = appContext.reactContext
            ?: throw IllegalStateException("React context is not available")
        ctx.getSharedPreferences(EDDYSTONE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Coroutine scope tied to module lifecycle
    private val moduleScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // BroadcastReceiver bridge from BeaconForegroundService to JS events
    private var eventReceiver: BeaconEventReceiver? = null

    // Current one-shot scan state
    private var scanPromise: Promise? = null
    private var eddystoneScanPromise: Promise? = null
    // Shared between iBeacon and Eddystone scans — mutual exclusion guard in
    // scanForBeaconsAsync/scanForEddystonesAsync prevents concurrent use.
    private var scanJob: Job? = null
    private val scanResults: MutableList<Beacon> = Collections.synchronizedList(mutableListOf())
    @Volatile
    private var scanUuidFilter: Set<String> = emptySet()
    private var isBoundForScan = false

    // Continuous scan state
    // @Volatile for visibility to onBeaconServiceConnect() (AltBeacon service thread).
    // All mutations happen on the JS thread — no atomicity concern.
    @Volatile private var continuousScanActive = false
    private val continuousScanRegion = Region("continuousScanRegion", null, null, null)

    // Cached paired beacon/eddystone data (invalidated on pair/unpair)
    private var cachedPairedBeacons: JSONArray? = null
    private var cachedPairedEddystones: JSONArray? = null

    override fun definition() = ModuleDefinition {
        Name("ExpoBeacon")

        Events("onBeaconEnter", "onBeaconExit", "onBeaconDistance", "onBeaconFound", "onEddystoneFound", "onEddystoneEnter", "onEddystoneExit", "onEddystoneDistance")

        AsyncFunction("scanForBeaconsAsync") { uuids: List<String>?, scanDurationMs: Int, promise: Promise ->
            if (scanDurationMs <= 0) {
                promise.reject("INVALID_DURATION", "Scan duration must be a positive integer", null)
                return@AsyncFunction
            }
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

        Function("cancelScan") {
            if (scanPromise != null) {
                cancelActiveScan()
                scanPromise?.reject("SCAN_CANCELLED", "Scan was cancelled", null)
                scanPromise = null
                scanUuidFilter = emptySet()
            }
            if (eddystoneScanPromise != null) {
                cancelActiveScan()
                eddystoneScanPromise?.reject("SCAN_CANCELLED", "Scan was cancelled", null)
                eddystoneScanPromise = null
            }
            unbindIfIdle()
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
        }

        Function("stopContinuousScan") {
            if (continuousScanActive) {
                continuousScanActive = false
                try {
                    beaconManager.stopRangingBeaconsInRegion(continuousScanRegion)
                } catch (_: RemoteException) {}
                beaconManager.removeRangeNotifier(continuousScanRangeNotifier)
                unbindIfIdle()
            }
        }

        AsyncFunction("scanForEddystonesAsync") { scanDurationMs: Int, promise: Promise ->
            if (scanDurationMs <= 0) {
                promise.reject("INVALID_DURATION", "Scan duration must be a positive integer", null)
                return@AsyncFunction
            }
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

            // Remove duplicate if exists
            removePairedEntry(prefs, PREFS_KEY, ::loadPairedBeaconsJson, identifier) { cachedPairedBeacons = null }
            val beacons = loadPairedBeaconsJson()
            val newBeacon = JSONObject().apply {
                put("identifier", identifier)
                put("uuid", uuid)
                put("major", major)
                put("minor", minor)
            }
            beacons.put(newBeacon)
            prefs.edit().putString(PREFS_KEY, beacons.toString()).apply()
            cachedPairedBeacons = null
        }

        Function("unpairBeacon") { identifier: String ->
            removePairedEntry(prefs, PREFS_KEY, ::loadPairedBeaconsJson, identifier) { cachedPairedBeacons = null }
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
            if (!namespace.matches(NAMESPACE_REGEX)) {
                throw expo.modules.kotlin.exception.CodedException("INVALID_NAMESPACE", "Namespace must be 20 hex characters, got: $namespace", null)
            }
            if (!instance.matches(INSTANCE_REGEX)) {
                throw expo.modules.kotlin.exception.CodedException("INVALID_INSTANCE", "Instance must be 12 hex characters, got: $instance", null)
            }

            // Remove duplicate if exists
            removePairedEntry(eddystonePrefs, EDDYSTONE_PREFS_KEY, ::loadPairedEddystonesJson, identifier) { cachedPairedEddystones = null }
            val eddystones = loadPairedEddystonesJson()
            val newEddystone = JSONObject().apply {
                put("identifier", identifier)
                put("namespace", namespace)
                put("instance", instance)
            }
            eddystones.put(newEddystone)
            eddystonePrefs.edit().putString(EDDYSTONE_PREFS_KEY, eddystones.toString()).apply()
            cachedPairedEddystones = null
        }

        Function("unpairEddystone") { identifier: String ->
            removePairedEntry(eddystonePrefs, EDDYSTONE_PREFS_KEY, ::loadPairedEddystonesJson, identifier) { cachedPairedEddystones = null }
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
            val ctx = appContext.reactContext ?: run {
                promise.reject("NO_CONTEXT", "React context is not available", null)
                return@AsyncFunction
            }
            var maxDistance: Double? = null
            var exitDistance: Double? = null
            when (options) {
                is Double -> maxDistance = options
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = options as Map<String, Any?>
                    maxDistance = (map["maxDistance"] as? Number)?.toDouble()
                    exitDistance = (map["exitDistance"] as? Number)?.toDouble()
                    val notifications = map["notifications"]
                    if (notifications is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        ctx.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                            .edit().putString("config", mapToJson(notifications as Map<String, Any?>).toString()).apply()
                    }
                }
            }
            ctx.getSharedPreferences(MONITORING_OPTIONS_PREFS, Context.MODE_PRIVATE)
                .edit().apply {
                    if (maxDistance != null) putString("max_distance", maxDistance.toString())
                    else remove("max_distance")
                    if (exitDistance != null) putString("exit_distance", exitDistance.toString())
                    else remove("exit_distance")
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
            val ctx = appContext.reactContext ?: return@Function
            ctx.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                .edit().putString("config", mapToJson(config).toString()).apply()
        }

        AsyncFunction("stopMonitoring") { promise: Promise ->
            val ctx = appContext.reactContext ?: run {
                promise.reject("NO_CONTEXT", "React context is not available", null)
                return@AsyncFunction
            }
            BeaconForegroundService.stop(ctx)
            unregisterEventReceiver()
            promise.resolve(null)
        }

        AsyncFunction("requestPermissionsAsync") { promise: Promise ->
            // Step 1: request foreground permissions
            val foreground = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
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
            with(this@ExpoBeaconModule) {
                unregisterEventReceiver()
                scanJob?.cancel()
                scanPromise = null
                eddystoneScanPromise = null
                moduleScope.cancel()
                if (continuousScanActive) {
                    continuousScanActive = false
                    try { beaconManager.stopRangingBeaconsInRegion(continuousScanRegion) } catch (_: RemoteException) {}
                    beaconManager.removeRangeNotifier(continuousScanRangeNotifier)
                }
                if (isBoundForScan) {
                    beaconManager.unbind(this@ExpoBeaconModule)
                    isBoundForScan = false
                }
            }
        }
    }

    // --- Scan cleanup helper ---

    /** Shared cleanup for cancelling an active scan (iBeacon or Eddystone). */
    private fun cancelActiveScan() {
        scanJob?.cancel()
        try {
            beaconManager.stopRangingBeaconsInRegion(SCAN_REGION)
        } catch (_: RemoteException) {}
        beaconManager.removeRangeNotifier(scanRangeNotifier)
        synchronized(scanResults) { scanResults.clear() }
    }

    // --- BeaconConsumer (for scan binding) ---

    override fun onBeaconServiceConnect() {
        if (scanPromise != null || eddystoneScanPromise != null) startScanRanging()
        if (continuousScanActive) startContinuousRanging()
    }

    override fun getApplicationContext(): Context {
        return appContext.reactContext
            ?: throw IllegalStateException("React context is not available")
    }

    private fun startScanRanging() {
        try {
            beaconManager.startRangingBeaconsInRegion(SCAN_REGION)
        } catch (e: RemoteException) {
            scanPromise?.reject("SCAN_ERROR", e.message, e)
            scanPromise = null
            eddystoneScanPromise?.reject("SCAN_ERROR", e.message, e)
            eddystoneScanPromise = null
        }
    }

    private val scanRangeNotifier = RangeNotifier { beacons, _ ->
        // Filter at ingestion: only collect the beacon type matching the active scan.
        val filtered = when {
            scanPromise != null -> beacons.filter { !isEddystoneBeacon(it) }
            eddystoneScanPromise != null -> beacons.filter { isEddystoneBeacon(it) }
            else -> return@RangeNotifier
        }
        val toAdd = if (scanUuidFilter.isEmpty()) {
            filtered
        } else {
            filtered.filter { beacon ->
                scanUuidFilter.contains(beacon.id1.toString().lowercase())
            }
        }
        synchronized(scanResults) { scanResults.addAll(toAdd) }
    }

    private fun isEddystoneBeacon(beacon: Beacon): Boolean {
        return beacon.serviceUuid == 0xfeaa
    }

    private val continuousScanRangeNotifier = RangeNotifier { beacons, _ ->
        beacons.forEach { beacon ->
            if (isEddystoneBeacon(beacon)) {
                sendEvent("onEddystoneFound", eddystoneBeaconToMap(beacon))
            } else if (beacon.identifiers.size >= 3) {
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
            beaconManager.stopRangingBeaconsInRegion(SCAN_REGION)
        } catch (_: RemoteException) {}
        beaconManager.removeRangeNotifier(scanRangeNotifier)

        val results = synchronized(scanResults) {
            val mapped = scanResults.distinctBy { "${it.id1}:${it.id2}:${it.id3}" }.map { beacon ->
                mapOf(
                    "uuid" to beacon.id1.toString().uppercase(),
                    "major" to beacon.id2.toInt(),
                    "minor" to beacon.id3.toInt(),
                    "rssi" to beacon.rssi,
                    "distance" to beacon.distance,
                    "txPower" to beacon.txPower
                )
            }
            scanResults.clear()
            mapped
        }
        scanPromise?.resolve(results)
        scanPromise = null
        scanUuidFilter = emptySet()
        unbindIfIdle()
    }

    private fun stopEddystoneScanAndResolve() {
        scanJob?.cancel()
        try {
            beaconManager.stopRangingBeaconsInRegion(SCAN_REGION)
        } catch (_: RemoteException) {}
        beaconManager.removeRangeNotifier(scanRangeNotifier)

        val results = synchronized(scanResults) {
            val mapped = scanResults
                .distinctBy {
                    if (it.identifiers.size >= 2) "uid:${it.id1}:${it.id2}"
                    else "url:${it.id1}"
                }
                .map { eddystoneBeaconToMap(it) }
            scanResults.clear()
            mapped
        }

        eddystoneScanPromise?.resolve(results)
        eddystoneScanPromise = null
        unbindIfIdle()
    }

    private fun eddystoneBeaconToMap(beacon: Beacon): Map<String, Any> {
        // AltBeacon provides distance via its built-in path-loss model.
        // iOS uses a custom calculateDistance() with NaN/Infinity clamping for Eddystone.
        // Both return -1.0 for invalid readings, but distance estimates may differ slightly.
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

    // Decodes an Eddystone-URL payload from AltBeacon's id1 byte array.
    // AltBeacon strips the frame-type (0x10) and txPower bytes before populating
    // identifiers, so bytes[0] is the URL scheme index. On iOS (CoreBluetooth raw
    // service data), data[0]=frameType, data[1]=txPower, data[2]=scheme — see
    // ExpoBeaconModule.swift decodeEddystoneURL.
    private fun decodeEddystoneUrl(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val schemes = arrayOf("http://www.", "https://www.", "http://", "https://")
        // SYNC: This suffix table must match decodeEddystoneURL() in ExpoBeaconModule.swift
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
                is List<*> -> {
                    val arr = JSONArray()
                    value.forEach { item ->
                        when (item) {
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                arr.put(mapToJson(item as Map<String, Any?>))
                            }
                            else -> arr.put(item)
                        }
                    }
                    json.put(key, arr)
                }
                else -> json.put(key, value)
            }
        }
        return json
    }

    // --- Shared Preferences helpers ---

    /** Removes entries matching [identifier] from a paired JSON array, saves, and invalidates cache. */
    private fun removePairedEntry(
        prefs: SharedPreferences,
        key: String,
        loader: () -> JSONArray,
        identifier: String,
        cacheInvalidator: () -> Unit
    ) {
        val items = loader()
        val filtered = (0 until items.length())
            .map { items.getJSONObject(it) }
            .filter { it.getString("identifier") != identifier }
        val arr = JSONArray()
        filtered.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
        cacheInvalidator()
    }

    private fun loadPairedBeaconsJson(): JSONArray {
        cachedPairedBeacons?.let { return it }
        val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        val result = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        cachedPairedBeacons = result
        return result
    }

    private fun loadPairedEddystonesJson(): JSONArray {
        cachedPairedEddystones?.let { return it }
        val json = eddystonePrefs.getString(EDDYSTONE_PREFS_KEY, "[]") ?: "[]"
        val result = try { JSONArray(json) } catch (_: Exception) { JSONArray() }
        cachedPairedEddystones = result
        return result
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
        val ctx = appContext.reactContext ?: return false
        return ctx.bindService(intent, connection, mode)
    }

    override fun unbindService(connection: ServiceConnection) {
        appContext.reactContext?.unbindService(connection)
    }

    /** Unbind from AltBeacon service when no scan or continuous mode is active. */
    private fun unbindIfIdle() {
        if (scanPromise == null && eddystoneScanPromise == null && !continuousScanActive && isBoundForScan) {
            beaconManager.unbind(this)
            isBoundForScan = false
        }
    }
}
