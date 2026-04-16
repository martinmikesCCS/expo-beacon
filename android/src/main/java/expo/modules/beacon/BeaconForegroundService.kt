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
internal const val FOREGROUND_NOTIF_ID = 1001
/**
 * Base ID for per-beacon enter/exit notifications; incremented per unique region.
 * With FOREGROUND_NOTIF_ID at 1001, this allows up to 999 unique regions
 * before ID collision. Sufficient for real-world beacon deployments.
 */
private const val ENTER_EXIT_NOTIF_BASE_ID = 2000

class BeaconForegroundService : Service(), BeaconConsumer {

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

    // Distance logging
    private val distanceLogRegions = java.util.concurrent.CopyOnWriteArraySet<Region>()

    // Timeout timers — fire once after beacon stays in range for configured duration
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnables = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
    // Per-beacon timeout seconds lookup (identifier → seconds), loaded from paired data
    private val beaconTimeouts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var eventLogger: BeaconEventLogger? = null
    private var apiForwarder: BeaconApiForwarder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
            // SecurityException on Android 14+ if BT permissions missing,
            // or other platform-specific issues. Stop gracefully instead of crashing.
            Log.e(TAG, "startForeground failed — stopping service", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (serviceConnected) {
            // Already bound from a prior onStartCommand — reload regions directly
            // so that re-starting monitoring from JS always takes effect.
            loadAndMonitorRegions()
        } else {
            beaconManager.bind(this)
        }
        return START_STICKY
    }

    override fun onBeaconServiceConnect() {
        serviceConnected = true
        // Read max distance, exit distance, and min RSSI from options prefs
        val optPrefs = getSharedPreferences(MONITORING_OPTIONS_PREFS, Context.MODE_PRIVATE)
        maxDistance = optPrefs.getString("max_distance", null)?.toDoubleOrNull()
        exitDistance = optPrefs.getString("exit_distance", null)?.toDoubleOrNull()
        minRssiThreshold = optPrefs.getInt("min_rssi", DEFAULT_MIN_RSSI)

        beaconManager.addMonitorNotifier(monitorNotifier)
        beaconManager.addRangeNotifier(rangeNotifier)
        beaconManager.addRangeNotifier(distanceLoggingRangeNotifier)
        loadAndMonitorRegions()
    }

    private fun loadAndMonitorRegions() {
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
            }
            // Start ranging this region for distance logging
            if (distanceLogRegions.add(region)) {
                try {
                    beaconManager.startRangingBeaconsInRegion(region)
                } catch (e: RemoteException) {
                    distanceLogRegions.remove(region)
                    Log.e(TAG, "Failed to start ranging iBeacon region ${region.uniqueId}", e)
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
            }
            if (distanceLogRegions.add(region)) {
                try {
                    beaconManager.startRangingBeaconsInRegion(region)
                } catch (ex: RemoteException) {
                    distanceLogRegions.remove(region)
                    Log.e(TAG, "Failed to start ranging Eddystone region $identifier", ex)
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
        if (!monitoredRegionIds.contains(region.uniqueId)) return@RangeNotifier
        val closest = beacons.filter { it.distance >= 0 && it.rssi >= minRssiThreshold }.minByOrNull { it.distance }
        if (closest != null) {
            lastSeenAtMs[region.uniqueId] = SystemClock.elapsedRealtime()
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
                // OS-level exit safety net — start the timeout clock.
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

                // Apply EMA smoothing; jump guard returns null for outliers
                val smoothed = smoothDistance(region.uniqueId, beacon.distance)
                if (smoothed == null) {
                    // Outlier — treat as miss without resetting enter counter
                    return@RangeNotifier
                }

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
                        // Beacon left — start the timeout clock.
                        scheduleTimeoutIfConfigured(region)
                    }
                    HysteresisAction.NONE -> {}
                }
            } else {
                // No valid beacon reading — break distance hysteresis streaks and
                // track consecutive misses for disappearance-based exit detection.
                enterCounters[region.uniqueId] = 0
                exitCounters[region.uniqueId] = 0
                val count = (missCounters[region.uniqueId] ?: 0) + 1
                missCounters[region.uniqueId] = count

                if (enteredRegions.contains(region.uniqueId) && count >= EXIT_MISS_THRESHOLD) {
                    enteredRegions.remove(region.uniqueId)
                    missCounters[region.uniqueId] = 0
                    enterCounters[region.uniqueId] = 0
                    exitCounters[region.uniqueId] = 0
                    sendBeaconBroadcast(region, "exit", -1.0)
                    showEnterExitNotification(region, "exit")
                    // Beacon disappeared — start the timeout clock.
                    scheduleTimeoutIfConfigured(region)
                }
            }
        }
    }

    // MARK: - Distance-based enter/exit hysteresis

    private enum class HysteresisAction { NONE, ENTER, EXIT }

    /**
     * Apply exponential moving average (EMA) smoothing to a raw distance reading.
     * Returns null if the reading is a jump outlier (raw differs from smoothed by > DISTANCE_JUMP_FACTOR).
     */
    private fun smoothDistance(regionId: String, rawDistance: Double): Double? {
        val prev = smoothedDistances[regionId]
        if (prev == null) {
            smoothedDistances[regionId] = rawDistance
            return rawDistance
        }
        // Jump guard: if the raw value is wildly different, treat as outlier
        val ratio = if (prev > 0.001) rawDistance / prev else rawDistance
        if (ratio > DISTANCE_JUMP_FACTOR || (ratio > 0 && ratio < 1.0 / DISTANCE_JUMP_FACTOR)) {
            return null
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
            if (count >= HYSTERESIS_COUNT) {
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
            if (!enteredRegions.contains(regionId) && count >= HYSTERESIS_COUNT) {
                enterCounters[regionId] = 0
                return HysteresisAction.ENTER
            }
        } else if (distance > exitDist) {
            // Outside exit threshold
            enterCounters[regionId] = 0
            val count = (exitCounters[regionId] ?: 0) + 1
            exitCounters[regionId] = count
            if (enteredRegions.contains(regionId) && count >= HYSTERESIS_COUNT) {
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
        }
        timeoutRunnables[region.uniqueId] = runnable
        timeoutHandler.postDelayed(runnable, seconds * 1000L)
    }

    private fun cancelTimeout(regionId: String) {
        timeoutRunnables.remove(regionId)?.let { timeoutHandler.removeCallbacks(it) }
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

        // Forward enter/exit/timeout events to remote API (skip distance — too frequent)
        if (eventType != "distance") {
            apiForwarder?.forwardEvent(params)
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

        val defaultTitle = if (eventType == "enter") "Beacon Entered" else "Beacon Exited"
        val title = if (eventType == "enter") {
            eventsConfig?.optString("enterTitle")?.takeIf { it.isNotEmpty() } ?: defaultTitle
        } else {
            eventsConfig?.optString("exitTitle")?.takeIf { it.isNotEmpty() } ?: defaultTitle
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

    private fun buildForegroundNotification(): Notification {
        return Companion.buildForegroundNotification(this)
    }

    companion object {
        /** EMA weight for new readings. 0.4 balances responsiveness vs noise rejection. */
        const val DISTANCE_EMA_ALPHA = 0.4
        /** If raw distance differs from smoothed by more than this factor, treat as outlier. */
        const val DISTANCE_JUMP_FACTOR = 5.0

        private const val PREF_IS_MONITORING = "expo.beacon.is_monitoring"

        fun start(context: Context) {
            context.getSharedPreferences(PREF_IS_MONITORING, Context.MODE_PRIVATE)
                .edit().putBoolean("active", true).apply()
            ensureNotificationChannel(context)
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
            context.stopService(Intent(context, BeaconForegroundService::class.java))
        }

        fun isMonitoringActive(context: Context): Boolean {
            return context.getSharedPreferences(PREF_IS_MONITORING, Context.MODE_PRIVATE)
                .getBoolean("active", false)
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
         * Build the foreground notification from any Context (service or module).
         * Shared so that ExpoBeaconModule can pass the same notification to
         * enableForegroundServiceScanning() before the service starts.
         */
        fun buildForegroundNotification(context: Context): Notification {
            val json = context.getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
                .getString("config", null)
            val config = try { org.json.JSONObject(json ?: "") } catch (_: Exception) { org.json.JSONObject() }
            val fgConfig = config.optJSONObject("foregroundService")

            val title = fgConfig?.optString("title")?.takeIf { it.isNotEmpty() }
                ?: "Beacon Monitoring Active"
            val text = fgConfig?.optString("text")?.takeIf { it.isNotEmpty() }
                ?: "Monitoring for iBeacons in the background"
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
        serviceConnected = false
        timeoutHandler.removeCallbacksAndMessages(null)
        timeoutRunnables.clear()
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
        beaconManager.unbind(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun getApplicationContext(): Context = super.getApplicationContext()
}

const val ACTION_BEACON_EVENT = "expo.modules.beacon.BEACON_EVENT"
