package dev.dsh.pocket.data

import org.junit.Assert.*
import org.junit.Test

class IdentityRulesTest {
    @Test fun aNameIsShortPlainAndNotTheAppsOwn() {
        assertNull(IdentityRules.problem("小满"))
        assertNull(IdentityRules.problem("  阿  简 "))
        assertEquals("阿 简", IdentityRules.normalize("  阿  简 "))
        assertNotNull(IdentityRules.problem("  "))
        assertNotNull(IdentityRules.problem("一二三四五六七八九十一二三"))
        assertNull(IdentityRules.problem("一二三四五六七八九十一二"))
        assertNotNull("the app is called 通奏", IdentityRules.problem("通奏"))
        assertNotNull(IdentityRules.problem("Continuo"))
        assertNotNull(IdentityRules.problem("a{b}"))
    }

    @Test fun avatarsAreTheShippedOnes() {
        assertEquals("claude", IdentityRules.avatar("claude"))
        assertEquals(IdentityRules.DEFAULT_AVATAR, IdentityRules.avatar("doubao"))
        assertEquals(IdentityRules.DEFAULT_AVATAR, IdentityRules.avatar(null))
        assertEquals(8, IdentityRules.AVATARS.size)
    }

    @Test fun suggestionsChangeEachRound() {
        assertEquals(3, IdentityRules.suggestions(0).size)
        assertNotEquals(IdentityRules.suggestions(0), IdentityRules.suggestions(1))
        assertEquals(IdentityRules.suggestions(0), IdentityRules.suggestions(IdentityRules.SUGGESTIONS.size))
        assertEquals("它", Identity().shown)
        assertEquals("小满", Identity("小满").shown)
    }
}
