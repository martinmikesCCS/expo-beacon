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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.altbeacon.beacon.*
import org.json.JSONArray

private const val PREFS_NAME = "expo.beacon.paired"
private const val PREFS_KEY = "paired_beacons"
private const val CHANNEL_ID = "expo_beacon_channel"
private const val FOREGROUND_NOTIF_ID = 1001
private const val ENTER_EXIT_NOTIF_BASE_ID = 2000

/// Number of consecutive ranging misses before emitting a distance-based exit event.
private const val EXIT_MISS_THRESHOLD = 3
/// Number of consecutive readings required to confirm a distance-based enter or exit transition.
private const val HYSTERESIS_COUNT = 3

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
    private var notifIdCounter = 0
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
            // Register iBeacon parser if not already registered
            if (manager.beaconParsers.none { it.layout?.contains("0215") == true }) {
                manager.beaconParsers.add(
                    BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25")
                )
            }
            // Use continuous scanning (not JobScheduler) for foreground service
            // Guard: throws if called after ranging/monitoring has already started
            try { manager.setEnableScheduledScanJobs(false) } catch (_: IllegalStateException) {}
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
        val optPrefs = getSharedPreferences("expo.beacon.monitoring_options", Context.MODE_PRIVATE)
        maxDistance = if (optPrefs.contains("max_distance"))
            optPrefs.getFloat("max_distance", Float.MAX_VALUE).toDouble()
        else null

        beaconManager.addMonitorNotifier(monitorNotifier)
        beaconManager.addRangeNotifier(rangeNotifier)
        beaconManager.addRangeNotifier(distanceLoggingRangeNotifier)
        loadAndMonitorRegions()
    }

    private fun loadAndMonitorRegions() {
        val prefs: SharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        val beacons = try { JSONArray(json) } catch (_: Exception) { JSONArray() }

        // Stop previous regions and distance-log ranging
        distanceLogRegions.forEach {
            try { beaconManager.stopRangingBeaconsInRegion(it) } catch (_: RemoteException) {}
        }
        distanceLogRegions.clear()
        monitoredRegions.forEach {
            try { beaconManager.stopMonitoringBeaconsInRegion(it) } catch (_: RemoteException) {}
        }
        monitoredRegions.clear()

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
                e.printStackTrace()
            }
            // Start ranging this region for distance logging
            if (distanceLogRegions.add(region)) {
                try {
                    beaconManager.startRangingBeaconsInRegion(region)
                } catch (e: RemoteException) {
                    distanceLogRegions.remove(region)
                    e.printStackTrace()
                }
            }
        }
    }

    private val distanceLoggingRangeNotifier = RangeNotifier { beacons, region ->
        val closest = beacons.filter { it.distance >= 0 }.minByOrNull { it.distance }
        if (closest != null) {
            Log.d("BeaconMonitor", "[${region.uniqueId}] distance: ${"%.2f".format(closest.distance)} m  rssi=${closest.rssi}  txPower=${closest.txPower}")
            sendBeaconBroadcast(region, "distance", closest.distance)

            // Reset miss counter — we got a valid reading
            missCounters[region.uniqueId] = 0

            val maxDist = maxDistance
            if (maxDist != null) {
                synchronized(distanceLock) {
                    if (closest.distance <= maxDist) {
                        // Reading is inside threshold
                        exitCounters[region.uniqueId] = 0
                        val count = (enterCounters[region.uniqueId] ?: 0) + 1
                        enterCounters[region.uniqueId] = count

                        if (!enteredRegions.contains(region.uniqueId) && count >= HYSTERESIS_COUNT) {
                            enteredRegions.add(region.uniqueId)
                            enterCounters[region.uniqueId] = 0
                            rangingRegions.remove(region)
                            sendBeaconBroadcast(region, "enter", closest.distance)
                            showEnterExitNotification(region, "enter")
                        }
                    } else {
                        // Reading is outside threshold
                        enterCounters[region.uniqueId] = 0
                        val count = (exitCounters[region.uniqueId] ?: 0) + 1
                        exitCounters[region.uniqueId] = count

                        if (enteredRegions.contains(region.uniqueId) && count >= HYSTERESIS_COUNT) {
                            enteredRegions.remove(region.uniqueId)
                            exitCounters[region.uniqueId] = 0
                            rangingRegions.add(region)
                            sendBeaconBroadcast(region, "exit", closest.distance)
                            showEnterExitNotification(region, "exit")
                        }
                    }
                }
            }
        } else {
            Log.d("BeaconMonitor", "[${region.uniqueId}] no beacons in range")

            // Beacon may have disappeared — track consecutive misses
            val maxDist = maxDistance
            if (maxDist != null) {
                val count = (missCounters[region.uniqueId] ?: 0) + 1
                missCounters[region.uniqueId] = count

                if (enteredRegions.contains(region.uniqueId) && count >= EXIT_MISS_THRESHOLD) {
                    synchronized(distanceLock) {
                        if (enteredRegions.remove(region.uniqueId)) {
                            missCounters[region.uniqueId] = 0
                            enterCounters[region.uniqueId] = 0
                            exitCounters[region.uniqueId] = 0
                            sendBeaconBroadcast(region, "exit", -1.0)
                            showEnterExitNotification(region, "exit")
                        }
                    }
                }
            }
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
                // When maxDistance is set, distance ranging handles exit events.
                // Just clean up tracked state so distance-driven exit can fire.
                enteredRegions.remove(region.uniqueId)
                return
            }

            enteredRegions.remove(region.uniqueId)
            sendBeaconBroadcast(region, "exit", -1.0)
            showEnterExitNotification(region, "exit")
        }

        override fun didDetermineStateForRegion(state: Int, region: Region) {}
    }

    private val rangeNotifier = RangeNotifier { beacons, region ->
        val maxDist = maxDistance ?: return@RangeNotifier
        if (!rangingRegions.contains(region)) return@RangeNotifier

        // Find the matching beacon (best distance reading)
        val beacon = beacons
            .filter { it.distance >= 0 }
            .minByOrNull { it.distance } ?: return@RangeNotifier

        synchronized(distanceLock) {
            if (beacon.distance <= maxDist && !enteredRegions.contains(region.uniqueId)) {
                val count = (enterCounters[region.uniqueId] ?: 0) + 1
                enterCounters[region.uniqueId] = count

                if (count >= HYSTERESIS_COUNT) {
                    enteredRegions.add(region.uniqueId)
                    enterCounters[region.uniqueId] = 0
                    rangingRegions.remove(region)
                    sendBeaconBroadcast(region, "enter", beacon.distance)
                    showEnterExitNotification(region, "enter")
                }
            }
        }
    }

    private fun sendBeaconBroadcast(region: Region, eventType: String, distance: Double) {
        val intent = Intent(ACTION_BEACON_EVENT).apply {
            putExtra("identifier", region.uniqueId)
            putExtra("uuid", region.id1?.toString() ?: "")
            putExtra("major", region.id2?.toInt() ?: 0)
            putExtra("minor", region.id3?.toInt() ?: 0)
            putExtra("event", eventType)
            putExtra("distance", distance)
            setPackage(packageName)
        }
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
            val notifId = notifIdMap.getOrPut(region.uniqueId) {
                ENTER_EXIT_NOTIF_BASE_ID + notifIdCounter++
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
        val json = getSharedPreferences("expo.beacon.notification_config", Context.MODE_PRIVATE)
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
