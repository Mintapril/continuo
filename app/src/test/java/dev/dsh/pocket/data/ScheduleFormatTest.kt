package dev.dsh.pocket.data

import dev.dsh.pocket.data.ScheduleFormat.TimingDraft
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduleFormatTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test fun bundleIsFoundByItsScheduleRowNotByName() {
        val bundles = JSONArray().put(obj("name" to "@x/voice", "rows" to JSONArray().put(obj("rowId" to "voice", "moduleName" to "@deepseek-ai/dsh-voice"))))
            .put(obj("name" to "@deepseek-ai/dsh-experimental-schedule-bundle", "enabled" to false, "rows" to JSONArray()
                .put(obj("rowId" to "time-context", "moduleName" to "@deepseek-ai/dsh-time-context"))
                .put(obj("rowId" to "schedule", "moduleName" to "@deepseek-ai/dsh-schedule"))))
        assertEquals("@deepseek-ai/dsh-experimental-schedule-bundle", ScheduleFormat.findBundle(bundles)?.text("name"))
        assertNull(ScheduleFormat.findBundle(JSONArray().put(bundles.getJSONObject(0))))
    }

    @Test fun expectedRecordDropsCatalogOnlyFields() {
        val entry = obj("id" to "s1", "kind" to "weekly", "title" to "周报", "prompt" to "写周报", "scheduledAt" to "2026-10-23T01:00:00.000Z",
            "time" to "09:00:00", "timeZone" to "Asia/Shanghai", "weekdays" to JSONArray(listOf(1, 5)),
            "sessionId" to "sess", "status" to "active", "lastDelivery" to obj("messageId" to "m"))
        val record = ScheduleFormat.record(entry)
        assertEquals(setOf("id", "kind", "title", "prompt", "scheduledAt", "time", "timeZone", "weekdays"), record.keys().asSequence().toSet())
        val after = ScheduleFormat.record(obj("id" to "a", "kind" to "after", "title" to "t", "prompt" to "p", "afterSeconds" to 600, "scheduledAt" to "x", "status" to "inactive"))
        assertEquals(600, after.getInt("afterSeconds")); assertFalse(after.has("status"))
    }

    @Test fun frequencyWordsAndZoneOnlyWhenForeign() {
        fun entry(vararg pairs: Pair<String, Any?>) = obj(*pairs)
        assertEquals("每天 09:00", ScheduleFormat.frequency(entry("kind" to "daily", "time" to "09:00:00", "timeZone" to "Asia/Shanghai"), "Asia/Shanghai"))
        assertEquals("每天 23:00:30 · Asia/Tokyo", ScheduleFormat.frequency(entry("kind" to "daily", "time" to "23:00:30.000", "timeZone" to "Asia/Tokyo"), "Asia/Shanghai"))
        assertEquals("工作日 08:30", ScheduleFormat.frequency(entry("kind" to "weekly", "time" to "08:30:00", "timeZone" to "UTC", "weekdays" to JSONArray(listOf(5, 4, 3, 2, 1))), "UTC"))
        assertEquals("每周一、三 09:00", ScheduleFormat.frequency(entry("kind" to "weekly", "time" to "09:00:00", "timeZone" to "UTC", "weekdays" to JSONArray(listOf(3, 1))), "UTC"))
        assertEquals("每 30 分钟", ScheduleFormat.frequency(entry("kind" to "every", "everySeconds" to 1800)))
        assertEquals("每 2 小时", ScheduleFormat.frequency(entry("kind" to "every", "everySeconds" to 7200)))
        assertEquals("每 90 秒", ScheduleFormat.frequency(entry("kind" to "every", "everySeconds" to 90)))
        assertEquals("仅一次", ScheduleFormat.frequency(entry("kind" to "at")))
        assertEquals("Cron */15 9-17 * * 1-5", ScheduleFormat.frequency(entry("kind" to "cron", "expression" to "*/15 9-17 * * 1-5", "timeZone" to "UTC"), "UTC"))
    }

    @Test fun localTimeAndRelative() {
        val now = Instant.parse("2026-10-20T08:00:00Z")
        assertEquals("10月21日 周三 09:00", ScheduleFormat.localTime("2026-10-21T01:00:00.000Z", now, shanghai))
        assertEquals("2027年1月1日 08:00", ScheduleFormat.localTime("2027-01-01T00:00:00Z", now, shanghai))
        assertEquals("30 分钟后", ScheduleFormat.relative("2026-10-20T08:30:00Z", now))
        assertEquals("2 小时 5 分钟后", ScheduleFormat.relative("2026-10-20T10:05:00Z", now))
        assertEquals("已到期，等待投递", ScheduleFormat.relative("2026-10-20T07:59:00Z", now))
        assertEquals("garbage", ScheduleFormat.localTime("garbage", now, shanghai))
    }

    @Test fun timingDraftValidatesAndBuildsTheHostChange() {
        assertEquals("09:05:00", ScheduleFormat.clock("09:05")); assertEquals("23:59:59", ScheduleFormat.clock("23:59:59"))
        assertNull(ScheduleFormat.clock("9:5")); assertNull(ScheduleFormat.clock("24:00"))
        val daily = TimingDraft("daily", time = "7:30", zone = "Asia/Shanghai")
        assertNotNull(daily.problem())
        val ok = daily.copy(time = "07:30")
        assertNull(ok.problem())
        ok.change().let { assertEquals("daily", it.getString("kind")); assertEquals("07:30:00", it.getJSONObject("daily").getString("time")); assertEquals("Asia/Shanghai", it.getJSONObject("daily").getString("time_zone")); assertEquals(2, it.length()) }
        assertEquals("每天 07:30（时区 Asia/Shanghai）", ok.describe())
        val weekly = TimingDraft("weekly", time = "09:00", days = setOf(5, 1), zone = "UTC")
        assertEquals(JSONArray(listOf(1, 5)).toString(), weekly.change().getJSONObject("weekly").getJSONArray("weekdays").toString())
        assertNotNull(weekly.copy(days = emptySet()).problem())
        assertEquals(1800L, TimingDraft("every", minutes = "30").change().getLong("every_seconds"))
        assertNotNull(TimingDraft("every", minutes = "0").problem())
        assertNull(TimingDraft("every", minutes = "5", zone = "not a zone").problem()) // an interval has no zone
        assertNotNull(TimingDraft("cron", cron = "0 9 * *", zone = "UTC").problem())
        assertEquals("0 9 * * 1-5", TimingDraft("cron", cron = " 0  9 * *   1-5 ", zone = "UTC").change().getJSONObject("cron").getString("expression"))
        assertNotNull(TimingDraft("daily", zone = "Shanghai").problem())
        val once = TimingDraft("once", date = "2026-10-21", time = "09:00", zone = "Asia/Shanghai")
        once.change().let { assertEquals("at", it.getString("kind")); val at = it.getJSONObject("at"); assertEquals(listOf("2026-10-21", "09:00:00", "Asia/Shanghai"), listOf(at.getString("date"), at.getString("time"), at.getString("time_zone"))) }
        assertNotNull(once.copy(date = "2026/10/21").problem())
    }

    @Test fun timingDraftOfAStoredTask() {
        val weekly = TimingDraft.of(obj("kind" to "weekly", "time" to "09:00:00", "timeZone" to "Asia/Tokyo", "weekdays" to JSONArray(listOf(2, 4))), shanghai)
        assertEquals(TimingDraft("weekly", time = "09:00", days = setOf(2, 4), zone = "Asia/Tokyo"), weekly)
        val once = TimingDraft.of(obj("kind" to "at", "scheduledAt" to "2026-10-21T01:30:00.000Z"), shanghai)
        assertEquals(TimingDraft("once", "2026-10-21", "09:30", zone = "Asia/Shanghai"), once)
        assertEquals("15", TimingDraft.of(obj("kind" to "every", "everySeconds" to 900), shanghai).minutes)
        // Unchanged rule = equal draft, so the edit sheet sends no timing change.
        assertEquals(TimingDraft.of(obj("kind" to "cron", "expression" to "0 9 * * *", "timeZone" to "UTC"), shanghai), TimingDraft("cron", cron = "0 9 * * *", zone = "UTC"))
    }

    @Test fun deliveredReminderPromptsAreRecovered() {
        val single = "[SCHEDULE REMINDER]\nThis is a scheduled message from the user\nschedule_id_json: \"s1\"\noccurrence_at: 2026-10-21T01:00:00.000Z\nreminder_prompt_json: \"打开日历\\n看今天的会议\""
        assertEquals(listOf("打开日历\n看今天的会议"), ScheduleFormat.reminderPrompts(single))
        val batch = "[SCHEDULE REMINDER BATCH]\nThis is a scheduled message from the user\nreminders_json: " +
            JSONArray().put(JSONObject().put("schedule_id", "a").put("occurrence_at", "x").put("reminder_prompt", "签到"))
                .put(JSONObject().put("schedule_id", "b").put("occurrence_at", "y").put("reminder_prompt", "喝水")).toString()
        assertEquals(listOf("签到", "喝水"), ScheduleFormat.reminderPrompts(batch))
        assertNull(ScheduleFormat.reminderPrompts("hello"))
        assertNull(ScheduleFormat.reminderPrompts("[SCHEDULE REMINDER]\nno payload"))
    }

    @Test fun newTaskRequestNamesEverything() {
        val text = ScheduleFormat.request(" 每日签到 ", "打开 App 签到 ", TimingDraft("daily", time = "08:00", zone = "Asia/Shanghai"))
        assertTrue(text.contains("schedule_create"))
        assertTrue(text.contains("名称：每日签到\n"))
        assertTrue(text.contains("时间：每天 08:00（时区 Asia/Shanghai）\n"))
        assertTrue(text.endsWith("到时要做：打开 App 签到"))
    }

    @Test fun hostCodesInWords() {
        assertEquals("间隔至少 60 秒", ScheduleFormat.failure("frequency_too_high"))
        assertTrue(ScheduleFormat.failure("weird_code", "detail").contains("weird_code"))
    }
}
