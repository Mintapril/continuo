package dev.dsh.pocket.data

import org.json.JSONObject

/** Durable outbox, never a send queue: only an explicit user retry consumes a retained request. */
class PendingPromptStore(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit,
) {
    @Synchronized fun get(sessionId: String): JSONObject? = read(sessionId)?.let { encoded ->
        JSONObject(encoded).also { request ->
            check(request.getString("sessionId") == sessionId && request.getString("requestId").isNotBlank()) {
                "待确认的请求记录不完整；为避免重复发送，已阻止新请求。"
            }
            request.getJSONArray("content")
        }
    }

    /** The storage callback must commit atomically before the caller starts the HTTP request. */
    @Synchronized fun retain(sessionId: String, request: JSONObject) {
        require(request.getString("sessionId") == sessionId)
        require(request.getString("requestId").isNotBlank())
        check(get(sessionId) == null) { "此会话已有待确认的请求，请使用原请求重试。" }
        write(sessionId, request.toString())
    }

    @Synchronized fun clear(sessionId: String, requestId: String) {
        val existing = get(sessionId) ?: return
        check(existing.getString("requestId") == requestId) { "不能清除不同的待确认请求" }
        write(sessionId, null)
    }
}
