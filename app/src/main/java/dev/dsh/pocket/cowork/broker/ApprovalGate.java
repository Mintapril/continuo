package dev.dsh.pocket.cowork.broker;

import java.util.Objects;
import java.util.UUID;

/** Exact-action approval reserves an already-consumed observation; never extends its expiry. */
public final class ApprovalGate {
    public static final class Reservation {
        public final String id, session, tool, arguments, fingerprint;
        public final long createdAt, deadline;
        private Reservation(String session,String tool,String arguments,String fingerprint,long now,long deadline) {
            id=UUID.randomUUID().toString();this.session=session;this.tool=tool;this.arguments=arguments;
            this.fingerprint=fingerprint;createdAt=now;this.deadline=deadline;
        }
    }
    private Reservation pending;
    public synchronized Reservation reserve(String session,String tool,String arguments,String fingerprint,long now,long deadline) {
        if(pending!=null)ActionGate.fail("APPROVAL_PENDING","Resolve the previous action first");
        if(now<0||deadline<now||deadline-now>ActionGate.OBSERVATION_TTL_MS)ActionGate.fail("APPROVAL_STALE","Observation deadline expired");
        pending=new Reservation(session,tool,arguments,fingerprint,now,deadline);return pending;
    }
    public synchronized Reservation consume(String id,String session,String fingerprint,long now) {
        Reservation p=pending;pending=null; // Even a failed confirmation revokes the reservation.
        if(p==null||!Objects.equals(p.id,id)||!Objects.equals(p.session,session)||!Objects.equals(p.fingerprint,fingerprint)||now<p.createdAt||now>p.deadline)
            ActionGate.fail("APPROVAL_STALE","Approval expired or screen changed; observe again");
        return p;
    }
    public synchronized Reservation pending(){return pending;}
    public synchronized void clear(){pending=null;}
}
