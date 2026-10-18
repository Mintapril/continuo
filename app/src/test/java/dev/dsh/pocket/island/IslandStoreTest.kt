package dev.dsh.pocket.island

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IslandStoreTest {
    private fun task(revision: Long, state: IslandTaskState = IslandTaskState.RUNNING, id: String = "a") =
        IslandTaskUpdate(id, revision, state)
    @Test fun taskRevisionCannotRegressAndSameRevisionCannotChangeState() = runTest {
        val store = IslandStore(backgroundScope)
        store.selectTask(task(9))
        assertFalse(store.updateTask(task(8, IslandTaskState.ERROR)))
        assertFalse(store.updateTask(task(9, IslandTaskState.COMPLETED)))
        assertFalse(store.updateTask(task(10, id = "b")))
        assertEquals(task(9), store.snapshots.value.task)
        assertTrue(store.updateTask(task(10, IslandTaskState.APPROVAL)))
        assertEquals("需要确认", store.snapshots.value.statusLabel)
    }

    private fun ev(id: String, text: String) = IslandEvent("act:$id", IslandEventKind.PROGRESS, text, privacySafe = true)

    @Test fun activityRequiresSelectedTaskAndMonotonicSequence() = runTest {
        val store = IslandStore(backgroundScope)
        assertFalse(store.submitActivity("a", 1, "浏览器", "打开应用", emptyList()))
        store.selectTask(task(4))
        assertFalse(store.submitActivity("other", 1, "浏览器", "打开应用", emptyList()))
        assertTrue(store.submitActivity("a", 2, "浏览器", "打开应用", listOf(ev("1", "打开 浏览器"))))
        assertFalse(store.submitActivity("a", 2, "地图", "duplicate", emptyList()))
        assertFalse(store.submitActivity("a", 1, "地图", "stale", emptyList()))
        assertEquals("浏览器", store.snapshots.value.activity.app)
        assertEquals("打开应用", store.snapshots.value.activity.text)
        assertTrue(store.submitActivity("a", 3, "地图", "输入文字", listOf(ev("1", "打开 浏览器"), ev("2", "输入文字"))))
        assertEquals(listOf("act:1", "act:2"), store.snapshots.value.recentEvents.takeLast(2).map { it.id })
        assertEquals("地图", store.snapshots.value.activity.app)
    }

    @Test fun activityIsBoundedRedactedAndResetWithTask() = runTest {
        val store = IslandStore(backgroundScope)
        store.selectTask(task(1))
        store.submitActivity("a", 1, "app", "token=abcdef user@example.com", (1..20).map { ev("e$it", "step $it") })
        assertEquals(IslandStore.MAX_EVENTS, store.snapshots.value.recentEvents.size)
        assertFalse(store.snapshots.value.activity.text.contains("abcdef"))
        assertFalse(store.snapshots.value.activity.text.contains("user@example"))
        store.selectTask(task(1, id = "b"))
        assertEquals(IslandActivity(), store.snapshots.value.activity)
        assertTrue(store.submitActivity("b", 1, "app", "x", emptyList()))
        store.clearTask()
        assertNull(store.snapshots.value.task)
        assertEquals(IslandActivity(), store.snapshots.value.activity)
        assertTrue(store.snapshots.value.recentEvents.isEmpty())
    }
    @Test fun staleClicksCannotActOnNewRevisionOrTaskAndNeverAutoApprove() = runTest {
        val store = IslandStore(backgroundScope)
        val actions = mutableListOf<IslandActionRequest>()
        val handler: suspend (IslandActionRequest) -> Unit = { actions.add(it); Unit }
        store.selectTask(task(2, IslandTaskState.APPROVAL))
        assertFalse(store.requestAction("a", 1, IslandAction.STOP, handler))
        assertFalse(store.requestAction("b", 2, IslandAction.STOP, handler))
        assertFalse(store.requestAction("a", 2, IslandAction.PAUSE, handler))
        runCurrent()
        assertTrue(actions.isEmpty())
        assertTrue(store.requestAction("a", 2, IslandAction.STOP, handler))
        assertFalse(store.requestAction("a", 2, IslandAction.STOP, handler))
        runCurrent()
        assertEquals(IslandTaskState.APPROVAL, store.snapshots.value.task?.state)
        assertEquals(1, actions.size)
        assertNotNull(store.snapshots.value.pendingAction)
        store.updateTask(task(3, IslandTaskState.COMPLETED))
        assertNull(store.snapshots.value.pendingAction)
    }

    @Test fun actionFailureAndTimeoutNeverInventSuccessfulState() = runTest {
        val store = IslandStore(backgroundScope, actionTimeoutMillis = 100)
        store.selectTask(task(1))
        assertFalse(store.requestAction("a", 1, IslandAction.PAUSE, null))
        assertTrue(store.snapshots.value.actionError)
        assertTrue(store.requestAction("a", 1, IslandAction.PAUSE) { })
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertTrue(store.snapshots.value.actionError)
        assertNull(store.snapshots.value.pendingAction)
        assertEquals(IslandTaskState.RUNNING, store.snapshots.value.task?.state)
        assertTrue(store.requestAction("a", 1, IslandAction.STOP) { error("private backend response") })
        runCurrent()
        assertTrue(store.snapshots.value.actionError)
        assertEquals(IslandTaskState.RUNNING, store.snapshots.value.task?.state)
    }

    @Test fun olderForegroundCannotUndoNewerExclusionOrUnknownInvalidation() = runTest {
        val store = IslandStore(backgroundScope)
        assertTrue(store.updateEnvironment(IslandEnvironment("game", true, 30)))
        assertFalse(store.updateEnvironment(IslandEnvironment("reader", false, 20)))
        assertEquals("game", store.snapshots.value.environment.foregroundPackage)
        assertTrue(store.updateEnvironment(IslandEnvironment()))
        assertFalse(store.updateEnvironment(IslandEnvironment("reader", false, 30)))
        assertNull(store.snapshots.value.environment.foregroundPackage)
        assertTrue(store.updateEnvironment(IslandEnvironment("reader", false, 31)))
    }

    @Test fun privacyRedactsCommonSecrets() {
        val label = IslandPrivacy.safeLabel("https://site.example/private user@example.com token=abcdef +8613812345678")
        assertFalse(label.contains("site.example"))
        assertFalse(label.contains("user@example"))
        assertFalse(label.contains("abcdef"))
        assertFalse(label.contains("13812345678"))
    }
}
