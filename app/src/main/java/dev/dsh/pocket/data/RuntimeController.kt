package dev.dsh.pocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/** Only fixed operations on the dedicated DSH deployment, never user-provided shell. */
class RuntimeController {
    enum class Action(val wire: String) {
        STATUS("status"), START("start"), STOP("stop"), RESTART("restart"),
        RECREATE_ENVIRONMENT("restart-environment"), AUTH("auth")
    }

    suspend fun execute(action: Action): String = withContext(Dispatchers.IO) {
        withTimeout(70_000) {
            val process = ProcessBuilder("su", "-c", "/system/bin/sh /data/local/dsh-native/pocket-control.sh ${action.wire}")
                .redirectErrorStream(true).start()
            try {
                // Control output is bounded. AUTH output must never be logged or shown in diagnostics.
                val output = StringBuilder()
                val reader = process.inputStream.bufferedReader()
                val worker = Thread {
                    reader.useLines { lines -> lines.forEach { if (output.length < 16_384) output.append(it).append('\n') } }
                }.apply { isDaemon = true; start() }
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroy()
                    throw IllegalStateException("Root 操作超时；请在 KernelSU 中确认授权后重试。")
                }
                worker.join(2_000)
                if (process.exitValue() != 0) {
                    val detail = if (action == Action.AUTH) "无法读取本机连接凭据" else output.toString().trim().take(1500)
                    throw IllegalStateException("运行环境操作被拒绝：$detail。请检查 KernelSU 对 Continuo 的授权。")
                }
                output.toString().trim()
            } finally { if (process.isAlive) process.destroy() }
        }
    }
}
