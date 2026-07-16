
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
import android.util.Log
import androidx.core.content.ContextCompat
import expo.modules.interfaces.permissions.PermissionsStatus
import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.util.Collections
import kotlinx.coroutines.*
import org.altbeacon.beacon.*
import org.json.JSONArray
import org.json.JSONObject

class ExpoBeaconModule : Module(), BeaconConsumer {

    companion object {
        private const val TAG = "ExpoBeaconModule"
        private val SCAN_REGION = Region("scanRegion", null, null, null)
        private val NAMESPACE_REGEX = Regex("^[0-9a-fA-F]{20}$")
        private val INSTANCE_REGEX = Regex("^[0-9a-fA-F]{12}$")
    }

    private val beaconManager: BeaconManager by lazy {
        val ctx =
                appContext.reactContext
                        ?: throw IllegalStateException("React context is not available")
        BeaconManager.getInstanceForApplication(ctx).also { manager ->
            manager.setEnableScheduledScanJobs(false)
            BeaconParsers.ensureRegistered(manager)
        }
    }

    private val prefs: SharedPreferences by lazy {
        val ctx =
                appContext.reactContext
                        ?: throw IllegalStateException("React context is not available")
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val eddystonePrefs: SharedPreferences by lazy {
        val ctx =
                appContext.reactContext
                        ?: throw IllegalStateException("React context is not available")
        ctx.getSharedPreferences(EDDYSTONE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Coroutine scope tied to module lifecycle
    private val moduleScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // BroadcastReceiver bridge from BeaconForegroundService to JS events
    private var eventReceiver: BeaconEventReceiver? = null

    /**
     * Independent state for a one-shot scan so the iBeacon and Eddystone scans can run concurrently
     * (matches iOS). Results, timer job, and range notifier are per type; ranging of the shared
     * SCAN_REGION is started once and only stopped when neither scan is active.
     */
    private inner class OneShotScanState(val eddystone: Boolean) {
        // @Volatile: read from AltBeacon callback threads.
        @Volatile var promise: Promise? = null
        @Volatile var job: Job? = null
        val results: MutableList<Beacon> = Collections.synchronizedList(mutableListOf())
        val notifier = RangeNotifier { beacons, _ ->
            if (promise == null) return@RangeNotifier
            // Filter at ingestion: only collect the beacon type matching this scan.
            var matched =
                    beacons.filter {
                        isEddystoneBeacon(it) == eddystone &&
                                (if (eddystone) it.identifiers.isNotEmpty()
                                else it.identifiers.size >= 3)
                    }
            if (!eddystone && scanUuidFilter.isNotEmpty()) {
                matched = matched.filter { scanUuidFilter.contains(it.id1.toString().lowercase()) }
            }
            synchronized(results) { results.addAll(matched) }
        }
    }

    private val iBeaconScan = OneShotScanState(eddystone = false)
    private val eddystoneScan = OneShotScanState(eddystone = true)
    @Volatile private var scanUuidFilter: Set<String> = emptySet()
    /** True from bind request until explicit unbind; connection completes asynchronously. */
    @Volatile private var isBoundForScan = false
    @Volatile private var scanServiceConnected = false

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

    // Remote API forwarder for module-emitted events; shut down in OnDestroy.
    private var apiForwarder: BeaconApiForwarder? = null

    override fun definition() = ModuleDefinition {
        Name("ExpoBeacon")

        Events(
                "onBeaconEnter",
                "onBeaconExit",
                "onBeaconDistance",
                "onBeaconTimeout",
                "onBeaconFound",
                "onEddystoneFound",
                "onEddystoneEnter",
                "onEddystoneExit",
                "onEddystoneDistance",
                "onEddystoneTimeout",
                "onBeaconError"
        )

        AsyncFunction("scanForBeaconsAsync") {
                uuids: List<String>?,
                scanDurationMs: Int?,
                promise: Promise ->
            val durationMs = scanDurationMs ?: DEFAULT_SCAN_DURATION_MS
            if (durationMs <= 0) {
                rejectWithError(
                        promise,
                        "INVALID_DURATION",
                        "Scan duration must be a positive integer"
                )
                return@AsyncFunction
            }
            val normalizedUuids =
                    try {
                        uuids?.map { java.util.UUID.fromString(it).toString().lowercase() }
                                ?: emptyList()
                    } catch (_: IllegalArgumentException) {
                        rejectWithError(
                                promise,
                                "INVALID_UUID",
                                "All scan UUIDs must use the canonical UUID format"
                        )
                        return@AsyncFunction
                    }
            val context = appContext.reactContext
            if (context == null || !hasForegroundScanPermissions(context)) {
                rejectWithError(
                        promise,
                        "PERMISSION_DENIED",
                        "Location and Bluetooth permissions are required for beacon scanning. Call requestPermissionsAsync() first."
                )
                return@AsyncFunction
            }
            if (iBeaconScan.promise != null) {
                rejectWithError(promise, "SCAN_IN_PROGRESS", "An iBeacon scan is already running")
                return@AsyncFunction
            }
            scanUuidFilter = normalizedUuids.toSet()
            startOneShotScan(iBeaconScan, durationMs, promise)
        }

        Function("cancelScan") {
            cancelOneShotScan(iBeaconScan)
            cancelOneShotScan(eddystoneScan)
            unbindIfIdle()
        }

        Function("startContinuousScan") {
            if (!continuousScanActive) {
                val context =
                        appContext.reactContext
                                ?: throw expo.modules.kotlin.exception.CodedException(
                                        "NO_CONTEXT",
                                        "React context is not available",
                                        null
                                )
                if (!hasForegroundScanPermissions(context)) {
                    emitBeaconError(
                            "PERMISSION_DENIED",
                            "Location and Bluetooth permissions are required for beacon scanning. Call requestPermissionsAsync() first."
                    )
                    throw expo.modules.kotlin.exception.CodedException(
                            "PERMISSION_DENIED",
                            "Location and Bluetooth permissions are required for beacon scanning. Call requestPermissionsAsync() first.",
                            null
                    )
                }
                try {
                    continuousScanActive = true
                    beaconManager.addRangeNotifier(continuousScanRangeNotifier)
                    if (!isBoundForScan) {
                        isBoundForScan = true
                        beaconManager.bind(this@ExpoBeaconModule)
                    } else if (scanServiceConnected) {
                        startContinuousRanging()
                    }
                } catch (error: Throwable) {
                    continuousScanActive = false
                    beaconManager.removeRangeNotifier(continuousScanRangeNotifier)
                    unbindIfIdle()
                    emitBeaconError(
                            "SCAN_ERROR",
                            "Failed to start continuous beacon scanning: ${error.message}"
                    )
                    throw expo.modules.kotlin.exception.CodedException(
                            "SCAN_ERROR",
                            "Failed to start continuous beacon scanning: ${error.message}",
                            error
                    )
                }
            }
        }

        Function("stopContinuousScan") {
            if (continuousScanActive) {
                continuousScanActive = false
                try {
                    beaconManager.stopRangingBeaconsInRegion(continuousScanRegion)
                } catch (_: Throwable) {}
                try {
                    beaconManager.removeRangeNotifier(continuousScanRangeNotifier)
                } catch (_: Throwable) {}
                unbindIfIdle()
            }
        }

        AsyncFunction("scanForEddystonesAsync") { scanDurationMs: Int?, promise: Promise ->
            val durationMs = scanDurationMs ?: DEFAULT_SCAN_DURATION_MS
            if (durationMs <= 0) {
                rejectWithError(
                        promise,
                        "INVALID_DURATION",
                        "Scan duration must be a positive integer"
                )
                return@AsyncFunction
            }
            val context = appContext.reactContext
            if (context == null || !hasForegroundScanPermissions(context)) {
                rejectWithError(
                        promise,
                        "PERMISSION_DENIED",
                        "Location and Bluetooth permissions are required for beacon scanning. Call requestPermissionsAsync() first."
                )
                return@AsyncFunction
            }
            if (eddystoneScan.promise != null) {
                rejectWithError(promise, "SCAN_IN_PROGRESS", "An Eddystone scan is already running")
                return@AsyncFunction
            }
            startOneShotScan(eddystoneScan, durationMs, promise)
        }

        Function("pairBeacon") {
                identifier: String,
                uuid: String,
                major: Int,
                minor: Int,
                name: String?,
                timeoutSeconds: Int? ->
            if (identifier.isEmpty()) {
                throw expo.modules.kotlin.exception.CodedException(
                        "INVALID_IDENTIFIER",
                        "Identifier must not be empty",
                        null
                )
            }
            // Validate UUID format
            val normalizedUuid =
                    try {
                        java.util.UUID.fromString(uuid).toString().uppercase()
                    } catch (_: IllegalArgumentException) {
                        throw expo.modules.kotlin.exception.CodedException(
                                "INVALID_UUID",
                                "Invalid UUID format: $uuid",
                                null
                        )
                    }
            if (major !in 0..65535) {
                throw expo.modules.kotlin.exception.CodedException(
                        "INVALID_MAJOR",
                        "Major must be 0\u201365535, got $major",
                        null
                )
            }
            if (minor !in 0..65535) {
                throw expo.modules.kotlin.exception.CodedException(
                        "INVALID_MINOR",
                        "Minor must be 0\u201365535, got $minor",
                        null
                )
            }
            validateTimeoutSeconds(timeoutSeconds)
            // Reject identifiers already used by the other beacon type
            if (containsIdentifier(loadPairedEddystonesJson(), identifier)) {
                throw expo.modules.kotlin.exception.CodedException(
                        "DUPLICATE_IDENTIFIER",
                        "Identifier '$identifier' is already used by a paired Eddystone",
                        null
                )
            }
            if (containsIBeaconIdentity(
                            loadPairedBeaconsJson(),
                            identifier,
                            normalizedUuid,
                            major,
                            minor
                    )
            ) {
                throw expo.modules.kotlin.exception.CodedException(
                        "DUPLICATE_BEACON_IDENTITY",
                        "This iBeacon UUID/major/minor is already paired under another identifier",
                        null
                )
            }

            // Remove duplicate if exists
            removePairedEntry(prefs, PREFS_KEY, ::loadPairedBeaconsJson, identifier) {
                cachedPairedBeacons = null
            }
            val beacons = loadPairedBeaconsJson()
            val newBeacon =
                    JSONObject().apply {
                        put("identifier", identifier)
                        put("uuid", normalizedUuid)
                        put("major", major)
                        put("minor", minor)
                        if (name != null) put("name", name)
                        if (timeoutSeconds != null) put("timeoutSeconds", timeoutSeconds)
                    }
            beacons.put(newBeacon)
            prefs.edit().putString(PREFS_KEY, beacons.toString()).apply()
            cachedPairedBeacons = null
            reconcileMonitoringAfterPairChange(identifier)
        }

        Function("unpairBeacon") { identifier: String ->
            removePairedEntry(prefs, PREFS_KEY, ::loadPairedBeaconsJson, identifier) {
                cachedPairedBeacons = null
            }
            reconcileMonitoringAfterPairChange(identifier)
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

        Function("pairEddystone") {
                identifier: String,
                namespace: String,
                instance: String,
                name: String?,
                timeoutSeconds: Int? ->
            if (identifier.isEmpty()) {
                throw expo.modules.kotlin.exception.CodedException(
                        "INVALID_IDENTIFIER",
                        "Identifier must not be empty",
                        null
                )
            }
            if (!namespace.matches(NAMESPACE_REGEX)) {
                throw expo.modules.kotlin.exception.CodedException(
                        "INVALID_NAMESPACE",
                        "Namespace must be 20 hex characters, got: $namespace",
                        null
                )
            }
            if (!instance.matches(INSTANCE_REGEX)) {
                throw expo.modules.kotlin.exception.CodedException(
                        "INVALID_INSTANCE",
                        "Instance must be 12 hex characters, got: $instance",
                        null
                )
            }
            validateTimeoutSeconds(timeoutSeconds)
            // Reject identifiers already used by the other beacon type
            if (containsIdentifier(loadPairedBeaconsJson(), identifier)) {
                throw expo.modules.kotlin.exception.CodedException(
                        "DUPLICATE_IDENTIFIER",
                        "Identifier '$identifier' is already used by a paired beacon",
                        null
                )
            }
            val normalizedNamespace = namespace.lowercase()
            val normalizedInstance = instance.lowercase()
            if (containsEddystoneIdentity(
                            loadPairedEddystonesJson(),
                            identifier,
                            normalizedNamespace,
                            normalizedInstance
                    )
            ) {
                throw expo.modules.kotlin.exception.CodedException(
                        "DUPLICATE_EDDYSTONE_IDENTITY",
                        "This Eddystone namespace/instance is already paired under another identifier",
                        null
                )
            }

            // Remove duplicate if exists
            removePairedEntry(
                    eddystonePrefs,
                    EDDYSTONE_PREFS_KEY,
                    ::loadPairedEddystonesJson,
                    identifier
            ) { cachedPairedEddystones = null }
            val eddystones = loadPairedEddystonesJson()
            val newEddystone =
                    JSONObject().apply {
                        put("identifier", identifier)
                        // Persist lowercase — AltBeacon emits lowercase hex in events (iOS does the
                        // same).
                        put("namespace", normalizedNamespace)
                        put("instance", normalizedInstance)
                        if (name != null) put("name", name)
                        if (timeoutSeconds != null) put("timeoutSeconds", timeoutSeconds)
                    }
            eddystones.put(newEddystone)
            eddystonePrefs.edit().putString(EDDYSTONE_PREFS_KEY, eddystones.toString()).apply()
            cachedPairedEddystones = null
            reconcileMonitoringAfterPairChange(identifier)
        }

        Function("unpairEddystone") { identifier: String ->
            removePairedEntry(
                    eddystonePrefs,
                    EDDYSTONE_PREFS_KEY,
                    ::loadPairedEddystonesJson,
                    identifier
            ) { cachedPairedEddystones = null }
            reconcileMonitoringAfterPairChange(identifier)
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
            val ctx =
                    appContext.reactContext
                            ?: run {
                                rejectWithError(
                                        promise,
                                        "NO_CONTEXT",
                                        "React context is not available"
                                )
                                return@AsyncFunction
                            }
            if (loadPairedBeaconsJson().length() == 0 &&
                            loadPairedEddystonesJson().length() == 0
            ) {
                rejectWithError(
                        promise,
                        "NO_PAIRED_BEACONS",
                        "Pair at least one iBeacon or Eddystone before starting monitoring"
                )
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
                    @Suppress("UNCHECKED_CAST") val map = options as Map<String, Any?>
                    maxDistance = (map["maxDistance"] as? Number)?.toDouble()
                    exitDistance = (map["exitDistance"] as? Number)?.toDouble()
                    minRssi = (map["minRssi"] as? Number)?.toInt()
                    // Coerce invalid levels to "all" (matches iOS).
                    level =
                            (map["level"] as? String)?.takeIf { it == "all" || it == "events" }
                                    ?: "all"
                    exitTimeoutSeconds = (map["exitTimeoutSeconds"] as? Number)?.toDouble()
                    val notifications = map["notifications"]
                    if (notifications is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        mergeNotificationConfig(ctx, notifications as Map<String, Any?>)
                    }
                }
            }
            if (maxDistance != null && (!maxDistance.isFinite() || maxDistance <= 0.0)) {
                rejectWithError(
                        promise,
                        "INVALID_MAX_DISTANCE",
                        "maxDistance must be a finite number greater than 0"
                )
                return@AsyncFunction
            }
            if (exitDistance != null && (!exitDistance.isFinite() || exitDistance <= 0.0)) {
                rejectWithError(
                        promise,
                        "INVALID_EXIT_DISTANCE",
                        "exitDistance must be a finite number greater than 0"
                )
                return@AsyncFunction
            }
            if (exitDistance != null && maxDistance == null) {
                rejectWithError(
                        promise,
                        "INVALID_EXIT_DISTANCE",
                        "exitDistance requires maxDistance to be set"
                )
                return@AsyncFunction
            }
            if (maxDistance != null && exitDistance != null && exitDistance < maxDistance) {
                rejectWithError(
                        promise,
                        "INVALID_EXIT_DISTANCE",
                        "exitDistance must be greater than or equal to maxDistance"
                )
                return@AsyncFunction
            }
            if (exitTimeoutSeconds != null &&
                            (!exitTimeoutSeconds.isFinite() || exitTimeoutSeconds <= 0.0)
            ) {
                rejectWithError(
                        promise,
                        "INVALID_EXIT_TIMEOUT",
                        "exitTimeoutSeconds must be a finite number greater than 0"
                )
                return@AsyncFunction
            }
            ctx.getSharedPreferences(MONITORING_OPTIONS_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .apply {
                        if (maxDistance != null)
                                putString(MONITORING_OPT_MAX_DISTANCE, maxDistance.toString())
                        else remove(MONITORING_OPT_MAX_DISTANCE)
                        if (exitDistance != null)
                                putString(MONITORING_OPT_EXIT_DISTANCE, exitDistance.toString())
                        else remove(MONITORING_OPT_EXIT_DISTANCE)
                        if (minRssi != null) putInt(MONITORING_OPT_MIN_RSSI, minRssi)
                        else remove(MONITORING_OPT_MIN_RSSI)
                        putString(MONITORING_OPT_LEVEL, level)
                        if (exitTimeoutSeconds != null)
                                putString(
                                        MONITORING_OPT_EXIT_TIMEOUT_SECONDS,
                                        exitTimeoutSeconds.toString()
                                )
                        else remove(MONITORING_OPT_EXIT_TIMEOUT_SECONDS)
                    }
                    .apply()
            // Verify we have the permissions needed for background monitoring
            val hasLocation =
                    ContextCompat.checkSelfPermission(
                            ctx,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
            val hasBgLocation =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                            ContextCompat.checkSelfPermission(
                                    ctx,
                                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED
            if (!hasLocation || !hasBgLocation) {
                rejectWithError(
                        promise,
                        "PERMISSION_DENIED",
                        "Location permissions required for background monitoring. Call requestPermissionsAsync() first."
                )
                return@AsyncFunction
            }
            // Android 12+ requires BLUETOOTH_SCAN for BLE operations;
            // Android 14+ additionally requires it for connectedDevice foreground services.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasBtScan =
                        ContextCompat.checkSelfPermission(
                                ctx,
                                Manifest.permission.BLUETOOTH_SCAN
                        ) == PackageManager.PERMISSION_GRANTED
                val hasBtConnect =
                        ContextCompat.checkSelfPermission(
                                ctx,
                                Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                if (!hasBtScan || !hasBtConnect) {
                    rejectWithError(
                            promise,
                            "PERMISSION_DENIED",
                            "Bluetooth permissions required for beacon monitoring. Call requestPermissionsAsync() first."
                    )
                    return@AsyncFunction
                }
            }

            if (!registerEventReceiver()) {
                rejectWithError(
                        promise,
                        "RECEIVER_REGISTRATION_FAILED",
                        "Failed to register native beacon event delivery"
                )
                return@AsyncFunction
            }
            try {
                BeaconForegroundService.start(ctx)
            } catch (e: Exception) {
                unregisterEventReceiver()
                rejectWithError(
                        promise,
                        "SERVICE_START_FAILED",
                        "Failed to start monitoring service: ${e.message}",
                        e
                )
                return@AsyncFunction
            }
            promise.resolve(null)
        }

        Function("setNotificationConfig") { config: Map<String, Any?> ->
            val ctx = appContext.reactContext ?: return@Function
            ctx.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(NOTIFICATION_CONFIG_KEY, mapToJson(config).toString())
                    .apply()
        }

        Function("setBeaconNotificationConfig") { config: Map<String, Any?> ->
            val ctx = appContext.reactContext ?: return@Function
            updateNotificationSection(
                    ctx,
                    "beacons",
                    config,
                    setOf("events", "foregroundService", "channel")
            )
        }

        AsyncFunction("stopMonitoring") { promise: Promise ->
            val ctx =
                    appContext.reactContext
                            ?: run {
                                rejectWithError(
                                        promise,
                                        "NO_CONTEXT",
                                        "React context is not available"
                                )
                                return@AsyncFunction
                            }
            BeaconForegroundService.stop(ctx)
            // Clear persisted monitoring options so a later start begins from defaults (matches
            // iOS).
            ctx.getSharedPreferences(MONITORING_OPTIONS_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
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
                val context =
                        appContext.reactContext
                                ?: run {
                                    promise.resolve(false)
                                    return@AsyncFunction
                                }
                val foregroundGranted =
                        foreground.all {
                            ContextCompat.checkSelfPermission(context, it) ==
                                    PackageManager.PERMISSION_GRANTED
                        }
                val backgroundGranted =
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                                ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED
                promise.resolve(foregroundGranted && backgroundGranted)
                return@AsyncFunction
            }

            permissionsManager.askForPermissions(
                    { results ->
                        val fgGranted =
                                foreground.all { perm ->
                                    results[perm]?.status == PermissionsStatus.GRANTED
                                }
                        if (!fgGranted) {
                            promise.resolve(false)
                            return@askForPermissions
                        }
                        // Step 2: request background location (Android 10+)
                        // Must be requested separately after foreground location is granted
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            permissionsManager.askForPermissions(
                                    { bgResults ->
                                        val bgGranted =
                                                bgResults[
                                                                Manifest.permission
                                                                        .ACCESS_BACKGROUND_LOCATION]
                                                        ?.status == PermissionsStatus.GRANTED
                                        promise.resolve(bgGranted)
                                    },
                                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            )
                        } else {
                            promise.resolve(true)
                        }
                    },
                    *foreground.toTypedArray()
            )
        }

        Function("enableEventLogging") {
            val ctx =
                    appContext.reactContext
                            ?: throw IllegalStateException("React context is not available")
            if (eventLogger == null) {
                eventLogger = BeaconEventLogger(ctx)
            }
            BeaconEventLogger.setLoggingEnabled(ctx, true)
        }

        Function("disableEventLogging") {
            appContext.reactContext?.let { BeaconEventLogger.setLoggingEnabled(it, false) }
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

        Function("clearEventLogs") { getOrCreateEventLogger()?.clearEvents() }

        Function("destroyEventLogs") {
            val ctx = appContext.reactContext ?: return@Function null
            BeaconEventLogger.setLoggingEnabled(ctx, false)
            eventLogger?.close()
            eventLogger = null
            BeaconEventLogger.deleteLogDatabase(ctx)
        }

        // MARK: - API Forwarding

        Function("setApiEndpoint") { url: String, apiKey: String?, id: String? ->
            val forwarder =
                    getOrCreateApiForwarder()
                            ?: throw IllegalStateException("React context is not available")
            forwarder.configure(url, apiKey, id)
        }

        Function("getApiEndpoint") {
            val forwarder =
                    getOrCreateApiForwarder()
                            ?: throw IllegalStateException("React context is not available")
            forwarder.getConfig()
        }

        Function("getMonitoringConfig") {
            val ctx =
                    appContext.reactContext
                            ?: throw IllegalStateException("React context is not available")
            val optPrefs = ctx.getSharedPreferences(MONITORING_OPTIONS_PREFS, Context.MODE_PRIVATE)
            buildMap<String, Any?> {
                put("isMonitoring", BeaconForegroundService.isMonitoringActive(ctx))
                optPrefs.getString(MONITORING_OPT_MAX_DISTANCE, null)?.toDoubleOrNull()?.let {
                    put("maxDistance", it)
                }
                optPrefs.getString(MONITORING_OPT_EXIT_DISTANCE, null)?.toDoubleOrNull()?.let {
                    put("exitDistance", it)
                }
                if (optPrefs.contains(MONITORING_OPT_MIN_RSSI))
                        put("minRssi", optPrefs.getInt(MONITORING_OPT_MIN_RSSI, DEFAULT_MIN_RSSI))
                optPrefs.getString(MONITORING_OPT_LEVEL, null)?.let { put("level", it) }
                optPrefs.getString(MONITORING_OPT_EXIT_TIMEOUT_SECONDS, null)
                        ?.toDoubleOrNull()
                        ?.let { put("exitTimeoutSeconds", it) }
                val json =
                        ctx.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                                .getString(NOTIFICATION_CONFIG_KEY, null)
                if (json != null) {
                    try {
                        put("notifications", jsonToMap(org.json.JSONObject(json)))
                    } catch (_: Exception) {
                        /* ignore malformed JSON */
                    }
                }
            }
        }

        Function("getMonitoredDeviceState") { identifier: String ->
            buildMonitoredDeviceState(identifier)
        }

        Function("getMonitoredDeviceStates") { buildMonitoredDeviceStates() }

        // MARK: - Battery Optimization

        Function("isBatteryOptimizationExempt") {
            val ctx = appContext.reactContext ?: return@Function false
            val pm =
                    ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                            ?: return@Function false
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        }

        AsyncFunction("requestBatteryOptimizationExemption") { promise: Promise ->
            val ctx =
                    appContext.reactContext
                            ?: run {
                                rejectWithError(
                                        promise,
                                        "NO_CONTEXT",
                                        "React context is not available"
                                )
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
                val intent =
                        Intent(
                                        android.provider.Settings
                                                .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                )
                                .apply {
                                    data = Uri.parse("package:${ctx.packageName}")
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                ctx.startActivity(intent)
                // The system dialog is fire-and-forget; we cannot observe the result
                // from a non-Activity context. Resolve true to indicate the dialog was shown.
                promise.resolve(true)
            } catch (e: Exception) {
                // The TS API promises `false` on failure rather than a rejection.
                emitBeaconError(
                        "BATTERY_OPT_ERROR",
                        "Failed to open battery optimization settings: ${e.message}"
                )
                promise.resolve(false)
            }
        }

        OnCreate {
            // If the foreground service is already monitoring (process relaunch
            // after death), re-attach the broadcast bridge so JS receives beacon
            // events again without requiring another startMonitoring() call.
            appContext.reactContext?.let { ctx ->
                if (BeaconForegroundService.isMonitoringActive(ctx)) registerEventReceiver()
            }
        }

        OnDestroy {
            with(this@ExpoBeaconModule) {
                unregisterEventReceiver()
                eventLogger?.close()
                eventLogger = null
                apiForwarder?.shutdown()
                apiForwarder = null
                val hadOneShotScan = iBeaconScan.promise != null || eddystoneScan.promise != null
                disposeOneShotScan(iBeaconScan)
                disposeOneShotScan(eddystoneScan)
                if (hadOneShotScan) stopScanRangingIfIdle()
                moduleScope.cancel()
                if (continuousScanActive) {
                    continuousScanActive = false
                    try {
                        beaconManager.stopRangingBeaconsInRegion(continuousScanRegion)
                    } catch (_: Throwable) {}
                    try {
                        beaconManager.removeRangeNotifier(continuousScanRangeNotifier)
                    } catch (_: Throwable) {}
                }
                if (isBoundForScan) {
                    try {
                        beaconManager.unbind(this@ExpoBeaconModule)
                    } catch (_: Throwable) {}
                    isBoundForScan = false
                    scanServiceConnected = false
                }
            }
        }
    }

    // --- One-shot scan helpers (shared by iBeacon and Eddystone scans) ---

    private fun startOneShotScan(state: OneShotScanState, scanDurationMs: Int, promise: Promise) {
        synchronized(state.results) { state.results.clear() }
        state.promise = promise
        try {
            beaconManager.addRangeNotifier(state.notifier)
            if (!isBoundForScan) {
                isBoundForScan = true
                beaconManager.bind(this@ExpoBeaconModule)
            } else if (scanServiceConnected) {
                startScanRanging()
                if (state.promise == null) return
            }

            // Resolve after duration.
            state.job =
                    moduleScope.launch {
                        delay(scanDurationMs.toLong())
                        finishOneShotScan(state)
                    }
        } catch (error: Throwable) {
            failActiveOneShotScans(error)
        }
    }

    private fun finishOneShotScan(state: OneShotScanState) {
        state.job = null
        val promise = state.promise ?: return
        try {
            val results =
                    synchronized(state.results) {
                        if (state.eddystone) {
                            state.results
                                    .distinctBy {
                                        if (it.identifiers.size >= 2) "uid:${it.id1}:${it.id2}"
                                        else "url:${it.id1}"
                                    }
                                    .map { eddystoneBeaconToMap(it) }
                        } else {
                            state.results.distinctBy { "${it.id1}:${it.id2}:${it.id3}" }.map {
                                iBeaconToMap(it)
                            }
                        }
                    }
            disposeOneShotScan(state)
            stopScanRangingIfIdle()
            unbindIfIdle()
            promise.resolve(results)
        } catch (error: Throwable) {
            disposeOneShotScan(state)
            stopScanRangingIfIdle()
            unbindIfIdle()
            val message =
                    "Failed to finish beacon scan: ${error.message ?: error.javaClass.simpleName}"
            promise.reject("SCAN_ERROR", message, error)
            emitBeaconError("SCAN_ERROR", message)
        }
    }

    private fun cancelOneShotScan(state: OneShotScanState) {
        val promise = state.promise ?: return
        disposeOneShotScan(state)
        stopScanRangingIfIdle()
        unbindIfIdle()
        // A user-initiated cancel is not an error — reject without an onBeaconError event.
        promise.reject("SCAN_CANCELLED", "Scan was cancelled", null)
    }

    private fun disposeOneShotScan(state: OneShotScanState) {
        val notifierWasRegistered = state.promise != null
        state.promise = null
        state.job?.cancel()
        state.job = null
        if (notifierWasRegistered) {
            try {
                beaconManager.removeRangeNotifier(state.notifier)
            } catch (_: Throwable) {}
        }
        synchronized(state.results) { state.results.clear() }
        if (!state.eddystone) scanUuidFilter = emptySet()
    }

    private fun failActiveOneShotScans(error: Throwable) {
        val promises =
                listOf(iBeaconScan, eddystoneScan).mapNotNull { state ->
                    state.promise?.also { disposeOneShotScan(state) }
                }
        try {
            beaconManager.stopRangingBeaconsInRegion(SCAN_REGION)
        } catch (_: Throwable) {}
        unbindIfIdle()
        val message = "Failed to scan for beacons: ${error.message ?: error.javaClass.simpleName}"
        promises.forEach { it.reject("SCAN_ERROR", message, error) }
        emitBeaconError("SCAN_ERROR", message)
    }

    /** Stop ranging the shared SCAN_REGION once neither one-shot scan is active. */
    private fun stopScanRangingIfIdle() {
        if (iBeaconScan.promise == null && eddystoneScan.promise == null) {
            try {
                beaconManager.stopRangingBeaconsInRegion(SCAN_REGION)
            } catch (_: Throwable) {}
        }
    }

    // --- BeaconConsumer (for scan binding) ---

    override fun onBeaconServiceConnect() {
        if (!isBoundForScan) return
        scanServiceConnected = true
        if (iBeaconScan.promise != null || eddystoneScan.promise != null) startScanRanging()
        if (continuousScanActive) startContinuousRanging()
    }

    override fun getApplicationContext(): Context {
        return appContext.reactContext
                ?: throw IllegalStateException("React context is not available")
    }

    private fun startScanRanging() {
        try {
            beaconManager.startRangingBeaconsInRegion(SCAN_REGION)
        } catch (error: Throwable) {
            failActiveOneShotScans(error)
        }
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
                val map = iBeaconToMap(beacon)
                logBeaconEvent("onBeaconFound", map)
                sendEvent("onBeaconFound", map)
            }
        }
    }

    private fun startContinuousRanging() {
        try {
            beaconManager.startRangingBeaconsInRegion(continuousScanRegion)
        } catch (error: Throwable) {
            continuousScanActive = false
            try {
                beaconManager.removeRangeNotifier(continuousScanRangeNotifier)
            } catch (_: Throwable) {}
            unbindIfIdle()
            emitBeaconError(
                    "SCAN_ERROR",
                    "Failed to start continuous beacon scanning: ${error.message}"
            )
        }
    }

    private fun iBeaconToMap(beacon: Beacon): Map<String, Any?> {
        return buildMap {
            put("uuid", beacon.id1.toString().uppercase())
            put("major", beacon.id2.toInt())
            put("minor", beacon.id3.toInt())
            put("rssi", beacon.rssi)
            put("distance", beacon.distance)
            put("txPower", beacon.txPower)
            beacon.bluetoothName?.let { put("name", it) }
        }
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
        val suffixes =
                mapOf(
                        0x00.toByte() to ".com/",
                        0x01.toByte() to ".org/",
                        0x02.toByte() to ".edu/",
                        0x03.toByte() to ".net/",
                        0x04.toByte() to ".info/",
                        0x05.toByte() to ".biz/",
                        0x06.toByte() to ".gov/",
                        0x07.toByte() to ".com",
                        0x08.toByte() to ".org",
                        0x09.toByte() to ".edu",
                        0x0A.toByte() to ".net",
                        0x0B.toByte() to ".info",
                        0x0C.toByte() to ".biz",
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
                    @Suppress("UNCHECKED_CAST") json.put(key, mapToJson(value as Map<String, Any?>))
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

    private fun updateNotificationSection(
            context: Context,
            sectionKey: String,
            config: Map<String, Any?>,
            nestedKeys: Set<String>
    ) {
        val prefs = context.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
        val current =
                prefs.getString(NOTIFICATION_CONFIG_KEY, null)?.let { json ->
                    try {
                        JSONObject(json)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                }
                        ?: JSONObject()
        current.put(sectionKey, normalizeNotificationSection(config, nestedKeys))
        prefs.edit().putString(NOTIFICATION_CONFIG_KEY, current.toString()).apply()
    }

    private fun mergeNotificationConfig(context: Context, config: Map<String, Any?>) {
        val prefs = context.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
        val current =
                prefs.getString(NOTIFICATION_CONFIG_KEY, null)?.let { json ->
                    try {
                        JSONObject(json)
                    } catch (_: Exception) {
                        JSONObject()
                    }
                }
                        ?: JSONObject()
        val incoming = mapToJson(config)
        for (key in incoming.keys()) {
            current.put(key, incoming.opt(key))
        }
        prefs.edit().putString(NOTIFICATION_CONFIG_KEY, current.toString()).apply()
    }

    private fun normalizeNotificationSection(
            config: Map<String, Any?>,
            nestedKeys: Set<String>
    ): JSONObject {
        return if (config.keys.any { it in nestedKeys }) {
            mapToJson(config)
        } else {
            JSONObject().put("events", mapToJson(config))
        }
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            val value = obj.opt(key)
            map[key] =
                    when (value) {
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

    private fun hasForegroundScanPermissions(context: Context): Boolean {
        val hasLocation =
                ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun validateTimeoutSeconds(timeoutSeconds: Int?) {
        if (timeoutSeconds != null && timeoutSeconds <= 0) {
            throw expo.modules.kotlin.exception.CodedException(
                    "INVALID_TIMEOUT",
                    "timeoutSeconds must be greater than 0",
                    null
            )
        }
    }

    /** Apply a pair/unpair mutation to an already-running native monitoring session. */
    private fun reconcileMonitoringAfterPairChange(identifier: String) {
        val context = appContext.reactContext ?: return
        BeaconForegroundService.reconcilePairedRegions(context, identifier)
    }

    // --- Shared Preferences helpers ---

    /** Returns true if any entry in [items] uses [identifier]. */
    private fun containsIdentifier(items: JSONArray, identifier: String): Boolean {
        return (0 until items.length()).any {
            items.getJSONObject(it).getString("identifier") == identifier
        }
    }

    private fun containsIBeaconIdentity(
            items: JSONArray,
            identifier: String,
            normalizedUuid: String,
            major: Int,
            minor: Int
    ): Boolean {
        return (0 until items.length()).any { index ->
            val item = items.getJSONObject(index)
            item.optString("identifier") != identifier &&
                    runCatching {
                                java.util.UUID.fromString(item.getString("uuid"))
                                        .toString()
                                        .uppercase()
                            }
                            .getOrNull() == normalizedUuid &&
                    item.optInt("major", -1) == major &&
                    item.optInt("minor", -1) == minor
        }
    }

    private fun containsEddystoneIdentity(
            items: JSONArray,
            identifier: String,
            namespace: String,
            instance: String
    ): Boolean {
        return (0 until items.length()).any { index ->
            val item = items.getJSONObject(index)
            item.optString("identifier") != identifier &&
                    item.optString("namespace").lowercase() == namespace &&
                    item.optString("instance").lowercase() == instance
        }
    }

    /**
     * Removes entries matching [identifier] from a paired JSON array, saves, and invalidates cache.
     */
    private fun removePairedEntry(
            prefs: SharedPreferences,
            key: String,
            loader: () -> JSONArray,
            identifier: String,
            cacheInvalidator: () -> Unit
    ) {
        val items = loader()
        val filtered =
                (0 until items.length()).map { items.getJSONObject(it) }.filter {
                    it.getString("identifier") != identifier
                }
        val arr = JSONArray()
        filtered.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
        cacheInvalidator()
    }

    private fun loadPairedBeaconsJson(): JSONArray {
        cachedPairedBeacons?.let {
            return it
        }
        val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        val result =
                try {
                    JSONArray(json)
                } catch (_: Exception) {
                    JSONArray()
                }
        cachedPairedBeacons = result
        return result
    }

    private fun loadPairedEddystonesJson(): JSONArray {
        cachedPairedEddystones?.let {
            return it
        }
        val json = eddystonePrefs.getString(EDDYSTONE_PREFS_KEY, "[]") ?: "[]"
        val result =
                try {
                    JSONArray(json)
                } catch (_: Exception) {
                    JSONArray()
                }
        cachedPairedEddystones = result
        return result
    }

    // --- Event receiver registration ---

    private fun registerEventReceiver(): Boolean {
        if (eventReceiver != null) return true
        val context = appContext.reactContext ?: return false
        val receiver = BeaconEventReceiver { eventName, params -> sendEvent(eventName, params) }
        val filter = IntentFilter(ACTION_BEACON_EVENT)
        return try {
            ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            )
            eventReceiver = receiver
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to register the native beacon event receiver", error)
            false
        }
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
        if (iBeaconScan.promise == null &&
                        eddystoneScan.promise == null &&
                        !continuousScanActive &&
                        isBoundForScan
        ) {
            try {
                beaconManager.unbind(this)
            } catch (_: Throwable) {
                // Binding may have failed before AltBeacon registered the consumer.
            } finally {
                isBoundForScan = false
                scanServiceConnected = false
            }
        }
    }

    private fun getOrCreateEventLogger(): BeaconEventLogger? {
        val context = appContext.reactContext ?: return null
        return eventLogger ?: BeaconEventLogger(context).also { eventLogger = it }
    }

    /** Log an event to SQLite if logging is enabled (prefs-checked per event, like the service). */
    private fun logBeaconEvent(eventType: String, params: Map<String, Any?>) {
        val context = appContext.reactContext ?: return
        if (!BeaconEventLogger.isLoggingEnabled(context)) return
        val identifier = params["identifier"] as? String
        getOrCreateEventLogger()?.logEvent(eventType, identifier, params)
    }

    private fun getOrCreateApiForwarder(): BeaconApiForwarder? {
        val context = appContext.reactContext ?: return null
        return apiForwarder ?: BeaconApiForwarder(context).also { apiForwarder = it }
    }

    /**
     * Emit an onBeaconError event through the full pipeline — SQLite log, remote API forwarder, and
     * the JS bridge — mirroring the service-side error path (and iOS sendLoggedEvent).
     */
    private fun emitBeaconError(code: String, message: String) {
        val params = mapOf<String, Any?>("identifier" to "", "code" to code, "message" to message)
        logBeaconEvent("onBeaconError", params)
        try {
            getOrCreateApiForwarder()?.forwardEvent(params, "onBeaconError")
        } catch (_: Throwable) {}
        sendEvent("onBeaconError", params)
    }

    /** Reject [promise] with [code]/[message] and emit the matching onBeaconError event. */
    private fun rejectWithError(
            promise: Promise,
            code: String,
            message: String,
            cause: Throwable? = null
    ) {
        promise.reject(code, message, cause)
        emitBeaconError(code, message)
    }


}
