package dev.dsh.pocket.notify

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import dev.dsh.pocket.cowork.CoworkConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Process-wide notification triggers. When on, every app's notifications (except excluded apps and structural noise)
 * join one batch; once no app has posted for 10 seconds the batch goes, signed, to the Cowork plugin, where Jev
 * decides whether it wakes the model and the model decides what to do. Fed by [NotifyListener]; shown by the settings
 * page. Notification text is held in memory for the pending batch only.
 */
object NotifyCenter {
    data class LogEntry(val at: Long, val text: String)

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private val enabledState = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()
    private val excludedState = MutableStateFlow<Set<String>>(emptySet())
    /** Packages whose notifications are never read, judged or sent. */
    val excluded: StateFlow<Set<String>> = excludedState.asStateFlow()
    private val logState = MutableStateFlow<List<LogEntry>>(emptyList())
    val log: StateFlow<List<LogEntry>> = logState.asStateFlow()

    /** Main thread only. */
    private val throttle = NotifyThrottle()
    private val labels = HashMap<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private val flushTask = Runnable { flush() }
    @Volatile private var secret: String? = null
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS).build()

    /** The Cowork work profile's user id as the broker last reported it, kept across restarts: the system often starts
     *  the listener without the app, so without (or before) a broker connection. -1 until first seen. */
    @Volatile var profileUserId = -1; private set

    @Synchronized fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("notify-triggers", Context.MODE_PRIVATE)
        enabledState.value = prefs.getBoolean("enabled", false)
        excludedState.value = runCatching { JSONArray(prefs.getString("excluded", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
        profileUserId = prefs.getInt("profileUserId", -1)
        scope.launch { CoworkConnection.status.collect { status ->
            val id = status.optInt("profileUserId", -1)
            if (id > 0 && id != profileUserId) { profileUserId = id; prefs.edit().putInt("profileUserId", id).apply() }
        } }
    }

    fun setEnabled(on: Boolean) {
        enabledState.value = on; prefs.edit().putBoolean("enabled", on).apply()
        if (!on) main.post { throttle.forget(); main.removeCallbacks(flushTask) }
    }
    fun setExcluded(packages: Set<String>) { excludedState.value = packages; prefs.edit().putString("excluded", JSONArray(packages.sorted()).toString()).apply() }

    /** Whether a posted notification of this package may be read at all. */
    fun watches(packageName: String): Boolean = ::prefs.isInitialized && enabledState.value &&
        packageName != NotifyDefaults.OWN_PACKAGE && packageName !in excludedState.value

    /** Main thread, from the listener. */
    fun posted(n: Posted, appInfo: ApplicationInfo? = null) {
        if (!watches(n.packageName) || NotifyFilter.check(n) != Verdict.ACCEPT) return
        if (throttle.add(n, labelOf(n.packageName, appInfo)) != Verdict.ACCEPT) return
        schedule()
    }

    /** The app's name: from the notification's own ApplicationInfo first, since the personal user cannot see an app
     *  that only the work profile has; then the Cowork app list, then this user's PackageManager. A name found is
     *  kept; the package's last segment is only a fallback and is looked up again with the next notification. */
    private fun labelOf(packageName: String, appInfo: ApplicationInfo?): String {
        labels[packageName]?.let { return it }
        val pm = appContext.packageManager
        val found = sequenceOf(
            { appInfo?.takeIf { it.packageName == packageName }?.loadLabel(pm)?.toString() },
            { CoworkConnection.apps.value.firstOrNull { it.packageName == packageName }?.label },
            { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() },
        ).mapNotNull { runCatching(it).getOrNull()?.takeIf { name -> name.isNotBlank() && name != packageName } }.firstOrNull()
        val label = NotifyDefaults.label(found, packageName)
        if (found != null) labels[packageName] = label
        return label
    }

    private fun schedule() {
        main.removeCallbacks(flushTask)
        val at = throttle.fireAt() ?: return
        main.postDelayed(flushTask, (at - System.currentTimeMillis()).coerceIn(0, NotifyDefaults.MAX_WAIT_MS))
    }

    private fun flush() {
        if (!enabledState.value) return
        val batch = throttle.take(System.currentTimeMillis())
        if (batch != null) scope.launch { sending.withLock { deliver(batch) } }
        schedule()
    }

    private fun deliver(batch: NotifyThrottle.Batch) {
        val apps = batch.items.map { it.app }.distinct().let { it.take(3).joinToString("、") + if (it.size > 3) " 等" else "" }
        val what = "$apps · ${batch.count} 条"
        val result = runCatching { send(batch) }
        result.onFailure { record("$what：未能送去判断（${it.message ?: "发送失败"}）") }
        result.onSuccess { outcome ->
            val p = outcome.meaningful?.let { "%.2f".format(it) }
            record(when {
                outcome.queued -> "$what：Jev 判定 ${outcome.kept}/${outcome.total} 条有意义（最高 $p），只把这几条交给 Cowork（副屏忙时排队）"
                outcome.judgeError != null -> "$what：Jev 未能判断（${NotifyWire.judgeError(outcome.judgeError)}），未唤起"
                else -> "$what：Jev 判定都没有实际意义（最高 ${p ?: "?"}），未唤起"
            })
        }
    }

    private fun send(batch: NotifyThrottle.Batch): NotifyWire.Outcome {
        var retried = false
        while (true) {
            val key = secret ?: rootToken().also { secret = it }
            val sentAt = System.currentTimeMillis()
            val bytes = NotifyWire.body(batch, UUID.randomUUID().toString(), sentAt).toByteArray(Charsets.UTF_8)
            val request = Request.Builder().url(NotifyWire.URL).header(NotifyWire.SIGNATURE_HEADER, NotifyWire.mac(key, bytes))
                .post(bytes.toRequestBody("application/json".toMediaType())).build()
            val (code, text) = try { http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() } }
                catch (e: java.io.IOException) { error("DSH 未运行，或 Cowork 插件未部署通知入口") }
            if (code == 401 && !retried) { secret = null; retried = true; continue } // token re-provisioned: read it again
            check(code == 200 || code == 202) { "通知入口拒绝（HTTP $code${text.take(80).let { if (it.isBlank()) "" else "：$it" }}）" }
            return NotifyWire.outcome(text)
        }
    }

    /** Blocking. A GET on the POST-only route answers 405 exactly when the plugin's ingress is listening. */
    fun ingressListening(): Boolean = runCatching {
        http.newCall(Request.Builder().url(NotifyWire.URL).get().build()).execute().use { it.code == 405 }
    }.getOrDefault(false)

    /** The shared webhook token, created by the root service script on first use. */
    private fun rootToken(): String {
        val process = ProcessBuilder("su", "-c", "/system/bin/sh /data/local/dsh-native/cowork-service.sh notify-token").redirectErrorStream(true).start()
        check(process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); "KernelSU 授权超时" }
        val text = process.inputStream.use { String(it.readNBytes(256), Charsets.UTF_8) }.trim()
        check(process.exitValue() == 0 && Regex("[0-9a-f]{64}").matches(text)) { "无法读取通知入口令牌；请检查 KernelSU 授权并更新 cowork-service.sh" }
        return text
    }

    private fun record(text: String) {
        val entry = LogEntry(System.currentTimeMillis(), text)
        synchronized(logState) { logState.value = (listOf(entry) + logState.value).take(40) }
    }
}
