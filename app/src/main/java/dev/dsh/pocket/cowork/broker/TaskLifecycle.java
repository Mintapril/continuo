package dev.dsh.pocket.cowork.broker;

import org.json.*;
import java.util.*;

/** Trusted DSH-turn lease, independent of display ownership. No model-authored lifecycle.
 *  Self-healing: a pause/stop command that its agent does not acknowledge within COMMAND_GRACE_MS is
 *  settled by native itself (the agent crashed, hung or lost its connection), and terminal operations on an
 *  already settled execution are idempotent no-ops, so neither side can wedge the other in a pending state. */
public final class TaskLifecycle {
    public static final long AGENT_TTL_MS=15_000L;
    public static final long COMMAND_GRACE_MS=8_000L;
    private final String instance;
    private String task="",execution="",state="idle",command="",commandId="",supersededPauseId="";
    private boolean active=false;
    private long seen=0,commandSeen=0;
    public TaskLifecycle(String instance){this.instance=instance;}
    public synchronized boolean active(){return active;}
    public synchronized String task(){return task;}
    public synchronized String execution(){return execution;}
    public synchronized String state(){return state;}
    public synchronized String command(){return command;}
    public synchronized boolean overdue(long now){return active&&(now<seen||now-seen>AGENT_TTL_MS);}
    public synchronized void begin(JSONObject input,long revision,long now){
        exact(input,Set.of("operation","taskId","instanceId","revision"));
        if(!"begin".equals(input.opt("operation"))||!instance.equals(input.opt("instanceId")))deny();
        Object rev=input.opt("revision");
        if(!(rev instanceof Number)||((Number)rev).doubleValue()!=revision)ActionGate.fail("REVISION_STALE","Task state changed");
        String id=text(input,"taskId");if(!id.matches("[a-zA-Z0-9_-]{1,100}"))ActionGate.fail("SESSION_INVALID","Invalid session identifier");
        if(active)ActionGate.fail("SESSION_BUSY","Another Cowork turn is active");
        task=id;execution=UUID.randomUUID().toString();state="running";command="";commandId="";supersededPauseId="";active=true;seen=now;commandSeen=0;
    }
    public synchronized void match(JSONObject input){
        if(!instance.equals(input.opt("instanceId"))||execution.isEmpty()||!execution.equals(input.opt("executionId"))||!task.equals(input.opt("taskId")))deny();
    }
    /** Poll of the current execution, live or settled: a settled snapshot tells a late agent its turn is over. */
    public synchronized void poll(JSONObject input,long now){
        exact(input,Set.of("operation","taskId","instanceId","executionId"));match(input);if(active)seen=now;
    }
    public synchronized void requireRunning(String session){
        if(!active||!task.equals(session))ActionGate.fail("SESSION_MISMATCH","No admitted Cowork turn");
        if(!command.isEmpty()||!state.equals("running"))ActionGate.fail("PAUSED","Cowork turn is not running");
    }
    public synchronized boolean request(String action){
        if(!Set.of("pause","stop").contains(action))ActionGate.fail("CONTROL_DENIED","Invalid task control");
        if(!active)return false;
        if(command.equals("stop")||command.equals(action))return false;
        if(command.equals("pause")&&action.equals("stop"))supersededPauseId=commandId;
        command=action;commandId=UUID.randomUUID().toString();state=action.equals("stop")?"stopping":"pausing";commandSeen=0;return true;
    }
    /** Watchdog tick. Returns true exactly once when an unacknowledged command is settled by native. */
    public synchronized boolean expire(long now){
        if(!active||command.isEmpty())return false;
        if(commandSeen==0||now<commandSeen){commandSeen=Math.max(1,now);return false;}
        if(now-commandSeen<=COMMAND_GRACE_MS)return false;
        settle();return true;
    }
    public synchronized void phase(String next){
        if(!Set.of("running","waiting_user").contains(next))ActionGate.fail("CONTROL_DENIED","Invalid task phase");
        if(active&&command.isEmpty())state=next;
    }
    public synchronized void stopSettled(){if(active)ActionGate.fail("CONTROL_DENIED","Task control must settle first");if(!execution.isEmpty())state="stopped";}
    /** Whether the AI display is parked (the AI desktop covers it, so no app stays in the foreground there and none
     *  suppresses its own notifications) when a turn ends: always, except while the turn waits for the user (an
     *  approval, a pause, a question), whose screen must stay. {@code waitingForUser}: approval pending or paused. */
    public static boolean parksDisplay(String outcome,boolean waitingForUser){return !waitingForUser&&!"waiting_user".equals(outcome);}

    /** Returns true when this call settled the execution. Ending an already settled execution is a no-op. */
    public synchronized boolean end(JSONObject input,boolean waitingForUser){
        exact(input,Set.of("operation","taskId","instanceId","executionId","outcome"));match(input);
        String next=text(input,"outcome");if(!Set.of("completed","stopped","error","waiting_user").contains(next))ActionGate.fail("CONTROL_DENIED","Invalid task outcome");
        if(!active)return false;
        // A command arriving after the client's final poll wins. Return its snapshot for cancellation+ACK.
        if(!command.isEmpty())return false;
        active=false;state=waitingForUser?"waiting_user":next;return true;
    }
    /** Returns true when this ACK settled the command. ACK of an already settled execution is a no-op. */
    public synchronized boolean acknowledge(JSONObject input){
        exact(input,Set.of("operation","taskId","instanceId","executionId","commandId"));match(input);
        if(!active)return false;
        if(command.isEmpty())deny();
        // Only a genuine in-flight pause ACK superseded by stop may observe its replacement.
        // It never settles, closes the display, advances revision or accepts an arbitrary old ID.
        if(command.equals("stop")&&!supersededPauseId.isEmpty()&&supersededPauseId.equals(input.opt("commandId")))return false;
        if(!commandId.equals(input.opt("commandId")))deny();
        settle();return true;
    }
    private void settle(){state=command.equals("pause")?"paused":"stopped";active=false;command="";commandId="";supersededPauseId="";commandSeen=0;}
    public synchronized JSONObject snapshot(long revision)throws JSONException{
        return new JSONObject().put("instanceId",instance).put("executionId",execution).put("taskId",task).put("revision",revision).put("state",state).put("command",command).put("commandId",commandId);
    }
    private static String text(JSONObject input,String key){Object v=input.opt(key);if(!(v instanceof String))deny();return (String)v;}
    private static void exact(JSONObject value,Set<String> keys){
        if(value.length()!=keys.size())ActionGate.fail("CONTROL_DENIED","Unexpected task fields");
        Iterator<String> it=value.keys();while(it.hasNext())if(!keys.contains(it.next()))ActionGate.fail("CONTROL_DENIED","Unexpected task fields");
    }
    private static void deny(){ActionGate.fail("EXECUTION_STALE","Cowork execution changed");}
}
