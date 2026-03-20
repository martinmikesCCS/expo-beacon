package expo.modules.beacon

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.altbeacon.beacon.*
import org.json.JSONArray

private const val CHANNEL_ID = "expo_beacon_channel"
private const val FOREGROUND_NOTIF_ID = 1001
/**
 * Base ID for per-beacon enter/exit notifications; incremented per unique region.
 * With FOREGROUND_NOTIF_ID at 1001, this allows up to 999 unique regions
 * before ID collision. Sufficient for real-world beacon deployments.
 */
private const val ENTER_EXIT_NOTIF_BASE_ID = 2000

class BeaconForegroundService : Service(), BeaconConsumer {

    private lateinit var beaconManager: BeaconManager
    private val monitoredRegions = mutableListOf<Region>()

    // Distance filtering
    @Volatile private var maxDistance: Double? = null
    private val rangingRegions = java.util.concurrent.CopyOnWriteArraySet<Region>()
    private val enteredRegions = java.util.concurrent.CopyOnWriteArraySet<String>()

    // Hysteresis counters (synchronized on distanceLock)
    private val distanceLock = Any()
    private val enterCounters = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val exitCounters = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val missCounters = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Notification ID counter for unique per-beacon notifications
    private val notifIdCounter = AtomicInteger(0)
    private val notifIdMap = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Distance logging
    private val distanceLogRegions = java.util.concurrent.CopyOnWriteArraySet<Region>()

    companion object {
        private const val PREF_IS_MONITORING = "expo.beacon.is_monitoring"

        fun start(context: Context) {
            context.getSharedPreferences(PREF_IS_MONITORING, Context.MODE_PRIVATE)
                .edit().putBoolean("active", true).apply()
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
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        beaconManager = BeaconManager.getInstanceForApplication(this).also { manager ->
            BeaconParsers.ensureRegistered(manager)
            try { manager.setEnableScheduledScanJobs(false) } catch (e: IllegalStateException) { Log.w(TAG, "setEnableScheduledScanJobs failed", e) }
            manager.setBackgroundBetweenScanPeriod(5000L)  // 5s between scans
            manager.setBackgroundScanPeriod(1100L)         // 1.1s scan window
            manager.setForegroundScanPeriod(1000L)         // 1s scan window for distance logging
            manager.setForegroundBetweenScanPeriod(0L)     // no pause between scans
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                FOREGROUND_NOTIF_ID,
                buildForegroundNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(FOREGROUND_NOTIF_ID, buildForegroundNotification())
        }
        beaconManager.bind(this)
        return START_STICKY
    }

    override fun onBeaconServiceConnect() {
        // Read max distance from options prefs
        val optPrefs = getSharedPreferences(MONITORING_OPTIONS_PREFS, Context.MODE_PRIVATE)
        maxDistance = optPrefs.getString("max_distance", null)?.toDoubleOrNull()

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

        // Stop previous regions and distance-log ranging
        distanceLogRegions.forEach {
            try { beaconManager.stopRangingBeaconsInRegion(it) } catch (_: RemoteException) {}
        }
        distanceLogRegions.clear()
        monitoredRegions.forEach {
            try { beaconManager.stopMonitoringBeaconsInRegion(it) } catch (_: RemoteException) {}
        }
        monitoredRegions.clear()

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
            Log.d(TAG, "No paired beacons — stopping idle foreground service")
            stopSelf()
        }
    }

    // Distance logging only — emits distance broadcasts. Enter/exit logic lives in rangeNotifier.
    private val distanceLoggingRangeNotifier = RangeNotifier { beacons, region ->
        val closest = beacons.filter { it.distance >= 0 }.minByOrNull { it.distance }
        if (closest != null) {
            sendBeaconBroadcast(region, "distance", closest.distance)
        }
    }

    private val monitorNotifier = object : MonitorNotifier {
        override fun didEnterRegion(region: Region) {
            val maxDist = maxDistance
            if (maxDist != null) {
                // Mark region for distance confirmation — ranging is already active via distance logging
                rangingRegions.add(region)
            } else {
                enteredRegions.add(region.uniqueId)
                sendBeaconBroadcast(region, "enter", -1.0)
                showEnterExitNotification(region, "enter")
            }
        }

        override fun didExitRegion(region: Region) {
            rangingRegions.remove(region)

            if (maxDistance != null) {
                // Distance ranging normally handles exit. But if the beacon was
                // in "entered" state when OS fires didExitRegion, we must emit
                // the exit event — ranging will no longer receive readings.
                val wasEntered = enteredRegions.remove(region.uniqueId)
                synchronized(distanceLock) {
                    enterCounters.remove(region.uniqueId)
                    exitCounters.remove(region.uniqueId)
                    missCounters.remove(region.uniqueId)
                }
                if (wasEntered) {
                    sendBeaconBroadcast(region, "exit", -1.0)
                    showEnterExitNotification(region, "exit")
                }
                return
            }

            enteredRegions.remove(region.uniqueId)
            sendBeaconBroadcast(region, "exit", -1.0)
            showEnterExitNotification(region, "exit")
        }

        override fun didDetermineStateForRegion(state: Int, region: Region) {
            // Intentionally empty — enter/exit handled by didEnterRegion/didExitRegion.
        }
    }

    // Single source of truth for distance-based enter/exit with hysteresis.
    // Processes regions added to rangingRegions by monitorNotifier.didEnterRegion,
    // and also handles exit via miss counting when beacons disappear.
    private val rangeNotifier = RangeNotifier { beacons, region ->
        val maxDist = maxDistance ?: return@RangeNotifier
        if (!rangingRegions.contains(region) && !enteredRegions.contains(region.uniqueId)) return@RangeNotifier

        val beacon = beacons
            .filter { it.distance >= 0 }
            .minByOrNull { it.distance }

        synchronized(distanceLock) {
            if (beacon != null) {
                // Got a valid reading — reset miss counter
                missCounters[region.uniqueId] = 0

                val action = evaluateDistanceHysteresis(region.uniqueId, beacon.distance, maxDist)
                when (action) {
                    HysteresisAction.ENTER -> {
                        enteredRegions.add(region.uniqueId)
                        rangingRegions.remove(region)
                        sendBeaconBroadcast(region, "enter", beacon.distance)
                        showEnterExitNotification(region, "enter")
                    }
                    HysteresisAction.EXIT -> {
                        enteredRegions.remove(region.uniqueId)
                        rangingRegions.add(region)
                        sendBeaconBroadcast(region, "exit", beacon.distance)
                        showEnterExitNotification(region, "exit")
                    }
                    HysteresisAction.NONE -> {}
                }
            } else {
                // No valid beacon reading — track consecutive misses for exit detection
                val count = (missCounters[region.uniqueId] ?: 0) + 1
                missCounters[region.uniqueId] = count

                if (enteredRegions.contains(region.uniqueId) && count >= EXIT_MISS_THRESHOLD) {
                    enteredRegions.remove(region.uniqueId)
                    missCounters[region.uniqueId] = 0
                    enterCounters[region.uniqueId] = 0
                    exitCounters[region.uniqueId] = 0
                    sendBeaconBroadcast(region, "exit", -1.0)
                    showEnterExitNotification(region, "exit")
                }
            }
        }
    }

    // MARK: - Distance-based enter/exit hysteresis

    private enum class HysteresisAction { NONE, ENTER, EXIT }

    /**
     * Evaluate distance-based enter/exit with hysteresis counters.
     * Must be called within synchronized(distanceLock).
     * Mirrors [ExpoBeaconModule.swift evaluateDistanceHysteresis].
     */
    private fun evaluateDistanceHysteresis(
        regionId: String,
        distance: Double,
        maxDist: Double
    ): HysteresisAction {
        if (distance <= maxDist) {
            // Inside threshold
            exitCounters[regionId] = 0
            val count = (enterCounters[regionId] ?: 0) + 1
            enterCounters[regionId] = count
            if (!enteredRegions.contains(regionId) && count >= HYSTERESIS_COUNT) {
                enterCounters[regionId] = 0
                return HysteresisAction.ENTER
            }
        } else {
            // Outside threshold
            enterCounters[regionId] = 0
            val count = (exitCounters[regionId] ?: 0) + 1
            exitCounters[regionId] = count
            if (enteredRegions.contains(regionId) && count >= HYSTERESIS_COUNT) {
                exitCounters[regionId] = 0
                return HysteresisAction.EXIT
            }
        }
        return HysteresisAction.NONE
    }

    private fun sendBeaconBroadcast(region: Region, eventType: String, distance: Double) {
        // Determine if this is an Eddystone region based on identifier format
        // Eddystone regions have id1 as a hex namespace (not a UUID)
        val id1Str = region.id1?.toString() ?: ""
        val isEddystone = id1Str.startsWith("0x")

        val intent = Intent(ACTION_BEACON_EVENT).apply {
            putExtra("identifier", region.uniqueId)
            putExtra("event", eventType)
            putExtra("distance", distance)
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
        val config = readNotificationConfig()
        val fgConfig = config.optJSONObject("foregroundService")

        val title = fgConfig?.optString("title")?.takeIf { it.isNotEmpty() }
            ?: "Beacon Monitoring Active"
        val text = fgConfig?.optString("text")?.takeIf { it.isNotEmpty() }
            ?: "Monitoring for iBeacons in the background"
        val iconName = fgConfig?.optString("icon")?.takeIf { it.isNotEmpty() }
        val iconResId = iconName?.let { name ->
            try { resources.getIdentifier(name, "drawable", packageName).takeIf { it != 0 } }
            catch (_: Exception) { null }
        } ?: android.R.drawable.ic_dialog_info

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(iconResId)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun readNotificationConfig(): org.json.JSONObject {
        val json = getSharedPreferences(NOTIFICATION_CONFIG_PREFS, Context.MODE_PRIVATE)
            .getString("config", null) ?: return org.json.JSONObject()
        return try { org.json.JSONObject(json) } catch (_: Exception) { org.json.JSONObject() }
    }

    override fun onDestroy() {
        beaconManager.removeMonitorNotifier(monitorNotifier)
        beaconManager.removeRangeNotifier(rangeNotifier)
        beaconManager.removeRangeNotifier(distanceLoggingRangeNotifier)
        rangingRegions.forEach {
            try { beaconManager.stopRangingBeaconsInRegion(it) } catch (_: RemoteException) {}
        }
        rangingRegions.clear()
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
