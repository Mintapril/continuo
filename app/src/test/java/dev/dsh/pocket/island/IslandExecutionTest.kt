package dev.dsh.pocket.island

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IslandExecutionTest {
    private val execution=IslandExecution("broker","run")
    private fun task(revision:Long,state:IslandTaskState=IslandTaskState.RUNNING,run:IslandExecution=execution)=IslandTaskUpdate("task",revision,state,run)
    @Test fun staleRevisionSafetyControlsAcceptOnlySameExecution()=runTest{
        val store=IslandStore(backgroundScope);store.selectTask(task(10))
        val handler:suspend(IslandActionRequest)->Unit={}
        assertFalse(store.requestAction("task",1,IslandAction.STOP,handler,null))
        assertFalse(store.requestAction("task",1,IslandAction.STOP,handler,IslandExecution("broker","old")))
        assertFalse(store.requestAction("task",11,IslandAction.STOP,handler,execution))
        assertFalse(store.requestAction("task",1,IslandAction.OPEN_TASK,handler,execution))
        assertTrue(store.requestAction("task",1,IslandAction.STOP,handler,execution))
    }
    @Test fun stopRemainsInFlightAcrossProgressAndPauseUntilStopped()=runTest{
        val store=IslandStore(backgroundScope);store.selectTask(task(1))
        val release=CompletableDeferred<Unit>();var finished=false
        assertTrue(store.requestAction("task",1,IslandAction.STOP,{release.await();finished=true},execution));runCurrent()
        store.updateTask(task(2));store.updateTask(task(3,IslandTaskState.PAUSED));runCurrent()
        assertNotNull(store.snapshots.value.pendingAction);release.complete(Unit);runCurrent();assertTrue(finished)
        store.updateTask(task(4,IslandTaskState.STOPPED));assertNull(store.snapshots.value.pendingAction)
        assertFalse(store.snapshots.value.actionError)
    }
    @Test fun pauseNotCancelledByApprovalProgress()=runTest{
        val store=IslandStore(backgroundScope);store.selectTask(task(1))
        assertTrue(store.requestAction("task",1,IslandAction.PAUSE,{},execution));runCurrent()
        store.updateTask(task(2,IslandTaskState.APPROVAL));assertNotNull(store.snapshots.value.pendingAction)
        store.updateTask(task(3,IslandTaskState.PAUSED));assertNull(store.snapshots.value.pendingAction)
    }
    @Test fun replacementExecutionRejectsOldControlAndClearsPending()=runTest{
        val store=IslandStore(backgroundScope);store.selectTask(task(1))
        store.requestAction("task",1,IslandAction.STOP,{},execution)
        val next=IslandExecution("broker","next");store.selectTask(task(2,run=next))
        assertNull(store.snapshots.value.pendingAction)
        assertFalse(store.requestAction("task",1,IslandAction.STOP,{},execution))
    }
    @Test fun disconnectedRetainsTaskButDisablesControls()=runTest{
        val store=IslandStore(backgroundScope);store.selectTask(task(1));store.setConnected(false)
        assertNotNull(store.snapshots.value.task);assertFalse(store.requestAction("task",1,IslandAction.STOP,{},execution))
        store.setConnected(true);assertTrue(store.requestAction("task",1,IslandAction.STOP,{},execution))
    }
}
