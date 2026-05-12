package expo.modules.beacon

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val API_PREFS = "expo.beacon.api_config"
private const val API_URL_KEY = "api_url"
private const val API_KEY_KEY = "api_key"
private const val ID_KEY = "id"
private const val MAX_RETRIES = 3

/**
 * Fire-and-forget HTTP event forwarder for beacon events.
 * Sends enter/exit/timeout events to a configured API endpoint from native code,
 * ensuring delivery even when the JS bridge is not active (app backgrounded).
 */
internal class BeaconApiForwarder(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(API_PREFS, Context.MODE_PRIVATE)
    }

    fun configure(url: String, apiKey: String?, id: String? = null) {
        prefs.edit().apply {
            putString(API_URL_KEY, url)
            if (apiKey != null) putString(API_KEY_KEY, apiKey)
            else remove(API_KEY_KEY)
            if (id != null) putString(ID_KEY, id)
            else remove(ID_KEY)
        }.apply()
    }

    fun getConfig(): Map<String, String?> {
        return mapOf(
            "url" to prefs.getString(API_URL_KEY, null),
            "apiKey" to prefs.getString(API_KEY_KEY, null),
            "id" to prefs.getString(ID_KEY, null)
        )
    }

    /**
     * Send a beacon event to the configured API endpoint.
     * Fire-and-forget with simple retry (3 attempts, exponential backoff).
     * No-op if no endpoint is configured.
     */
    fun forwardEvent(params: Map<String, Any?>) {
        val url = prefs.getString(API_URL_KEY, null)
        if (url.isNullOrEmpty()) return

        val apiKey = prefs.getString(API_KEY_KEY, null)
        val id = prefs.getString(ID_KEY, null)
        val payload = JSONObject().apply {
            for ((k, v) in params) {
                put(k, v ?: JSONObject.NULL)
            }
            if (!id.isNullOrEmpty()) put("id", id)
            put("timestamp", System.currentTimeMillis())
            put("platform", "android")
            put("sdkVersion", Build.VERSION.SDK_INT)
        }

        executor.execute {
            var lastException: Exception? = null
            for (attempt in 1..MAX_RETRIES) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.apply {
                        requestMethod = "POST"
                        setRequestProperty("Content-Type", "application/json")
                        apiKey?.let { setRequestProperty("X-CSFR-Token", it) }
                        connectTimeout = 10_000
                        readTimeout = 10_000
                        doOutput = true
                    }
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..299) return@execute
                    // 4xx client errors — don't retry
                    if (code in 400..499) {
                        Log.w(TAG, "API forward failed with $code — not retrying")
                        return@execute
                    }
                    lastException = RuntimeException("HTTP $code")
                } catch (e: Exception) {
                    lastException = e
                }
                // Exponential backoff: 1s, 2s, 4s
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(1000L * (1 shl (attempt - 1))) } catch (_: InterruptedException) {}
                }
            }
            Log.w(TAG, "API forward failed after $MAX_RETRIES attempts: ${lastException?.message}")
        }
    }
}
