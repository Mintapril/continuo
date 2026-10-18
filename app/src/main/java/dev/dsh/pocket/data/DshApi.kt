package dev.dsh.pocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

fun obj(vararg entries: Pair<String, Any?>): JSONObject = JSONObject().apply {
    entries.forEach { (k, v) -> if (v != null) put(k, v) }
}
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.objects() ?: emptyList()
fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")

/** A matched RPC envelope explicitly rejected this call; unlike an I/O failure, the outcome is known. */
class RpcRejectedException(val code: String, message: String) : IOException("$code：$message")

/** A plugin HTTP route answered 404: the plugin is not deployed on this device. */
class PluginUnavailableException(message: String) : IOException(message)

/** The local service is the only allowed destination. Redirects cannot leak credentials. */
class DshApi(val origin: String = "http://127.0.0.1:3080") {
    init { require(origin == "http://127.0.0.1:3080") }
    @Volatile private var cookie = ""
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()

    suspend fun login(token: String) = withContext(Dispatchers.IO) {
        require(token.matches(Regex("[A-Za-z0-9_-]{16,}"))) { "本机连接令牌格式错误" }
        val request = Request.Builder().url("$origin/?token=$token").build()
        client.newCall(request).execute().use { response ->
            val cookies = response.headers("Set-Cookie").map { it.substringBefore(';') }
            check(response.code in 300..399 && cookies.isNotEmpty()) { "本机身份验证失败（HTTP ${response.code}）" }
            cookie = cookies.joinToString("; ")
        }
    }
    fun disconnect() { cookie = "" }

    suspend fun rpc(method: String, args: JSONObject = obj()): Any? = withContext(Dispatchers.IO) {
        check(cookie.isNotEmpty()) { "请先连接本机 DSH" }
        require(method == "\$events/result" || method.matches(Regex("[A-Za-z][A-Za-z0-9/-]*")))
        val id = UUID.randomUUID().toString()
        val envelope = obj("type" to "client-request", "rpcId" to id, "method" to method, "payload" to obj("args" to args))
        val request = Request.Builder().url("$origin/api/$method").header("Cookie", cookie).header("Origin", origin)
            .post(envelope.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("$method：HTTP ${response.code}" + if (response.code == 401) "，请重新连接" else "")
            val body = JSONObject(response.body?.string() ?: error("服务返回空响应"))
            check(body.text("type") == "server-response" && body.text("rpcId") == id) { "DSH 协议响应不匹配" }
            val result = body.getJSONObject("result")
            check(result.opt("ok") is Boolean) { "DSH 协议结果缺少明确状态" }
            if (!result.getBoolean("ok")) {
                val error = result.optJSONObject("error")
                throw RpcRejectedException(error?.text("code")?.ifBlank { "RPC_ERROR" } ?: "RPC_ERROR",
                    error?.text("message")?.ifBlank { "请求被拒绝" } ?: "请求被拒绝")
            }
            result.opt("value").let { if (it === JSONObject.NULL) null else it }
        }
    }
    suspend fun call(method: String, request: JSONObject = obj()): JSONObject =
        rpc(method, obj("request" to request)) as? JSONObject ?: obj()

    /**
     * Plain HTTP route served by a plugin (e.g. `/api/dsh-mneme/list`), not an RPC method.
     * Plugin routes authenticate with the same session cookie; there is no envelope.
     */
    suspend fun rest(path: String, method: String = "GET", body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        check(cookie.isNotEmpty()) { "请先连接本机 DSH" }
        require(path.startsWith("/api/") && !path.contains("://")) { "只允许本机插件路由" }
        val builder = Request.Builder().url("$origin$path").header("Cookie", cookie).header("Origin", origin)
        val payload = body?.toString()?.toRequestBody("application/json".toMediaType())
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, payload ?: obj().toString().toRequestBody("application/json".toMediaType()))
        }
        client.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            // A plugin route that is not registered answers 404 {"error":"not-found"}; that is the
            // only signal we get that the plugin is not installed on this device.
            if (response.code == 404) throw PluginUnavailableException("插件未安装或未启用")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}" + if (response.code == 401) "，请重新连接" else "")
            runCatching { JSONObject(raw) }.getOrElse { error("插件返回的不是 JSON") }
        }
    }

    fun stream(endpoint: String, args: JSONObject): Flow<JSONObject> = callbackFlow {
        check(cookie.isNotEmpty()) { "连接已断开" }
        // Bounded: a slow consumer blocks OkHttp's reader thread (TCP back-pressure) instead of growing memory or
        // dropping the stream; the server then waits for us rather than cutting the connection for a full buffer.
        val items = Channel<JSONObject>(capacity = 1024)
        val reader = launch { for (next in items) send(next) }
        val id = UUID.randomUUID().toString()
        val request = Request.Builder().url("ws://127.0.0.1:3080/api/remote.mux")
            .header("Cookie", cookie).header("Origin", origin).build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(obj("type" to "open", "streamId" to id, "endpoint" to endpoint,
                    "payload" to obj("args" to args)).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = JSONObject(text)
                    if (frame.text("streamId") != id) return
                    when (frame.text("type")) {
                        "item" -> {
                            items.trySendBlocking(frame.getJSONObject("value"))
                        }
                        "error" -> close(IOException(frame.optJSONObject("error")?.text("message") ?: "实时流出错"))
                        "end" -> close()
                    }
                } catch (e: Exception) { close(e) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Do not expose request URLs (auth token) or complete wire payloads in errors.
                close(IOException("本机实时连接中断：${t.javaClass.simpleName}"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        awaitClose {
            reader.cancel(); items.cancel()
            socket.send(obj("type" to "cancel", "streamId" to id).toString()); socket.close(1000, "screen detached")
        }
    }
}
