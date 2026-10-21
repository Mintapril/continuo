package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ActionGate
import dev.dsh.pocket.cowork.broker.TaskLifecycle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TaskLifecycleTest {
    private fun begin(id:String="session",revision:Long=4)=JSONObject().put("operation","begin").put("taskId",id).put("instanceId","broker").put("revision",revision)
    private fun call(g:TaskLifecycle,op:String)=JSONObject().put("operation",op).put("taskId",g.task()).put("instanceId","broker").put("executionId",g.execution())
    private fun denied(code:String,body:()->Unit){try{body();fail("Expected $code")}catch(e:ActionGate.Denied){assertEquals(code,e.code)}}
    private fun running()=TaskLifecycle("broker").also{it.begin(begin(),4,100)}
    @Test fun filesOnlyLeaseNeedsNoPhoneDisplay(){val g=running();assertTrue(g.active());assertEquals("running",g.state());g.requireRunning("session");assertTrue(g.execution().isNotBlank())}
    @Test fun snapshotIsExactFlatContract(){val s=running().snapshot(5);assertEquals(setOf("instanceId","executionId","taskId","revision","state","command","commandId"),s.keys().asSequence().toSet());assertEquals(5L,s.getLong("revision"))}
    @Test fun idleHasNoExecution(){val s=TaskLifecycle("broker").snapshot(0);assertEquals("",s.getString("taskId"));assertEquals("",s.getString("executionId"));assertEquals("idle",s.getString("state"))}
    @Test fun admissionIsAtomicAndRevisionBound(){val g=running();denied("SESSION_BUSY"){g.begin(begin("another"),4,200)};assertEquals("session",g.task());denied("REVISION_STALE"){TaskLifecycle("broker").begin(begin(revision=3),4,200)}}
    @Test fun exactSchemaAndSessionCharacters(){for(p in listOf(begin().put("extra",1),begin().put("revision","4"))){denied(if(p.has("extra"))"CONTROL_DENIED" else "REVISION_STALE"){TaskLifecycle("broker").begin(p,4,0)}};denied("SESSION_INVALID"){TaskLifecycle("broker").begin(begin("../other"),4,0)}}
    @Test fun stalePollNeverRefreshesLease(){val g=running();denied("EXECUTION_STALE"){g.poll(call(g,"poll").put("executionId","old"),15101)};assertTrue(g.overdue(15101))}
    @Test fun heartbeatBoundaryAndMonotonicClock(){val g=running();assertFalse(g.overdue(15100));assertTrue(g.overdue(15101));assertTrue(g.overdue(99));g.poll(call(g,"poll"),200);assertFalse(g.overdue(15200))}
    @Test fun controlStaysPendingUntilExactSettlement(){val g=running();assertTrue(g.request("pause"));val id=g.snapshot(6).getString("commandId");assertFalse(g.request("pause"));assertEquals(id,g.snapshot(6).getString("commandId"));assertTrue(g.active());assertEquals("pausing",g.state());denied("PAUSED"){g.requireRunning("session")};denied("EXECUTION_STALE"){g.acknowledge(call(g,"ack").put("commandId","old"))};assertTrue(g.active());g.acknowledge(call(g,"ack").put("commandId",id));assertEquals("paused",g.state());assertFalse(g.active())}
    @Test fun stopUpgradesPauseAndOldAckCannotSettle(){val g=running();g.request("pause");val old=g.snapshot(1).getString("commandId");g.request("stop");assertFalse(g.request("pause"));val before=g.snapshot(2).toString();assertFalse(g.acknowledge(call(g,"ack").put("commandId",old)));assertEquals(before,g.snapshot(2).toString());assertEquals("stopping",g.state());assertTrue(g.active());denied("EXECUTION_STALE"){g.acknowledge(call(g,"ack").put("commandId","arbitrary-old"))};assertTrue(g.acknowledge(call(g,"ack").put("commandId",g.snapshot(2).getString("commandId"))));assertEquals("stopped",g.state());assertFalse(g.acknowledge(call(g,"ack").put("commandId",old)));assertEquals("stopped",g.state())}
    @Test fun commandWinsFinalPollEndRace(){val g=running();g.request("stop");assertFalse(g.end(call(g,"end").put("outcome","completed"),false));assertTrue(g.active());assertEquals("stopping",g.state());assertTrue(g.snapshot(5).getString("commandId").isNotBlank())}
    @Test fun normalTerminalRetainsIdentityAndAllowsFreshLease(){val g=running();val old=g.execution();assertTrue(g.end(call(g,"end").put("outcome","completed"),false));assertEquals(old,g.execution());assertEquals("completed",g.state());g.begin(begin(),4,200);assertNotEquals(old,g.execution())}
    @Test fun approvalCannotBecomeCompletedOrResumeFromPhase(){val g=running();g.phase("waiting_user");assertTrue(g.end(call(g,"end").put("outcome","completed"),true));assertEquals("waiting_user",g.state());g.phase("running");assertEquals("waiting_user",g.state());assertFalse(g.active())}
    @Test fun lateOldExecutionCannotCancelSuccessor(){val g=running();g.request("stop");val oldAck=call(g,"ack").put("commandId",g.snapshot(1).getString("commandId"));g.acknowledge(oldAck);g.begin(begin(),4,300);denied("EXECUTION_STALE"){g.acknowledge(oldAck)};assertEquals("running",g.state())}
    @Test fun unknownOutcomesAndPhasesDenied(){val g=running();denied("CONTROL_DENIED"){g.end(call(g,"end").put("outcome","success-from-model"),false)};denied("CONTROL_DENIED"){g.phase("stopped")};denied("CONTROL_DENIED"){g.request("resume")};assertTrue(g.active())}
    @Test fun terminalStopNeverClaimsCompletion(){val g=running();g.end(call(g,"end").put("outcome","waiting_user"),true);g.stopSettled();assertEquals("stopped",g.state());assertFalse(g.active())}
    @Test fun everyFinishedTurnParksTheDisplayExceptWhileWaitingForTheUser(){
        listOf("completed","stopped","error").forEach{assertTrue(it,TaskLifecycle.parksDisplay(it,false))}
        assertFalse(TaskLifecycle.parksDisplay("waiting_user",false))
        listOf("completed","stopped","error").forEach{assertFalse(it,TaskLifecycle.parksDisplay(it,true))}
    }
    @Test fun settledEndAndAckAreIdempotentNoOps(){val g=running();assertTrue(g.end(call(g,"end").put("outcome","completed"),false));val before=g.snapshot(9).toString();assertFalse(g.end(call(g,"end").put("outcome","error"),false));assertFalse(g.acknowledge(call(g,"ack").put("commandId","whatever")));assertEquals(before,g.snapshot(9).toString());assertEquals("completed",g.state())}
    @Test fun settledPollIsAnsweredNotDenied(){val g=running();g.end(call(g,"end").put("outcome","completed"),false);g.poll(call(g,"poll"),99999);assertFalse(g.active());assertFalse(g.overdue(99999))}
    @Test fun unacknowledgedStopSelfSettlesAfterGrace(){val g=running();assertTrue(g.request("stop"));assertFalse(g.expire(1000));assertFalse(g.expire(1000+TaskLifecycle.COMMAND_GRACE_MS));assertEquals("stopping",g.state());assertTrue(g.expire(1001+TaskLifecycle.COMMAND_GRACE_MS));assertEquals("stopped",g.state());assertFalse(g.active());assertEquals("",g.command());assertFalse(g.expire(999999));g.begin(begin(),4,200000);assertEquals("running",g.state())}
    @Test fun unacknowledgedPauseSelfSettlesToPaused(){val g=running();g.request("pause");g.expire(10);assertTrue(g.expire(20+TaskLifecycle.COMMAND_GRACE_MS));assertEquals("paused",g.state());assertFalse(g.active())}
    @Test fun stopUpgradeRestartsGraceAndRunningTaskNeverExpires(){val g=running();assertFalse(g.expire(5));assertFalse(g.expire(999999));assertTrue(g.active());g.request("pause");g.expire(10);g.request("stop");assertFalse(g.expire(9000));assertFalse(g.expire(9000+TaskLifecycle.COMMAND_GRACE_MS));assertTrue(g.expire(9001+TaskLifecycle.COMMAND_GRACE_MS));assertEquals("stopped",g.state())}
    @Test fun lateAckAfterSelfSettleIsHarmless(){val g=running();g.request("stop");val id=g.snapshot(1).getString("commandId");g.expire(1);g.expire(2+TaskLifecycle.COMMAND_GRACE_MS);assertFalse(g.acknowledge(call(g,"ack").put("commandId",id)));assertEquals("stopped",g.state())}
}