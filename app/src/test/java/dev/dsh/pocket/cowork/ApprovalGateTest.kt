package dev.dsh.pocket.cowork

import dev.dsh.pocket.cowork.broker.ActionGate
import dev.dsh.pocket.cowork.broker.ApprovalGate
import org.junit.Assert.*
import org.junit.Test

class ApprovalGateTest {
    private fun denied(block:()->Unit){try{block();fail("Expected stale approval")}catch(e:ActionGate.Denied){assertEquals("APPROVAL_STALE",e.code)}}
    @Test fun reservedActionCannotExtendObservationDeadline(){
        val gate=ActionGate(listOf("fixture"));gate.start("A");val id=gate.observe("A","page",100)
        val deadline=gate.consume("A",id,"page",59000)
        val approvals=ApprovalGate();val p=approvals.reserve("A","phone.key","exact-json","page",59000,deadline)
        assertEquals(60100L,p.deadline);denied{approvals.consume(p.id,"A","page",60101)}
    }
    @Test fun onlyExactSessionAndScreenAcceptedOnce(){
        val g=ApprovalGate();val p=g.reserve("A","phone.tap","args","page",100,60100)
        assertEquals("args",g.consume(p.id,"A","page",60100).arguments)
        denied{g.consume(p.id,"A","page",60100)}
    }
    @Test fun wrongSessionRevokes(){val g=ApprovalGate();val p=g.reserve("A","phone.key","{}","page",100,60100);denied{g.consume(p.id,"B","page",200)};assertNull(g.pending())}
    @Test fun changedScreenRevokes(){val g=ApprovalGate();val p=g.reserve("A","phone.key","{}","page",100,60100);denied{g.consume(p.id,"A","new",200)}}
    @Test fun backwardClockDenied(){val g=ApprovalGate();val p=g.reserve("A","phone.key","{}","page",100,60100);denied{g.consume(p.id,"A","page",99)}}
    @Test fun pauseOrRejectCanClear(){val g=ApprovalGate();val p=g.reserve("A","phone.key","{}","page",100,60100);g.clear();denied{g.consume(p.id,"A","page",200)}}
    @Test fun cannotReserveExpiredOrExtended(){val g=ApprovalGate();denied{g.reserve("A","phone.key","{}","page",100,99)};denied{g.reserve("A","phone.key","{}","page",100,60101)}}
}
