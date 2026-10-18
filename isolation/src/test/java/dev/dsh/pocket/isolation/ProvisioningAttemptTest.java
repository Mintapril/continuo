package dev.dsh.pocket.isolation;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class ProvisioningAttemptTest {
    private static final class Memory implements ProvisioningAttempt.Store {
        boolean used, writesSucceed=true, failRead=false, failWrite=false; int writes;
        public boolean attempted() { if(failRead)throw new IllegalStateException(); return used; }
        public boolean persistAttempt() { writes++; if(failWrite)throw new IllegalStateException(); if(!writesSucceed)return false; used=true; return true; }
    }
    @Test public void firstClaimPersistsBeforeGrantAndCannotRepeat() {
        Memory m=new Memory(); assertTrue(ProvisioningAttempt.available(m)); assertTrue(ProvisioningAttempt.claim(m));
        assertTrue(m.used); assertFalse(ProvisioningAttempt.available(m)); assertFalse(ProvisioningAttempt.claim(m)); assertEquals(1,m.writes);
    }
    @Test public void restoredStoreKeepsPriorAttemptClosed() {
        Memory m=new Memory();m.used=true;assertFalse(ProvisioningAttempt.available(m));assertFalse(ProvisioningAttempt.claim(m));assertEquals(0,m.writes);
    }
    @Test public void failedCommitDoesNotGrant() {
        Memory m=new Memory();m.writesSucceed=false;assertFalse(ProvisioningAttempt.claim(m));assertEquals(1,m.writes);
    }
    @Test public void unknownReadFailsClosed() {
        Memory m=new Memory();m.failRead=true;assertFalse(ProvisioningAttempt.available(m));assertFalse(ProvisioningAttempt.claim(m));assertEquals(0,m.writes);
    }
    @Test public void writeExceptionFailsClosed() {
        Memory m=new Memory();m.failWrite=true;assertFalse(ProvisioningAttempt.claim(m));
    }
    @Test public void separateActivityAdaptersCannotBothClaim() throws Exception {
        Memory m=new Memory();CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);AtomicInteger wins=new AtomicInteger();
        Runnable attempt=()->{ProvisioningAttempt.Store adapter=new ProvisioningAttempt.Store(){public boolean attempted(){return m.attempted();}public boolean persistAttempt(){return m.persistAttempt();}};
            ready.countDown();try{go.await();if(ProvisioningAttempt.claim(adapter))wins.incrementAndGet();}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        Thread a=new Thread(attempt),b=new Thread(attempt);a.start();b.start();ready.await();go.countDown();a.join(2000);b.join(2000);
        assertFalse(a.isAlive());assertFalse(b.isAlive());assertEquals(1,wins.get());assertEquals(1,m.writes);
    }
}
