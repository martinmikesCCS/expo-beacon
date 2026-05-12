package expo.modules.beacon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer

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
     * Begin observing connection state. Idempotent — calling twice replaces the
     * emit callback but does not register a duplicate observer.
     * Emits `onCarPlayConnected` immediately if already connected.
     */
    fun start(emit: Emit) {
        runOnMain {
            this.emit = emit
            if (observer == null) {
                val obs = Observer<Int> { type -> handleType(type) }
                observer = obs
                try {
                    liveData.observeForever(obs)
                    Log.d(TAG, "CarPlay monitoring started")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to start CarPlay monitoring: ${e.message}")
                }
            }
        }
    }

    /** Stop observing connection state and release the emit callback. */
    fun stop() {
        runOnMain {
            observer?.let {
                try { liveData.removeObserver(it) } catch (_: Exception) {}
            }
            observer = null
            emit = null
            lastConnected = null
            Log.d(TAG, "CarPlay monitoring stopped")
        }
    }

    private fun handleType(type: Int) {
        val connected = type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        if (lastConnected == connected) return
        lastConnected = connected
        val callback = emit ?: return
        if (connected) {
            val transport = when (type) {
                CarConnection.CONNECTION_TYPE_PROJECTION -> "projection"
                CarConnection.CONNECTION_TYPE_NATIVE -> "native"
                else -> "unknown"
            }
            callback("onCarPlayConnected", mapOf(
                "transport" to transport,
                "timestamp" to System.currentTimeMillis(),
            ))
        } else {
            callback("onCarPlayDisconnected", mapOf(
                "timestamp" to System.currentTimeMillis(),
            ))
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private companion object {
        const val TAG = "CarPlayMonitor"
    }
}
