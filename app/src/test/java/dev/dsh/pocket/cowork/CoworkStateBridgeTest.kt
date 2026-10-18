package dev.dsh.pocket.cowork

import dev.dsh.pocket.island.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoworkStateBridgeTest {
    private fun state(rev:Long,state:String="running",id:String="task",instance:String="one",at:Long=rev,seq:Long=at,execution:String="run")=JSONObject()
        .put("instanceId",instance).put("executionId",execution).put("taskId",id).put("revision",rev).put("state",state)
        .put("mainPackage","launcher").put("mainIsGame",false).put("locked",false).put("environmentSequence",seq).put("environmentObservedAt",at)
    private fun withActivity(rev:Long=1,at:Long=1,seq:Long=1,app:String="浏览器",text:String="打开 浏览器",ids:List<String> = listOf("1"))=state(rev,at=at)
        .put("activity",JSONObject().put("seq",seq).put("app",app).put("text",text)
            .put("events",org.json.JSONArray().also{arr->ids.forEach{arr.put(JSONObject().put("id",it).put("text","动作 $it"))}}))
    @Test fun activityFeedsIslandWithoutAnyModelText()=runTest{
        val store=IslandStore(backgroundScope);val bridge=CoworkStateBridge(store)
        bridge.apply(withActivity(),100)
        assertEquals("浏览器",store.snapshots.value.activity.app);assertEquals("打开 浏览器",store.snapshots.value.activity.text)
        assertEquals(listOf("act:1"),store.snapshots.value.recentEvents.map{it.id}.filter{it.startsWith("act:")})
        bridge.apply(withActivity(at=2,seq=2,app="地图",text="输入文字",ids=listOf("1","2")),100)
        assertEquals("地图",store.snapshots.value.activity.app);assertEquals(listOf("act:1","act:2"),store.snapshots.value.recentEvents.map{it.id}.filter{it.startsWith("act:")})
    }
    @Test fun staleActivitySequenceIsIgnoredAndNewTaskResetsActivity()=runTest{
        val store=IslandStore(backgroundScope);val bridge=CoworkStateBridge(store)
        bridge.apply(withActivity(seq=5),100)
        bridge.apply(withActivity(at=2,seq=3,app="旧").put("revision",1),100)
        assertEquals("浏览器",store.snapshots.value.activity.app)
        bridge.apply(state(2,id="next",at=3),100)
        assertEquals(IslandActivity(),store.snapshots.value.activity)
    }    @Test fun stoppedPollingNeverInventsRevisions()=runTest{
        val store=IslandStore(backgroundScope);val bridge=CoworkStateBridge(store)
        bridge.apply(state(1),100);bridge.apply(state(2,"stopped"),200)
        repeat(20){bridge.apply(state(2,"stopped",at=3+it.toLong()),201+it.toLong())}
        assertEquals(2L,store.snapshots.value.task?.revision);assertEquals(IslandTaskState.STOPPED,store.snapshots.value.task?.state)
        assertEquals("已停止",store.snapshots.value.statusLabel)
    }
    @Test fun delayedStatusCannotRollBackOrSelectOldTask()=runTest{
        val store=IslandStore(backgroundScope);val bridge=CoworkStateBridge(store)
        bridge.apply(state(4,id="new"),100)
        assertFalse(bridge.apply(state(3,id="old",at=5),200));assertEquals("new",store.snapshots.value.task?.taskId)
    }
    @Test fun brokerRestartResetsSameSessionRevisionButCannotResurrectOldInstance()=runTest{
        val store=IslandStore(backgroundScope);val bridge=CoworkStateBridge(store)
        bridge.apply(state(30),100);assertTrue(bridge.apply(state(1,instance="two",at=40),200))
        assertEquals(1L,store.snapshots.value.task?.revision)
        assertFalse(bridge.apply(state(31,at=41),210));assertEquals("two",store.snapshots.value.task?.execution?.instanceId)
    }
    @Test fun disconnectRetainsIdentityWithoutClaimingCompletionAndRecovers()=runTest{
        val store=IslandStore(backgroundScope);val bridge=CoworkStateBridge(store)
        bridge.apply(state(1),100);bridge.disconnected();assertNotNull(store.snapshots.value.task)
        assertEquals("连接中断",store.snapshots.value.statusLabel);assertFalse(store.snapshots.value.backendConnected)
        assertTrue(bridge.apply(state(1,at=2),200));assertTrue(store.snapshots.value.backendConnected)
        bridge.apply(state(2,"idle",id="",at=3),200);assertNull(store.snapshots.value.task)
    }
    @Test fun stoppedTaskHasNoPauseOrStopAction()=runTest{
        val store=IslandStore(backgroundScope);val bridge=CoworkStateBridge(store)
        bridge.apply(state(1,"stopped"),100)
        assertFalse(IslandPolicy.allows(store.snapshots.value.task,IslandAction.PAUSE))
        assertFalse(IslandPolicy.allows(store.snapshots.value.task,IslandAction.STOP))
        assertTrue(IslandPolicy.allows(store.snapshots.value.task,IslandAction.OPEN_TASK))
    }
    @Test fun staleEnvironmentDoesNotBecomeFreshOnReceipt()=runTest{
        val store=IslandStore(backgroundScope);val bridge=CoworkStateBridge(store)
        assertTrue(bridge.apply(state(1,at=100),1000));assertEquals(100L,store.snapshots.value.environment.observedAtElapsedMillis)
        assertFalse(bridge.apply(state(1,at=99),2000));assertFalse(bridge.apply(state(1,at=100),3000))
        assertFalse(bridge.apply(state(1,at=4000),3000));assertEquals(100L,store.snapshots.value.environment.observedAtElapsedMillis)
    }
    @Test fun olderUnseenInstanceCannotReplaceCurrent()=runTest{
        val store=IslandStore(backgroundScope);val bridge=CoworkStateBridge(store)
        bridge.apply(state(1,at=200),200)
        assertFalse(bridge.apply(state(4,instance="older",at=100),300))
        assertEquals("one",store.snapshots.value.task?.execution?.instanceId)
    }
    @Test fun missingExecutionFailsBeforeMutatingState()=runTest{
        val store=IslandStore(backgroundScope);val bridge=CoworkStateBridge(store)
        assertFalse(bridge.apply(state(1,execution=""),100));assertNull(store.snapshots.value.task)
        assertTrue(bridge.apply(state(1),100))
    }
}
