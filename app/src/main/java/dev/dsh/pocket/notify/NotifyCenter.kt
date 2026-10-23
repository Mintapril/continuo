package dev.dsh.pocket.notify

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import dev.dsh.pocket.cowork.CoworkConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The settings of the notification triggers. The root broker reads the notifications ([NotifyRelay] in NotifyBridge):
 * ColorOS freezes Continuo a few seconds after the screen goes off and holds a listener's callbacks until it is on
 * again, so Continuo cannot be the reader. The switch, the excluded packages and the wait are kept here and sent to the
 * broker on every change and whenever its state shows other ones (the broker keeps them across restarts, so it works
 * after a boot before Continuo runs). The outcomes the broker records come back through its state for the settings page.
 */
object NotifyCenter {
    data class LogEntry(val at: Long, val text: String)

    private lateinit var prefs: SharedPreferences
    private val enabledState = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()
    private val excludedState = MutableStateFlow<Set<String>>(emptySet())
    /** Packages whose notifications are never read, judged or sent. */
    val excluded: StateFlow<Set<String>> = excludedState.asStateFlow()
    private val quietState = MutableStateFlow(NotifyDefaults.DEFAULT_QUIET_SECONDS)
    /** Seconds without a new notification before the batch goes to Jev. */
    val quietSeconds: StateFlow<Int> = quietState.asStateFlow()
    private val logState = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = logState.asStateFlow()
    private val readingState = MutableStateFlow<Boolean?>(null)
    /** Whether the connected broker reads notifications with these settings; null while no broker is connected. */
    val reading: StateFlow<Boolean?> = readingState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lastPush = 0L
    private val http = OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    @Synchronized fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("notify-triggers", Context.MODE_PRIVATE)
        enabledState.value = prefs.getBoolean("enabled", false)
        excludedState.value = runCatching { JSONArray(prefs.getString("excluded", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
        quietState.value = NotifyDefaults.quietSeconds(prefs.getInt("quietSeconds", NotifyDefaults.DEFAULT_QUIET_SECONDS))
        scope.launch { CoworkConnection.status.collect { state -> observe(state.optJSONObject("notify")) } }
    }

    fun setEnabled(on: Boolean) { enabledState.value = on; prefs.edit().putBoolean("enabled", on).apply(); push() }
    fun setQuietSeconds(seconds: Int) {
        val value = NotifyDefaults.quietSeconds(seconds)
        quietState.value = value; prefs.edit().putInt("quietSeconds", value).apply(); push()
    }
    fun setExcluded(packages: Set<String>) { excludedState.value = packages; prefs.edit().putString("excluded", JSONArray(packages.sorted()).toString()).apply(); push() }

    private fun config() = NotifyConfig(enabledState.value, excludedState.value, quietState.value)

    /** The broker's part of its state: the settings in force and the newest outcomes. */
    private fun observe(notify: JSONObject?) {
        if (notify == null) { readingState.value = null; return }
        val rows = notify.optJSONArray("log") ?: JSONArray()
        logState.value = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map { LogEntry(it.optLong("at"), it.optString("text")) }
        val same = NotifyConfig.parse(notify.optJSONObject("settings")) == config()
        readingState.value = same
        // A broker that started with other settings (its file, or none yet) gets these; at most every 10 s while it differs.
        if (!same && SystemClock.elapsedRealtime() - lastPush > 10_000) push()
    }

    private fun push() {
        lastPush = SystemClock.elapsedRealtime()
        val body = config().json()
        scope.launch { runCatching { observe(CoworkConnection.notifySettings(body)) } }
    }

    /** Blocking. A GET on the POST-only route answers 405 exactly when the plugin's ingress is listening. */
    fun ingressListening(): Boolean = runCatching {
        http.newCall(Request.Builder().url(NotifyWire.URL).get().build()).execute().use { it.code == 405 }
    }.getOrDefault(false)
}
