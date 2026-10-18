package dev.dsh.pocket.island

import org.junit.Assert.*
import org.junit.Test

class IslandPolicyTest {
    private val now = 10_000L
    private val visible = IslandSnapshot(
        task = IslandTaskUpdate("a", 1, IslandTaskState.RUNNING),
        preferences = IslandPreferences(enabled = true, overlayEnabled = true),
        environment = IslandEnvironment("example.reader", false, now),
    )

    private fun policy(snapshot: IslandSnapshot = visible, permission: Boolean = true, locked: Boolean = false) =
        IslandPolicy.visibility(snapshot, now, permission, locked)

    @Test fun safeKnownMainDisplayForegroundAllowsOverlay() {
        assertEquals(IslandSurface.OVERLAY, policy().surface)
    }

    @Test fun explicitlyPrearmedIdleServiceIsNotificationOnly() {
        assertEquals(IslandSurface.NOTIFICATION, policy(visible.copy(task = null)).surface)
        assertEquals(IslandSuppression.NO_TASK, policy(visible.copy(task = null)).reason)
        assertEquals(IslandSuppression.DISABLED,
            policy(visible.copy(preferences = IslandPreferences())).reason)
    }

    @Test fun permissionAndLockNeverBypassed() {
        assertEquals(IslandSuppression.PERMISSION_MISSING, policy(permission = false).reason)
        assertEquals(IslandSurface.NOTIFICATION, policy(permission = false).surface)
        assertEquals(IslandSuppression.DEVICE_LOCKED, policy(locked = true).reason)
        assertEquals(IslandSurface.NOTIFICATION, policy(locked = true).surface)
    }

    @Test fun gameAndUserExclusionsHaveNotificationFallback() {
        val game = visible.copy(environment = visible.environment.copy(foregroundIsGame = true))
        assertEquals(IslandSuppression.GAME, policy(game).reason)
        assertEquals(IslandSurface.NOTIFICATION, policy(game).surface)
        val excluded = visible.copy(preferences = visible.preferences.copy(excludedPackages = setOf("example.reader")))
        assertEquals(IslandSuppression.EXCLUDED_APP, policy(excluded).reason)
        assertEquals(IslandSurface.NOTIFICATION, policy(excluded).surface)
        val gamesAllowed = game.copy(preferences = game.preferences.copy(hideInGames = false))
        assertEquals(IslandSurface.OVERLAY, policy(gamesAllowed).surface)
    }

    @Test fun unknownOrStaleMainDisplayObservationFailsClosed() {
        assertEquals(IslandSuppression.FOREGROUND_UNKNOWN,
            policy(visible.copy(environment = IslandEnvironment())).reason)
        assertEquals(IslandSuppression.FOREGROUND_UNKNOWN,
            policy(visible.copy(environment = visible.environment.copy(foregroundIsGame = null))).reason)
        assertEquals(IslandSuppression.FOREGROUND_STALE,
            policy(visible.copy(environment = visible.environment.copy(observedAtElapsedMillis = now - 5_001))).reason)
        assertEquals(IslandSuppression.FOREGROUND_STALE,
            policy(visible.copy(environment = visible.environment.copy(observedAtElapsedMillis = now + 1))).reason)
        assertEquals(IslandSurface.OVERLAY,
            policy(visible.copy(environment = visible.environment.copy(observedAtElapsedMillis = now - 5_000))).surface)
    }

    @Test fun notificationOnlyIsExplicitUserChoice() {
        val snapshot = visible.copy(preferences = visible.preferences.copy(overlayEnabled = false))
        assertEquals(IslandSuppression.USER_NOTIFICATION_ONLY, policy(snapshot).reason)
        assertEquals(IslandSurface.NOTIFICATION, policy(snapshot).surface)
    }

    @Test fun controlsCannotResumeOrApproveAndTerminalTaskCannotBeStopped() {
        for (state in IslandTaskState.entries) {
            val task = IslandTaskUpdate("a", 1, state)
            assertEquals(state == IslandTaskState.RUNNING, IslandPolicy.allows(task, IslandAction.PAUSE))
            assertEquals(!state.terminal, IslandPolicy.allows(task, IslandAction.STOP))
            assertTrue(IslandPolicy.allows(task, IslandAction.OPEN_TASK))
        }
        assertEquals(setOf(IslandAction.PAUSE, IslandAction.STOP, IslandAction.OPEN_TASK), IslandAction.entries.toSet())
        assertFalse(IslandPolicy.allows(null, IslandAction.STOP))
    }
}
