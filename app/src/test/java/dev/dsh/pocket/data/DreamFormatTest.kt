package dev.dsh.pocket.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class DreamFormatTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun run(status: String, error: String? = null, type: String = "auto") =
        obj("created_at" to "2026-10-23T12:07:50.419Z", "status" to status, "error" to error, "run_type" to type)

    private fun dreamStatus(vararg runs: JSONObject) = obj("lastRun" to runs.firstOrNull(), "runs" to JSONArray(runs.toList()))

    @Test fun noRunReadsNever() {
        assertEquals(DreamFormat.LastRun("从未", false, null), DreamFormat.lastRun(obj("lastRun" to null, "runs" to JSONArray()), shanghai))
        assertEquals("从未", DreamFormat.lastRun(JSONObject(), shanghai).label)
    }

    @Test fun lastRunIsReadFromCreatedAt() {
        // mneme 0.9 names the start time `created_at`; reading any other key showed 「从未」 forever.
        assertEquals(DreamFormat.LastRun("10-23 20:07", false, null), DreamFormat.lastRun(dreamStatus(run("ok")), shanghai))
    }

    @Test fun failedRunShowsStatusStreakAndReason() {
        val failed = run("failed", "no json array in llm output")
        val result = DreamFormat.lastRun(dreamStatus(failed, failed, run("failed", "llm failed"), run("ok")), shanghai)
        assertTrue(result.failed)
        assertEquals("10-23 20:07 · 失败", result.label)
        assertEquals("最近 3 次都失败：模型输出里没有可解析的 JSON", result.detail)

        val single = DreamFormat.lastRun(dreamStatus(run("failed", "llm failed"), run("ok")), shanghai)
        assertEquals("模型调用失败", single.detail)
    }

    @Test fun otherOutcomesAndKindsAreNamed() {
        assertEquals("10-23 20:07 · 无改动", DreamFormat.lastRun(dreamStatus(run("noop")), shanghai).label)
        assertEquals("10-23 20:07 · 部分完成", DreamFormat.lastRun(dreamStatus(run("degraded")), shanghai).label)
        assertEquals("10-23 20:07 · 睡眠整理", DreamFormat.lastRun(dreamStatus(run("ok", type = "sleep")), shanghai).label)
        val unknown = DreamFormat.lastRun(obj("lastRun" to obj("created_at" to "garbage", "status" to "failed")), shanghai)
        assertEquals(DreamFormat.LastRun("garbage · 失败", true, null), unknown)
    }
}
