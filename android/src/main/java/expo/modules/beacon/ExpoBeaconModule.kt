package expo.modules.beacon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
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

    // SQLite event logger
    private var eventLogger: BeaconEventLogger? = null
    @Volatile private var loggingEnabled = false

    override fun definition() = ModuleDefinition {
        Name("ExpoBeacon")

        Events("onBeaconEnter", "onBeaconExit", "onBeaconDistance", "onBeaconTimeout", "onBeaconFound", "onEddystoneFound", "onEddystoneEnter", "onEddystoneExit", "onEddystoneDistance", "onEddystoneTimeout", "onBeaconError", "onCarPlayConnected", "onCarPlayDisconnected")

        AsyncFunction("scanForBeaconsAsync") { uuids: List<String>?, scanDurationMs: Int, promise: Promise ->
            if (scanDurationMs <= 0) {
                promise.reject("INVALID_DURATION", "Scan duration must be a positive integer", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "INVALID_DURATION", "message" to "Scan duration must be a positive integer"))
                return@AsyncFunction
            }
            if (scanPromise != null || eddystoneScanPromise != null) {
                promise.reject("SCAN_IN_PROGRESS", "A scan is already running", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "SCAN_IN_PROGRESS", "message" to "A scan is already running"))
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
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "SCAN_CANCELLED", "message" to "Scan was cancelled"))
                scanPromise = null
                scanUuidFilter = emptySet()
            }
            if (eddystoneScanPromise != null) {
                cancelActiveScan()
                eddystoneScanPromise?.reject("SCAN_CANCELLED", "Scan was cancelled", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "SCAN_CANCELLED", "message" to "Scan was cancelled"))
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
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "INVALID_DURATION", "message" to "Scan duration must be a positive integer"))
                return@AsyncFunction
            }
            if (scanPromise != null || eddystoneScanPromise != null) {
                promise.reject("SCAN_IN_PROGRESS", "A scan is already running", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "SCAN_IN_PROGRESS", "message" to "A scan is already running"))
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

        Function("pairBeacon") { identifier: String, uuid: String, major: Int, minor: Int, name: String?, timeoutSeconds: Int? ->
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
                if (name != null) put("name", name)
                if (timeoutSeconds != null) put("timeoutSeconds", timeoutSeconds)
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
                buildMap<String, Any?> {
                    put("identifier", b.getString("identifier"))
                    put("uuid", b.getString("uuid"))
                    put("major", b.getInt("major"))
                    put("minor", b.getInt("minor"))
                    val n = b.optString("name").takeIf { it.isNotEmpty() }
                    if (n != null) put("name", n)
                    if (b.has("timeoutSeconds")) put("timeoutSeconds", b.getInt("timeoutSeconds"))
                }
            }
        }

        Function("pairEddystone") { identifier: String, namespace: String, instance: String, name: String?, timeoutSeconds: Int? ->
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
                if (name != null) put("name", name)
                if (timeoutSeconds != null) put("timeoutSeconds", timeoutSeconds)
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
                buildMap<String, Any?> {
                    put("identifier", e.getString("identifier"))
                    put("namespace", e.getString("namespace"))
                    put("instance", e.getString("instance"))
                    val n = e.optString("name").takeIf { it.isNotEmpty() }
                    if (n != null) put("name", n)
                    if (e.has("timeoutSeconds")) put("timeoutSeconds", e.getInt("timeoutSeconds"))
                }
            }
        }

        AsyncFunction("startMonitoring") { options: Any?, promise: Promise ->
            val ctx = appContext.reactContext ?: run {
                promise.reject("NO_CONTEXT", "React context is not available", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "NO_CONTEXT", "message" to "React context is not available"))
                return@AsyncFunction
            }
            var maxDistance: Double? = null
            var exitDistance: Double? = null
            var minRssi: Int? = null
            var level: String = "all"
            var exitTimeoutSeconds: Double? = null
            when (options) {
                is Double -> maxDistance = options
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val map = options as Map<String, Any?>
                    maxDistance = (map["maxDistance"] as? Number)?.toDouble()
                    exitDistance = (map["exitDistance"] as? Number)?.toDouble()
                    minRssi = (map["minRssi"] as? Number)?.toInt()
                    level = (map["level"] as? String) ?: "all"
                    exitTimeoutSeconds = (map["exitTimeoutSeconds"] as? Number)?.toDouble()
                    val notifications = map["notifications"]
                    if (notifications is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        ctx.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                            .edit().putString("config", mapToJson(notifications as Map<String, Any?>).toString()).apply()
                    }
                }
            }
            if (maxDistance != null && (!maxDistance.isFinite() || maxDistance <= 0.0)) {
                promise.reject("INVALID_MAX_DISTANCE", "maxDistance must be a finite number greater than 0", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "INVALID_MAX_DISTANCE", "message" to "maxDistance must be a finite number greater than 0"))
                return@AsyncFunction
            }
            if (exitDistance != null && (!exitDistance.isFinite() || exitDistance <= 0.0)) {
                promise.reject("INVALID_EXIT_DISTANCE", "exitDistance must be a finite number greater than 0", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "INVALID_EXIT_DISTANCE", "message" to "exitDistance must be a finite number greater than 0"))
                return@AsyncFunction
            }
            if (exitDistance != null && maxDistance == null) {
                promise.reject("INVALID_EXIT_DISTANCE", "exitDistance requires maxDistance to be set", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "INVALID_EXIT_DISTANCE", "message" to "exitDistance requires maxDistance to be set"))
                return@AsyncFunction
            }
            if (maxDistance != null && exitDistance != null && exitDistance < maxDistance) {
                promise.reject("INVALID_EXIT_DISTANCE", "exitDistance must be greater than or equal to maxDistance", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "INVALID_EXIT_DISTANCE", "message" to "exitDistance must be greater than or equal to maxDistance"))
                return@AsyncFunction
            }
            if (exitTimeoutSeconds != null && (!exitTimeoutSeconds.isFinite() || exitTimeoutSeconds <= 0.0)) {
                promise.reject("INVALID_EXIT_TIMEOUT", "exitTimeoutSeconds must be a finite number greater than 0", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "INVALID_EXIT_TIMEOUT", "message" to "exitTimeoutSeconds must be a finite number greater than 0"))
                return@AsyncFunction
            }
            ctx.getSharedPreferences(MONITORING_OPTIONS_PREFS, Context.MODE_PRIVATE)
                .edit().apply {
                    if (maxDistance != null) putString("max_distance", maxDistance.toString())
                    else remove("max_distance")
                    if (exitDistance != null) putString("exit_distance", exitDistance.toString())
                    else remove("exit_distance")
                    if (minRssi != null) putInt("min_rssi", minRssi)
                    else remove("min_rssi")
                    putString("level", level)
                    if (exitTimeoutSeconds != null) putString("exit_timeout_seconds", exitTimeoutSeconds.toString())
                    else remove("exit_timeout_seconds")
                }.apply()
            // Verify we have the permissions needed for background monitoring
            val hasLocation = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val hasBgLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasLocation || !hasBgLocation) {
                promise.reject("PERMISSION_DENIED", "Location permissions required for background monitoring. Call requestPermissionsAsync() first.", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "PERMISSION_DENIED", "message" to "Location permissions required for background monitoring. Call requestPermissionsAsync() first."))
                return@AsyncFunction
            }
            // Android 12+ requires BLUETOOTH_SCAN for BLE operations;
            // Android 14+ additionally requires it for connectedDevice foreground services.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasBtScan = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                val hasBtConnect = ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                if (!hasBtScan || !hasBtConnect) {
                    promise.reject("PERMISSION_DENIED", "Bluetooth permissions required for beacon monitoring. Call requestPermissionsAsync() first.", null)
                    sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "PERMISSION_DENIED", "message" to "Bluetooth permissions required for beacon monitoring. Call requestPermissionsAsync() first."))
                    return@AsyncFunction
                }
            }

            registerEventReceiver()
            try {
                BeaconForegroundService.start(ctx)
            } catch (e: Exception) {
                unregisterEventReceiver()
                promise.reject("SERVICE_START_FAILED", "Failed to start monitoring service: ${e.message}", e)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "SERVICE_START_FAILED", "message" to "Failed to start monitoring service: ${e.message}"))
                return@AsyncFunction
            }
            // Auto-enable CarPlay observation alongside beacon monitoring so
            // the service captures CarPlay/Android Auto events for the same
            // lifetime. Users can opt out at any time via stopCarPlayMonitoring().
            try { BeaconForegroundService.enableCarPlay(ctx) } catch (_: Throwable) {}
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
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "NO_CONTEXT", "message" to "React context is not available"))
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

        Function("enableEventLogging") {
            val ctx = appContext.reactContext
                ?: throw IllegalStateException("React context is not available")
            if (eventLogger == null) {
                eventLogger = BeaconEventLogger(ctx)
            }
            BeaconEventLogger.setLoggingEnabled(ctx, true)
            loggingEnabled = true
        }

        Function("disableEventLogging") {
            appContext.reactContext?.let { BeaconEventLogger.setLoggingEnabled(it, false) }
            loggingEnabled = false
        }

        Function("isEventLoggingEnabled") {
            val ctx = appContext.reactContext ?: return@Function false
            BeaconEventLogger.isLoggingEnabled(ctx)
        }

        Function("getEventLogs") { options: Map<String, Any?>? ->
            val logger = getOrCreateEventLogger() ?: return@Function emptyList<Map<String, Any?>>()
            val limit = (options?.get("limit") as? Number)?.toInt() ?: 1000
            val eventType = options?.get("eventType") as? String
            val sinceTimestamp = (options?.get("sinceTimestamp") as? Number)?.toLong()
            logger.getEvents(limit = limit, eventType = eventType, sinceTimestamp = sinceTimestamp)
        }

        Function("clearEventLogs") {
            getOrCreateEventLogger()?.clearEvents()
        }

        Function("destroyEventLogs") {
            loggingEnabled = false
            val ctx = appContext.reactContext ?: return@Function null
            BeaconEventLogger.setLoggingEnabled(ctx, false)
            eventLogger?.close()
            eventLogger = null
            BeaconEventLogger.deleteLogDatabase(ctx)
        }

        // MARK: - API Forwarding

        Function("setApiEndpoint") { url: String, apiKey: String?, id: String? ->
            val ctx = appContext.reactContext
                ?: throw IllegalStateException("React context is not available")
            BeaconApiForwarder(ctx).configure(url, apiKey, id)
        }

        Function("getApiEndpoint") {
            val ctx = appContext.reactContext
                ?: throw IllegalStateException("React context is not available")
            BeaconApiForwarder(ctx).getConfig()
        }

        Function("getMonitoringConfig") {
            val ctx = appContext.reactContext
                ?: throw IllegalStateException("React context is not available")
            val optPrefs = ctx.getSharedPreferences(MONITORING_OPTIONS_PREFS, Context.MODE_PRIVATE)
            buildMap<String, Any?> {
                put("isMonitoring", BeaconForegroundService.isMonitoringActive(ctx))
                optPrefs.getString("max_distance", null)?.toDoubleOrNull()?.let { put("maxDistance", it) }
                optPrefs.getString("exit_distance", null)?.toDoubleOrNull()?.let { put("exitDistance", it) }
                if (optPrefs.contains("min_rssi")) put("minRssi", optPrefs.getInt("min_rssi", -85))
                optPrefs.getString("level", null)?.let { put("level", it) }
                optPrefs.getString("exit_timeout_seconds", null)?.toDoubleOrNull()?.let { put("exitTimeoutSeconds", it) }
                val json = ctx.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                    .getString("config", null)
                if (json != null) {
                    try {
                        put("notifications", jsonToMap(org.json.JSONObject(json)))
                    } catch (_: Exception) { /* ignore malformed JSON */ }
                }
            }
        }

        Function("getMonitoredDeviceState") { identifier: String ->
            buildMonitoredDeviceState(identifier)
        }

        Function("getMonitoredDeviceStates") {
            buildMonitoredDeviceStates()
        }

        // MARK: - Battery Optimization

        Function("isBatteryOptimizationExempt") {
            val ctx = appContext.reactContext ?: return@Function false
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return@Function false
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }

        AsyncFunction("requestBatteryOptimizationExemption") { promise: Promise ->
            val ctx = appContext.reactContext ?: run {
                promise.reject("NO_CONTEXT", "React context is not available", null)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "NO_CONTEXT", "message" to "React context is not available"))
                return@AsyncFunction
            }
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm == null) {
                promise.resolve(false)
                return@AsyncFunction
            }
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                promise.resolve(true)
                return@AsyncFunction
            }
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                // The system dialog is fire-and-forget; we cannot observe the result
                // from a non-Activity context. Resolve true to indicate the dialog was shown.
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("BATTERY_OPT_ERROR", "Failed to open battery optimization settings: ${e.message}", e)
                sendEvent("onBeaconError", mapOf("identifier" to "", "code" to "BATTERY_OPT_ERROR", "message" to "Failed to open battery optimization settings: ${e.message}"))
            }
        }

        // MARK: - CarPlay / Android Auto

        AsyncFunction("startCarPlayMonitoring") { promise: Promise ->
            val ctx = appContext.reactContext
            if (ctx == null) {
                promise.reject("NO_CONTEXT", "React context is not available", null)
                return@AsyncFunction
            }
            try {
                BeaconForegroundService.enableCarPlay(ctx)
                promise.resolve(null)
            } catch (e: Throwable) {
                promise.reject("CARPLAY_START_FAILED", "Failed to start CarPlay monitoring: ${e.message}", e)
            }
        }

        AsyncFunction("stopCarPlayMonitoring") { promise: Promise ->
            val ctx = appContext.reactContext
            if (ctx == null) {
                promise.reject("NO_CONTEXT", "React context is not available", null)
                return@AsyncFunction
            }
            try {
                BeaconForegroundService.disableCarPlay(ctx)
                promise.resolve(null)
            } catch (e: Throwable) {
                promise.reject("CARPLAY_STOP_FAILED", "Failed to stop CarPlay monitoring: ${e.message}", e)
            }
        }

        Function("isCarPlayMonitoringEnabled") {
            val ctx = appContext.reactContext ?: return@Function false
            BeaconForegroundService.isCarPlayEnabled(ctx)
        }

        OnCreate {
            // Register this module instance for best-effort JS-bridge fan-out
            // of CarPlay events emitted by the foreground service. The service
            // holds a weak reference; cleared in OnDestroy.
            BeaconForegroundService.bindModule(this@ExpoBeaconModule)
        }

        OnDestroy {
            with(this@ExpoBeaconModule) {
                BeaconForegroundService.bindModule(null)
                unregisterEventReceiver()
                loggingEnabled = false
                eventLogger?.close()
                eventLogger = null
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
                val map = eddystoneBeaconToMap(beacon)
                logBeaconEvent("onEddystoneFound", map)
                sendEvent("onEddystoneFound", map)
            } else if (beacon.identifiers.size >= 3) {
                val map = buildMap<String, Any?> {
                    put("uuid", beacon.id1.toString().uppercase())
                    put("major", beacon.id2.toInt())
                    put("minor", beacon.id3.toInt())
                    put("rssi", beacon.rssi)
                    put("distance", beacon.distance)
                    put("txPower", beacon.txPower)
                    beacon.bluetoothName?.let { put("name", it) }
                }
                logBeaconEvent("onBeaconFound", map)
                sendEvent("onBeaconFound", map)
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
                buildMap<String, Any?> {
                    put("uuid", beacon.id1.toString().uppercase())
                    put("major", beacon.id2.toInt())
                    put("minor", beacon.id3.toInt())
                    put("rssi", beacon.rssi)
                    put("distance", beacon.distance)
                    put("txPower", beacon.txPower)
                    beacon.bluetoothName?.let { put("name", it) }
                }
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

    private fun eddystoneBeaconToMap(beacon: Beacon): Map<String, Any?> {
        // AltBeacon provides distance via its built-in path-loss model.
        // iOS uses a custom calculateDistance() with NaN/Infinity clamping for Eddystone.
        // Both return -1.0 for invalid readings, but distance estimates may differ slightly.
        return buildMap {
            put("rssi", beacon.rssi)
            put("distance", beacon.distance)
            put("txPower", beacon.txPower)
            if (beacon.identifiers.size >= 2) {
                put("frameType", "uid")
                put("namespace", beacon.id1.toString().removePrefix("0x"))
                put("instance", beacon.id2.toString().removePrefix("0x"))
            } else {
                put("frameType", "url")
                put("url", decodeEddystoneUrl(beacon.id1.toByteArray()))
            }
            beacon.bluetoothName?.let { put("name", it) }
        }
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

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            val value = obj.opt(key)
            map[key] = when (value) {
                is JSONObject -> jsonToMap(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
        return map
    }

    private fun buildMonitoredDeviceState(identifier: String): Map<String, Any?>? {
        val runtimeStates = BeaconForegroundService.getMonitoringRuntimeSnapshot()

        val beacons = loadPairedBeaconsJson()
        for (i in 0 until beacons.length()) {
            val beacon = beacons.getJSONObject(i)
            if (beacon.getString("identifier") == identifier) {
                return buildIBeaconMonitoringState(beacon, runtimeStates[identifier])
            }
        }

        val eddystones = loadPairedEddystonesJson()
        for (i in 0 until eddystones.length()) {
            val eddystone = eddystones.getJSONObject(i)
            if (eddystone.getString("identifier") == identifier) {
                return buildEddystoneMonitoringState(eddystone, runtimeStates[identifier])
            }
        }

        return null
    }

    private fun buildMonitoredDeviceStates(): List<Map<String, Any?>> {
        val runtimeStates = BeaconForegroundService.getMonitoringRuntimeSnapshot()
        val states = mutableListOf<Map<String, Any?>>()

        val beacons = loadPairedBeaconsJson()
        for (i in 0 until beacons.length()) {
            val beacon = beacons.getJSONObject(i)
            val identifier = beacon.getString("identifier")
            states.add(buildIBeaconMonitoringState(beacon, runtimeStates[identifier]))
        }

        val eddystones = loadPairedEddystonesJson()
        for (i in 0 until eddystones.length()) {
            val eddystone = eddystones.getJSONObject(i)
            val identifier = eddystone.getString("identifier")
            states.add(buildEddystoneMonitoringState(eddystone, runtimeStates[identifier]))
        }

        return states
    }

    private fun buildIBeaconMonitoringState(
        beacon: JSONObject,
        runtimeState: BeaconForegroundService.MonitoringRuntimeState?
    ): Map<String, Any?> {
        val identifier = beacon.getString("identifier")
        return buildMap<String, Any?> {
            put("kind", "ibeacon")
            put("identifier", identifier)
            put("uuid", beacon.getString("uuid"))
            put("major", beacon.getInt("major"))
            put("minor", beacon.getInt("minor"))
            put("state", if (runtimeState?.isEntered == true) "entered" else "exited")
            put("distance", normalizedMonitoringDistance(runtimeState))
        }
    }

    private fun buildEddystoneMonitoringState(
        eddystone: JSONObject,
        runtimeState: BeaconForegroundService.MonitoringRuntimeState?
    ): Map<String, Any?> {
        val identifier = eddystone.getString("identifier")
        return buildMap<String, Any?> {
            put("kind", "eddystone")
            put("identifier", identifier)
            put("namespace", eddystone.getString("namespace"))
            put("instance", eddystone.getString("instance"))
            put("state", if (runtimeState?.isEntered == true) "entered" else "exited")
            put("distance", normalizedMonitoringDistance(runtimeState))
        }
    }

    private fun normalizedMonitoringDistance(
        runtimeState: BeaconForegroundService.MonitoringRuntimeState?
    ): Double? {
        if (runtimeState?.isEntered != true) return null
        val distance = runtimeState.distance ?: return null
        return distance.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0 }
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

    private fun getOrCreateEventLogger(): BeaconEventLogger? {
        val context = appContext.reactContext ?: return null
        return eventLogger ?: BeaconEventLogger(context).also { eventLogger = it }
    }

    private fun isEventLoggingEnabled(): Boolean {
        if (loggingEnabled) return true
        val context = appContext.reactContext ?: return false
        if (!BeaconEventLogger.isLoggingEnabled(context)) return false
        loggingEnabled = true
        if (eventLogger == null) {
            eventLogger = BeaconEventLogger(context)
        }
        return true
    }

    /** Log an event to SQLite if logging is enabled. */
    private fun logBeaconEvent(eventType: String, params: Map<String, Any?>) {
        if (!isEventLoggingEnabled()) return
        val identifier = params["identifier"] as? String
        getOrCreateEventLogger()?.logEvent(eventType, identifier, params)
    }

    /**
     * Called by [BeaconForegroundService] (via weak reference) when it emits a
     * CarPlay event from its own observer. Best-effort delivery to the JS bridge
     * — the service has already handled SQLite logging, API forwarding, and
     * native plugin dispatch, so this method only needs to relay to JS.
     */
    fun forwardCarPlayEventFromService(eventName: String, payload: Map<String, Any?>) {
        try { sendEvent(eventName, payload) } catch (_: Throwable) { /* JS bridge may be torn down */ }
    }
}
