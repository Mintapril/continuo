package dev.dsh.pocket.data

import dev.dsh.pocket.data.ScheduleFormat.TimingDraft
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class CompanionTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun ownerNotesGoInTheirWordsAtThePickedTime() {
        val timing = Companion.once(LocalDate.of(2026, 10, 26), "08:00", zone)
        val request = Companion.noteRequest(Companion.OWNER_SESSION, "  把那 3 个视频的要点整理一下发我\n第二行  ", timing).getJSONObject("request")
        assertEquals("cowork-owner", request.getString("sessionId"))
        assertEquals("把那 3 个视频的要点整理一下发我\n第二行", request.getString("prompt"))
        assertEquals("把那 3 个视频的要点整理一下发我", request.getString("title"))
        val change = request.getJSONObject("change")
        assertEquals("at", change.getString("kind"))
        assertEquals("2026-10-26", change.getJSONObject("at").getString("date"))
        assertEquals("Asia/Shanghai", change.getJSONObject("at").getString("time_zone"))
        assertThrows(IllegalArgumentException::class.java) { Companion.noteRequest("s", "  ", timing) }
        assertThrows(IllegalArgumentException::class.java) { Companion.noteRequest("s", "x", TimingDraft("daily", time = "8点", zone = zone.id)) }
    }

    @Test fun aPatrolKeepsItsMarker() {
        val request = Companion.noteRequest(ScheduleFormat.PATROL_SESSION, ScheduleFormat.patrolTask(false), ScheduleFormat.patrolTiming("Asia/Shanghai")).getJSONObject("request")
        assertTrue(request.getString("prompt").startsWith("[巡视] "))
        assertEquals("cron", request.getJSONObject("change").getString("kind"))
    }

    @Test fun longTitlesAreCut() {
        assertEquals("一二三四五六七八九十一二三四五六七八九十一二三…", Companion.noteTitle("一二三四五六七八九十一二三四五六七八九十一二三四五六"))
        assertEquals("短", Companion.noteTitle("\n  短  \n长"))
    }

    @Test fun quickTimesMoveOnOnceTheHourHasPassed() {
        val morning = Companion.quickTimes(LocalDateTime.of(2026, 10, 25, 9, 0), zone)
        assertEquals(listOf("今晚 21:00", "明早 08:00"), morning.map { it.first })
        assertEquals("2026-10-25", morning[0].second.date)
        assertEquals("2026-10-26", morning[1].second.date)
        val late = Companion.quickTimes(LocalDateTime.of(2026, 10, 25, 22, 0), zone)
        assertEquals("明晚 21:00", late[0].first)
        assertEquals("2026-10-26", late[0].second.date)
        assertEquals("今早 08:00", Companion.quickTimes(LocalDateTime.of(2026, 10, 25, 6, 0), zone)[1].first)
    }

    @Test fun timesReadTheWayAPersonSaysThem() {
        val today = LocalDate.of(2026, 10, 25)
        assertEquals("今晚 21:00", Companion.dayAndTime(today, "21:00", today))
        assertEquals("明早 08:00", Companion.dayAndTime(today.plusDays(1), "08:00", today))
        assertEquals("明天 14:00", Companion.dayAndTime(today.plusDays(1), "14:00", today))
        assertEquals("后天 09:00", Companion.dayAndTime(today.plusDays(2), "09:00", today))
        assertEquals("11月3日 09:00", Companion.dayAndTime(LocalDate.of(2026, 11, 3), "09:00", today))
        val now = LocalDateTime.of(2026, 10, 25, 9, 0)
        assertEquals("每天 08:00", Companion.whenText(TimingDraft("daily", time = "08:00"), now))
        assertEquals("明早 08:00", Companion.whenText(Companion.once(today.plusDays(1), "08:00", zone), now))
        assertEquals("明早 08:00 照做：带伞", Companion.receipt("带伞", Companion.once(today.plusDays(1), "08:00", zone), now))
        assertEquals("每天 08:00 都照做：看天气", Companion.receipt("看天气", TimingDraft("daily", time = "08:00"), now))
        assertEquals("已改（明早 08:00 → 明早 09:00）", Companion.editReceipt("明早 08:00", "明早 09:00"))
        assertEquals("已改", Companion.editReceipt("每天 08:00", "每天 08:00"))
        assertEquals("明早 08:00", Companion.nextText("2026-10-26T00:00:00Z", Instant.parse("2026-10-25T03:00:00Z"), zone))
        assertEquals("", Companion.nextText("not a time"))
    }

    @Test fun usualCronRulesReadInWords() {
        assertEquals("每天 9–21 点每 3 小时", Companion.frequency(JSONObject("""{"kind":"cron","expression":"0 9-21/3 * * *","timeZone":"Asia/Shanghai"}""")))
        assertEquals("每天 08:30", Companion.frequency(JSONObject("""{"kind":"cron","expression":"30 8 * * *","timeZone":"Asia/Shanghai"}""")))
        assertTrue(Companion.frequency(JSONObject("""{"kind":"cron","expression":"0 9 * * 1-5","timeZone":"Asia/Shanghai"}""")).isNotBlank())
    }

    @Test fun theDiaryIsOneEntryADayReadNewestFirstAndMerged() {
        val page = Companion.diaryPage(JSONObject("""{"entries":[{"date":"2026-10-25","at":3000,"text":"今天去 B 站逛了逛。"},
            {"date":"2026-10-24","at":2000,"text":" "},{"date":"10-23","at":1500,"text":"x"},{"date":"2026-10-23","at":1000,"text":"第一天"}],"more":true}"""))
        assertEquals(listOf("2026-10-25", "2026-10-23"), page.entries.map { it.date })
        assertTrue(page.more)
        assertEquals(java.time.LocalDate.of(2026, 10, 25), page.entries[0].day)
        val older = Companion.DiaryEntry("2026-10-22", 500, "更早")
        assertEquals(listOf("2026-10-25", "2026-10-23", "2026-10-22"), Companion.merge(page.entries, listOf(older, page.entries[0])).map { it.date })
        assertEquals(Companion.DiaryPage(emptyList(), false), Companion.diaryPage(null))
        assertEquals(JSONObject("""{"request":{"before":3000,"limit":20}}""").toString(), Companion.diaryRequest(before = 3000).toString())
    }

    @Test fun eventsReadNewestFirstWithTheirAppAndMerge() {
        // The plugin's shape (coworkUnattended/events): an owner turn or an unknown kind is never an event.
        val page = Companion.eventsPage(JSONObject("""{"entries":[
            {"at":7,"sessionId":"cowork-app-personal-com-tencent-mm","kind":"notify","text":" 回了 ","reason":"aborted","app":{"profile":"personal","package":"com.tencent.mm"}},
            {"at":5,"sessionId":"cowork-app-work-com-tencent-mobileqq","kind":"note","notified":["群里有人找你"," "],"app":{"profile":"work","package":"com.tencent.mobileqq"}},
            {"at":4,"sessionId":"cowork-app-work-com-tencent-mobileqq","kind":"owner","text":"x"},
            {"at":0,"sessionId":"cowork-app-patrol","kind":"patrol","text":"x"},
            {"at":1,"sessionId":"cowork-app-patrol","kind":"patrol","text":"逛了一圈","apps":["tv.danmaku.bili",3]}],"more":true}"""))
        assertTrue(page.more)
        assertEquals(listOf(7L, 5L, 1L), page.entries.map { it.at })
        val (wechat, qq, patrol) = page.entries
        assertEquals(Companion.Event(7, "cowork-app-personal-com-tencent-mm", "notify", "回了", emptyList(), emptyList(), "com.tencent.mm", true, false), wechat)
        assertEquals(listOf("群里有人找你"), qq.notified)
        assertTrue(qq.finished)
        assertEquals(listOf("tv.danmaku.bili"), patrol.apps)
        assertNull(patrol.app)
        val label = { pkg: String -> mapOf("com.tencent.mm" to "微信", "com.tencent.mobileqq" to "QQ")[pkg] ?: pkg }
        assertEquals(listOf("通知 · 微信（个人）", "便条 · QQ", "巡视"), page.entries.map { Companion.eventTitle(it, label) })
        assertEquals("巡视 · QQ", Companion.eventTitle(qq.copy(kind = "patrol"), label))
        val older = patrol.copy(at = 0, sessionId = "cowork-app-patrol")
        assertEquals(listOf(7L, 5L, 1L, 0L), Companion.mergeEvents(page.entries, listOf(older, wechat)).map { it.at })
        assertEquals(Companion.EventPage(emptyList(), false), Companion.eventsPage(null))
        assertEquals(JSONObject("""{"request":{"before":7,"limit":30}}""").toString(), Companion.eventsRequest(before = 7).toString())
    }

    @Test fun aRemarkNamesTheDay() {
        val day = Companion.DiaryEntry("2026-10-25", 1, "在 B 站看了两个做菜视频。")
        assertEquals("关于你 10 月 25 日 的日记：下次也给我一份", Companion.annotation(day, " 下次也给我一份 "))
        assertThrows(IllegalArgumentException::class.java) { Companion.annotation(day, " ") }
    }

    @Test fun dayHeadings() {
        val now = Instant.parse("2026-10-25T04:00:00Z")
        assertEquals("今天 · 10 月 25 日", Companion.dayHeading(Instant.parse("2026-10-25T01:00:00Z").toEpochMilli(), now, zone))
        assertEquals("昨天 · 10 月 24 日", Companion.dayHeading(Instant.parse("2026-10-24T01:00:00Z").toEpochMilli(), now, zone))
        assertEquals("10 月 20 日 · 周二", Companion.dayHeading(Instant.parse("2026-10-20T01:00:00Z").toEpochMilli(), now, zone))
        assertEquals("昨天 · 10 月 24 日", Companion.dayHeading(java.time.LocalDate.of(2026, 10, 24), now, zone))
    }

    @Test fun theNameGoesToThePluginAsIs() {
        assertEquals("小满", Companion.identityRequest("小满").getJSONObject("request").getString("name"))
        assertFalse(Companion.identityRequest("").getJSONObject("request").has("name"))
        assertEquals("小满", Companion.storedName(JSONObject("""{"name":"小满"}""")))
        assertNull(Companion.storedName(JSONObject("""{"name":null}""")))
        assertNull(Companion.storedName(null))
    }

    @Test fun aClearedConversationContinuesInAFreshSession() {
        val next = Companion.nextOwnerSession(LocalDateTime.of(2026, 10, 25, 15, 30, 12))
        assertEquals("cowork-owner-20261025-153012", next)
        assertTrue(Regex("[a-zA-Z0-9_-]{1,100}").matches(next)) // the broker's session id rule
        assertTrue(Companion.isOwnerSession(next))
        assertTrue(Companion.isOwnerSession(Companion.OWNER_SESSION))
        for (other in listOf("cowork-owner-", "cowork-owner-x", "cowork-app-patrol", "s", null)) assertFalse(other.toString(), Companion.isOwnerSession(other))
    }

    @Test fun notesMoveWithTheirWordsMarkersAndTimes() {
        fun moved(entry: String) = Companion.movedNote("cowork-owner-20261025-153012", JSONObject(entry), zone).getJSONObject("request")
        val own = moved("""{"id":"a","kind":"after","title":"便条：看小王回了没","prompt":"[便条] 看看小王回了没有","scheduledAt":"2026-10-25T08:05:30.000Z","afterSeconds":1800,"sessionId":"cowork-owner","status":"active"}""")
        assertEquals("cowork-owner-20261025-153012", own.getString("sessionId"))
        assertEquals("便条：看小王回了没", own.getString("title"))
        assertEquals("[便条] 看看小王回了没有", own.getString("prompt"))
        val at = own.getJSONObject("change")
        assertEquals("at", at.getString("kind"))
        assertEquals("2026-10-25", at.getJSONObject("at").getString("date"))
        assertEquals("16:05:30", at.getJSONObject("at").getString("time"))
        assertEquals("Asia/Shanghai", at.getJSONObject("at").getString("time_zone"))
        val patrol = moved("""{"id":"p","kind":"cron","title":"巡视","prompt":"[巡视] 每次挑 2–3 个应用逛逛。","expression":"0 9-21/3 * * *","timeZone":"Asia/Shanghai","scheduledAt":"2026-10-25T09:00:00.000Z"}""")
        assertEquals("[巡视] 每次挑 2–3 个应用逛逛。", patrol.getString("prompt"))
        assertEquals("0 9-21/3 * * *", patrol.getJSONObject("change").getJSONObject("cron").getString("expression"))
        val weekly = moved("""{"id":"w","kind":"weekly","title":"t","prompt":"p","time":"09:00:00","timeZone":"Asia/Shanghai","weekdays":[5,1,3],"scheduledAt":"2026-10-27T01:00:00.000Z"}""")
        assertEquals("[1,3,5]", weekly.getJSONObject("change").getJSONObject("weekly").getJSONArray("weekdays").toString())
        assertEquals(5400, moved("""{"id":"e","kind":"every","title":"t","prompt":"p","everySeconds":5400,"scheduledAt":"2026-10-25T09:00:00.000Z"}""").getJSONObject("change").getInt("every_seconds"))
        assertEquals("08:00:00", moved("""{"id":"d","kind":"daily","title":"t","prompt":"p","time":"08:00:00","timeZone":"Asia/Shanghai","scheduledAt":"2026-10-26T00:00:00.000Z"}""").getJSONObject("change").getJSONObject("daily").getString("time"))
        assertThrows(IllegalStateException::class.java) { moved("""{"id":"x","kind":"odd","title":"t","prompt":"p"}""") }
    }
}
