package dev.dsh.pocket.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Its character, energy and mood, and how it may reach the owner: what Continuo sends and reads (coworkUnattended). */
class TemperamentTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test fun aCharacterReadsWithWhatThePluginWorkedOut() {
        // The plugin's shape (coworkUnattended/character).
        val profile = Temperament.profile(JSONObject("""{"traits":{"Se":25,"Si":50,"Ne":70,"Ni":30,"Te":30,"Ti":20,"Fe":35,"Fi":85},
            "derived":{"type":"INFP","stack":["Fi","Ne","Si","Te","Fe","Ni","Se","Ti"],"e":-0.4,"extraverted":30,
            "effort":{"browse":0.9,"read":1.1,"talk":1.25,"think":0.75},"share":{"browse":29,"read":21,"talk":15,"think":35}}}"""))
        assertEquals(85, profile.traits!!["Fi"])
        val d = profile.derived!!
        assertEquals("INFP", d.type)
        assertEquals(listOf("think", "browse", "read", "talk"), d.easiest)
        assertEquals("INFP · 外倾 30%", Temperament.summary(profile))
        assertEquals("最轻松：想事情、记东西；最费劲：和人说话", Temperament.easeText(d))
        // None set, an older plugin, or a damaged value: no character.
        assertEquals(Temperament.Profile(null, null), Temperament.profile(JSONObject("""{"traits":null,"derived":null}""")))
        assertEquals(Temperament.Profile(null, null), Temperament.profile(null))
        assertNull(Temperament.profile(JSONObject("""{"traits":{"Se":25}}""")).traits)
        assertEquals("没定", Temperament.summary(Temperament.Profile(null, null)))
    }

    @Test fun aCharacterIsSetByTypeOrByValuesOrCleared() {
        assertEquals("""{"request":{"type":"ENTP"}}""", Temperament.typeRequest("ENTP").toString())
        assertThrows(IllegalArgumentException::class.java) { Temperament.typeRequest("XXXX") }
        val values = Temperament.FUNCTIONS.associateWith { 50 }
        val request = Temperament.traitsRequest(values).getJSONObject("request").getJSONObject("traits")
        assertEquals(Temperament.FUNCTIONS.toSet(), request.keys().asSequence().toSet())
        assertThrows(IllegalArgumentException::class.java) { Temperament.traitsRequest(values + ("Fi" to 101)) }
        assertThrows(IllegalArgumentException::class.java) { Temperament.traitsRequest(values - "Fi") }
        assertEquals("""{"request":{"traits":null}}""", Temperament.clearRequest().toString())
        assertEquals(16, Temperament.TYPES.toSet().size)
    }

    @Test fun energyAndMoodReadInWords() {
        val p = Temperament.psyche(JSONObject("""{"active":true,"energy":42.4,"mood":130,"energyBand":"low","moodBand":"high","lastOwnerAt":5,"running":false}"""))!!
        assertTrue(p.active)
        assertEquals(100.0, p.mood, 0.0)
        assertEquals("有点累", Temperament.energyWords(p.energyBand))
        assertEquals("心情不错", Temperament.moodWords(p.moodBand))
        assertNull(Temperament.psyche("x"))
    }

    @Test fun outreachReadsWithDefaultsAndChangesOneFieldAtATime() {
        val o = Temperament.outreach(JSONObject("""{"contacts":[{"package":"com.tencent.mobileqq","remark":"主人","shownAs":"小满"},{"package":"x","remark":""}],
            "level":"few","quietFrom":"22:30","quietTo":"07:00","dndUntil":9,"preferences":"多看 B 站"}"""))
        assertEquals(listOf(Temperament.Contact("com.tencent.mobileqq", "主人", "小满")), o.contacts)
        assertEquals("few", o.level)
        assertEquals("少 · 每天最多 1 件事", Temperament.levelText(o.level))
        assertEquals("关", Temperament.levelText("off"))
        assertEquals(Temperament.Outreach(), Temperament.outreach(JSONObject("""{"level":"loud"}""")))
        assertEquals("""{"request":{"level":"many"}}""", Temperament.levelRequest("many").toString())
        assertThrows(IllegalArgumentException::class.java) { Temperament.levelRequest("loud") }
        val contacts = Temperament.contactsRequest(listOf(Temperament.Contact("com.tencent.mm", " 主人 ", "小满"))).getJSONObject("request").getJSONArray("contacts")
        assertEquals("主人", contacts.getJSONObject(0).getString("remark"))
        assertThrows(IllegalArgumentException::class.java) { Temperament.contactsRequest(List(2) { Temperament.Contact("com.tencent.mm", "a", "b") }) }
        assertThrows(IllegalArgumentException::class.java) { Temperament.contactsRequest(listOf(Temperament.Contact("com.tencent.mm", " ", "b"))) }
        assertEquals("""{"request":{"preferences":"少刷短视频"}}""", Temperament.preferencesRequest(" 少刷短视频 ").toString())
        assertThrows(IllegalArgumentException::class.java) { Temperament.preferencesRequest("字".repeat(601)) }
    }

    @Test fun quietHoursAreTwoClockTimes() {
        assertEquals("23:00" to "08:00", Temperament.quietOf("23:00-08:00"))
        assertEquals("22:30" to "07:00", Temperament.quietOf(" 22:30 到 7:00 "))
        assertEquals("23:00" to "08:00", Temperament.quietOf("23：00～08:00"))
        assertNull(Temperament.quietOf("23:00"))
        assertNull(Temperament.quietOf("25:00-08:00"))
        assertNull(Temperament.quietOf("08:00-08:00"))
        assertEquals(JSONObject("""{"request":{"quietFrom":"23:00","quietTo":"08:00"}}""").toString(), Temperament.quietRequest("23:00", "08:00").toString())
    }

    @Test fun doNotDisturbForAnHourOrUntilTheMorning() {
        val evening = at("2026-10-31T21:15:00")
        assertEquals(at("2026-10-31T22:15:00"), Temperament.inAnHour(evening))
        assertEquals(at("2026-11-01T08:00:00"), Temperament.nextMorning(evening, zone))
        assertEquals("before 8 it is this morning", at("2026-10-31T08:00:00"), Temperament.nextMorning(at("2026-10-31T02:00:00"), zone))
        assertEquals("明天 08:00 前别找我", Temperament.dndText(at("2026-11-01T08:00:00"), evening, zone))
        assertEquals("22:15 前别找我", Temperament.dndText(at("2026-10-31T22:15:00"), evening, zone))
        assertEquals("没开", Temperament.dndText(0, evening, zone))
        assertEquals("""{"request":{"until":0}}""", Temperament.dndRequest(-5).toString())
        assertEquals("""{"request":{"at":7}}""", Temperament.seenRequest(7).toString())
    }

    @Test fun aPatrolNoteBecomesPreferencesForItsOwnTime() {
        assertEquals("", Temperament.patrolDirection(ScheduleFormat.patrolTask(false)))
        assertEquals("", Temperament.patrolDirection(ScheduleFormat.patrolTask(true)))
        assertEquals("喜欢画画，常看 B 站教程", Temperament.patrolDirection("[巡视] 每次挑 2–3 个应用逛逛。喜欢画画，常看 B 站教程"))
        assertNull(Temperament.patrolDirection("[便条] 看看回复"))
        assertNull(Temperament.patrolDirection("每天签到"))
        assertEquals("多看 B 站\n少刷抖音", Temperament.mergePreferences("多看 B 站", listOf("", "多看 B 站", "少刷抖音")))
        assertEquals("少刷抖音", Temperament.mergePreferences(" ", listOf("少刷抖音")))
        assertEquals("a".repeat(600), Temperament.mergePreferences("a".repeat(600), listOf("b")))
    }

    @Test fun knownAppsHaveTheirNames() {
        assertEquals("QQ", Temperament.appLabel("com.tencent.mobileqq"))
        assertEquals("微信", Temperament.appLabel("com.tencent.mm"))
        assertEquals("org.example", Temperament.appLabel("org.example"))
    }
}
