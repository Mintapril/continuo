package dev.dsh.pocket.cowork.broker;

import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Fixed local capability endpoint. Started by trusted user service, never a model command. */
public final class BrokerMain {
    private static final String BASE="/data/local/dsh-native/cowork";
    private static final String FIXTURE="dev.dsh.pocket.fixture";
    private final ActionGate gate=new ActionGate(List.of());
    private final ProfileWorkspace workspace;
    private final BrokerAuth authentication;
    private final ArrayDeque<JSONObject> events=new ArrayDeque<>();
    private final LinkedHashMap<String,JSONObject> responses=new LinkedHashMap<>();
    private volatile long revision=0, environmentSequence=0;
    private final ActivityLog activity=new ActivityLog();
    private final OwnerNotices notices=new OwnerNotices();
    private final ApprovalGate approvals=new ApprovalGate();
    private volatile String state="idle", lastTaskId="", pauseReason="";
    private volatile boolean restartRequired=false;
    private volatile String executionId="";
    private final String instanceId=UUID.randomUUID().toString();
    private final TaskLifecycle tasks=new TaskLifecycle(instanceId);
    private volatile String workspaceExecutionId="";
    private static final class WorkspaceBinding {
        final String task,workspace;
        WorkspaceBinding(String task,String workspace){this.task=task;this.workspace=workspace;}
    }
    private volatile WorkspaceBinding cancellationBinding=new WorkspaceBinding("","");
    /** Argument problems detected before any event is injected; the task stays running and the model may retry. */
    private static final Set<String> REJECTED_BEFORE_INPUT=Set.of("TEXT_UNSUPPORTED","NODE_STALE","NOT_EDITABLE","SECRET_INPUT");
    /** Run of transitional watchdog checks for the current owner of the display (see {@link RefusalPolicy.Settling}). */
    private final RefusalPolicy.Settling screenSettling=new RefusalPolicy.Settling();
    /** A survivable refusal is reported; only a real boundary violation pauses the session ({@link RefusalPolicy}). */
    private boolean survives(ActionGate.Denied e){return RefusalPolicy.survives(e.code,e.getMessage());}
    /** Turn a transition refusal into an actionable one; a refusal that paused the session keeps its own code. */
    private static ActionGate.Denied settling(ActionGate.Denied e){return RefusalPolicy.reported(e);}
    /** Observation and input tools the model may not use while the user controls the screen. */
    private static final Set<String> USER_CONTROL_BLOCKED=Set.of("phone.screenshot","phone.ui_tree","phone.tap","phone.long_press","phone.swipe","phone.scroll","phone.read_page","phone.type_text","phone.key","phone.launch_app","phone.wait","phone.batch","phone.gesture","phone.uninstall_app");
    private final Takeover takeover=new Takeover();
    /** How often the live view re-reads the focused input field during a takeover (one accessibility query). */
    private static final long FIELD_POLL_MS=200;
    private final java.util.concurrent.atomic.AtomicInteger streams=new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean taskManaged=false;
    /** The latest phone_ui_tree could not describe the screen (custom-drawn, truncated): a screenshot is the documented
     *  next step, so it is allowed without reading the structure again. */
    private volatile boolean treeIncomplete=false;
    private volatile long controllerSeen=0;
    private volatile long wokeAt=0;
    private static final String CONTROLLER_COMPONENT="dev.dsh.pocket/dev.dsh.pocket.cowork.CoworkControllerService";
    /** Demand driven: a task request while the controller heartbeat is stale restarts the app-side controller service
     *  (root `am`, fixed component and action, no user data). Rate limited; the file BASE/no-auto-wake disables it. */
    private void wakeController(){
        long now=SystemClock.elapsedRealtime();
        if(now-controllerSeen<=15000||now-wokeAt<8000)return;
        wokeAt=now;
        new Thread(()->{
            try{
                if(Files.exists(Path.of(BASE,"no-auto-wake")))return;
                ProcessBuilder builder=new ProcessBuilder("/system/bin/am","start-foreground-service","--user","0","-n",CONTROLLER_COMPONENT,"-a","dev.dsh.pocket.cowork.CONNECT");
                builder.environment().remove("CLASSPATH");builder.redirectErrorStream(true);
                java.lang.Process process=builder.start();process.getInputStream().readNBytes(2048);
                if(!process.waitFor(10,TimeUnit.SECONDS))process.destroyForcibly();
            }catch(Exception e){System.err.println("controller wake failed: "+e.getClass().getSimpleName());}
        },"cowork-wake").start();
    }
    private volatile boolean running=true;
    private static final String CONTROLLER_PACKAGE="dev.dsh.pocket";
    private int controllerUid=-2;
    /** Vendor power managers (ColorOS OFreezer "Hans") freeze the whole cgroup of an app that is no longer visible, for
     *  example right after the user locks the screen, even though it runs a foreground service. A frozen controller cannot
     *  send its heartbeat, so a session would end as CONTROLLER_OFFLINE although nothing is wrong. The broker is root and
     *  owns the session, so while a session exists it thaws the Continuo cgroup (cgroup.freeze=0); it touches nothing but
     *  the Continuo UID, is a no-op on devices without that freezer, and stops with the session. */
    private static final String PROTECTED_ADJ="-800";
    /** While a session exists the low-memory killer and vendor cleaners must not pick the controller or this broker:
     *  best effort, only lowers oom_score_adj, never raises it, and only for the Continuo UID's processes and ourselves. */
    private void protectProcesses(Path uidGroup){
        try(java.util.stream.Stream<Path> entries=Files.list(uidGroup)){
            for(Path entry:(Iterable<Path>)entries::iterator){
                String name=entry.getFileName().toString();
                if(name.startsWith("pid_"))lowerAdj(name.substring(4));
            }
        }catch(Exception ignored){}
        lowerAdj("self");
    }
    private static void lowerAdj(String pid){
        try{
            Path adj=Path.of("/proc",pid,"oom_score_adj");
            int current=Integer.parseInt(new String(Files.readAllBytes(adj),StandardCharsets.UTF_8).trim());
            if(current>Integer.parseInt(PROTECTED_ADJ))Files.write(adj,PROTECTED_ADJ.getBytes(StandardCharsets.UTF_8));
        }catch(Exception ignored){}
    }
    private void keepControllerRunning(){
        try{
            if(!(tasks.active()||gate.owner()!=null||takeover.active()))return;
            if(controllerUid==-2){try{controllerUid=workspace.context().getPackageManager().getPackageUid(CONTROLLER_PACKAGE,0);}catch(Exception missing){controllerUid=-1;}}
            if(controllerUid<0)return;
            for(String root:new String[]{"/sys/fs/cgroup/apps/uid_","/sys/fs/cgroup/uid_"}){
                Path freeze=Path.of(root+controllerUid,"cgroup.freeze");
                if(!Files.exists(freeze))continue;
                if(new String(Files.readAllBytes(freeze),StandardCharsets.UTF_8).trim().equals("1"))Files.write(freeze,"0".getBytes(StandardCharsets.UTF_8));
                protectProcesses(Path.of(root+controllerUid));
                return;
            }
        }catch(Throwable ignored){}
    }
    /** Lock discipline (root cause of the stuck "stopping" state): `this` guards short control-plane state only and is
     *  NEVER held across a workspace operation. Phone tools take `toolLock` and the workspace's own monitor, so a
     *  multi-second ui_tree/launch/guard can no longer starve the heartbeat (/v1/state), lease polls (/v1/task) or
     *  user stop. Display teardown runs on `closer`, ordered, so a stop never waits for an in-flight gesture. */
    private final Object toolLock=new Object();
    private final ExecutorService closer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"cowork-close");t.setDaemon(true);return t;});
    private volatile Future<?> closing;
    private final java.util.concurrent.atomic.AtomicInteger toolsInFlight=new java.util.concurrent.atomic.AtomicInteger();
    public BrokerMain(String agent,String controller) throws Exception {authentication=new BrokerAuth(agent,controller,SystemClock::elapsedRealtime);workspace=new ProfileWorkspace();}
    public static void main(String[] ignored) throws Exception {
        Looper.prepareMainLooper();
        String agent=new String(Files.readAllBytes(Path.of(BASE,"agent.token")),StandardCharsets.UTF_8).trim();String controller=new String(Files.readAllBytes(Path.of(BASE,"controller.token")),StandardCharsets.UTF_8).trim();
        if(!agent.matches("[A-Za-z0-9_-]{40,}")||!controller.matches("[A-Za-z0-9_-]{40,}")||agent.equals(controller))throw new SecurityException("Invalid broker credentials");
        BrokerMain broker=new BrokerMain(agent,controller);
        Runtime.getRuntime().addShutdownHook(new Thread(()->broker.workspace.close()));
        new Thread(()->{try{broker.serve();}catch(Exception e){System.err.println("Broker stopped: "+e.getClass().getSimpleName());}finally{broker.running=false;broker.workspace.close();System.exit(0);}},"cowork-http").start();
        // Two independent watchdogs: the lifecycle one only touches control-plane state and always runs on time;
        // the guard one may wait on the workspace monitor without delaying lease expiry or command settlement.
        ScheduledExecutorService watchdog=Executors.newScheduledThreadPool(3);
        broker.closer.submit(()->broker.keepStandingDisplay());
        watchdog.scheduleAtFixedRate(broker::keepControllerRunning,1,2,TimeUnit.SECONDS);
        watchdog.scheduleAtFixedRate(broker::keepOwnedDisplayAwake,1,2,TimeUnit.SECONDS);
        watchdog.scheduleAtFixedRate(broker::lifecycleTick,1,1,TimeUnit.SECONDS);
        final int[] tick={0};
        watchdog.scheduleAtFixedRate(()->{try{broker.guardTick(++tick[0]);}catch(Throwable guardFailure){}},1,1,TimeUnit.SECONDS);
        Looper.loop();
    }
    /** While a turn owns the display its power group must not doze (see {@link ProfileWorkspace#keepAwake}). Runs on its own
     *  clock, so a long tool (phone_wait, a takeover) cannot let the group time out. */
    private void keepOwnedDisplayAwake(){try{if(gate.owner()!=null)workspace.keepAwake();}catch(Throwable keepAwakeFailure){}} // a throw would cancel the schedule
    /** Control-plane watchdog. Never waits on the workspace, so lease expiry and command settlement are always on time. */
    private void lifecycleTick(){
        try{synchronized(this){
            long now=SystemClock.elapsedRealtime();
            if(tasks.active()&&(tasks.overdue(now)||now-controllerSeen>15000))requestTaskControl("stop","CONTROL_CHANNEL_OFFLINE");
            // Self-heal: an agent that never acknowledges (crashed, hung, disconnected) can no longer wedge "stopping".
            if(tasks.expire(now)){System.err.println("task command unacknowledged for "+TaskLifecycle.COMMAND_GRACE_MS+" ms; settled by native");closeWorkspace();event("task.control-settled",tasks.state());}
        }}catch(Throwable lifecycleFailure){System.err.println("lifecycle tick failed: "+lifecycleFailure.getClass().getSimpleName());}
    }
    /** Workspace watchdog. Runs outside the control-plane lock and never interleaves with a running tool (which guards itself). */
    private void guardTick(int tick){
        if(tick%3==0&&toolsInFlight.get()==0)try{workspace.housekeeping();}catch(Exception housekeepingFailure){}
        // While the display is parked (after phone_stop), entries of apps whose last task there closed are hidden again.
        if(tick%3==0&&toolsInFlight.get()==0)try{workspace.hideIdleLaunchers();}catch(Exception idleHideFailure){}
        // Every 10 s: drift repair of the background pop-up gate. New installs are closed at once by the install watch,
        // any other package change of the profile within a second by hideNewInstalls.
        if(tick%10==0)try{workspace.closeBackgroundPopups();}catch(Exception popupGateFailure){}
        // Every 10 s: work-profile notification channels held at the lowest importance (apps add channels at any time).
        if(tick%10==0)try{workspace.quietNotifications();}catch(Exception quietFailure){}
        // 1 Hz: an app installed into the work profile must never stay visible to the personal launcher long enough
        // for an icon of it to land on the home screen (the user's report: icon shows up and only a reboot clears it).
        try{workspace.hideNewInstalls();}catch(Exception installSweepFailure){}
        // 1 Hz safety net, also between turns. The sources are closed (parked tasks keep each app's screens on the owned
        // display, the pop-up gate refuses screens of apps that are not on it); what still lands on the main screen, e.g.
        // a work-profile notification the user tapped there, is moved to the owned display. A running tool does it in its own check.
        if(toolsInFlight.get()==0)try{workspace.pullBackStrayTasks();}catch(Exception strayTaskFailure){}
        if(gate.owner()==null){if(tick%5==0&&toolsInFlight.get()==0)keepStandingDisplay();return;}
        long now=SystemClock.elapsedRealtime();
        if(takeover.expired(now))endTakeover("expired");
        if(toolsInFlight.get()>0||gate.paused())return; // a paused workspace runs nothing; resume re-guards it
        if(workspace.displayId()>0&&workspace.hasLaunched()&&!takeover.active())try{activity.observe(workspace.targetPackage(),workspace::appLabel);}catch(Exception activityFailure){}
        String reason=null;
        try{
            if(now-controllerSeen>15000)ActionGate.fail("CONTROLLER_OFFLINE","User control channel disconnected");
            if(takeover.active())workspace.guardUser();else guardNow();
            screenSettling.reset();
            ApprovalGate.Reservation p=approvals.pending();if(p!=null&&SystemClock.elapsedRealtime()>p.deadline)reason="APPROVAL_STALE";
        }catch(Exception e){
            ActionGate.Denied denied=e instanceof ActionGate.Denied?(ActionGate.Denied)e:null;
            if(denied!=null&&RefusalPolicy.transientDenied(denied.code,denied.getMessage())){
                // An app switch, a closing dialog or an animated screen is normal: the session survives it. Only a
                // screen that stays unusable for a long time ends the task.
                if(screenSettling.expired(now))reason="SCREEN_UNRESPONSIVE";
            }else reason=denied!=null?denied.code:"GUARD_FAILED";
        }
        if(reason!=null)synchronized(this){if(gate.owner()!=null&&(!gate.paused()||approvals.pending()!=null||!reason.equals(pauseReason)))safePause(reason);}
    }
    private void serve() throws Exception {
        ServerSocket server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),3091),32);
        ExecutorService clients=Executors.newFixedThreadPool(16);
        while(running){Socket socket=server.accept();clients.submit(()->handle(socket));}
        server.close();clients.shutdownNow();
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();int c;
        while((c=in.read())!=-1){if(c=='\n')break;if(c!='\r')b.write(c);if(b.size()>8192)throw new IOException("Header limit");}
        if(c==-1&&b.size()==0)throw new EOFException();return b.toString(StandardCharsets.US_ASCII);
    }
    private void handle(Socket socket) {
        try(socket){socket.setSoTimeout(5000);InputStream in=socket.getInputStream();String[] first=line(in).split(" ");
            if(first.length!=3)throw new IOException("Request line");Map<String,String> headers=new HashMap<>();int size=0;
            for(String h;(h=line(in)).length()>0;){size+=h.length();if(size>16384)throw new IOException("Header limit");int sep=h.indexOf(':');if(sep<1)throw new IOException("Header");String name=h.substring(0,sep).toLowerCase(Locale.ROOT);if(headers.put(name,h.substring(sep+1).trim())!=null)throw new IOException("Duplicate header");}
            if(headers.containsKey("transfer-encoding")||headers.containsKey("origin")){respond(socket,403,new JSONObject().put("ok",false));return;}
            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));if(length<0||length>65536)throw new IOException("Body limit");
            byte[] body=in.readNBytes(length);if(body.length!=length)throw new EOFException();
            JSONObject request=length==0?new JSONObject():new JSONObject(new String(body,StandardCharsets.UTF_8));
            if(first[0].equals("POST")&&first[1].equals("/v1/auth")){respond(socket,200,authentication.challenge(request));return;}
            BrokerAuth.Authenticated auth;
            try{auth=authentication.authenticate(headers.getOrDefault("authorization",""),first[0],first[1],body);}
            catch(SecurityException e){respond(socket,401,new JSONObject().put("ok",false));return;}
            boolean controller=auth.controller();
            // Only a verified request bound to the current run can interrupt a long gesture before its lock is released.
            WorkspaceBinding binding=cancellationBinding;
            if(controller&&first[0].equals("POST")&&first[1].equals("/v1/control")&&Set.of("pause","stop").contains(request.optString("action"))
                &&instanceId.equals(request.optString("instanceId"))&&!binding.task.isEmpty()&&binding.task.equals(request.optString("executionId")))workspace.requestCancellation(binding.workspace);
            if(controller&&first[0].equals("GET")&&first[1].equals("/v1/stream")){stream(socket,auth);return;}
            JSONObject result;
            // Control-plane routes take `this` briefly; workspace routes never hold it (see toolLock).
            if(first[0].equals("GET")&&first[1].equals("/v1/state")&&controller){controllerSeen=SystemClock.elapsedRealtime();result=status();}
            else if(first[0].equals("GET")&&first[1].startsWith("/v1/events?after=")){long after=Long.parseLong(first[1].substring(17));synchronized(this){JSONArray rows=new JSONArray();for(JSONObject e:events)if(e.getLong("revision")>after)rows.put(e);result=new JSONObject().put("events",rows).put("revision",revision);}}
            else if(first[0].equals("GET")&&first[1].equals("/v1/task-state")&&!controller){synchronized(this){wakeController();result=tasks.snapshot(revision);}}
            else if(first[0].equals("POST")&&first[1].equals("/v1/task")&&!controller){try{synchronized(this){result=taskRequest(request);}}catch(ActionGate.Denied e){respond(socket,409,denied(e),auth);return;}}
            else if(first[0].equals("POST")&&first[1].equals("/v1/tool")){result=tool(request);}
            else if(first[0].equals("GET")&&first[1].equals("/v1/apps")&&controller){result=new JSONObject().put("apps",workspace.availableApps(gate.packages()));}
            else if(first[0].equals("GET")&&first[1].equals("/v1/preview")&&controller){
                try{String owner=gate.owner();if(owner==null)ActionGate.fail("NO_DISPLAY","No active workspace");result=workspace.preview().put("instanceId",instanceId).put("executionId",executionId).put("taskId",owner).put("revision",revision);}
                catch(ActionGate.Denied e){respond(socket,409,denied(e),auth);return;}
            }
            else if(first[0].equals("POST")&&first[1].equals("/v1/control")&&controller){controllerSeen=SystemClock.elapsedRealtime();try{
                // Pause/stop/reject are pure control-plane and never wait for a running tool; resume/approve drive the
                // display, so they queue behind the current tool instead of racing it.
                if(Set.of("resume","approve").contains(request.optString("action"))){synchronized(toolLock){result=control(request);}}else result=control(request);
            }catch(ActionGate.Denied e){respond(socket,409,denied(e),auth);return;}}
            else if(first[0].equals("POST")&&first[1].equals("/v1/takeover")&&controller){controllerSeen=SystemClock.elapsedRealtime();try{result=takeoverControl(request);}catch(ActionGate.Denied e){respond(socket,409,denied(e),auth);return;}}
            else if(first[0].equals("POST")&&first[1].equals("/v1/user-input")&&controller){controllerSeen=SystemClock.elapsedRealtime();try{result=userInput(request);}catch(ActionGate.Denied e){respond(socket,409,denied(e),auth);return;}}
            else {respond(socket,404,new JSONObject().put("ok",false),auth);return;}
            respond(socket,200,result,auth);
        }catch(Exception ignored){/* Socket/parse errors close without disclosing credentials or platform internals. */}
    }
    private static void respond(Socket s,int status,JSONObject value)throws IOException{respond(s,status,value,null);}
    private static void respond(Socket s,int status,JSONObject value,BrokerAuth.Authenticated auth)throws IOException{
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);OutputStream out=s.getOutputStream();
        String proof=auth==null?"":"X-Cowork-Proof: "+auth.responseProof(status,bytes)+"\r\n";
        out.write(("HTTP/1.1 "+status+"\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nConnection: close\r\n"+proof+"Content-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
    }
    private JSONObject tool(JSONObject request)throws Exception{
        String id=request.optString("requestId"),session=request.optString("sessionId"),tool=request.optString("tool");
        JSONObject envelope=new JSONObject().put("protocolVersion",1).put("requestId",id);
        try{
            if(request.optInt("protocolVersion")!=1||!id.matches("[a-zA-Z0-9_-]{1,100}"))ActionGate.fail("PROTOCOL_INVALID","Invalid request envelope");
            gate.checkTool(tool);String key=session+":"+id;
            synchronized(responses){if(responses.containsKey(key))return responses.get(key);}
            JSONObject a=request.optJSONObject("arguments");if(a==null)ActionGate.fail("ARGUMENTS_INVALID","Expected arguments object");
            ToolContract.validate(tool,a);
            JSONObject value;
            // Tools are serialized among themselves (toolLock) but never block the control plane (`this`).
            synchronized(toolLock){toolsInFlight.incrementAndGet();try{value=execute(session,tool,a,false);}finally{toolsInFlight.decrementAndGet();}}
            envelope.put("ok",true).put("result",value);synchronized(responses){responses.put(key,envelope);while(responses.size()>128)responses.remove(responses.keySet().iterator().next());}
        }catch(ActionGate.Denied e){envelope.put("ok",false).put("error",new JSONObject().put("code",e.code).put("message",e.getMessage()));}
        catch(Exception e){
            // Private diagnostic contains classes/locations only, never request arguments or platform messages.
            Throwable cause=e;for(int depth=0;cause!=null&&depth<4;depth++,cause=cause.getCause()){
                System.err.println("Cowork platform failure: "+cause.getClass().getName());int count=0;
                for(StackTraceElement location:cause.getStackTrace()){if(count++>=12)break;System.err.println(" at "+location);}
            }
            envelope.put("ok",false).put("error",new JSONObject().put("code","PLATFORM_ERROR").put("message","Native platform operation failed"));}
        return envelope;
    }
    private JSONObject taskRequest(JSONObject a)throws Exception{
        String op=a.optString("operation");
        if(op.equals("begin")){
            long age=SystemClock.elapsedRealtime()-controllerSeen;if(age<0||age>15000){wakeController();ActionGate.fail("CONTROLLER_OFFLINE","Controller is starting");}
            if(gate.owner()!=null&&!tasks.active()){System.err.println("begin: closing workspace left by a settled execution");closeWorkspace();}
            activity.reset();tasks.begin(a,revision,SystemClock.elapsedRealtime());taskManaged=true;lastTaskId=tasks.task();executionId=tasks.execution();synchronized(responses){responses.clear();}restartRequired=false;pauseReason="";event("task.begin","running");
        }else if(op.equals("poll")){tasks.poll(a,SystemClock.elapsedRealtime());}
        else if(op.equals("end")){
            // Native pause/approval is authoritative; terminal reporting never approves a phone action.
            boolean pending=approvals.pending()!=null;
            boolean waiting=pending||gate.owner()!=null&&gate.paused();
            if(tasks.end(a,waiting)){
                // A finished turn leaves no app in the foreground of the AI display (an app left there counts as visible
                // and stops posting notifications): the AI desktop covers it, as after phone.stop.
                if(!pending){if(TaskLifecycle.parksDisplay(a.optString("outcome"),waiting))clearDisplay();else closeWorkspace();}
                event("task.end",tasks.state());
            }
        }else if(op.equals("ack")){
            if(tasks.acknowledge(a)){closeWorkspace();event("task.control-settled",tasks.state());}
        }else ActionGate.fail("CONTROL_DENIED","Unknown task operation");
        return tasks.snapshot(revision);
    }
    /** Code-level rule: a screenshot is only allowed after the UI structure of the current screen was read. */
    private volatile boolean treeRead;
    static final String TREE_REQUIRED_MESSAGE="Screenshot refused: call phone_ui_tree first and read the UI elements of the current screen. The tree is the primary, cheaper and more precise observation; screenshots are only for screens whose tree is incomplete (complete:false), custom-drawn content (WebView, game, canvas) or information the tree lacks. The requirement renews after every action, wait, or hand-back.";
    /** Revokes the turn immediately (owner, cancellation, approvals). The standing display and whatever is on it stay:
     *  content is parked only when the model asks for it ({@link #clearDisplay}). Safe from the control plane while a
     *  tool is running: the running tool sees the cancellation at its next guard. */
    private synchronized void closeWorkspace(){
        if(!workspaceExecutionId.isEmpty())workspace.requestCancellation(workspaceExecutionId);
        treeRead=false;activity.reset();if(takeover.finish("cancelled"))workspace.userRelease();cancellationBinding=new WorkspaceBinding("","");
        if(gate.owner()!=null)gate.stop(gate.owner());approvals.clear();workspaceExecutionId="";screenSettling.reset();
    }
    /** The one path that clears the standing display: the model called phone.stop. The AI desktop covers it; the profile
     *  tasks stay parked behind it as anchors (see {@link ProfileWorkspace#resetContent}). */
    private synchronized void clearDisplay(){
        String execution=workspaceExecutionId;
        closeWorkspace();
        closing=closer.submit(()->{try{workspace.resetContent(execution);}catch(Throwable e){System.err.println("workspace reset failed: "+e.getClass().getSimpleName());}});
    }
    /** The AI display is standing: it is created once and survives every turn. Only content and ownership are per turn. */
    private final Object displayLock=new Object();
    private volatile String standingFailure="";
    private int standingFails=0;
    private void ensureDisplay()throws Exception{
        synchronized(displayLock){
            if(workspace.ready())return;
            // The handle can outlive the display it names (system_server restart), so releasing it is best effort.
            try{if(workspace.displayId()>0)workspace.closeDisplay();}catch(Throwable ignored){}
            awaitClosed();
            workspace.create(ProfileWorkspace.STANDING);
        }
    }
    /** Best effort, never throws: re-creates the standing display when it is missing and nobody owns it. */
    private void keepStandingDisplay(){
        try{ensureDisplay();standingFails=0;standingFailure="";return;}
        catch(Throwable e){String why=e instanceof ActionGate.Denied?((ActionGate.Denied)e).code:e.getClass().getSimpleName();
            standingFails++;
            // One failed attempt is a transient (a lock, a settling profile); three in a row is what the user is told.
            if(standingFails==3)System.err.println("standing display unavailable after "+standingFails+" attempts: "+why);
            if(standingFails>=3)standingFailure=why;}
    }
    /** Guard for the owned display: an empty display (nothing launched this turn) has no app window to verify yet. */
    private void guardNow(){guardNow(400);}
    /** `budgetMs` is the retry window for one check. phone.wait passes 0: it polls on its own clock and must not
     *  let a single settling gap consume the time the caller asked to wait. */
    private void guardNow(long budgetMs){if(workspace.hasLaunched())workspace.guard(budgetMs);else workspace.guardIdle();screenSettling.reset();}
    /** First tool of an admitted turn takes the standing display (no phone_start needed). Ownership is per turn. */
    private void claim(String session,boolean userApproved)throws Exception{
        if(gate.owner()!=null||userApproved)return;
        if(SystemClock.elapsedRealtime()-controllerSeen>15000)ActionGate.fail("CONTROLLER_OFFLINE","Open Cowork controls before starting");
        awaitClosed();
        final String mine=UUID.randomUUID().toString();
        synchronized(this){
            if(tasks.active()&&!userApproved)tasks.requireRunning(session);
            gate.start(session);workspaceExecutionId=mine;screenSettling.reset();
            if(!tasks.active()){taskManaged=false;executionId=mine;}
            cancellationBinding=new WorkspaceBinding(executionId,mine);treeRead=false;treeIncomplete=false;
        }
        synchronized(responses){responses.clear();}
        try{
            // An unowned standing display is allowed to doze; the new owner needs it awake before anything is checked.
            ensureDisplay();workspace.keepAwake();workspace.beginScope(mine);
            // beginScope starts a fresh cancellation scope: re-check that no stop/pause revoked this claim meanwhile.
            synchronized(this){if(!mine.equals(workspaceExecutionId)||gate.owner()==null||gate.paused())ActionGate.fail("CONTROL_INTERRUPTED","User requested pause or stop");}
            restartRequired=false;pauseReason="";event("workspace.claim","running");
        }catch(Exception e){synchronized(this){if(mine.equals(workspaceExecutionId))closeWorkspace();}throw e;}
    }
    /** A new display may only be created after the previous one is gone. */
    private void awaitClosed()throws Exception{
        Future<?> f=closing;if(f==null)return;
        try{f.get(10,TimeUnit.SECONDS);}catch(TimeoutException e){ActionGate.fail("DISPLAY_BUSY","Previous workspace is still closing; retry shortly");}
    }
    private synchronized void requestTaskControl(String action,String reason)throws Exception{
        if(!tasks.request(action))return;
        // Private root-only broker.log: the code only. A native pause/stop is what ends the DSH turn as "aborted".
        System.err.println("task "+action+" requested: "+reason);
        pauseReason=reason;approvals.clear();
        if(gate.owner()!=null){workspace.requestCancellation(workspaceExecutionId);if(action.equals("stop"))closeWorkspace();else gate.pause(gate.owner());}
        event("task."+action,tasks.state());
    }
    private JSONObject execute(String session,String tool,JSONObject a,boolean userApproved)throws Exception{
        if(tasks.active()){if(!tasks.task().equals(session))ActionGate.fail("SESSION_MISMATCH","Cowork task belongs to another session");if(!userApproved&&!Set.of("phone.status","phone.apps","phone.stop","phone.pause","phone.request_intervention","phone.intervention_status").contains(tool))tasks.requireRunning(session);}
        else if(!userApproved&&!Set.of("phone.status","phone.apps").contains(tool)){
            // Fixed no-network acceptance fixture is the only direct, non-DSH tool caller.
            boolean fixtureTest=session.equals("cowork-fixture-acceptance")&&((tool.equals("phone.start")||tool.equals("phone.launch_app"))&&FIXTURE.equals(a.optString("packageName"))||FIXTURE.equals(workspace.targetPackage())||session.equals(gate.owner())||(tool.equals("phone.ui_tree")&&!workspace.hasLaunched()));
            if(!fixtureTest)ActionGate.fail("SESSION_MISMATCH","No admitted Cowork task");
        }
        if(tool.equals("phone.status"))return toolStatus();
        if(tool.equals("phone.intervention_status"))return takeover.status();
        if(tool.equals("phone.apps"))return new JSONObject().put("apps",workspace.availableApps(Set.of()));
        if(tool.equals("phone.notify_owner")){
            // A note for the owner from a run nobody watches; the controller shows it as a notification. No screen is touched.
            notices.post(a.getString("text"),System.currentTimeMillis());activity.note("已给你发通知");
            return performed();
        }
        if(tool.equals("phone.start")){
            // Optional now: the display already exists and is claimed by the first tool of the turn. start = claim + launch.
            if(a.has("packageName"))workspace.requirePackage(a.getString("packageName"));
            claim(session,userApproved);gate.requireActive(session);
            if(a.has("packageName")){
                treeRead=false;treeIncomplete=false;String pkg=a.getString("packageName");
                try{workspace.launch(pkg);activity.launched(pkg,workspace.appLabel(pkg));}
                catch(Exception e){
                    boolean keep=e instanceof ActionGate.Denied&&survives((ActionGate.Denied)e);
                    if(!keep){String mine=workspaceExecutionId;synchronized(this){if(mine.equals(workspaceExecutionId))closeWorkspace();}}
                    throw e;
                }
            }
            restartRequired=false;pauseReason="";event(tool,"running");
            return performed();
        }
        if(tool.equals("phone.stop")){if(gate.owner()!=null){gate.requireOwner(session);clearDisplay();event(tool,"stopped");}return performed();}
        if(tool.equals("phone.pause")){gate.requireOwner(session);safePause("USER_REQUEST");return performed();}
        claim(session,userApproved);gate.requireOwner(session);
        if(tool.equals("phone.request_intervention")){
            // The user takes the screen; the DSH turn stays alive and blocked in the plugin until hand-back.
            gate.requireActive(session);
            String id=takeover.start("model",a.optString("reason"),SystemClock.elapsedRealtime());treeRead=false;activity.note("请你接管操作");
            gate.invalidate();event("intervention.start",state.equals("idle")?"running":state);
            return performed().put("interventionId",id);
        }
        if(takeover.active()&&USER_CONTROL_BLOCKED.contains(tool))ActionGate.fail("USER_CONTROL","The user is controlling the screen; wait for hand-back");
        gate.requireActive(session);
        workspace.keepAwake();
        // The model's own app management (ProfileApps): no pixels are involved, so no observation and no screen check.
        // The screen may change all the same (a permission dialog may close, the app disappears): observe again after.
        if(tool.equals("phone.grant_permission")){
            String pkg=a.getString("packageName");
            JSONObject granted=workspace.apps().grant(pkg,a.getString("permission"));
            treeRead=false;treeIncomplete=false;gate.invalidate();
            if(granted.getJSONArray("granted").length()>0)activity.note("给"+workspace.appLabel(pkg)+"开了权限");
            event(tool,"running");return performed(granted);
        }
        if(tool.equals("phone.uninstall_app")){
            String pkg=a.getString("packageName");ProfileApps apps=workspace.apps();
            apps.requireManageable(pkg);
            // The app on screen would vanish under the guard (APP_UNKNOWN until the watchdog pauses): the AI desktop
            // takes the display first, as HOME does.
            if(workspace.showing(pkg))try{workspace.home();}catch(ActionGate.Denied e){if(!survives(e))safePause(e.code);throw settling(e);}catch(Exception e){safePause("PLATFORM_ERROR");throw e;}
            String label=workspace.appLabel(pkg);
            treeRead=false;treeIncomplete=false;gate.invalidate();
            apps.uninstall(pkg);
            workspace.forget(pkg);activity.note("卸载了"+label);
            event(tool,"running");return performed();
        }
        // phone.wait guards on its own clock below: a settling screen is exactly what it waits out, so this up-front
        // check must not turn it into an immediate SCREEN_SETTLING.
        if(!tool.equals("phone.wait"))try{guardNow();}catch(ActionGate.Denied e){if(!survives(e))safePause(e.code);throw settling(e);}
        if(tool.equals("phone.key")){
            // Virtual navigation keys of the AI screen. HOME and RECENTS claim nothing about pixels the model has
            // seen: they address the owned display's desktop and its task list, so no observation is required.
            String navKey=a.getString("key");
            if(navKey.equals("HOME")){
                String before=workspace.lastTreeSignature();
                try{workspace.home();}catch(ActionGate.Denied e){if(!survives(e))safePause(e.code);throw settling(e);}catch(Exception e){safePause("PLATFORM_ERROR");throw e;}
                treeRead=false;treeIncomplete=false;activity.note("回到桌面");event(tool,"running");return observeAfter(session,performed(),before,true);
            }
            if(navKey.equals("RECENTS")){event(tool,"running");return performed().put("note",workspace.recents());}
        }
        if(!workspace.hasLaunched()){
            // Standing display, nothing open yet this turn: the observation is simply empty, and an app can be opened directly.
            if(tool.equals("phone.ui_tree")){
                String observationId=gate.observeTree(session,"empty|"+workspace.fingerprintIdle(),SystemClock.elapsedRealtime());
                treeRead=true;treeIncomplete=false;
                return new JSONObject().put("title","").put("package","").put("complete",true).put("nodes",new org.json.JSONArray()).put("width",720).put("height",1608).put("capturedAt",System.currentTimeMillis()).put("advice","No app is open on the AI screen (it may show the AI desktop, with apps from earlier turns parked behind it). Call phone_launch_app with an installed package (phone_apps lists them); an app that is still running comes back where it was.").put("observationId",observationId);
            }
            if(tool.equals("phone.screenshot"))ActionGate.fail("APP_UNKNOWN","No app is open on the AI screen: call phone_launch_app first");
            if(tool.equals("phone.launch_app")){
                String pkg=a.getString("packageName");workspace.requirePackage(pkg);treeRead=false;treeIncomplete=false;
                try{workspace.launch(pkg);activity.launched(pkg,workspace.appLabel(pkg));guardNow();}catch(ActionGate.Denied e){if(!survives(e))safePause(e.code);throw settling(e);}
                event(tool,"running");return performed();
            }
        }
        if(tool.equals("phone.screenshot")&&!treeRead&&!treeIncomplete)ActionGate.fail("TREE_REQUIRED",TREE_REQUIRED_MESSAGE);
        if(tool.equals("phone.ui_tree")||tool.equals("phone.screenshot")){
            // A region zooms the screenshot: ToolContract checked its form, the workspace checks it against the screen.
            JSONArray region=a.optJSONArray("region");
            ProfileWorkspace.Observation observation=region!=null?workspace.zoom(region.getInt(0),region.getInt(1),region.getInt(2),region.getInt(3),a.getInt("scale")):workspace.observe(tool.equals("phone.screenshot"));
            JSONObject result=observation.result();
            String observationId=tool.equals("phone.ui_tree")?gate.observeTree(session,observation.fingerprint(),SystemClock.elapsedRealtime()):gate.observe(session,observation.fingerprint(),SystemClock.elapsedRealtime());
            if(tool.equals("phone.ui_tree")){workspace.bindTree(observationId);treeRead=true;treeIncomplete=!result.optBoolean("complete",true);}
            result.put("observationId",observationId);return result;
        }
        if(tool.equals("phone.wait")){treeRead=false;treeIncomplete=false;} // the screen may change while waiting: read the structure again before a screenshot
        if(tool.equals("phone.wait")){
            // A settling screen (app switch, dialog, animation) is exactly what this wait is for: one failed check
            // must not end it early. Keep polling until the requested time; only then report the last denial.
            int ms=a.optInt("timeoutMs",300);if(ms<0||ms>10000)ActionGate.fail("WAIT_LIMIT","Wait must be between 0 and 10000 ms");
            long until=SystemClock.elapsedRealtime()+ms;ActionGate.Denied last=null;String before=workspace.lastTreeSignature();
            while(true){
                try{guardNow(0);break;}
                catch(ActionGate.Denied e){if(!survives(e)){safePause(e.code);throw settling(e);}last=e;}
                long left=until-SystemClock.elapsedRealtime();
                if(left<=0)throw settling(last);
                SystemClock.sleep(Math.min(100,left));
            }
            return observeAfter(session,performed(),before,true);
        }
        if(tool.equals("phone.batch"))return batch(session,a);
        if(tool.equals("phone.gesture"))return gesture(session,a);
        if(!ActionGate.INPUTS.contains(tool))ActionGate.fail("TOOL_DENIED","Tool not implemented");
        treeRead=false;treeIncomplete=false; // any input changes the screen: a fresh phone.ui_tree is required before the next screenshot
        String before=workspace.lastTreeSignature(); // what the model last read, for the changed flag of the reply
        if(tool.equals("phone.launch_app"))workspace.requirePackage(a.getString("packageName"));
        // The foreground may be a system dialog hosted in the same task (permission prompt, installer):
        // it has no launcher entry, so only the profile app identity is required here, not a launcher activity.
        workspace.requireForeground(workspace.targetPackage());
        // Work-profile mode: the isolated profile is the authorization boundary, so there is no per-action
        // approval (user decision). Every observed action must still bind to the latest observation of the
        // same window set; stale or replayed observations are rejected by gate.consume.
        // High-impact acts (payment, sending, deletion, grants) stay model-prompt/user-consent rules.
        // An element action is bound to the tree observation (the node must still be visible, enabled and at its
        // recorded bounds); it does not consume the pixel observation, so tree -> screenshot -> tap(elementId) works.
        if(a.has("elementId"))workspace.requireTree(a.optString("observationId"));
        // Launching by package name addresses an installed app, not pixels: HOME then phone_launch_app must bring a
        // backgrounded app back without an intervening observation. Every other action stays bound to an observation.
        else if(!userApproved&&!tool.equals("phone.launch_app")){
            gate.consume(session,a.optString("observationId"),workspace.fingerprint(),SystemClock.elapsedRealtime());
        }
        event(tool,"running");
        try{switch(tool){
            case "phone.tap":workspace.tap(a,false);break;
            case "phone.long_press":workspace.tap(a,true);break;
            case "phone.swipe":workspace.swipe(a);break;
            case "phone.scroll":{JSONObject moved=workspace.scroll(a);workspace.guard();return observeAfter(session,performed(moved),before,false);} // scroll already waited for the fling
            case "phone.read_page":{JSONObject page=workspace.readPage(a);return performed(page);}
            case "phone.type_text":workspace.type(a);activity.note("输入文字");break;
            case "phone.key":workspace.key(a.getString("key"));if(a.getString("key").equals("ENTER"))activity.note("确认输入");break;
            case "phone.launch_app":workspace.launch(a.getString("packageName"));activity.launched(a.getString("packageName"),workspace.appLabel(a.getString("packageName")));break;
            default:ActionGate.fail("TOOL_DENIED","Unsupported operation");
        }
        workspace.guard();}catch(ActionGate.Denied e){if(!survives(e))safePause(e.code);throw settling(e);}catch(Exception e){safePause("PLATFORM_ERROR");throw e;}
        // A cold start is waited out with phone_wait, which returns the screen (measured: 9 of 10 launches were followed by it).
        if(tool.equals("phone.launch_app"))return performed();
        return observeAfter(session,performed(),before,true);
    }
    /** Several element inputs planned from one tree observation (phone_batch). Each step runs only after checks made as
     *  late as possible, right before its input: the window set and foreground UID are still those of the observation,
     *  nothing outside the previous step's element changed after that step settled, and the target still shows its
     *  observed label at its observed bounds; high-impact steps are left to a single call (BatchPolicy). The first failed
     *  check stops the batch with the screen as it is, so a misjudged batch costs about what single steps would.
     *  Before the first input a refusal is an ordinary refusal and keeps the observation. After it the reply is always
     *  performed:true with completed and, when stopped, stoppedAt/stopReason (uncertain: the stopped step's input may have
     *  partly happened): an error would make the model repeat inputs that already happened. A boundary violation still
     *  pauses, as the next call would. */
    private JSONObject batch(String session,JSONObject a)throws Exception{
        String observationId=a.getString("observationId");JSONArray steps=a.getJSONArray("steps");int last=steps.length()-1;
        if(treeIncomplete)ActionGate.fail("ARGUMENTS_INVALID","phone_batch needs a complete phone_ui_tree observation");
        treeRead=false;treeIncomplete=false;
        workspace.requireTree(observationId);
        workspace.requireForeground(workspace.targetPackage());
        String before=workspace.lastTreeSignature(),structure="",outside="",typed="";
        long deadline=SystemClock.elapsedRealtime()+BatchPolicy.TIME_LIMIT_MS;
        JSONObject result=performed();
        event("phone.batch","running");
        int i=0;boolean settle=true; // settle: the last input was not waited out yet
        for(;i<=last;i++){
            JSONObject step=steps.getJSONObject(i);String kind=step.getString("tool"),id=step.optString("elementId");
            boolean injecting=false;
            try{
                String stop=null;
                if(i>0){
                    // The previous input happened: let it settle, then compare the screen with the one before it.
                    boolean quiet=workspace.settle();settle=!quiet;
                    // A takeover first: the user's screen (maybe a secure one) is not checked as if the model held it.
                    if(takeover.active())stop="USER_CONTROL";
                    else{
                        workspace.guard();gate.requireActive(session);
                        String previous=steps.getJSONObject(i-1).getString("elementId");
                        String[] now=id.isEmpty()?workspace.outside(previous):workspace.outside(previous,id);
                        if(SystemClock.elapsedRealtime()>deadline)stop="BATCH_TIME_LIMIT";
                        else if(!quiet)stop="SCREEN_SETTLING";
                        else if(!structure.equals(workspace.fingerprint()))stop="WINDOW_CHANGED";
                        else if(!outside.equals(now[0]))stop="SCREEN_CHANGED_ELSEWHERE";
                        if(now.length>1)outside=now[1];
                    }
                }
                if(stop==null){
                    if(kind.equals("key")){if(step.getString("key").equals("ENTER")&&(typed.isEmpty()||!workspace.enterAllowed(typed)))stop="HIGH_IMPACT_SINGLE";}
                    else{workspace.verifyTarget(id);if(!kind.equals("type_text")&&!workspace.tapAllowed(id))stop="HIGH_IMPACT_SINGLE";}
                }
                if(stop!=null){
                    if(i==0)ActionGate.fail("HIGH_IMPACT_SINGLE","High-impact step: run it alone after checking the screen");
                    result.put("stoppedAt",i).put("stopReason",stop);break;
                }
                if(i==0){
                    structure=workspace.fingerprint();
                    gate.consume(session,observationId,structure,SystemClock.elapsedRealtime());
                    outside=workspace.outside(id)[0];
                }
                injecting=true;settle=true;
                switch(kind){
                    case "tap":workspace.tap(new JSONObject().put("elementId",id),false);break;
                    case "long_press":workspace.tap(new JSONObject().put("elementId",id),true);break;
                    case "type_text":workspace.type(new JSONObject().put("elementId",id).put("text",step.getString("text")));typed=id;activity.note("输入文字");break;
                    default:workspace.key(step.getString("key"));if(step.getString("key").equals("ENTER"))activity.note("确认输入");
                }
            }catch(Exception e){
                ActionGate.Denied denied=e instanceof ActionGate.Denied?(ActionGate.Denied)e:null;
                String code=denied==null?"PLATFORM_ERROR":denied.code;
                // Same pause policy as single calls: a boundary violation pauses; a platform failure pauses only when it
                // hit an input, not a read.
                if((denied==null?injecting:!survives(denied))&&!gate.paused())safePause(code);
                if(i==0)throw denied==null?e:settling(denied);
                result.put("stoppedAt",i).put("stopReason",denied==null?code:settling(denied).code);
                if(injecting&&!REJECTED_BEFORE_INPUT.contains(code))result.put("uncertain",true);
                break;
            }
        }
        result.put("completed",i);
        // The user holds the screen: nothing is read until the hand-back.
        if(result.optString("stopReason").equals("USER_CONTROL"))return result.put("observationError","USER_CONTROL");
        return observeAfter(session,result,before,settle);
    }
    /** Finger strokes along paths (phone_gesture: drawing, curved or multi-part swipes, long-press drags, two-finger
     *  pinch and rotate), bound to one observation and one admitted UID like a swipe. Strokes run in order with the
     *  fingers lifted for GAP_MS between them, after a tap for more than DOUBLE_TAP_MS when the next stroke goes down near
     *  it (two taps, not a double tap). Before each later stroke the window set must still be the observed one (a
     *  stroke that opened a dialog, menu or page stops the rest) and the stroke must fit the call's wall-clock budget; a
     *  user takeover interrupts even a running stroke. The screen content is not compared: drawing changes it on
     *  purpose. Results follow phone_batch: a refusal before the first stroke is an ordinary refusal; after it the reply
     *  is performed:true with completed and, when stopped, stoppedAt/stopReason (uncertain: the stopped stroke was cut
     *  off midway), so the model never redraws strokes that already happened. */
    private JSONObject gesture(String session,JSONObject a)throws Exception{
        JSONArray strokes=a.getJSONArray("strokes");
        // Every point is on this display before anything is consumed or injected, so a bad stroke refuses the whole call.
        int[][][] fingers=new int[strokes.length()][][];
        for(int i=0;i<fingers.length;i++){
            JSONArray stroke=strokes.getJSONObject(i).getJSONArray("fingers");fingers[i]=new int[stroke.length()][];
            for(int f=0;f<stroke.length();f++){
                JSONArray p=stroke.getJSONArray(f);int[] points=fingers[i][f]=new int[p.length()];
                for(int j=0;j<p.length();j++){points[j]=p.getInt(j);if(points[j]>=(j%2==0?ProfileWorkspace.W:ProfileWorkspace.H))ActionGate.fail("INVALID_GESTURE","Gesture bounds invalid");}
            }
        }
        treeRead=false;treeIncomplete=false;
        String before=workspace.lastTreeSignature();
        workspace.requireForeground(workspace.targetPackage());
        long begun=SystemClock.uptimeMillis();
        String structure=workspace.fingerprint();
        // When the last full check (ownership, target UID) ended, on the stroke's uptime clock: a stroke does not repeat it.
        long checked=SystemClock.uptimeMillis(),checks=checked-begun;
        gate.consume(session,a.optString("observationId"),structure,SystemClock.elapsedRealtime());
        int uid=workspace.gestureTarget();
        JSONObject result=performed();
        event("phone.gesture","running");
        long deadline=SystemClock.elapsedRealtime()+StrokePath.WALL_MS;
        int touchSlop=StrokePath.px(ProfileWorkspace.touchSlopDp(),ProfileWorkspace.DPI),doubleTapSlop=StrokePath.px(StrokePath.DOUBLE_TAP_SLOP_DP,ProfileWorkspace.DPI);
        long lifted=0;
        int i=0;
        for(;i<strokes.length();i++){
            JSONObject stroke=strokes.getJSONObject(i);
            boolean injecting=false;
            try{
                if(i>0){
                    // After a tap, a stroke that goes down near it waits until DOUBLE_TAP_MS have passed since the app had
                    // the tap's UP: GestureDetector would take the two for a double tap. Most of the wait
                    // comes before the checks (as long as they took last time), so the stroke starts from a recent check.
                    long notBefore=StrokePath.doubleTap(fingers[i-1],fingers[i],touchSlop,doubleTapSlop)?lifted+StrokePath.DOUBLE_TAP_MS+1:0;
                    SystemClock.sleep(Math.max(StrokePath.GAP_MS,notBefore-checks-SystemClock.uptimeMillis()));
                    // A takeover first: the user's screen is not checked as if the model held it.
                    if(takeover.active()){result.put("stoppedAt",i).put("stopReason","USER_CONTROL");break;}
                    if(SystemClock.elapsedRealtime()+stroke.getInt("durationMs")+stroke.getInt("holdStartMs")+stroke.getInt("holdEndMs")>deadline){result.put("stoppedAt",i).put("stopReason","GESTURE_TIME_LIMIT");break;}
                    long start=SystemClock.uptimeMillis();
                    workspace.guard();gate.requireActive(session);
                    if(!structure.equals(workspace.strokeFingerprint())){result.put("stoppedAt",i).put("stopReason","WINDOW_CHANGED");break;}
                    checked=SystemClock.uptimeMillis();checks=checked-start;
                    SystemClock.sleep(Math.max(0,notBefore-checked));
                }
                injecting=true;
                lifted=workspace.stroke(uid,fingers[i],stroke.getInt("durationMs"),stroke.getInt("holdStartMs"),stroke.getInt("holdEndMs"),takeover::active,checked);
            }catch(Exception e){
                ActionGate.Denied denied=e instanceof ActionGate.Denied?(ActionGate.Denied)e:null;
                String code=denied==null?"PLATFORM_ERROR":denied.code;
                // Same pause policy as phone_batch; the user taking over is a normal flow, never a pause.
                if(!code.equals("USER_CONTROL")&&(denied==null?injecting:!survives(denied))&&!gate.paused())safePause(code);
                if(i==0)throw denied==null?e:settling(denied);
                result.put("stoppedAt",i).put("stopReason",denied==null?code:settling(denied).code);
                if(injecting)result.put("uncertain",true);
                break;
            }
        }
        result.put("completed",i);
        // The user holds the screen: nothing is read until the hand-back.
        if(result.optString("stopReason").equals("USER_CONTROL"))return result.put("observationError","USER_CONTROL");
        return observeAfter(session,result,before,true);
    }
    /** Inputs return the settled screen, so the model continues from the reply instead of calling phone_ui_tree next
     *  (measured on a real session: 167 of 553 model steps were exactly that call). The observation is a full ui_tree
     *  observation: new id, element ids bound to it, screenshot allowed afterwards. The input has already happened, so
     *  nothing here may throw: a failed read is reported as observationError next to performed:true, otherwise the model
     *  would take a performed input for a failed one and repeat it. changed compares with the screen the model last read;
     *  settled:false means the content was still changing when it was read. */
    private JSONObject observeAfter(String session,JSONObject result,String before,boolean settle)throws JSONException{
        try{
            if(settle&&!workspace.settle())result.put("settled",false);
            ProfileWorkspace.Observation observation=workspace.observe(false);
            JSONObject tree=observation.result();
            String observationId=gate.observeTree(session,observation.fingerprint(),SystemClock.elapsedRealtime());
            workspace.bindTree(observationId);treeRead=true;treeIncomplete=!tree.optBoolean("complete",true);
            if(!before.isEmpty())result.put("changed",!before.equals(workspace.lastTreeSignature()));
            result.put("observation",tree.put("observationId",observationId));
        }catch(ActionGate.Denied e){
            // Same policy as the next tool's up-front check would apply, one call earlier; a pause keeps its first reason.
            if(!survives(e)&&!gate.paused())safePause(e.code);
            result.put("observationError",settling(e).code);
        }catch(Exception e){result.put("observationError","PLATFORM_ERROR");}
        return result;
    }
    private JSONObject control(JSONObject a)throws Exception{
        String action=a.optString("action"),session=a.optString("sessionId");
        if(!action.equals("allow_app")&&!action.equals("revoke_app")){
            if(!instanceId.equals(a.optString("instanceId"))||executionId.isEmpty()||!executionId.equals(a.optString("executionId")))ActionGate.fail("EXECUTION_STALE","Workspace changed; review current workspace");
            if(!action.equals("pause")&&!action.equals("stop")&&(!a.has("revision")||a.getLong("revision")!=revision))ActionGate.fail("REVISION_STALE","Task state changed; review current state first");
        }
        if(!action.equals("allow_app")&&!action.equals("revoke_app")){
            if(!lastTaskId.equals(session))ActionGate.fail("SESSION_MISMATCH","Task belongs to another session");
            if(gate.owner()!=null)gate.requireOwner(session);
        }
        switch(action){
            case "pause":if(taskManaged&&tasks.active())requestTaskControl("pause","USER_PAUSED");else safePause("USER_PAUSED");break;
            case "stop":synchronized(this){
                if(taskManaged&&tasks.active())requestTaskControl("stop","USER_STOPPED");
                else if(gate.owner()!=null||taskManaged){closeWorkspace();if(taskManaged)tasks.stopSettled();event("control.stop","stopped");}}break;
            case "resume":
                if(taskManaged)ActionGate.fail("RESTART_REQUIRED","Continue with a new explicit user turn; paused DSH turns cannot be resumed");
                gate.requireOwner(session);if(approvals.pending()!=null)ActionGate.fail("APPROVAL_PENDING","Approve or reject pending action first");
                if(restartRequired)ActionGate.fail("RESTART_REQUIRED","Main display changed; stop and start a new workspace");
                workspace.clearCancellation(workspaceExecutionId);try{workspace.guard();}catch(ActionGate.Denied e){safePause(e.code);throw e;}
                gate.resume(session);event("control.resume","running");break;
            case "approve":
                ApprovalGate.Reservation p;
                try{
                    if(restartRequired)ActionGate.fail("RESTART_REQUIRED","Main display changed; stop and start a new workspace");
                    workspace.guard();p=approvals.consume(a.optString("approvalId"),session,workspace.fingerprint(),SystemClock.elapsedRealtime());
                }catch(ActionGate.Denied e){safePause(e.code);throw e;}
                try{
                    gate.resume(p.session);execute(p.session,p.tool,new JSONObject(p.arguments),true);
                    // Approval authorizes exactly one action, never resurrects a settled DSH activity.
                    if(taskManaged){gate.pause(p.session);event("control.approved","waiting_user");}
                }catch(Exception e){safePause(e instanceof ActionGate.Denied?((ActionGate.Denied)e).code:"APPROVAL_FAILED");throw e;}break;
            case "reject":safePause("USER_REJECTED");break;
            case "allow_app":case "revoke_app":ActionGate.fail("CONTROL_DENIED","Per-app approvals were removed; only the verified work profile is accessible");break;
            default:ActionGate.fail("CONTROL_DENIED","Unknown user control");
        }
        return status();
    }
    private void endTakeover(String outcome){
        try{if(takeover.finish(outcome)){workspace.userRelease();activity.note(outcome.equals("handed_back")?"已交还给 AI":"接管已结束");gate.invalidate();event("intervention.end",state.equals("idle")?"running":state);}}catch(Exception ignored){}
    }
    private JSONObject takeoverControl(JSONObject a)throws Exception{
        String action=a.optString("action");
        if(!instanceId.equals(a.optString("instanceId")))ActionGate.fail("EXECUTION_STALE","Workspace changed; review current workspace");
        if(action.equals("start")){
            if(gate.owner()==null||workspace.displayId()<=0)ActionGate.fail("NO_DISPLAY","No active workspace to take over");
            if(takeover.active())return status();
            if(tasks.active()&&!tasks.command().isEmpty())ActionGate.fail("PAUSED","Task is stopping");
            takeover.start("user","",SystemClock.elapsedRealtime());activity.note("你接管了操作");gate.invalidate();event("intervention.start",state.equals("idle")?"running":state);
        }else if(action.equals("end")){endTakeover("handed_back");}
        else ActionGate.fail("CONTROL_DENIED","Unknown takeover action");
        return status();
    }
    private JSONObject userInput(JSONObject a)throws Exception{
        if(!instanceId.equals(a.optString("instanceId")))ActionGate.fail("EXECUTION_STALE","Workspace changed; review current workspace");
        if(!takeover.active())ActionGate.fail("CONTROL_DENIED","Take over the screen before sending input");
        String kind=a.optString("kind");
        try{
            switch(kind){
                case "touch":workspace.userTouch(a.optString("action"),(float)a.optDouble("x",-1),(float)a.optDouble("y",-1));break;
                case "key":workspace.userKey(a.optString("key"));break;
                case "text":workspace.userText(a.optString("text"));break;
                default:ActionGate.fail("ARGUMENTS_INVALID","Unknown input kind");
            }
        }catch(ActionGate.Denied e){throw e;}
        return new JSONObject().put("ok",true);
    }
    /** Long-lived controller-only live view. Frames are the hardware encoder's H.264 access units, each message
     *  prefixed by a 4-byte big-endian length (0 = keepalive) and a 1-byte type (see LiveStream). The server
     *  proves itself once in the response headers; the stream carries no secrets. */
    private void stream(Socket socket,BrokerAuth.Authenticated auth)throws Exception{
        if(workspace.displayId()<=0){respond(socket,409,new JSONObject().put("ok",false).put("error",new JSONObject().put("code","NO_DISPLAY").put("message","No active workspace")),auth);return;}
        if(streams.incrementAndGet()>1){streams.decrementAndGet();respond(socket,409,new JSONObject().put("ok",false).put("error",new JSONObject().put("code","DISPLAY_BUSY").put("message","A live view is already attached")),auth);return;}
        boolean attached=false;
        try{
            workspace.viewerAttach();attached=true;
            LiveStream video=workspace.liveStream();
            socket.setSoTimeout(0);socket.setTcpNoDelay(true);OutputStream out=new BufferedOutputStream(socket.getOutputStream(),65536);
            out.write(("HTTP/1.1 200\r\nContent-Type: application/x-cowork-frames\r\nCache-Control: no-store\r\nConnection: close\r\nX-Cowork-Proof: "+auth.responseProof(200,new byte[0])+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            // Same authenticated socket carries the human's input as newline-delimited JSON (no per-event HMAC round trips).
            final InputStream input=socket.getInputStream();
            // Reading and injecting are separate threads: a slow injection must not stop the socket from draining,
            // and queued finger MOVE samples are coalesced (only the newest of a run is injected) so the screen
            // follows the finger instead of replaying every stale sample.
            final java.util.concurrent.LinkedBlockingDeque<JSONObject> pending=new java.util.concurrent.LinkedBlockingDeque<>();
            final JSONObject closedMarker=new JSONObject();
            Thread worker=new Thread(()->{
                try{
                    while(true){
                        JSONObject message=pending.take();
                        if(message==closedMarker)return;
                        if(isMove(message)){
                            JSONObject next;
                            while((next=pending.peekFirst())!=null&&next!=closedMarker&&isMove(next))message=pending.takeFirst();
                        }
                        try{
                            if(takeover.active()){controllerSeen=SystemClock.elapsedRealtime();message.put("instanceId",instanceId);userInput(message);}
                        }catch(Exception rejected){/* stale or refused input is dropped; the user sees the screen unchanged */}
                    }
                }catch(InterruptedException stop){}
            },"cowork-user-inject");worker.setDaemon(true);worker.start();
            Thread reader=new Thread(()->{
                try{
                    InputStream buffered=new java.io.BufferedInputStream(input,4096);
                    ByteArrayOutputStream lineBytes=new ByteArrayOutputStream();int c;
                    while((c=buffered.read())!=-1){
                        if(c!='\n'){if(lineBytes.size()<4096)lineBytes.write(c);else lineBytes.reset();continue;}
                        String text=lineBytes.toString(StandardCharsets.UTF_8);lineBytes.reset();
                        try{JSONObject message=new JSONObject(text);
                            if(message.optString("kind").equals("sync")){LiveStream current=workspace.liveStream();if(current!=null)current.requestSync();}
                            else if(takeover.active())pending.addLast(message);}
                        catch(Exception rejected){/* malformed input is dropped */}
                    }
                }catch(IOException closed){}
                finally{pending.addLast(closedMarker);try{socket.close();}catch(IOException ignored){}}
            },"cowork-user-input");reader.setDaemon(true);reader.start();
            frame(out,video.info());
            byte[] first=workspace.initialFrame();if(first!=null)frame(out,LiveStream.message(LiveStream.STILL,first));
            out.flush();video.requestSync();
            long lastWrite=SystemClock.elapsedRealtime(),fieldChecked=0;String fieldSent="";
            while(running&&workspace.displayId()>0&&!socket.isClosed()){
                // While the human controls the screen, tell the viewer which input field has focus (password flag, input
                // type, bounds; never its text) so it can raise its own keyboard: the owned display's IME policy is HIDE.
                long now=SystemClock.elapsedRealtime();
                if(now-fieldChecked>=FIELD_POLL_MS){
                    fieldChecked=now;JSONObject field=takeover.active()?workspace.userField():null;
                    String encoded=(field==null?new JSONObject().put("editable",false):field).toString();
                    if(!encoded.equals(fieldSent)&&!(fieldSent.isEmpty()&&field==null)){frame(out,LiveStream.message(LiveStream.FIELD,encoded.getBytes(StandardCharsets.UTF_8)));out.flush();lastWrite=now;}
                    fieldSent=encoded;
                }
                byte[] next=video.take(FIELD_POLL_MS);
                if(next!=null){
                    frame(out,next);
                    // Flush only when the queue is drained so a burst leaves in one write, a lone frame immediately.
                    byte[] more;while((more=video.take(0))!=null)frame(out,more);
                    out.flush();lastWrite=SystemClock.elapsedRealtime();
                }
                else if(SystemClock.elapsedRealtime()-lastWrite>=1000){out.write(new byte[]{0,0,0,0});out.flush();lastWrite=SystemClock.elapsedRealtime();}
            }
        }catch(IOException closed){/* viewer went away */}
        finally{if(attached)workspace.viewerDetach();streams.decrementAndGet();}
    }    private static boolean isMove(JSONObject message){return "touch".equals(message.optString("kind"))&&"move".equals(message.optString("action"));}
    private static void frame(OutputStream out,byte[] bytes)throws IOException{
        out.write(new byte[]{(byte)(bytes.length>>>24),(byte)(bytes.length>>>16),(byte)(bytes.length>>>8),(byte)bytes.length});out.write(bytes);
    }
    private synchronized void safePause(String reason){try{
        approvals.clear();
        if(reason.equals("MAIN_CHANGED")||reason.equals("MAIN_UNKNOWN")||reason.equals("APP_CONFLICT"))restartRequired=true;
        if(taskManaged&&tasks.active()){requestTaskControl("pause",reason);return;}
        if(gate.owner()==null)return;
        System.err.println("workspace paused: "+reason);
        pauseReason=reason;gate.pause(gate.owner());activity.note("任务已暂停");event(reason,"paused");
    }catch(Exception ignored){}}
    private synchronized void event(String action,String next)throws Exception{
        if(taskManaged){
            if(tasks.active()&&(next.equals("running")||next.equals("waiting_user")))tasks.phase(next);
            lastTaskId=tasks.task();next=tasks.state();
        }else if(gate.owner()!=null)lastTaskId=gate.owner();
        revision++;state=next;
        
        
        events.add(new JSONObject().put("taskId",lastTaskId).put("revision",revision).put("action",action).put("state",next).put("app",workspace.targetPackage()));while(events.size()>32)events.removeFirst();
    }
    private static JSONObject denied(ActionGate.Denied error)throws JSONException{return new JSONObject().put("ok",false).put("error",new JSONObject().put("code",error.code).put("message",error.getMessage()));}
    private static JSONObject performed()throws JSONException{return new JSONObject().put("performed",true);}
    private static JSONObject performed(JSONObject extra)throws JSONException{JSONObject r=new JSONObject().put("performed",true);java.util.Iterator<String> keys=extra.keys();while(keys.hasNext()){String k=keys.next();r.put(k,extra.get(k));}return r;}
    private JSONObject toolStatus()throws Exception{return new JSONObject().put("state",gate.owner()==null?"ready":gate.paused()?"paused":"ready").put("accessibilityReady",workspace.displayId()>0).put("screenCaptureReady",workspace.displayId()>0).put("userControl",takeover.active());}
    private synchronized JSONObject status()throws Exception{
        long observedAt=SystemClock.elapsedRealtime();long sample=++environmentSequence;
        String main=workspace.mainPackage();Object game=JSONObject.NULL;if(main.equals("dev.dsh.pocket"))game=false;else try{android.content.pm.ApplicationInfo info=workspace.context().getPackageManager().getApplicationInfo(main,0);int category=info.category;if(category!=android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED)game=category==android.content.pm.ApplicationInfo.CATEGORY_GAME;else if((info.flags&android.content.pm.ApplicationInfo.FLAG_IS_GAME)!=0)game=true;else if((info.flags&android.content.pm.ApplicationInfo.FLAG_SYSTEM)!=0)game=false;}catch(Exception ignored){}
        JSONObject out=new JSONObject().put("profileUserId",workspace.profileUserId()).put("profileSerial",workspace.profileSerial()).put("accessMode","managed-profile").put("instanceId",instanceId).put("executionId",executionId).put("taskId",lastTaskId).put("revision",revision).put("state",state).put("taskManaged",taskManaged).put("paused",taskManaged?!state.equals("running"):gate.paused()).put("restartRequired",restartRequired).put("displayAlert",standingFailure).put("pauseReason",pauseReason).put("displayId",workspace.displayId()).put("workspaceActive",gate.owner()!=null).put("packageName",gate.owner()==null?"":workspace.targetPackage()).put("mainPackage",main).put("mainIsGame",game).put("environmentSequence",sample).put("environmentObservedAt",observedAt).put("locked",workspace.locked()).put("activity",activity.json()).put("ownerNotices",notices.json());
        takeover.describe(out);
        ApprovalGate.Reservation p=approvals.pending();if(p!=null)out.put("pending",new JSONObject().put("sessionId",p.session).put("tool",p.tool).put("arguments",new JSONObject(p.arguments)).put("approvalId",p.id).put("remainingMs",Math.max(0,p.deadline-SystemClock.elapsedRealtime())));return out;
    }
}
