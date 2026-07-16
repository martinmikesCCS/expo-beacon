

package expo.modules.beacon

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger
import org.altbeacon.beacon.*
import org.json.JSONArray

private const val CHANNEL_ID = "expo_beacon_channel"
private const val FOREGROUND_CHANNEL_ID = "expo_beacon_foreground_channel"
internal const val FOREGROUND_NOTIF_ID = 1001
/**
 * Base ID for per-beacon enter/exit notifications; incremented per unique region. With
 * FOREGROUND_NOTIF_ID at 1001, this allows up to 999 unique regions before ID collision. Sufficient
 * for real-world beacon deployments.
 */
private const val ENTER_EXIT_NOTIF_BASE_ID = 2000

class BeaconForegroundService : Service(), BeaconConsumer {

    data class MonitoringRuntimeState(val isEntered: Boolean, val distance: Double?)

    private lateinit var beaconManager: BeaconManager
    private val monitoredRegions = mutableListOf<Region>()

    /** Tracks whether onBeaconServiceConnect has fired for the current bind. */
    @Volatile private var serviceConnected = false

    // Distance filtering
    @Volatile private var maxDistance: Double? = null
    @Volatile private var exitDistance: Double? = null
    @Volatile private var minRssiThreshold: Int = DEFAULT_MIN_RSSI
    private val monitoredRegionIds = java.util.concurrent.CopyOnWriteArraySet<String>()
    private val enteredRegions = java.util.concurrent.CopyOnWriteArraySet<String>()
    private val lastSeenAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Hysteresis counters (synchronized on distanceLock)
    private val distanceLock = Any()
    private val enterCounters = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val exitCounters = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val missCounters = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Distance smoothing (EMA)
    private val smoothedDistances = java.util.concurrent.ConcurrentHashMap<String, Double>()

    // Notification ID counter for unique per-beacon notifications
    private val notifIdCounter = AtomicInteger(0)
    private val notifIdMap = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Debounce guard: prevent loadAndMonitorRegions() from running more than once per 500 ms.
    // This protects against rapid double-invocations when JS calls stopMonitoring/startMonitoring
    // in quick succession during activity lifecycle transitions (e.g. onHostPause → onHostResume),
    // which would otherwise stop and restart all AltBeacon regions within milliseconds.
    @Volatile private var lastLoadRegionsMs: Long = 0L
    // Set while a trailing-edge loadAndMonitorRegions() re-run is queued on timeoutHandler.
    @Volatile private var pendingLoadRegions = false

    // Regions ranged for distance events and distance-based enter/exit hysteresis.
    private val rangedRegions = java.util.concurrent.CopyOnWriteArraySet<Region>()

    // Timeout timers — fire once after beacon stays in range for configured duration
    private val timeoutHandler = Handler(Looper.getMainLooper())
    // Kept separate because region reloads intentionally clear timer callbacks.
    private val controlHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnables = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
    // Per-beacon timeout seconds lookup (identifier → seconds), loaded from paired data
    private val beaconTimeouts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // Inactivity timers — start timeout countdown when no BLE readings for 60 s
    private val inactivityRunnables = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
    private var eventLogger: BeaconEventLogger? = null
    private var apiForwarder: BeaconApiForwarder? = null
    // Event level: "all" emits distance + enter/exit/timeout; "events" suppresses distance.
    @Volatile private var eventLevel: String = "all"
    // Seconds of silence after last valid sighting before a disappearance-based exit fires.
    @Volatile private var exitTimeoutMs: Long = (DEFAULT_EXIT_TIMEOUT_SECONDS * 1000.0).toLong()


    override fun onCreate() {
        super.onCreate()
        activeService = this
        ensureForegroundNotificationChannel(this)
        ensureNotificationChannel(this)
        apiForwarder = BeaconApiForwarder(this)
        beaconManager =
                BeaconManager.getInstanceForApplication(this).also { manager ->
                    BeaconParsers.ensureRegistered(manager)
                    try {
                        manager.setEnableScheduledScanJobs(false)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "setEnableScheduledScanJobs failed", e)
                    }
                    manager.setBackgroundBetweenScanPeriod(MONITORING_BETWEEN_SCAN_PERIOD_MS)
                    manager.setBackgroundScanPeriod(MONITORING_SCAN_PERIOD_MS)
                    manager.setForegroundScanPeriod(MONITORING_SCAN_PERIOD_MS)
                    manager.setForegroundBetweenScanPeriod(MONITORING_BETWEEN_SCAN_PERIOD_MS)
                }
        // Increase AltBeacon's region exit period so didExitRegion doesn't fire
        // prematurely during brief BLE scan gaps.
        BeaconManager.setRegionExitPeriod(REGION_EXIT_PERIOD_MS)
        // NOTE: We intentionally do NOT call enableForegroundServiceScanning().
        // Our BeaconForegroundService is already a foreground service (startForeground
        // in onStartCommand). AltBeacon's internal BeaconService runs in the same
        // process and inherits the elevated priority. Calling enable/disable on the
        // shared singleton causes crashes when the ExpoBeaconModule has an active
        // scan bound to the same BeaconManager.

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        FOREGROUND_NOTIF_ID,
                        buildForegroundNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(FOREGROUND_NOTIF_ID, buildForegroundNotification())
            }
        } catch (e: Exception) {
            // SecurityException on Android 14+ if BT runtime permissions weren't yet granted,
            // or ForegroundServiceStartNotAllowedException on Android 12+ / Android 17 beta
            // if the service start window was missed (e.g. BT not yet initialized at boot).
            val retryCount = intent?.getIntExtra(EXTRA_RETRY_COUNT, 0) ?: 0
            Log.e(TAG, "startForeground failed (retry=$retryCount) — stopping service", e)
            sendErrorBroadcast(
                    null,
                    "SERVICE_START_FAILED",
                    "startForeground failed (retry=$retryCount): ${e.message}"
            )
            // Runtime permission revocation is not transient. Clear only the affected desired
            // state so public status does not remain "enabled" while the service can never start.
            if (isMonitoringActive(this) && !hasBeaconMonitoringPermissions(this)) {
                setMonitoringActive(this, false)
                clearAllTimeoutDeadlines(this)
            }
            // Schedule a capped retry while persisted beacon monitoring is active.
            if (retryCount < MAX_STARTFOREGROUND_RETRIES && isMonitoringActive(this)) {
                scheduleServiceRetry(retryCount + 1)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isMonitoringActive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (serviceConnected) {
            // Already bound from a prior onStartCommand — reload regions directly
            // so that re-starting monitoring from JS always takes effect.
            loadAndMonitorRegions()
        } else {
            try {
                beaconManager.bind(this)
            } catch (error: Throwable) {
                Log.e(TAG, "Failed to bind the beacon scanner", error)
                setMonitoringActive(this, false)
                clearAllTimeoutDeadlines(this)
                sendErrorBroadcast(
                        null,
                        "MONITORING_FAILED",
                        "Failed to start beacon monitoring: ${error.message}"
                )
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /** Schedule a permission-free, inexact retry through the app-private recovery receiver. */
    private fun scheduleServiceRetry(retryCount: Int) {
        BootReceiver.scheduleServiceRetry(this, retryCount)
    }

    override fun onBeaconServiceConnect() {
        if (!isMonitoringActive(this)) {
            // stopMonitoring() raced the AltBeacon bind — drop the connection
            // instead of arming regions that were just disabled.
            try {
                beaconManager.unbind(this)
            } catch (_: Throwable) {}
            return
        }
        serviceConnected = true
        beaconManager.addMonitorNotifier(monitorNotifier)
        beaconManager.addRangeNotifier(rangeNotifier)
        loadAndMonitorRegions()
    }

    /**
     * (Re-)read monitoring options from prefs. Called on every region load so a second
     * startMonitoring on a live bound service picks up new options.
     */
    private fun applyMonitoringOptions() {
        val optPrefs = getSharedPreferences(MONITORING_OPTIONS_PREFS, Context.MODE_PRIVATE)
        maxDistance = optPrefs.getString(MONITORING_OPT_MAX_DISTANCE, null)?.toDoubleOrNull()
        exitDistance = optPrefs.getString(MONITORING_OPT_EXIT_DISTANCE, null)?.toDoubleOrNull()
        minRssiThreshold = optPrefs.getInt(MONITORING_OPT_MIN_RSSI, DEFAULT_MIN_RSSI)
        eventLevel = optPrefs.getString(MONITORING_OPT_LEVEL, "all") ?: "all"
        exitTimeoutMs =
                ((optPrefs.getString(MONITORING_OPT_EXIT_TIMEOUT_SECONDS, null)?.toDoubleOrNull()
                                ?: DEFAULT_EXIT_TIMEOUT_SECONDS) * 1000.0).toLong()
    }

    private fun loadAndMonitorRegions() {
        applyMonitoringOptions()
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = now - lastLoadRegionsMs
        if (elapsed < LOAD_REGIONS_DEBOUNCE_MS) {
            // Trailing-edge re-run so a debounced call is deferred, not dropped.
            if (!pendingLoadRegions) {
                pendingLoadRegions = true
                timeoutHandler.postDelayed(
                        {
                            pendingLoadRegions = false
                            loadAndMonitorRegions()
                        },
                        LOAD_REGIONS_DEBOUNCE_MS - elapsed
                )
            }
            Log.d(
                    TAG,
                    "loadAndMonitorRegions: debounced (${elapsed}ms after last load) — re-running on trailing edge"
            )
            return
        }
        lastLoadRegionsMs = now
        pendingLoadRegions = false

        if (!hasBeaconMonitoringPermissions(this)) {
            abortMonitoringStartup(
                    "PERMISSION_DENIED",
                    "Beacon monitoring permissions were revoked before scanning started"
            )
            return
        }

        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        val beacons =
                try {
                    JSONArray(json)
                } catch (_: Exception) {
                    JSONArray()
                }

        // Load paired Eddystones
        val eddystonePrefs: SharedPreferences =
                getSharedPreferences(EDDYSTONE_PREFS_NAME, Context.MODE_PRIVATE)
        val eddystoneJson = eddystonePrefs.getString(EDDYSTONE_PREFS_KEY, "[]") ?: "[]"
        val eddystones =
                try {
                    JSONArray(eddystoneJson)
                } catch (_: Exception) {
                    JSONArray()
                }

        // Build timeout lookup from paired beacon data
        beaconTimeouts.clear()
        for (i in 0 until beacons.length()) {
            val b = beacons.getJSONObject(i)
            val id = b.getString("identifier")
            if (b.has("timeoutSeconds")) {
                val secs = b.optInt("timeoutSeconds", 0)
                if (secs > 0) beaconTimeouts[id] = secs
            }
        }
        for (i in 0 until eddystones.length()) {
            val e = eddystones.getJSONObject(i)
            val id = e.getString("identifier")
            if (e.has("timeoutSeconds")) {
                val secs = e.optInt("timeoutSeconds", 0)
                if (secs > 0) beaconTimeouts[id] = secs
            }
        }

        // Stop previous regions and distance-log ranging
        rangedRegions.forEach {
            try {
                beaconManager.stopRangingBeaconsInRegion(it)
            } catch (_: Throwable) {}
        }
        rangedRegions.clear()
        monitoredRegions.forEach {
            try {
                beaconManager.stopMonitoringBeaconsInRegion(it)
            } catch (_: Throwable) {}
        }
        monitoredRegions.clear()
        monitoredRegionIds.clear()
        timeoutHandler.removeCallbacksAndMessages(null)
        timeoutRunnables.clear()
        inactivityRunnables.clear()
        synchronized(distanceLock) {
            enterCounters.clear()
            exitCounters.clear()
            missCounters.clear()
            smoothedDistances.clear()
        }
        // NOTE: enteredRegions is intentionally NOT cleared here.
        // Clearing it on every reload (e.g. START_STICKY restart or repeated
        // startMonitoring calls) would reset the "already entered" state and
        // cause the hysteresis to fire another ENTER event for beacons that
        // are still nearby. Stale entries are pruned below after new regions
        // are determined.

        var fatalSecurityFailure = false

        // iBeacon regions
        for (i in 0 until beacons.length()) {
            val b = beacons.getJSONObject(i)
            val region =
                    Region(
                            b.getString("identifier"),
                            Identifier.parse(b.getString("uuid")),
                            Identifier.fromInt(b.getInt("major")),
                            Identifier.fromInt(b.getInt("minor"))
                    )
            monitoredRegions.add(region)
            monitoredRegionIds.add(region.uniqueId)
            try {
                beaconManager.startMonitoringBeaconsInRegion(region)
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to start monitoring iBeacon region ${region.uniqueId}", e)
                sendErrorBroadcast(
                        region.uniqueId,
                        "MONITORING_FAILED",
                        "Failed to start monitoring iBeacon region ${region.uniqueId}"
                )
            } catch (e: SecurityException) {
                fatalSecurityFailure = true
                // Android 17+ may throw SecurityException if BLUETOOTH_SCAN/CONNECT were
                // not held at the exact moment monitoring starts.
                Log.e(
                        TAG,
                        "Security exception starting monitoring for ${region.uniqueId} — check BT permissions",
                        e
                )
                sendErrorBroadcast(
                        region.uniqueId,
                        "SECURITY_EXCEPTION",
                        "Security exception starting monitoring for ${region.uniqueId} — check BT permissions"
                )
            }
            // Start ranging this region for distance logging
            if (rangedRegions.add(region)) {
                try {
                    beaconManager.startRangingBeaconsInRegion(region)
                } catch (e: RemoteException) {
                    rangedRegions.remove(region)
                    Log.e(TAG, "Failed to start ranging iBeacon region ${region.uniqueId}", e)
                    sendErrorBroadcast(
                            region.uniqueId,
                            "RANGING_FAILED",
                            "Failed to start ranging iBeacon region ${region.uniqueId}"
                    )
                } catch (e: SecurityException) {
                    fatalSecurityFailure = true
                    rangedRegions.remove(region)
                    Log.e(
                            TAG,
                            "Security exception starting ranging for ${region.uniqueId} — check BT permissions",
                            e
                    )
                    sendErrorBroadcast(
                            region.uniqueId,
                            "SECURITY_EXCEPTION",
                            "Security exception starting ranging for ${region.uniqueId} — check BT permissions"
                    )
                }
            }
        }

        // Eddystone-UID regions
        for (i in 0 until eddystones.length()) {
            val e = eddystones.getJSONObject(i)
            val identifier = e.getString("identifier")
            val namespace = e.getString("namespace")
            val instance = e.getString("instance")
            val region =
                    Region(
                            identifier,
                            Identifier.parse("0x$namespace"),
                            Identifier.parse("0x$instance"),
                            null
                    )
            monitoredRegions.add(region)
            monitoredRegionIds.add(region.uniqueId)
            try {
                beaconManager.startMonitoringBeaconsInRegion(region)
            } catch (ex: RemoteException) {
                Log.e(TAG, "Failed to start monitoring Eddystone region $identifier", ex)
                sendErrorBroadcast(
                        identifier,
                        "MONITORING_FAILED",
                        "Failed to start monitoring Eddystone region $identifier"
                )
            } catch (ex: SecurityException) {
                fatalSecurityFailure = true
                Log.e(
                        TAG,
                        "Security exception starting monitoring for Eddystone $identifier — check BT permissions",
                        ex
                )
                sendErrorBroadcast(
                        identifier,
                        "SECURITY_EXCEPTION",
                        "Security exception starting monitoring for Eddystone $identifier — check BT permissions"
                )
            }
            if (rangedRegions.add(region)) {
                try {
                    beaconManager.startRangingBeaconsInRegion(region)
                } catch (ex: RemoteException) {
                    rangedRegions.remove(region)
                    Log.e(TAG, "Failed to start ranging Eddystone region $identifier", ex)
                    sendErrorBroadcast(
                            identifier,
                            "RANGING_FAILED",
                            "Failed to start ranging Eddystone region $identifier"
                    )
                } catch (ex: SecurityException) {
                    fatalSecurityFailure = true
                    rangedRegions.remove(region)
                    Log.e(
                            TAG,
                            "Security exception starting ranging for Eddystone $identifier — check BT permissions",
                            ex
                    )
                    sendErrorBroadcast(
                            identifier,
                            "SECURITY_EXCEPTION",
                            "Security exception starting ranging for Eddystone $identifier — check BT permissions"
                    )
                }
            }
        }

        if (fatalSecurityFailure) {
            abortMonitoringStartup(
                    "SECURITY_EXCEPTION",
                    "Android denied beacon scanning while regions were starting; check runtime permissions"
            )
            return
        }

        restoreTimeoutDeadlines()
        lastSeenAtMs.keys.retainAll(monitoredRegionIds)

        // Stop the foreground service when the final paired region is removed.
        if (monitoredRegions.isEmpty()) {
            enteredRegions.clear()
            setMonitoringActive(this, false)
            clearAllTimeoutDeadlines(this)
            Log.d(TAG, "No paired beacons; stopping idle foreground service")
            stopSelf()
        } else {
            // Prune enteredRegions for regions that are no longer monitored            // Prune enteredRegions for regions that are no longer monitored
            enteredRegions.retainAll(monitoredRegionIds)
        }
    }

    private val monitorNotifier =
            object : MonitorNotifier {
                override fun didEnterRegion(region: Region) {
                    // Enter is synthesized from ranging so distance and enter/exit stay in sync.
                }

                override fun didExitRegion(region: Region) {
                    if (!monitoredRegionIds.contains(region.uniqueId)) return
                    if (wasSeenRecently(region.uniqueId)) {
                        Log.d(
                                TAG,
                                "Ignoring stale didExitRegion for ${region.uniqueId}; beacon was seen by ranging recently"
                        )
                        return
                    }

                    lastSeenAtMs.remove(region.uniqueId)

                    // Ranging-based hysteresis handles exit in the normal case. If the OS
                    // fires didExitRegion after ranging has already stopped, emit exit as a
                    // safety net only if the region was previously in the entered state.
                    val wasEntered = enteredRegions.remove(region.uniqueId)
                    synchronized(distanceLock) {
                        enterCounters.remove(region.uniqueId)
                        exitCounters.remove(region.uniqueId)
                        missCounters.remove(region.uniqueId)
                    }
                    if (wasEntered) {
                        synchronized(distanceLock) { smoothedDistances.remove(region.uniqueId) }
                        sendBeaconBroadcast(region, "exit", -1.0)
                        showEnterExitNotification(region, "exit")
                        // OS-level exit safety net — cancel inactivity timer and start the timeout
                        // clock.
                        cancelInactivity(region.uniqueId)
                        scheduleTimeoutIfConfigured(region)
                    }
                }

                override fun didDetermineStateForRegion(state: Int, region: Region) {
                    // Intentionally empty — enter/exit handled by didEnterRegion/didExitRegion.
                }
            }

    // Single source of truth for distance-based enter/exit with hysteresis.
    // Processes only actual monitoring regions and handles exit via miss counting
    // when beacons disappear. State transitions are computed under distanceLock;
    // the slow side effects (SQLite insert, API enqueue, broadcast, notification)
    // run after the lock is released.
    private val rangeNotifier = RangeNotifier { beacons, region ->
        val maxDist = maxDistance
        if (!monitoredRegionIds.contains(region.uniqueId)) return@RangeNotifier

        val beacon =
                beacons.filter { it.distance >= 0 && it.rssi >= minRssiThreshold }.minByOrNull {
                    it.distance
                }
        val pendingDistance =
                beacon?.takeIf { eventLevel == "all" }?.let { it.distance to it.rssi }

        // Pending transition to emit after the lock is released: (eventType, distance, rssi).
        var pendingEvent: Triple<String, Double, Int>? = null
        synchronized(distanceLock) {
            if (beacon != null) {
                // Got a valid reading — reset miss counter
                lastSeenAtMs[region.uniqueId] = SystemClock.elapsedRealtime()
                missCounters[region.uniqueId] = 0
                // Valid BLE reading — reset inactivity timer.
                rescheduleInactivity(region)

                // Apply EMA smoothing; jump resets EMA to the new value
                val smoothed = smoothDistance(region.uniqueId, beacon.distance)

                when (evaluateDistanceHysteresis(region.uniqueId, smoothed, maxDist)) {
                    HysteresisAction.ENTER -> {
                        enteredRegions.add(region.uniqueId)
                        pendingEvent = Triple("enter", beacon.distance, beacon.rssi)
                    }
                    HysteresisAction.EXIT -> {
                        enteredRegions.remove(region.uniqueId)
                        smoothedDistances.remove(region.uniqueId)
                        pendingEvent = Triple("exit", beacon.distance, beacon.rssi)
                    }
                    HysteresisAction.NONE -> {}
                }
            } else {
                // No valid beacon reading — track consecutive misses for disappearance-based
                // exit detection. enterCounters/exitCounters are intentionally NOT reset here.
                // On Android 17+ (API 37) the BLE scan callbacks are more intermittent: valid
                // readings are interspersed with occasional null cycles even when the beacon is
                // nearby. Resetting direction counters on every null would prevent the hysteresis
                // from ever accumulating to ENTER_HYSTERESIS_COUNT, breaking enter/exit entirely
                // while
                // still allowing distance events (which fire on each individual valid reading).
                // Direction counters are reset by evaluateDistanceHysteresis when a valid reading
                // contradicts the current direction (e.g., in-range reading resets exitCounters).
                val count = (missCounters[region.uniqueId] ?: 0) + 1
                missCounters[region.uniqueId] = count

                val lastSeen = lastSeenAtMs[region.uniqueId]
                val silentMs =
                        if (lastSeen != null) SystemClock.elapsedRealtime() - lastSeen
                        else Long.MAX_VALUE
                if (enteredRegions.contains(region.uniqueId) && silentMs >= exitTimeoutMs) {
                    enteredRegions.remove(region.uniqueId)
                    missCounters[region.uniqueId] = 0
                    enterCounters[region.uniqueId] = 0
                    exitCounters[region.uniqueId] = 0
                    smoothedDistances.remove(region.uniqueId)
                    pendingEvent = Triple("exit", -1.0, 0)
                }
            }
        }

        pendingDistance?.let { (distance, rssi) ->
            sendBeaconBroadcast(region, "distance", distance, rssi)
        }
        pendingEvent?.let { (eventType, distance, rssi) ->
            sendBeaconBroadcast(region, eventType, distance, rssi)
            showEnterExitNotification(region, eventType)
            if (eventType == "enter") {
                // Beacon returned — cancel any running timeout timer.
                cancelTimeout(region.uniqueId)
            } else {
                // Beacon left — cancel inactivity timer and start the timeout clock.
                cancelInactivity(region.uniqueId)
                scheduleTimeoutIfConfigured(region)
            }
        }
    }

    // MARK: - Distance-based enter/exit hysteresis

    private enum class HysteresisAction {
        NONE,
        ENTER,
        EXIT
    }

    /**
     * Apply exponential moving average (EMA) smoothing to a raw distance reading. If the reading is
     * a large jump (> DISTANCE_JUMP_FACTOR), resets the EMA to the new value instead of rejecting
     * it — this ensures the hysteresis pipeline keeps receiving data and can fire exit events when
     * the user moves away from a beacon, rather than freezing because the EMA is stuck at the old
     * close-range value.
     */
    private fun smoothDistance(regionId: String, rawDistance: Double): Double {
        val prev = smoothedDistances[regionId]
        if (prev == null) {
            smoothedDistances[regionId] = rawDistance
            return rawDistance
        }
        // Jump guard: if the raw value is wildly different, reset EMA to the new reading
        // so the hysteresis pipeline keeps receiving data and can fire the exit event.
        val ratio = if (prev > 0.001) rawDistance / prev else rawDistance
        if (ratio > DISTANCE_JUMP_FACTOR || (ratio > 0 && ratio < 1.0 / DISTANCE_JUMP_FACTOR)) {
            smoothedDistances[regionId] = rawDistance
            return rawDistance
        }
        val smoothed = DISTANCE_EMA_ALPHA * rawDistance + (1 - DISTANCE_EMA_ALPHA) * prev
        smoothedDistances[regionId] = smoothed
        return smoothed
    }

    /**
     * Computes the effective exit distance from maxDistance and an optional explicit exitDistance.
     * Default: maxDistance + min(maxDistance × 0.5, 2.5).
     */
    private fun effectiveExitDistance(maxDist: Double): Double {
        exitDistance?.let {
            return it
        }
        return maxDist + minOf(maxDist * 0.5, 2.5)
    }

    /**
     * Evaluate distance-based enter/exit with hysteresis counters. Must be called within
     * synchronized(distanceLock). Mirrors [ExpoBeaconModule.swift evaluateDistanceHysteresis].
     */
    private fun evaluateDistanceHysteresis(
            regionId: String,
            distance: Double,
            maxDist: Double?
    ): HysteresisAction {
        if (maxDist == null) {
            exitCounters[regionId] = 0
            if (enteredRegions.contains(regionId)) {
                enterCounters[regionId] = 0
                return HysteresisAction.NONE
            }
            val count = (enterCounters[regionId] ?: 0) + 1
            enterCounters[regionId] = count
            if (count >= ENTER_HYSTERESIS_COUNT) {
                enterCounters[regionId] = 0
                return HysteresisAction.ENTER
            }
            return HysteresisAction.NONE
        }

        val exitDist = effectiveExitDistance(maxDist)
        if (distance <= maxDist) {
            // Inside enter threshold
            exitCounters[regionId] = 0
            val count = (enterCounters[regionId] ?: 0) + 1
            enterCounters[regionId] = count
            if (!enteredRegions.contains(regionId) && count >= ENTER_HYSTERESIS_COUNT) {
                enterCounters[regionId] = 0
                return HysteresisAction.ENTER
            }
        } else if (distance > exitDist) {
            // Outside exit threshold
            enterCounters[regionId] = 0
            val count = (exitCounters[regionId] ?: 0) + 1
            exitCounters[regionId] = count
            if (enteredRegions.contains(regionId) && count >= EXIT_HYSTERESIS_COUNT) {
                exitCounters[regionId] = 0
                return HysteresisAction.EXIT
            }
        } else {
            // In the hysteresis band (maxDist < distance <= exitDist) — do nothing
            enterCounters[regionId] = 0
            exitCounters[regionId] = 0
        }
        return HysteresisAction.NONE
    }

    private fun wasSeenRecently(regionId: String): Boolean {
        val lastSeen = lastSeenAtMs[regionId] ?: return false
        return SystemClock.elapsedRealtime() - lastSeen <= RECENT_RANGING_SIGHTING_GRACE_MS
    }

    // MARK: - Timeout timer helpers

    private fun scheduleTimeoutIfConfigured(region: Region) {
        val seconds = beaconTimeouts[region.uniqueId] ?: return
        // Cancel any existing timer so each exit resets the clock.
        cancelTimeout(region.uniqueId)
        val deadlineMs = System.currentTimeMillis() + seconds * 1000L
        timeoutDeadlinePrefs().edit().putLong(region.uniqueId, deadlineMs).apply()
        scheduleTimeoutAt(region, deadlineMs)
    }

    private fun scheduleTimeoutAt(region: Region, deadlineMs: Long) {
        val runnable = Runnable {
            timeoutRunnables.remove(region.uniqueId)
            timeoutDeadlinePrefs().edit().remove(region.uniqueId).apply()
            sendBeaconBroadcast(region, "timeout", -1.0)
            showEnterExitNotification(region, "timeout")
        }
        timeoutRunnables[region.uniqueId] = runnable
        timeoutHandler.postDelayed(
                runnable,
                (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
        )
    }

    private fun cancelTimeout(regionId: String, clearPersistedDeadline: Boolean = true) {
        timeoutRunnables.remove(regionId)?.let { timeoutHandler.removeCallbacks(it) }
        if (clearPersistedDeadline) {
            timeoutDeadlinePrefs().edit().remove(regionId).apply()
        }
    }

    private fun abortMonitoringStartup(code: String, message: String) {
        Log.e(TAG, message)
        sendErrorBroadcast(null, code, message)
        setMonitoringActive(this, false)
        clearAllTimeoutDeadlines(this)
        disableMonitoringInternal(clearPersistedTimeouts = true)
        stopSelf()
    }

    private fun timeoutDeadlinePrefs(): SharedPreferences =
            getSharedPreferences(TIMEOUT_DEADLINE_PREFS, Context.MODE_PRIVATE)

    /** Restore post-exit timeout deadlines after a region reload or process recreation. */
    private fun restoreTimeoutDeadlines() {
        val regionsById = monitoredRegions.associateBy { it.uniqueId }
        val prefs = timeoutDeadlinePrefs()
        val editor = prefs.edit()
        prefs.all.forEach { (regionId, rawDeadline) ->
            val region = regionsById[regionId]
            val deadline = (rawDeadline as? Number)?.toLong()
            if (region == null || deadline == null || !beaconTimeouts.containsKey(regionId)) {
                editor.remove(regionId)
                return@forEach
            }
            cancelTimeout(regionId, clearPersistedDeadline = false)
            scheduleTimeoutAt(region, deadline)
        }
        editor.apply()
    }

    // MARK: - Inactivity timer helpers (no BLE readings → start timeout countdown)

    private fun rescheduleInactivity(region: Region) {
        val regionId = region.uniqueId
        if (!beaconTimeouts.containsKey(regionId)) return
        cancelInactivity(regionId)
        val runnable = Runnable {
            inactivityRunnables.remove(regionId)
            // No BLE readings for 60 s — start the configured timeout countdown.
            scheduleTimeoutIfConfigured(region)
        }
        inactivityRunnables[regionId] = runnable
        timeoutHandler.postDelayed(runnable, DISTANCE_INACTIVITY_MS)
    }

    private fun cancelInactivity(regionId: String) {
        inactivityRunnables.remove(regionId)?.let { timeoutHandler.removeCallbacks(it) }
    }

    /** Drop state tied to a paired-device definition before that definition is reloaded. */
    private fun clearRegionRuntimeState(regionId: String) {
        // Ignore callbacks from the old Region object while a debounced reload is pending.
        monitoredRegionIds.remove(regionId)
        cancelTimeout(regionId)
        cancelInactivity(regionId)
        beaconTimeouts.remove(regionId)
        enteredRegions.remove(regionId)
        lastSeenAtMs.remove(regionId)
        synchronized(distanceLock) {
            enterCounters.remove(regionId)
            exitCounters.remove(regionId)
            missCounters.remove(regionId)
            smoothedDistances.remove(regionId)
        }
    }

    private fun sendBeaconBroadcast(
            region: Region,
            eventType: String,
            distance: Double,
            rssi: Int = 0
    ) {
        // Determine if this is an Eddystone region based on identifier format
        // Eddystone regions have id1 as a hex namespace (not a UUID)
        val id1Str = region.id1?.toString() ?: ""
        val isEddystone = id1Str.startsWith("0x")
        val eventName = monitoringEventName(isEddystone, eventType) ?: return

        // Single payload shared by the SQLite log, the API forwarder, and the
        // JS broadcast. "event" is part of the public payload for enter/exit
        // only — distance and timeout omit it (matches the TS types and iOS).
        val params =
                buildMap<String, Any?> {
                    put("identifier", region.uniqueId)
                    if (isEddystone) {
                        put("namespace", id1Str.removePrefix("0x"))
                        put("instance", region.id2?.toString()?.removePrefix("0x") ?: "")
                    } else {
                        put("uuid", id1Str.uppercase())
                        put("major", region.id2?.toInt() ?: 0)
                        put("minor", region.id3?.toInt() ?: 0)
                    }
                    if (eventType == "enter" || eventType == "exit") put("event", eventType)
                    put("distance", distance)
                    put("rssi", rssi)
                }
        logBeaconEvent(eventName, params)

        // Forward all produced events to remote API
        apiForwarder?.forwardEvent(params, eventName)

        // Dispatch enter/exit/timeout to registered plugins (e.g. to start/stop BGLocation)
        if (eventType == "enter" || eventType == "exit" || eventType == "timeout") {
            val identifier = region.uniqueId
            val uuid = if (!isEddystone) id1Str.uppercase() else ""
            val major = if (!isEddystone) region.id2?.toInt() ?: 0 else 0
            val minor = if (!isEddystone) region.id3?.toInt() ?: 0 else 0
            val namespace = if (isEddystone) id1Str.removePrefix("0x") else ""
            val instance = if (isEddystone) region.id2?.toString()?.removePrefix("0x") ?: "" else ""
            when (eventType) {
                "enter" ->
                        BeaconPluginRegistry.dispatchEnter(
                                isEddystone,
                                identifier,
                                uuid,
                                major,
                                minor,
                                namespace,
                                instance,
                                distance
                        )
                "exit" ->
                        BeaconPluginRegistry.dispatchExit(
                                isEddystone,
                                identifier,
                                uuid,
                                major,
                                minor,
                                namespace,
                                instance,
                                distance
                        )
                "timeout" ->
                        BeaconPluginRegistry.dispatchTimeout(
                                isEddystone,
                                identifier,
                                uuid,
                                major,
                                minor,
                                namespace,
                                instance,
                                distance
                        )
            }
        }

        // Scoped system broadcast — see BeaconEventReceiver.kt for architecture rationale.
        sendEventBroadcast(eventName, params)
    }

    private fun sendErrorBroadcast(identifier: String?, code: String, message: String) {
        val params =
                buildMap<String, Any?> {
                    put("identifier", identifier ?: "")
                    put("code", code)
                    put("message", message)
                }
        logBeaconEvent("onBeaconError", params)
        sendEventBroadcast("onBeaconError", params)
    }

    /** Pack the resolved JS event name + payload into one intent for [BeaconEventReceiver]. */
    private fun sendEventBroadcast(eventName: String, params: Map<String, Any?>) {
        val bundle = android.os.Bundle()
        for ((key, value) in params) {
            when (value) {
                is String -> bundle.putString(key, value)
                is Int -> bundle.putInt(key, value)
                is Double -> bundle.putDouble(key, value)
                is Boolean -> bundle.putBoolean(key, value)
            }
        }
        val intent =
                Intent(ACTION_BEACON_EVENT).apply {
                    putExtra(EXTRA_EVENT_NAME, eventName)
                    putExtra(EXTRA_EVENT_PARAMS, bundle)
                    setPackage(packageName)
                }
        sendBroadcast(intent)
    }

    private fun monitoringEventName(isEddystone: Boolean, eventType: String): String? {
        return when (eventType) {
            "enter" -> if (isEddystone) "onEddystoneEnter" else "onBeaconEnter"
            "exit" -> if (isEddystone) "onEddystoneExit" else "onBeaconExit"
            "distance" -> if (isEddystone) "onEddystoneDistance" else "onBeaconDistance"
            "timeout" -> if (isEddystone) "onEddystoneTimeout" else "onBeaconTimeout"
            else -> null
        }
    }

    @Synchronized
    private fun getOrCreateEventLogger(): BeaconEventLogger {
        return eventLogger ?: BeaconEventLogger(applicationContext).also { eventLogger = it }
    }

    @Synchronized
    private fun releaseEventLogger() {
        eventLogger?.close()
        eventLogger = null
    }

    private fun logBeaconEvent(eventType: String, params: Map<String, Any?>) {
        if (!BeaconEventLogger.isLoggingEnabled(this)) {
            releaseEventLogger()
            return
        }
        val identifier = params["identifier"] as? String
        getOrCreateEventLogger().logEvent(eventType, identifier, params)
    }

    private fun showEnterExitNotification(region: Region, eventType: String) {
        val config = readNotificationConfig()
        val eventsConfig = notificationSection(config, "beacons", "events", "beaconEvents")

        // Respect the enabled flag (defaults to true)
        if (eventsConfig != null && !eventsConfig.optBoolean("enabled", true)) return

        val defaultTitle =
                when (eventType) {
                    "enter" -> "Beacon Entered"
                    "timeout" -> "Beacon Timeout"
                    else -> "Beacon Exited"
                }
        val title =
                when (eventType) {
                    "enter" -> eventsConfig?.optString("enterTitle")?.takeIf { it.isNotEmpty() }
                                    ?: defaultTitle
                    "timeout" -> eventsConfig?.optString("timeoutTitle")?.takeIf { it.isNotEmpty() }
                                    ?: defaultTitle
                    else -> eventsConfig?.optString("exitTitle")?.takeIf { it.isNotEmpty() }
                                    ?: defaultTitle
                }

        val bodyTemplate =
                eventsConfig?.optString("body")?.takeIf { it.isNotEmpty() }
                        ?: "{identifier} region {event}ed"
        val message =
                bodyTemplate.replace("{identifier}", region.uniqueId).replace("{event}", eventType)

        val notifId =
                notifIdMap.computeIfAbsent(region.uniqueId) {
                    ENTER_EXIT_NOTIF_BASE_ID + notifIdCounter.getAndIncrement()
                }
        postEventNotification(CHANNEL_ID, eventsConfig, title, message, notifId)
    }

    /**
     * Build and post a user-facing event notification; silently skipped without POST_NOTIFICATIONS.
     */
    private fun postEventNotification(
            channelId: String,
            eventsConfig: org.json.JSONObject?,
            title: String,
            message: String,
            notifId: Int
    ) {
        val notification =
                NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(resolveIconRes(this, eventsConfig))
                        .setContentTitle(title)
                        .setContentText(message)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .build()

        try {
            NotificationManagerCompat.from(this).notify(notifId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently skip notification
        }
    }

    private fun buildForegroundNotification(): Notification {
        return Companion.buildForegroundNotification(this)
    }

    private fun snapshotMonitoringRuntimeState(): Map<String, MonitoringRuntimeState> {
        synchronized(distanceLock) {
            val regionIds = monitoredRegionIds.toList()
            return regionIds.associateWith { regionId ->
                MonitoringRuntimeState(
                        isEntered = enteredRegions.contains(regionId),
                        distance = smoothedDistances[regionId]
                )
            }
        }
    }

    companion object {
        /** EMA weight for new readings. 0.4 balances responsiveness vs noise rejection. */
        const val DISTANCE_EMA_ALPHA = 0.4
        /** If raw distance differs from smoothed by more than this factor, treat as outlier. */
        const val DISTANCE_JUMP_FACTOR = 5.0

        private const val EXTRA_RETRY_COUNT = "retryCount"
        private const val MAX_STARTFOREGROUND_RETRIES = 3
        /** Minimum milliseconds between consecutive loadAndMonitorRegions() calls. */
        private const val LOAD_REGIONS_DEBOUNCE_MS = 500L
        @Volatile private var activeService: BeaconForegroundService? = null


        fun start(context: Context) {
            val appContext = context.applicationContext
            val wasActive = isMonitoringActive(appContext)
            setMonitoringActive(appContext, true)
            try {
                ensureForegroundNotificationChannel(appContext)
                ensureNotificationChannel(appContext)
                val intent = Intent(appContext, BeaconForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (error: Throwable) {
                if (!wasActive) {
                    setMonitoringActive(appContext, false)
                    clearAllTimeoutDeadlines(appContext)
                }
                throw error
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            setMonitoringActive(appContext, false)
            clearAllTimeoutDeadlines(appContext)
            appContext.stopService(Intent(appContext, BeaconForegroundService::class.java))
        }

        internal fun setMonitoringActive(context: Context, enabled: Boolean) {
            context.applicationContext
                    .getSharedPreferences(MONITORING_ACTIVE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(MONITORING_ACTIVE_KEY, enabled)
                    .apply()
        }

        fun isMonitoringActive(context: Context): Boolean {
            return context.applicationContext
                    .getSharedPreferences(MONITORING_ACTIVE_PREFS, Context.MODE_PRIVATE)
                    .getBoolean(MONITORING_ACTIVE_KEY, false)
        }

        /**
         * Android 14 requires a connected-device foreground service to hold at least one of the
         * connected-device runtime prerequisites. This library's relevant prerequisite is a
         * granted Bluetooth permission.
         */
        fun hasConnectedDeviceForegroundServicePrerequisite(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                            PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                            PackageManager.PERMISSION_GRANTED
        }

        internal fun hasBeaconMonitoringPermissions(context: Context): Boolean {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                            ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_SCAN
                        ) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
            }
            return true
        }

        fun clearTimeoutDeadline(context: Context, identifier: String) {
            context.applicationContext
                    .getSharedPreferences(TIMEOUT_DEADLINE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .remove(identifier)
                    .apply()
        }

        internal fun clearAllTimeoutDeadlines(context: Context) {
            context.applicationContext
                    .getSharedPreferences(TIMEOUT_DEADLINE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply()
        }

        /** Reload paired regions without requiring the foreground service to be restarted. */
        fun reconcilePairedRegions(context: Context, resetIdentifier: String? = null) {
            val appContext = context.applicationContext
            resetIdentifier?.let { clearTimeoutDeadline(appContext, it) }
            if (!isMonitoringActive(appContext)) return

            val service = activeService
            if (service == null) {
                try {
                    start(appContext)
                } catch (error: Throwable) {
                    Log.e(TAG, "Failed to restart monitoring after pair data changed", error)
                }
                return
            }

            service.controlHandler.post {
                if (!isMonitoringActive(service)) return@post
                resetIdentifier?.let { service.clearRegionRuntimeState(it) }
                if (service.serviceConnected) {
                    service.loadAndMonitorRegions()
                } else {
                    try {
                        service.beaconManager.bind(service)
                    } catch (error: Throwable) {
                        setMonitoringActive(service, false)
                        clearAllTimeoutDeadlines(service)
                        service.sendErrorBroadcast(
                                null,
                                "MONITORING_FAILED",
                                "Failed to reload paired beacon regions: ${error.message}"
                        )
                    }
                }
            }
        }

        fun getMonitoringRuntimeSnapshot(): Map<String, MonitoringRuntimeState> {
            return activeService?.snapshotMonitoringRuntimeState() ?: emptyMap()
        }

        /** Read the persisted notification config JSON; empty object when unset or malformed. */
        internal fun readNotificationConfig(context: Context): org.json.JSONObject {
            val json =
                    context.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                            .getString(NOTIFICATION_CONFIG_KEY, null)
                            ?: return org.json.JSONObject()
            return try {
                org.json.JSONObject(json)
            } catch (_: Exception) {
                org.json.JSONObject()
            }
        }

        internal fun notificationSection(
                config: org.json.JSONObject,
                parentKey: String,
                childKey: String,
                legacyKey: String
        ): org.json.JSONObject? =
                config.optJSONObject(parentKey)?.optJSONObject(childKey)
                        ?: config.optJSONObject(legacyKey)

        /**
         * Resolve the configured small-icon drawable from [config], falling back to a stock icon.
         */
        private fun resolveIconRes(context: Context, config: org.json.JSONObject?): Int {
            val iconName = config?.optString("icon")?.takeIf { it.isNotEmpty() }
            return iconName?.let { name ->
                try {
                    context.resources.getIdentifier(name, "drawable", context.packageName).takeIf {
                        it != 0
                    }
                } catch (_: Exception) {
                    null
                }
            }
                    ?: android.R.drawable.ic_dialog_info
        }

        private fun ensureChannel(
                context: Context,
                channelId: String,
                parentKey: String,
                childKey: String,
                legacyKey: String,
                defaultName: String,
                defaultDescription: String,
                fallbackImportance: Int
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channelConfig =
                    notificationSection(
                            readNotificationConfig(context),
                            parentKey,
                            childKey,
                            legacyKey
                    )

            val channelName =
                    channelConfig?.optString("name")?.takeIf { it.isNotEmpty() } ?: defaultName
            val channelDesc =
                    channelConfig?.optString("description")?.takeIf { it.isNotEmpty() }
                            ?: defaultDescription
            val importance =
                    when (channelConfig?.optString("importance")) {
                        "high" -> NotificationManager.IMPORTANCE_HIGH
                        "default" -> NotificationManager.IMPORTANCE_DEFAULT
                        "low" -> NotificationManager.IMPORTANCE_LOW
                        else -> fallbackImportance
                    }

            val notifMgr = context.getSystemService(NotificationManager::class.java)
            // Only create channel if it doesn't exist yet — preserves user notification preferences
            if (notifMgr?.getNotificationChannel(channelId) == null) {
                val channel =
                        NotificationChannel(channelId, channelName, importance).apply {
                            description = channelDesc
                        }
                notifMgr?.createNotificationChannel(channel)
            }
        }

        /**
         * Ensure the notification channel exists. Must be called before building a notification
         * from a non-service context (e.g. ExpoBeaconModule).
         */
        fun ensureNotificationChannel(context: Context) =
                ensureChannel(
                        context,
                        CHANNEL_ID,
                        "beacons",
                        "channel",
                        "channel",
                        "Beacon Monitoring",
                        "Used for background iBeacon region monitoring",
                        NotificationManager.IMPORTANCE_LOW
                )

        /**
         * Ensure the persistent foreground-service notification has a quiet channel of its own.
         * Event channels can be default/high importance, but the always-on service status should
         * never make sound or vibrate.
         */
        fun ensureForegroundNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val notifMgr = context.getSystemService(NotificationManager::class.java)
            if (notifMgr?.getNotificationChannel(FOREGROUND_CHANNEL_ID) == null) {
                val channel =
                        NotificationChannel(
                                        FOREGROUND_CHANNEL_ID,
                                        "Beacon Foreground Service",
                                        NotificationManager.IMPORTANCE_LOW
                                )
                                .apply {
                                    description =
                                            "Persistent status for beacon background monitoring"
                                    setSound(null, null)
                                    enableVibration(false)
                                }
                notifMgr?.createNotificationChannel(channel)
            }
        }

        /**
         * Build the persistent foreground-service notification from any Context. Static so
         * cold-start paths that run before a service instance exists (e.g. [BootReceiver]) read the
         * same persisted config.
         */
        fun buildForegroundNotification(context: Context): Notification {
            ensureForegroundNotificationChannel(context)
            val config = readNotificationConfig(context)

            val defaultTitle = "Beacon Monitoring Active"
            val defaultText = "Monitoring for iBeacons in the background"
            val fgConfig = notificationSection(
                    config,
                    "beacons",
                    "foregroundService",
                    "foregroundService"
            )
            val title = fgConfig?.optString("title")?.takeIf { it.isNotEmpty() } ?: defaultTitle
            val text = fgConfig?.optString("text")?.takeIf { it.isNotEmpty() } ?: defaultText

            return NotificationCompat.Builder(context, FOREGROUND_CHANNEL_ID)
                    .setSmallIcon(resolveIconRes(context, fgConfig))
                    .setContentTitle(title)
                    .setContentText(text)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setSilent(true)
                    .setOngoing(true)
                    .build()
        }
    }

    private fun readNotificationConfig(): org.json.JSONObject = readNotificationConfig(this)

    /**
     * Stop all beacon ranging/monitoring, cancel beacon timers, and unbind from AltBeacon, while
     * Safe to call when monitoring was never armed. Used during shutdown and failed startup.
     */
    private fun disableMonitoringInternal(clearPersistedTimeouts: Boolean = false) {
        pendingLoadRegions = false
        timeoutHandler.removeCallbacksAndMessages(null)
        timeoutRunnables.clear()
        inactivityRunnables.clear()
        beaconTimeouts.clear()
        if (clearPersistedTimeouts) clearAllTimeoutDeadlines(this)
        lastSeenAtMs.clear()
        rangedRegions.forEach {
            try {
                beaconManager.stopRangingBeaconsInRegion(it)
            } catch (_: Throwable) {}
        }
        rangedRegions.clear()
        monitoredRegions.forEach {
            try {
                beaconManager.stopMonitoringBeaconsInRegion(it)
            } catch (_: Throwable) {}
        }
        monitoredRegions.clear()
        monitoredRegionIds.clear()
        enteredRegions.clear()
        synchronized(distanceLock) {
            enterCounters.clear()
            exitCounters.clear()
            missCounters.clear()
            smoothedDistances.clear()
        }
        try {
            beaconManager.removeMonitorNotifier(monitorNotifier)
        } catch (_: Throwable) {}
        try {
            beaconManager.removeRangeNotifier(rangeNotifier)
        } catch (_: Throwable) {}
        // Only unbind if this service instance successfully bound to AltBeacon.
        if (serviceConnected) {
            serviceConnected = false
            try {
                beaconManager.unbind(this)
            } catch (_: Throwable) {}
        }
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        controlHandler.removeCallbacksAndMessages(null)
        disableMonitoringInternal(clearPersistedTimeouts = !isMonitoringActive(this))
        notifIdMap.clear()
        releaseEventLogger()
        apiForwarder?.shutdown()
        apiForwarder = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Keep persisted beacon monitoring recoverable when the host task is removed. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val keepAlive = isMonitoringActive(this)
        if (keepAlive) {
            try {
                BootReceiver.scheduleTaskRemovedKeepAlive(this)
            } catch (error: Throwable) {
                Log.w(TAG, "Failed to arm task-removed keepalive", error)
            }
        }
        Log.d(TAG, "onTaskRemoved received (monitoring=$keepAlive). Service will remain in foreground.")
        super.onTaskRemoved(rootIntent)
    }
}

const val ACTION_BEACON_EVENT = "expo.modules.beacon.BEACON_EVENT"
/** Intent extra holding the resolved JS event name (e.g. "onBeaconEnter"). */
internal const val EXTRA_EVENT_NAME = "eventName"
/**
 * Intent extra holding the event payload as a Bundle, unpacked verbatim by [BeaconEventReceiver].
 */
internal const val EXTRA_EVENT_PARAMS = "params"
