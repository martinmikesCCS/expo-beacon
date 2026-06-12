package expo.modules.beacon

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.altbeacon.beacon.*
import org.json.JSONArray

private const val CHANNEL_ID = "expo_beacon_channel"
private const val CARPLAY_CHANNEL_ID = "expo_beacon_carplay_channel"
internal const val FOREGROUND_NOTIF_ID = 1001
/**
 * Base ID for per-beacon enter/exit notifications; incremented per unique region.
 * With FOREGROUND_NOTIF_ID at 1001, this allows up to 999 unique regions
 * before ID collision. Sufficient for real-world beacon deployments.
 */
private const val ENTER_EXIT_NOTIF_BASE_ID = 2000
/**
 * Fixed notification IDs for CarPlay connect / disconnect events. Each event
 * type uses a single ID so repeated events of the same type replace the prior
 * notification rather than stacking, while connect and disconnect remain
 * independently visible.
 */
private const val CARPLAY_CONNECTED_NOTIF_ID = 3000
private const val CARPLAY_DISCONNECTED_NOTIF_ID = 3001

class BeaconForegroundService : Service(), BeaconConsumer {

    data class MonitoringRuntimeState(
        val isEntered: Boolean,
        val distance: Double?
    )

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

    // Distance logging
    private val distanceLogRegions = java.util.concurrent.CopyOnWriteArraySet<Region>()

    // Timeout timers — fire once after beacon stays in range for configured duration
    private val timeoutHandler = Handler(Looper.getMainLooper())
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

    // CarPlay / Android Auto observer hosted by the foreground service so that
    // CarConnection events are captured for as long as the service runs —
    // independent of the JS bridge / module lifecycle. Survives app suspension
    // and (via BootReceiver) device reboot.
    // `internal` so the companion's `getCarPlayStatus()` and `reEmitCarPlayStateIfNeeded`
    // can read it directly (all callers are within the same package).
    @Volatile internal var carPlayMonitor: CarPlayMonitor? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this
        createNotificationChannel()
        createCarPlayNotificationChannel()
        apiForwarder = BeaconApiForwarder(this)
        beaconManager = BeaconManager.getInstanceForApplication(this).also { manager ->
            BeaconParsers.ensureRegistered(manager)
            try { manager.setEnableScheduledScanJobs(false) } catch (e: IllegalStateException) { Log.w(TAG, "setEnableScheduledScanJobs failed", e) }
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

        // Restore CarPlay observer if the user previously enabled CarPlay
        // monitoring. Reading the flag here means cold-started services
        // (e.g. via BootReceiver after reboot) automatically re-attach the
        // observer with no JS interaction required.
        if (isCarPlayEnabled(this)) {
            startCarPlayObserverInternal()
            // Re-arm the WorkManager watchdog on every cold start. KEEP policy
            // makes this a cheap no-op if a schedule already exists, but it
            // recovers the schedule if WorkManager's DB was wiped by an OEM
            // cleaner or by a `pm clear` from adb.
            CarPlayWatchdogWorker.schedule(this)
        }
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
            sendErrorBroadcast(null, "SERVICE_START_FAILED", "startForeground failed (retry=$retryCount): ${e.message}")
            // Schedule a retry so monitoring can recover without user interaction, capped to
            // avoid infinite crash loops. Retry if EITHER beacon monitoring OR CarPlay
            // observation is supposed to be active — without the CarPlay branch the
            // service silently dies when the very first startCarPlayMonitoring() call
            // races with a transient BT permission / FGS-restriction failure.
            if (retryCount < MAX_STARTFOREGROUND_RETRIES &&
                (isMonitoringActive(this) || isCarPlayEnabled(this))) {
                scheduleServiceRetry(retryCount + 1)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        // Handle CarPlay enable/disable actions. These can arrive after the
        // service is already running for beacon monitoring, OR be the reason
        // the service was started in the first place (CarPlay-only mode).
        when (intent?.action) {
            ACTION_ENABLE_CARPLAY -> {
                setCarPlayEnabled(this, true)
                startCarPlayObserverInternal()
            }
            ACTION_DISABLE_CARPLAY -> {
                setCarPlayEnabled(this, false)
                stopCarPlayObserverInternal()
                // If the service is only alive for CarPlay (no beacon monitoring),
                // shut it down so we don't keep an unnecessary foreground notification.
                if (!isMonitoringActive(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        if (serviceConnected) {
            // Already bound from a prior onStartCommand — reload regions directly
            // so that re-starting monitoring from JS always takes effect.
            loadAndMonitorRegions()
        } else if (isMonitoringActive(this)) {
            // Only bind to AltBeacon when beacon monitoring is active.
            // CarPlay-only mode keeps the service alive without scanning.
            beaconManager.bind(this)
        }
        return START_STICKY
    }

    /**
     * Schedules a one-shot alarm to restart this service via startForegroundService().
     * Used when startForeground() fails transiently (e.g. BT not yet ready at boot).
     */
    private fun scheduleServiceRetry(retryCount: Int) {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val retryIntent = Intent(this, BeaconForegroundService::class.java)
            .putExtra(EXTRA_RETRY_COUNT, retryCount)
        val pendingIntent = PendingIntent.getService(
            this,
            RETRY_SERVICE_REQUEST_CODE,
            retryIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + RETRY_DELAY_MS,
            pendingIntent
        )
        Log.w(TAG, "startForeground retry $retryCount scheduled in ${RETRY_DELAY_MS}ms")
    }

    /**
     * Called by the system on Android 14+ (API 34) if a foreground service with a time-limited
     * type exceeds its allowed duration. connectedDevice has no documented time limit as of
     * Android 17, but this override ensures we handle a future limit gracefully rather than
     * receiving an ANR.
     */
    @Suppress("NewApi")
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "BeaconForegroundService.onTimeout(startId=$startId, fgsType=$fgsType) — scheduling restart")
        if (isMonitoringActive(this)) {
            scheduleServiceRetry(0)
        }
        stopSelf()
    }

    override fun onBeaconServiceConnect() {
        serviceConnected = true
        // Read max distance, exit distance, and min RSSI from options prefs
        val optPrefs = getSharedPreferences(MONITORING_OPTIONS_PREFS, Context.MODE_PRIVATE)
        maxDistance = optPrefs.getString("max_distance", null)?.toDoubleOrNull()
        exitDistance = optPrefs.getString("exit_distance", null)?.toDoubleOrNull()
        minRssiThreshold = optPrefs.getInt("min_rssi", DEFAULT_MIN_RSSI)
        eventLevel = optPrefs.getString("level", "all") ?: "all"
        exitTimeoutMs = ((optPrefs.getString("exit_timeout_seconds", null)?.toDoubleOrNull() ?: DEFAULT_EXIT_TIMEOUT_SECONDS) * 1000.0).toLong()

        beaconManager.addMonitorNotifier(monitorNotifier)
        beaconManager.addRangeNotifier(rangeNotifier)
        beaconManager.addRangeNotifier(distanceLoggingRangeNotifier)
        loadAndMonitorRegions()
    }

    private fun loadAndMonitorRegions() {
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = now - lastLoadRegionsMs
        if (elapsed < LOAD_REGIONS_DEBOUNCE_MS) {
            Log.d(TAG, "loadAndMonitorRegions: skipping duplicate call (${elapsed}ms after last load)")
            return
        }
        lastLoadRegionsMs = now

        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        val beacons = try { JSONArray(json) } catch (_: Exception) { JSONArray() }

        // Load paired Eddystones
        val eddystonePrefs: SharedPreferences = getSharedPreferences(EDDYSTONE_PREFS_NAME, Context.MODE_PRIVATE)
        val eddystoneJson = eddystonePrefs.getString(EDDYSTONE_PREFS_KEY, "[]") ?: "[]"
        val eddystones = try { JSONArray(eddystoneJson) } catch (_: Exception) { JSONArray() }

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
        distanceLogRegions.forEach {
            try { beaconManager.stopRangingBeaconsInRegion(it) } catch (_: RemoteException) {}
        }
        distanceLogRegions.clear()
        monitoredRegions.forEach {
            try { beaconManager.stopMonitoringBeaconsInRegion(it) } catch (_: RemoteException) {}
        }
        monitoredRegions.clear()
        monitoredRegionIds.clear()
        lastSeenAtMs.clear()
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

        // iBeacon regions
        for (i in 0 until beacons.length()) {
            val b = beacons.getJSONObject(i)
            val region = Region(
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
                sendErrorBroadcast(region.uniqueId, "MONITORING_FAILED", "Failed to start monitoring iBeacon region ${region.uniqueId}")
            } catch (e: SecurityException) {
                // Android 17+ may throw SecurityException if BLUETOOTH_SCAN/CONNECT were
                // not held at the exact moment monitoring starts.
                Log.e(TAG, "Security exception starting monitoring for ${region.uniqueId} — check BT permissions", e)
                sendErrorBroadcast(region.uniqueId, "SECURITY_EXCEPTION", "Security exception starting monitoring for ${region.uniqueId} — check BT permissions")
            }
            // Start ranging this region for distance logging
            if (distanceLogRegions.add(region)) {
                try {
                    beaconManager.startRangingBeaconsInRegion(region)
                } catch (e: RemoteException) {
                    distanceLogRegions.remove(region)
                    Log.e(TAG, "Failed to start ranging iBeacon region ${region.uniqueId}", e)
                    sendErrorBroadcast(region.uniqueId, "RANGING_FAILED", "Failed to start ranging iBeacon region ${region.uniqueId}")
                } catch (e: SecurityException) {
                    distanceLogRegions.remove(region)
                    Log.e(TAG, "Security exception starting ranging for ${region.uniqueId} — check BT permissions", e)
                    sendErrorBroadcast(region.uniqueId, "SECURITY_EXCEPTION", "Security exception starting ranging for ${region.uniqueId} — check BT permissions")
                }
            }
        }

        // Eddystone-UID regions
        for (i in 0 until eddystones.length()) {
            val e = eddystones.getJSONObject(i)
            val identifier = e.getString("identifier")
            val namespace = e.getString("namespace")
            val instance = e.getString("instance")
            val region = Region(
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
                sendErrorBroadcast(identifier, "MONITORING_FAILED", "Failed to start monitoring Eddystone region $identifier")
            } catch (ex: SecurityException) {
                Log.e(TAG, "Security exception starting monitoring for Eddystone $identifier — check BT permissions", ex)
                sendErrorBroadcast(identifier, "SECURITY_EXCEPTION", "Security exception starting monitoring for Eddystone $identifier — check BT permissions")
            }
            if (distanceLogRegions.add(region)) {
                try {
                    beaconManager.startRangingBeaconsInRegion(region)
                } catch (ex: RemoteException) {
                    distanceLogRegions.remove(region)
                    Log.e(TAG, "Failed to start ranging Eddystone region $identifier", ex)
                    sendErrorBroadcast(identifier, "RANGING_FAILED", "Failed to start ranging Eddystone region $identifier")
                } catch (ex: SecurityException) {
                    distanceLogRegions.remove(region)
                    Log.e(TAG, "Security exception starting ranging for Eddystone $identifier — check BT permissions", ex)
                    sendErrorBroadcast(identifier, "SECURITY_EXCEPTION", "Security exception starting ranging for Eddystone $identifier — check BT permissions")
                }
            }
        }

        // If no regions to monitor, stop the service to avoid idling
        if (monitoredRegions.isEmpty()) {
            enteredRegions.clear()
            Log.d(TAG, "No paired beacons — stopping idle foreground service")
            stopSelf()
        } else {
            // Prune enteredRegions for regions that are no longer monitored
            enteredRegions.retainAll(monitoredRegionIds)
        }
    }

    // Distance logging only — emits distance broadcasts. Enter/exit logic lives in rangeNotifier.
    private val distanceLoggingRangeNotifier = RangeNotifier { beacons, region ->
        if (eventLevel != "all") return@RangeNotifier
        if (!monitoredRegionIds.contains(region.uniqueId)) return@RangeNotifier
        val closest = beacons.filter { it.distance >= 0 && it.rssi >= minRssiThreshold }.minByOrNull { it.distance }
        if (closest != null) {
            lastSeenAtMs[region.uniqueId] = SystemClock.elapsedRealtime()
            // Valid BLE reading — reset inactivity timer.
            rescheduleInactivity(region)
            sendBeaconBroadcast(region, "distance", closest.distance, closest.rssi)
        }
    }

    private val monitorNotifier = object : MonitorNotifier {
        override fun didEnterRegion(region: Region) {
            // Enter is synthesized from ranging so distance and enter/exit stay in sync.
        }

        override fun didExitRegion(region: Region) {
            if (!monitoredRegionIds.contains(region.uniqueId)) return
            if (wasSeenRecently(region.uniqueId)) {
                Log.d(TAG, "Ignoring stale didExitRegion for ${region.uniqueId}; beacon was seen by ranging recently")
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
                sendBeaconBroadcast(region, "exit", -1.0)
                showEnterExitNotification(region, "exit")
                // OS-level exit safety net — cancel inactivity timer and start the timeout clock.
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
    // when beacons disappear.
    private val rangeNotifier = RangeNotifier { beacons, region ->
        val maxDist = maxDistance
        if (!monitoredRegionIds.contains(region.uniqueId)) return@RangeNotifier

        val beacon = beacons
            .filter { it.distance >= 0 && it.rssi >= minRssiThreshold }
            .minByOrNull { it.distance }

        synchronized(distanceLock) {
            if (beacon != null) {
                // Got a valid reading — reset miss counter
                lastSeenAtMs[region.uniqueId] = SystemClock.elapsedRealtime()
                missCounters[region.uniqueId] = 0
                // Valid BLE reading — reset inactivity timer.
                rescheduleInactivity(region)

                // Apply EMA smoothing; jump resets EMA to the new value
                val smoothed = smoothDistance(region.uniqueId, beacon.distance)

                val action = evaluateDistanceHysteresis(region.uniqueId, smoothed, maxDist)
                when (action) {
                    HysteresisAction.ENTER -> {
                        enteredRegions.add(region.uniqueId)
                        sendBeaconBroadcast(region, "enter", beacon.distance, beacon.rssi)
                        showEnterExitNotification(region, "enter")
                        // Beacon returned — cancel any running timeout timer.
                        cancelTimeout(region.uniqueId)
                    }
                    HysteresisAction.EXIT -> {
                        enteredRegions.remove(region.uniqueId)
                        sendBeaconBroadcast(region, "exit", beacon.distance, beacon.rssi)
                        showEnterExitNotification(region, "exit")
                        // Beacon left — cancel inactivity timer and start the timeout clock.
                        cancelInactivity(region.uniqueId)
                        scheduleTimeoutIfConfigured(region)
                    }
                    HysteresisAction.NONE -> {}
                }
            } else {
                // No valid beacon reading — track consecutive misses for disappearance-based
                // exit detection. enterCounters/exitCounters are intentionally NOT reset here.
                // On Android 17+ (API 37) the BLE scan callbacks are more intermittent: valid
                // readings are interspersed with occasional null cycles even when the beacon is
                // nearby. Resetting direction counters on every null would prevent the hysteresis
                // from ever accumulating to ENTER_HYSTERESIS_COUNT, breaking enter/exit entirely while
                // still allowing distance events (which fire on each individual valid reading).
                // Direction counters are reset by evaluateDistanceHysteresis when a valid reading
                // contradicts the current direction (e.g., in-range reading resets exitCounters).
                val count = (missCounters[region.uniqueId] ?: 0) + 1
                missCounters[region.uniqueId] = count

                val lastSeen = lastSeenAtMs[region.uniqueId]
                val silentMs = if (lastSeen != null) SystemClock.elapsedRealtime() - lastSeen else Long.MAX_VALUE
                if (enteredRegions.contains(region.uniqueId) && silentMs >= exitTimeoutMs) {
                    enteredRegions.remove(region.uniqueId)
                    missCounters[region.uniqueId] = 0
                    enterCounters[region.uniqueId] = 0
                    exitCounters[region.uniqueId] = 0
                    sendBeaconBroadcast(region, "exit", -1.0)
                    showEnterExitNotification(region, "exit")
                    // Beacon disappeared — cancel inactivity timer and start the timeout clock.
                    cancelInactivity(region.uniqueId)
                    scheduleTimeoutIfConfigured(region)
                }
            }
        }
    }

    // MARK: - Distance-based enter/exit hysteresis

    private enum class HysteresisAction { NONE, ENTER, EXIT }

    /**
     * Apply exponential moving average (EMA) smoothing to a raw distance reading.
     * If the reading is a large jump (> DISTANCE_JUMP_FACTOR), resets the EMA to the new
     * value instead of rejecting it — this ensures the hysteresis pipeline keeps receiving
     * data and can fire exit events when the user moves away from a beacon, rather than
     * freezing because the EMA is stuck at the old close-range value.
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
        exitDistance?.let { return it }
        return maxDist + minOf(maxDist * 0.5, 2.5)
    }

    /**
     * Evaluate distance-based enter/exit with hysteresis counters.
     * Must be called within synchronized(distanceLock).
     * Mirrors [ExpoBeaconModule.swift evaluateDistanceHysteresis].
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
        val runnable = Runnable {
            timeoutRunnables.remove(region.uniqueId)
            sendBeaconBroadcast(region, "timeout", -1.0)
            showEnterExitNotification(region, "timeout")
        }
        timeoutRunnables[region.uniqueId] = runnable
        timeoutHandler.postDelayed(runnable, seconds * 1000L)
    }

    private fun cancelTimeout(regionId: String) {
        timeoutRunnables.remove(regionId)?.let { timeoutHandler.removeCallbacks(it) }
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

    private fun sendBeaconBroadcast(region: Region, eventType: String, distance: Double, rssi: Int = 0) {
        // Determine if this is an Eddystone region based on identifier format
        // Eddystone regions have id1 as a hex namespace (not a UUID)
        val id1Str = region.id1?.toString() ?: ""
        val isEddystone = id1Str.startsWith("0x")

        val params = if (isEddystone) {
            buildMap<String, Any?> {
                put("identifier", region.uniqueId)
                put("namespace", id1Str.removePrefix("0x"))
                put("instance", region.id2?.toString()?.removePrefix("0x") ?: "")
                put("event", eventType)
                put("distance", distance)
                put("rssi", rssi)
            }
        } else {
            buildMap<String, Any?> {
                put("identifier", region.uniqueId)
                put("uuid", id1Str)
                put("major", region.id2?.toInt() ?: 0)
                put("minor", region.id3?.toInt() ?: 0)
                put("event", eventType)
                put("distance", distance)
                put("rssi", rssi)
            }
        }
        monitoringEventName(isEddystone, eventType)?.let { logBeaconEvent(it, params) }

        // Forward all produced events to remote API
        apiForwarder?.forwardEvent(params)

        // Dispatch enter/exit/timeout to registered plugins (e.g. to start/stop BGLocation)
        if (eventType == "enter" || eventType == "exit" || eventType == "timeout") {
            val identifier = region.uniqueId
            val uuid = if (!isEddystone) id1Str else ""
            val major = if (!isEddystone) region.id2?.toInt() ?: 0 else 0
            val minor = if (!isEddystone) region.id3?.toInt() ?: 0 else 0
            val namespace = if (isEddystone) id1Str.removePrefix("0x") else ""
            val instance = if (isEddystone) region.id2?.toString()?.removePrefix("0x") ?: "" else ""
            when (eventType) {
                "enter" -> BeaconPluginRegistry.dispatchEnter(isEddystone, identifier, uuid, major, minor, namespace, instance, distance)
                "exit" -> BeaconPluginRegistry.dispatchExit(isEddystone, identifier, uuid, major, minor, namespace, instance, distance)
                "timeout" -> BeaconPluginRegistry.dispatchTimeout(isEddystone, identifier, uuid, major, minor, namespace, instance, distance)
            }
        }

        val intent = Intent(ACTION_BEACON_EVENT).apply {
            putExtra("identifier", region.uniqueId)
            putExtra("event", eventType)
            putExtra("distance", distance)
            putExtra("rssi", rssi)
            if (isEddystone) {
                putExtra("beaconType", "eddystone")
                putExtra("namespace", id1Str.removePrefix("0x"))
                putExtra("instance", region.id2?.toString()?.removePrefix("0x") ?: "")
            } else {
                putExtra("beaconType", "ibeacon")
                putExtra("uuid", id1Str)
                putExtra("major", region.id2?.toInt() ?: 0)
                putExtra("minor", region.id3?.toInt() ?: 0)
            }
            setPackage(packageName)
        }
        // Scoped system broadcast — see BeaconEventReceiver.kt for architecture rationale.
        sendBroadcast(intent)
    }

    private fun sendErrorBroadcast(identifier: String?, code: String, message: String) {
        val params = buildMap<String, Any?> {
            put("identifier", identifier ?: "")
            put("event", "error")
            put("code", code)
            put("message", message)
        }
        logBeaconEvent("onBeaconError", params)
        val intent = Intent(ACTION_BEACON_EVENT).apply {
            putExtra("identifier", identifier ?: "")
            putExtra("event", "error")
            putExtra("errorCode", code)
            putExtra("errorMessage", message)
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
        val eventsConfig = config.optJSONObject("beaconEvents")

        // Respect the enabled flag (defaults to true)
        if (eventsConfig != null && !eventsConfig.optBoolean("enabled", true)) return

        val defaultTitle = when (eventType) {
            "enter" -> "Beacon Entered"
            "timeout" -> "Beacon Timeout"
            else -> "Beacon Exited"
        }
        val title = when (eventType) {
            "enter" -> eventsConfig?.optString("enterTitle")?.takeIf { it.isNotEmpty() } ?: defaultTitle
            "timeout" -> eventsConfig?.optString("timeoutTitle")?.takeIf { it.isNotEmpty() } ?: defaultTitle
            else -> eventsConfig?.optString("exitTitle")?.takeIf { it.isNotEmpty() } ?: defaultTitle
        }

        val bodyTemplate = eventsConfig?.optString("body")?.takeIf { it.isNotEmpty() }
            ?: "{identifier} region {event}ed"
        val message = bodyTemplate
            .replace("{identifier}", region.uniqueId)
            .replace("{event}", eventType)

        val iconName = eventsConfig?.optString("icon")?.takeIf { it.isNotEmpty() }
        val iconResId = iconName?.let { name ->
            try { resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 } }
            catch (_: Exception) { null }
        } ?: android.R.drawable.ic_dialog_info

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconResId)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        try {
            val notifId = notifIdMap.computeIfAbsent(region.uniqueId) {
                ENTER_EXIT_NOTIF_BASE_ID + notifIdCounter.getAndIncrement()
            }
            NotificationManagerCompat.from(this).notify(notifId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently skip notification
        }
    }

    // MARK: - CarPlay observer (service-hosted)

    /**
     * Lazily instantiate and start the CarPlay observer. Idempotent —
     * `CarPlayMonitor.start` itself is safe to call multiple times.
     * Must be called from any thread; the monitor hops to main internally.
     */
    private fun startCarPlayObserverInternal() {
        val monitor = carPlayMonitor ?: try {
            CarPlayMonitor(applicationContext).also { carPlayMonitor = it }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to create CarPlayMonitor", e)
            return
        }
        try {
            monitor.start { eventName, payload ->
                emitCarPlayEvent(eventName, payload)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to start CarPlayMonitor", e)
        }
    }

    private fun stopCarPlayObserverInternal() {
        carPlayMonitor?.stop()
        // Keep the instance — it's idempotent, and recreating costs nothing
        // beyond the `CarConnection` LiveData wrapper. Setting null here is
        // also safe but unnecessary.
    }

    /**
     * Re-emit CarPlay state to a freshly-bound module. Invoked from [bindModule]
     * whenever a new [ExpoBeaconModule] attaches while the foreground service is
     * already running (typical after the app process is killed and relaunched).
     *
     * Three outcomes:
     *  1. Observer running + car connected → re-emit `onCarPlayConnected` (JS-only;
     *     SQLite / API / registry already ran when the event originally fired).
     *  2. Observer running + car disconnected + JS last knew "connected" →
     *     emit synthetic `onCarPlayDisconnected` with `reason = "reconciled"`.
     *  3. Observer not yet running (fresh service start) → LiveData delivers
     *     the initial value naturally; no action needed.
     */
    private fun reEmitCarPlayStateIfNeeded(module: ExpoBeaconModule) {
        val monitor = carPlayMonitor ?: return
        if (!monitor.isObserving()) return  // Fresh service start; LiveData handles first delivery.

        val connPayload = monitor.buildConnectedPayload()
        if (connPayload != null) {
            // Car is still connected — give the new module the current state.
            try { module.forwardCarPlayEventFromService("onCarPlayConnected", connPayload) } catch (_: Throwable) {}
            return
        }

        // Car is disconnected. If JS last knew it was connected, synthesise a
        // reconciled disconnect (mirrors iOS `reconcileOnProcessStart()`).
        if (readJsConnected()) {
            val now = System.currentTimeMillis()
            val payload = mapOf<String, Any?>(
                "timestamp" to now,
                "timestampIso" to buildIsoTimestamp(now),
                "reason" to "reconciled",
            )
            try { module.forwardCarPlayEventFromService("onCarPlayDisconnected", payload) } catch (_: Throwable) {}
            writeJsConnected(false)
        }
    }

    // MARK: - JS delivery state helpers

    private fun readJsConnected(): Boolean =
        try { getSharedPreferences(PREF_CARPLAY_JS_STATE, Context.MODE_PRIVATE).getBoolean("js_connected", false) }
        catch (_: Throwable) { false }

    private fun writeJsConnected(connected: Boolean) {
        try {
            getSharedPreferences(PREF_CARPLAY_JS_STATE, Context.MODE_PRIVATE)
                .edit().putBoolean("js_connected", connected).apply()
        } catch (_: Throwable) {}
    }

    private fun buildIsoTimestamp(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(millis))
    }

    /**
     * Fan out a CarPlay event to all sinks: SQLite log, remote API forwarder,
     * native plugin registry, and (best-effort) the live JS bridge. Runs from
     * the main thread (CarPlayMonitor's emit hop).
     */
    private fun emitCarPlayEvent(eventName: String, payload: Map<String, Any?>) {
        // SQLite log (only if event logging is enabled).
        try {
            if (BeaconEventLogger.isLoggingEnabled(this)) {
                val identifier = payload["identifier"] as? String
                getOrCreateEventLogger().logEvent(eventName, identifier, payload)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "CarPlay log write failed", e)
        }
        // Remote API forwarder (no-op if unconfigured).
        try { apiForwarder?.forwardEvent(payload) } catch (_: Throwable) {}
        // Native plugin registry (BeaconGeoPlugin etc.).
        when (eventName) {
            "onCarPlayConnected" -> BeaconPluginRegistry.dispatchCarPlayConnected(
                payload["transport"] as? String ?: "unknown"
            )
            "onCarPlayDisconnected" -> BeaconPluginRegistry.dispatchCarPlayDisconnected()
        }
        // Best-effort delivery to the live JS bridge if a module instance is bound.
        // Also track JS delivery state so a future module rebind can reconcile.
        try {
            val m = boundModule?.get()
            m?.forwardCarPlayEventFromService(eventName, payload)
            if (m != null) writeJsConnected(eventName == "onCarPlayConnected")
        } catch (_: Throwable) {}
        // Local notification for connect/disconnect (config-gated).
        try {
            when (eventName) {
                "onCarPlayConnected" -> showCarPlayNotification(
                    "connected",
                    payload["transport"] as? String,
                )
                "onCarPlayDisconnected" -> showCarPlayNotification("disconnected", null)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "CarPlay notification post failed", e)
        }
    }

    private fun showCarPlayNotification(eventType: String, transport: String?) {
        val config = readNotificationConfig()
        val eventsConfig = config.optJSONObject("carPlayEvents")

        // Respect the enabled flag (defaults to true)
        if (eventsConfig != null && !eventsConfig.optBoolean("enabled", true)) return

        val defaultTitle = if (eventType == "connected") "CarPlay Connected" else "CarPlay Disconnected"
        val title = when (eventType) {
            "connected" -> eventsConfig?.optString("connectedTitle")?.takeIf { it.isNotEmpty() } ?: defaultTitle
            else -> eventsConfig?.optString("disconnectedTitle")?.takeIf { it.isNotEmpty() } ?: defaultTitle
        }

        val bodyTemplate = eventsConfig?.optString("body")?.takeIf { it.isNotEmpty() }
            ?: "CarPlay session {event}"
        val message = bodyTemplate
            .replace("{event}", eventType)
            .replace("{transport}", transport ?: "")

        val iconName = eventsConfig?.optString("icon")?.takeIf { it.isNotEmpty() }
        val iconResId = iconName?.let { name ->
            try { resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 } }
            catch (_: Exception) { null }
        } ?: android.R.drawable.ic_dialog_info

        val notification = NotificationCompat.Builder(this, CARPLAY_CHANNEL_ID)
            .setSmallIcon(iconResId)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val notifId = if (eventType == "connected") CARPLAY_CONNECTED_NOTIF_ID
                      else CARPLAY_DISCONNECTED_NOTIF_ID
        try {
            NotificationManagerCompat.from(this).notify(notifId, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — silently skip notification
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val config = readNotificationConfig()
            val channelConfig = config.optJSONObject("channel")

            val channelName = channelConfig?.optString("name")?.takeIf { it.isNotEmpty() }
                ?: "Beacon Monitoring"
            val channelDesc = channelConfig?.optString("description")?.takeIf { it.isNotEmpty() }
                ?: "Used for background iBeacon region monitoring"
            val importance = when (channelConfig?.optString("importance")) {
                "high" -> NotificationManager.IMPORTANCE_HIGH
                "default" -> NotificationManager.IMPORTANCE_DEFAULT
                else -> NotificationManager.IMPORTANCE_LOW
            }

            val notifMgr = getSystemService(NotificationManager::class.java)
            // Only create channel if it doesn't exist yet — preserves user notification preferences
            if (notifMgr?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
                    description = channelDesc
                }
                notifMgr?.createNotificationChannel(channel)
            }
        }
    }

    private fun createCarPlayNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val config = readNotificationConfig()
            val channelConfig = config.optJSONObject("carPlayChannel")

            val channelName = channelConfig?.optString("name")?.takeIf { it.isNotEmpty() }
                ?: "CarPlay / Android Auto"
            val channelDesc = channelConfig?.optString("description")?.takeIf { it.isNotEmpty() }
                ?: "CarPlay and Android Auto connect/disconnect notifications"
            val importance = when (channelConfig?.optString("importance")) {
                "high" -> NotificationManager.IMPORTANCE_HIGH
                "low" -> NotificationManager.IMPORTANCE_LOW
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }

            val notifMgr = getSystemService(NotificationManager::class.java)
            if (notifMgr?.getNotificationChannel(CARPLAY_CHANNEL_ID) == null) {
                val channel = NotificationChannel(CARPLAY_CHANNEL_ID, channelName, importance).apply {
                    description = channelDesc
                }
                notifMgr?.createNotificationChannel(channel)
            }
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

        private const val PREF_IS_MONITORING = "expo.beacon.is_monitoring"
        private const val PREF_CARPLAY_ENABLED = "expo.beacon.carplay_enabled"
        /** Tracks what the JS layer last received — used for reconciled-disconnect on module rebind. */
        private const val PREF_CARPLAY_JS_STATE = "expo.beacon.carplay_js_state"
        private const val EXTRA_RETRY_COUNT = "retryCount"
        /** Intent action: enable CarPlay/Android Auto observation in the foreground service. */
        const val ACTION_ENABLE_CARPLAY = "expo.modules.beacon.ENABLE_CARPLAY"
        /** Intent action: disable CarPlay/Android Auto observation. Stops the service if no other reason to run. */
        const val ACTION_DISABLE_CARPLAY = "expo.modules.beacon.DISABLE_CARPLAY"
        private const val MAX_STARTFOREGROUND_RETRIES = 3
        private const val RETRY_DELAY_MS = 10_000L
        private const val RETRY_SERVICE_REQUEST_CODE = 0x42454143 // "BEAC"
        /** Minimum milliseconds between consecutive loadAndMonitorRegions() calls. */
        private const val LOAD_REGIONS_DEBOUNCE_MS = 500L
        @Volatile private var activeService: BeaconForegroundService? = null
        /** Weak reference to the live ExpoBeaconModule for best-effort JS bridge fan-out. */
        @Volatile private var boundModule: java.lang.ref.WeakReference<ExpoBeaconModule>? = null

        fun start(context: Context) {
            context.getSharedPreferences(PREF_IS_MONITORING, Context.MODE_PRIVATE)
                .edit().putBoolean("active", true).apply()
            ensureNotificationChannel(context)
            ensureCarPlayNotificationChannel(context)
            val intent = Intent(context, BeaconForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREF_IS_MONITORING, Context.MODE_PRIVATE)
                .edit().putBoolean("active", false).apply()
            // Keep the service alive if it's still needed for CarPlay observation;
            // otherwise the user would silently lose CarPlay events when calling
            // stopMonitoring() while CarPlay monitoring was independently enabled.
            if (isCarPlayEnabled(context)) {
                return
            }
            context.stopService(Intent(context, BeaconForegroundService::class.java))
        }

        fun isMonitoringActive(context: Context): Boolean {
            return context.getSharedPreferences(PREF_IS_MONITORING, Context.MODE_PRIVATE)
                .getBoolean("active", false)
        }

        // MARK: - CarPlay public API

        /**
         * Persist whether the user wants CarPlay observation. Cold service starts
         * (e.g. via [BootReceiver]) read this flag in [onCreate] and re-attach
         * the observer automatically.
         */
        internal fun setCarPlayEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF_CARPLAY_ENABLED, Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).apply()
        }

        fun isCarPlayEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREF_CARPLAY_ENABLED, Context.MODE_PRIVATE)
                .getBoolean("enabled", false)
        }

        /**
         * Enable CarPlay observation. Starts the foreground service if it's not
         * already running so the observer survives app suspension and process death.
         * Idempotent and safe to call from any context.
         */
        fun enableCarPlay(context: Context) {
            setCarPlayEnabled(context, true)
            ensureNotificationChannel(context)
            ensureCarPlayNotificationChannel(context)
            // Arm the WorkManager watchdog (15-min periodic safety net) and the
            // AlarmManager loop (~11-min cadence, above the OS quota). Both
            // call back into this same idempotent entry point if the service
            // is killed while CarPlay observation is enabled.
            CarPlayWatchdogWorker.schedule(context)
            BootReceiver.scheduleCarPlayWatchdogAlarm(context)
            val intent = Intent(context, BeaconForegroundService::class.java)
                .setAction(ACTION_ENABLE_CARPLAY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Disable CarPlay observation. The foreground service stops itself
         * if no other monitoring reason remains.
         */
        fun disableCarPlay(context: Context) {
            setCarPlayEnabled(context, false)
            // Stop the safety-net watchdogs — without this they would keep
            // restarting the service on every tick.
            CarPlayWatchdogWorker.cancel(context)
            BootReceiver.cancelCarPlayWatchdogAlarm(context)
            // Wipe the monitor's persisted last-known connection so a future
            // re-enable starts from a clean slate (no stale "was connected"
            // assumption that would arm the bootstrap-grace re-check).
            CarPlayMonitor.clearPersistedState(context)
            // Clear the JS delivery state so a future re-enable starts from a clean slate.
            try {
                context.getSharedPreferences(PREF_CARPLAY_JS_STATE, Context.MODE_PRIVATE)
                    .edit().clear().apply()
            } catch (_: Throwable) {}
            val intent = Intent(context, BeaconForegroundService::class.java)
                .setAction(ACTION_DISABLE_CARPLAY)
            // Best-effort: if the service isn't running, sending the intent will
            // start it just to stop it. Skip startForegroundService unless beacon
            // monitoring is also active (otherwise the start might fail with
            // ForegroundServiceStartNotAllowedException on some Android versions).
            if (activeService != null || isMonitoringActive(context)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        /**
         * Bind a live module instance for best-effort JS-bridge delivery of
         * service-emitted events. The reference is weak; pass `null` from the
         * module's OnDestroy to clear it.
         */
        fun bindModule(module: ExpoBeaconModule?) {
            boundModule = module?.let { java.lang.ref.WeakReference(it) }
            // When a fresh module attaches to an already-running service, re-emit
            // the current CarPlay state so the new JS layer is immediately in sync.
            // Covers the common "app killed while car connected → user reopens app" path.
            if (module != null) activeService?.reEmitCarPlayStateIfNeeded(module)
        }

        fun getMonitoringRuntimeSnapshot(): Map<String, MonitoringRuntimeState> {
            return activeService?.snapshotMonitoringRuntimeState() ?: emptyMap()
        }

        /**
         * Returns a snapshot of the current CarPlay / Android Auto connection state.
         * Reads from the live [CarPlayMonitor] if the foreground service is running,
         * otherwise falls back to the persisted last-known state.
         */
        fun getCarPlayStatus(context: Context): Map<String, Any?> {
            val monitor = activeService?.carPlayMonitor
            if (monitor != null && monitor.isObserving()) {
                val connPayload = monitor.buildConnectedPayload()
                if (connPayload != null) {
                    return connPayload + mapOf("connected" to true)
                }
                return mapOf("connected" to false)
            }
            // Fallback: persisted last-known state (valid even when service is stopped).
            val connected = try {
                context.getSharedPreferences("expo_beacon_carplay_monitor", Context.MODE_PRIVATE)
                    .getBoolean("last_connected", false)
            } catch (_: Throwable) { false }
            return mapOf("connected" to connected)
        }

        /**
         * Returns a diagnostic snapshot of the CarPlay / Android Auto detection
         * pipeline. Probes for the host-app AA registration meta-data and the
         * resolvability of the CarConnection content provider so callers can
         * tell from JS whether the underlying detection requirements are met.
         */
        fun getCarPlayDiagnostics(context: Context): Map<String, Any?> {
            val pm = context.packageManager
            // 1) Is the host app declared as an Android-Auto-aware app?
            val metadataPresent = try {
                val ai = pm.getApplicationInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_META_DATA,
                )
                ai.metaData?.containsKey("com.google.android.gms.car.application") == true
            } catch (_: Throwable) { false }

            // 2) Can the system resolve any provider for the CAR_PROVIDER action?
            //    This combines (a) <queries> visibility on API 30+, and (b) a
            //    Gearhead / AAOS install that actually advertises the provider.
            val providerQueryable = try {
                val intent = Intent("androidx.car.app.connection.action.CAR_PROVIDER")
                val resolved = pm.queryIntentContentProviders(intent, 0)
                resolved.isNotEmpty()
            } catch (_: Throwable) { false }

            val monitor = activeService?.carPlayMonitor
            val lastRaw: Int? = if (monitor != null && monitor.hasObservedValue) monitor.lastObservedType else null
            val observerActive = monitor?.isObserving() == true
            val serviceAlive = activeService != null

            return mapOf(
                "isCarAppMetadataPresent" to metadataPresent,
                "isCarProviderQueryable" to providerQueryable,
                "lastRawConnectionType" to lastRaw,
                "observerActive" to observerActive,
                "serviceAlive" to serviceAlive,
            )
        }

        /**
         * Ensure the notification channel exists. Must be called before building
         * a notification from a non-service context (e.g. ExpoBeaconModule).
         */
        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val json = context.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                    .getString("config", null)
                val config = try { org.json.JSONObject(json ?: "") } catch (_: Exception) { org.json.JSONObject() }
                val channelConfig = config.optJSONObject("channel")

                val channelName = channelConfig?.optString("name")?.takeIf { it.isNotEmpty() }
                    ?: "Beacon Monitoring"
                val channelDesc = channelConfig?.optString("description")?.takeIf { it.isNotEmpty() }
                    ?: "Used for background iBeacon region monitoring"
                val importance = when (channelConfig?.optString("importance")) {
                    "high" -> NotificationManager.IMPORTANCE_HIGH
                    "default" -> NotificationManager.IMPORTANCE_DEFAULT
                    else -> NotificationManager.IMPORTANCE_LOW
                }

                val notifMgr = context.getSystemService(NotificationManager::class.java)
                if (notifMgr?.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(CHANNEL_ID, channelName, importance).apply {
                        description = channelDesc
                    }
                    notifMgr?.createNotificationChannel(channel)
                }
            }
        }

        /**
         * Ensure the CarPlay notification channel exists. Mirrors
         * [ensureNotificationChannel] for the dedicated CarPlay channel so that
         * users can mute CarPlay notifications independently in system settings.
         */
        fun ensureCarPlayNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val json = context.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                    .getString("config", null)
                val config = try { org.json.JSONObject(json ?: "") } catch (_: Exception) { org.json.JSONObject() }
                val channelConfig = config.optJSONObject("carPlayChannel")

                val channelName = channelConfig?.optString("name")?.takeIf { it.isNotEmpty() }
                    ?: "CarPlay / Android Auto"
                val channelDesc = channelConfig?.optString("description")?.takeIf { it.isNotEmpty() }
                    ?: "CarPlay and Android Auto connect/disconnect notifications"
                val importance = when (channelConfig?.optString("importance")) {
                    "high" -> NotificationManager.IMPORTANCE_HIGH
                    "low" -> NotificationManager.IMPORTANCE_LOW
                    else -> NotificationManager.IMPORTANCE_DEFAULT
                }

                val notifMgr = context.getSystemService(NotificationManager::class.java)
                if (notifMgr?.getNotificationChannel(CARPLAY_CHANNEL_ID) == null) {
                    val channel = NotificationChannel(CARPLAY_CHANNEL_ID, channelName, importance).apply {
                        description = channelDesc
                    }
                    notifMgr?.createNotificationChannel(channel)
                }
            }
        }

        /**
         * Build the foreground notification from any Context (service or module).
         * Shared so that ExpoBeaconModule can pass the same notification to
         * enableForegroundServiceScanning() before the service starts.
         */
        fun buildForegroundNotification(context: Context): Notification {
            val json = context.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                .getString("config", null)
            val config = try { org.json.JSONObject(json ?: "") } catch (_: Exception) { org.json.JSONObject() }
            val fgConfig = config.optJSONObject("foregroundService")

            // If beacon monitoring is not active and the service is alive only
            // for CarPlay observation, fall back to a generic notification so
            // users don't see "Monitoring for iBeacons" when no beacons are being
            // monitored.
            val carPlayOnly = !isMonitoringActive(context) && isCarPlayEnabled(context)
            val defaultTitle = if (carPlayOnly) "Connected device monitoring active" else "Beacon Monitoring Active"
            val defaultText = if (carPlayOnly) "Monitoring connected vehicle (CarPlay/Android Auto)" else "Monitoring for iBeacons in the background"

            val title = fgConfig?.optString("title")?.takeIf { it.isNotEmpty() } ?: defaultTitle
            val text = fgConfig?.optString("text")?.takeIf { it.isNotEmpty() } ?: defaultText
            val iconName = fgConfig?.optString("icon")?.takeIf { it.isNotEmpty() }
            val iconResId = iconName?.let { name ->
                try { context.resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 } }
                catch (_: Exception) { null }
            } ?: android.R.drawable.ic_dialog_info

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(iconResId)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
        }
    }

    private fun readNotificationConfig(): org.json.JSONObject {
        val json = getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
            .getString("config", null) ?: return org.json.JSONObject()
        return try { org.json.JSONObject(json) } catch (_: Exception) { org.json.JSONObject() }
    }

    override fun onDestroy() {
        if (activeService === this) {
            activeService = null
        }
        // Stop CarPlay observer first so the LiveData observer doesn't leak
        // past the service lifecycle. Safe even if it was never started.
        try {
            carPlayMonitor?.stop()
        } catch (_: Throwable) {}
        carPlayMonitor = null
        val wasBound = serviceConnected
        serviceConnected = false
        timeoutHandler.removeCallbacksAndMessages(null)
        timeoutRunnables.clear()
        inactivityRunnables.clear()
        beaconTimeouts.clear()
        lastSeenAtMs.clear()
        monitoredRegionIds.clear()
        releaseEventLogger()
        beaconManager.removeMonitorNotifier(monitorNotifier)
        beaconManager.removeRangeNotifier(rangeNotifier)
        beaconManager.removeRangeNotifier(distanceLoggingRangeNotifier)
        distanceLogRegions.forEach {
            try { beaconManager.stopRangingBeaconsInRegion(it) } catch (_: RemoteException) {}
        }
        distanceLogRegions.clear()
        enteredRegions.clear()
        enterCounters.clear()
        exitCounters.clear()
        missCounters.clear()
        notifIdMap.clear()
        monitoredRegions.forEach {
            try { beaconManager.stopMonitoringBeaconsInRegion(it) } catch (_: RemoteException) {}
        }
        // Only unbind if we successfully bound — CarPlay-only service
        // instances skip beaconManager.bind() in onStartCommand.
        if (wasBound) {
            try { beaconManager.unbind(this) } catch (_: Throwable) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Defensive override: when the user swipes the host app away from
     * Recents, Android delivers `onTaskRemoved` to bound services. Our
     * service is started via `startForegroundService` with `START_STICKY`
     * and is intended to keep running independently of the app's task —
     * specifically so that CarPlay / Android Auto observation and beacon
     * monitoring continue across swipe-away. We intentionally do NOT call
     * `stopSelf()` here; the system will redeliver `onStartCommand` on
     * its own if the process is later reclaimed.
     *
    * We also arm a near-term keepalive alarm so devices that tear down the
    * process on task removal recover before the slower periodic watchdogs run.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val keepAlive = isMonitoringActive(this) || isCarPlayEnabled(this)
        if (keepAlive) {
            try {
                if (isCarPlayEnabled(this)) {
                    startCarPlayObserverInternal()
                    CarPlayWatchdogWorker.schedule(this)
                    BootReceiver.scheduleCarPlayWatchdogAlarm(this)
                }
                BootReceiver.scheduleTaskRemovedKeepAlive(this)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to arm task-removed keepalive", t)
            }
        }
        Log.d(
            TAG,
            "onTaskRemoved received (monitoring=${isMonitoringActive(this)}, " +
                "carPlay=${isCarPlayEnabled(this)}, keepAlive=$keepAlive). " +
                "Service will remain in foreground."
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun getApplicationContext(): Context = super.getApplicationContext()
}

const val ACTION_BEACON_EVENT = "expo.modules.beacon.BEACON_EVENT"
