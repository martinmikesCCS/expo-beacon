package expo.modules.beacon

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wraps [CarConnection] LiveData to surface Android Auto / Automotive OS
 * connection events. No special permissions or Android Auto certification
 * are required — `CarConnection.type` is read-only state.
 *
 * Lifecycle: this monitor uses `observeForever` and therefore must be
 * explicitly stopped to avoid leaks. The owning module is responsible for
 * calling [stop] in `OnDestroy`.
 *
 * All observer registration / removal happens on the main thread because
 * [LiveData.observeForever] requires it.
 */
internal class CarPlayMonitor(private val context: Context) {

    /** Emit callback signature: (eventName, payload). */
    fun interface Emit {
        operator fun invoke(eventName: String, payload: Map<String, Any?>)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val liveData: LiveData<Int> = CarConnection(context.applicationContext).type
    private var observer: Observer<Int>? = null
    private var emit: Emit? = null
    @Volatile private var lastConnected: Boolean? = null
    /**
     * Pending bootstrap-grace runnable that will re-check the connection state
     * after [BOOTSTRAP_GRACE_MS] before emitting a `disconnected` event.
     * Used only when the persisted state was `connected` but the first
     * observed value after [start] is `NOT_CONNECTED` — this absorbs the
     * LiveData/Content-Provider initial-value race that occurs after a
     * cold service restart.
     */
    private var pendingDisconnectCheck: Runnable? = null
    /** Most recent raw type seen from the LiveData; used by the grace re-check. */
    @Volatile internal var lastObservedType: Int = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        private set

    /**
     * Whether the observer has ever received a value from the LiveData since
     * [start] was called. Useful for diagnostics — if this is `false` 5+
     * seconds after `start()`, the `CarConnection` content provider isn't
     * resolving (most commonly because the host app isn't registered as an
     * Android Auto app via `automotive_app_desc.xml`).
     */
    @Volatile internal var hasObservedValue: Boolean = false
        private set

    /**
     * Begin observing connection state. Idempotent — calling twice replaces the
     * emit callback but does not register a duplicate observer.
     * Emits `onCarPlayConnected` immediately if already connected.
     */
    fun start(emit: Emit) {
        runOnMain {
            this.emit = emit
            // Seed in-memory state from persisted last-known connection so a
            // cold restart can distinguish "we lost a previously-active
            // connection" from "we have no prior knowledge yet".
            if (lastConnected == null) {
                lastConnected = readPersistedConnected()
            }
            if (observer == null) {
                val obs = Observer<Int> { type -> handleType(type) }
                observer = obs
                try {
                    liveData.observeForever(obs)
                    Log.d(TAG, "CarPlay monitoring started (seeded lastConnected=$lastConnected, lib=androidx.car.app)")
                    // Diagnostic safety-net: if no value arrives within
                    // [DIAGNOSTIC_PROBE_MS], emit a loud warning. The most
                    // common cause is a missing `com.google.android.gms.car.application`
                    // meta-data entry in the host app's manifest — Gearhead
                    // then silently returns NOT_CONNECTED and the LiveData
                    // never updates beyond the bootstrap value.
                    mainHandler.postDelayed({
                        if (!hasObservedValue) {
                            Log.w(TAG, "CarPlay diagnostic: no CarConnection value received after ${DIAGNOSTIC_PROBE_MS}ms. " +
                                "Host app is likely missing the AndroidAuto registration meta-data " +
                                "(<meta-data android:name=\"com.google.android.gms.car.application\" android:resource=\"@xml/automotive_app_desc\"/>). " +
                                "Enable the expo-beacon plugin's `android.androidAuto.register` option (default in 0.9+) and re-run `expo prebuild`.")
                        } else {
                            Log.d(TAG, "CarPlay diagnostic: lastObservedType=$lastObservedType after ${DIAGNOSTIC_PROBE_MS}ms (OK)")
                        }
                    }, DIAGNOSTIC_PROBE_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start CarPlay monitoring: ${e.message}")
                }
            }
        }
    }

    /** Stop observing connection state and release the emit callback. */
    fun stop() {
        runOnMain {
            cancelPendingDisconnectCheck()
            observer?.let {
                try { liveData.removeObserver(it) } catch (_: Exception) {}
            }
            observer = null
            emit = null
            lastConnected = null
            Log.d(TAG, "CarPlay monitoring stopped")
        }
    }

    // MARK: - State inspection (called by BeaconForegroundService for re-emit on module rebind)

    /** Returns `true` if the LiveData observer is currently registered. */
    internal fun isObserving(): Boolean = observer != null

    /** Returns `true` if the last observed connection state is connected. */
    internal fun isCurrentlyConnected(): Boolean = lastConnected == true

    /**
     * Builds an `onCarPlayConnected` payload from the last observed state.
     * Returns `null` if the monitor is not currently in a connected state.
     * Thread-safe: reads only `@Volatile` fields and calls the synchronized
     * [formatIso] helper.
     */
    internal fun buildConnectedPayload(): Map<String, Any?>? {
        if (lastConnected != true) return null
        val type = lastObservedType
        val transport = when (type) {
            CarConnection.CONNECTION_TYPE_PROJECTION -> "projection"
            CarConnection.CONNECTION_TYPE_NATIVE -> "native"
            else -> "unknown"
        }
        val now = System.currentTimeMillis()
        return mapOf(
            "transport" to transport,
            "timestamp" to now,
            "timestampIso" to formatIso(now),
        )
    }

    private fun handleType(type: Int) {
        lastObservedType = type
        hasObservedValue = true
        val connected = type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        val previous = lastConnected

        // Same state as last emitted/seeded value → cancel any pending grace
        // re-check (the connection came back) and bail.
        if (previous == connected) {
            if (connected) cancelPendingDisconnectCheck()
            return
        }

        // First-ever observation in this process AND no persisted prior state:
        // the LiveData's initial value during a cold start is frequently
        // NOT_CONNECTED before the underlying Content-Provider query resolves.
        // Treat this purely as a state seed; do not emit a spurious disconnect.
        if (previous == null && !connected) {
            lastConnected = false
            Log.d(TAG, "Suppressed initial NOT_CONNECTED — seeded lastConnected=false")
            return
        }

        // Persisted/known state was `connected` but we just observed
        // `NOT_CONNECTED`. This is the most likely false-positive path on
        // process restart — defer emission for a short grace period and
        // re-check; only emit if it's still NOT_CONNECTED.
        if (previous == true && !connected) {
            scheduleDisconnectCheck()
            return
        }

        // Genuine transition — emit immediately.
        emitTransition(connected, type)
    }

    private fun scheduleDisconnectCheck() {
        cancelPendingDisconnectCheck()
        val r = Runnable {
            pendingDisconnectCheck = null
            val nowType = lastObservedType
            val nowConnected = nowType != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
            if (nowConnected) {
                Log.d(TAG, "Disconnect grace re-check: connection recovered (type=$nowType) — suppressed")
                // lastConnected stays true; no event.
                return@Runnable
            }
            // Still disconnected after grace — emit the real transition.
            emitTransition(false, nowType)
        }
        pendingDisconnectCheck = r
        mainHandler.postDelayed(r, BOOTSTRAP_GRACE_MS)
        Log.d(TAG, "Deferred CarPlay disconnect by ${BOOTSTRAP_GRACE_MS}ms (grace re-check pending)")
    }

    private fun cancelPendingDisconnectCheck() {
        pendingDisconnectCheck?.let { mainHandler.removeCallbacks(it) }
        pendingDisconnectCheck = null
    }

    private fun emitTransition(connected: Boolean, type: Int) {
        lastConnected = connected
        writePersistedConnected(connected)
        val callback = emit ?: return
        val now = System.currentTimeMillis()
        val nowIso = formatIso(now)
        if (connected) {
            val transport = when (type) {
                CarConnection.CONNECTION_TYPE_PROJECTION -> "projection"
                CarConnection.CONNECTION_TYPE_NATIVE -> "native"
                else -> "unknown"
            }
            callback("onCarPlayConnected", mapOf(
                "transport" to transport,
                "timestamp" to now,
                "timestampIso" to nowIso,
            ))
        } else {
            callback("onCarPlayDisconnected", mapOf(
                "timestamp" to now,
                "timestampIso" to nowIso,
            ))
        }
    }

    private fun readPersistedConnected(): Boolean? {
        val prefs = prefs() ?: return null
        if (!prefs.contains(KEY_LAST_CONNECTED)) return null
        return prefs.getBoolean(KEY_LAST_CONNECTED, false)
    }

    private fun writePersistedConnected(connected: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_LAST_CONNECTED, connected)?.apply()
    }

    private fun prefs(): SharedPreferences? = try {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (e: Throwable) {
        Log.w(TAG, "Failed to open CarPlayMonitor prefs", e)
        null
    }

    private fun formatIso(millis: Long): String {
        // SimpleDateFormat is not thread-safe — synchronize on the shared instance.
        synchronized(ISO_FORMAT) {
            return ISO_FORMAT.format(Date(millis))
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        private const val TAG = "CarPlayMonitor"
        private const val PREFS_NAME = "expo_beacon_carplay_monitor"
        private const val KEY_LAST_CONNECTED = "last_connected"
        /**
         * Grace window before emitting a `disconnected` event when the
         * persisted state was `connected` but the freshly-attached observer
         * reports `NOT_CONNECTED`. Absorbs the LiveData / Content-Provider
         * initial-value race that occurs during a cold service restart
         * (e.g. after the app is swiped away and START_STICKY revives us).
         */
        private const val BOOTSTRAP_GRACE_MS = 3_000L
        /**
         * Delay after which [start] emits a loud diagnostic log if the
         * LiveData has not delivered a single value. 5 s comfortably covers
         * the cold-start content-provider resolution path even on slow
         * devices.
         */
        private const val DIAGNOSTIC_PROBE_MS = 5_000L
        // ISO 8601 UTC with millisecond precision. Safe for all supported APIs (minSdk 23).
        private val ISO_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        /**
         * Clear the persisted last-known connection state. Call when CarPlay
         * monitoring is being explicitly disabled by the user, so that a
         * subsequent re-enable does not assume a stale prior connection.
         */
        @JvmStatic
        fun clearPersistedState(context: Context) {
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove(KEY_LAST_CONNECTED)
                    .apply()
            } catch (_: Throwable) { /* best-effort */ }
        }
    }
}
