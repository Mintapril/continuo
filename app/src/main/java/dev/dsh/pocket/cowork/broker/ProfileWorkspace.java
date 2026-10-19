package dev.dsh.pocket.cowork.broker;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.PixelFormat;
import android.hardware.display.*;
import android.media.*;
import android.os.*;
import android.view.*;
import org.json.*;
import java.lang.reflect.*;
import java.util.*;

/** Profile-only pixel/input driver. No personal-user fallback, application allowlist, clipboard,
 * arbitrary keys or accessibility provider calls. Android profiles are not app-escape containment.
 * The original AndroidWorkspace is retained separately; this driver never falls back to it. */
public final class ProfileWorkspace implements AutoCloseable {
    private final Context context;
    private final ProfileContext profile;
    private final LauncherHider launchers;
    private final WindowOwnershipProbe windows;
    private final SecureWindowProbe protection=new SecureWindowProbe();
    private final SecureSettingProbe secureSetting=new SecureSettingProbe();
    private final ExecutionCancellation cancellation=new ExecutionCancellation();
    private final HandlerThread drain=new HandlerThread("cowork-profile-frame-drain");
    private volatile VirtualDisplay display;
    private volatile ImageReader reader;
    private String displayIdentity;
    private volatile String displayExecution="";
    private long generation;
    private final Set<String> launched=new HashSet<>();
    private record Frame(byte[] png,long at,String fingerprint,int uid){}
    private final ProfileStructure tree;
    private final Object frameLock=new Object();
    private Handler drainHandler;
    private PackageInstaller.SessionCallback installs;
    private volatile int viewers;
    private java.nio.ByteBuffer rowBuffer;
    private long userDown,userCheck;
    private int userUid=-1;
    private float userX,userY;
    /** Same aspect ratio and density feel as the main screen (set once from the real default display). */
    static volatile int W=720,H=1600,DPI=290;static final int FPS=120;
    private static final int FLAG_OWN_DISPLAY_GROUP=1<<11,FLAG_ALWAYS_UNLOCKED=1<<12;
    /** True once the owned display really retained ALWAYS_UNLOCKED + OWN_DISPLAY_GROUP: the main-screen lock no longer gates it. */
    private volatile boolean unlockedDisplay;
    private volatile LiveStream live;

    public ProfileWorkspace()throws Exception{
        Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);
        context=(Context)at.getMethod("getSystemContext").invoke(thread);
        sizeFromMainScreen();profile=new ProfileContext(context);launchers=new LauncherHider(profile);tree=new ProfileStructure(profile);windows=new WindowOwnershipProbe();try{windows.awaitInitial();}catch(IllegalStateException e){System.err.println("window snapshot not yet available at start; every operation re-checks it");}drain.start();drainHandler=new Handler(drain.getLooper());watchInstalls();
        changedPackages(); // baseline of the 1 Hz change read, taken before the start-up sweep below
        // Drift repair of the launcher entries (e.g. an app installed back before this start): housekeeping repairs it only
        // while no owned display exists, and the standing display is created right after this constructor.
        launchers.reconcile();
        try{popups=new BackgroundPopupGate(context,profile);popups.enforce();}catch(Exception e){System.err.println("background pop-up gate unavailable: "+e.getClass().getSimpleName()+": "+e.getMessage());}
    }
    private volatile BackgroundPopupGate popups;
    /** See {@link BackgroundPopupGate}: drift repair from the watchdog; new installs are handled by the install watch. */
    public void closeBackgroundPopups(){BackgroundPopupGate gate=popups;if(gate!=null)gate.enforce();}
    private void sizeFromMainScreen(){
        try{
            android.util.DisplayMetrics m=new android.util.DisplayMetrics();
            context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(m);
            int mw=Math.min(m.widthPixels,m.heightPixels),mh=Math.max(m.widthPixels,m.heightPixels);
            if(mw>0&&mh>0){int h=(int)Math.round(720.0*mh/mw);h=Math.max(1280,Math.min(2000,h))&~1;W=720;H=h;DPI=Math.max(120,Math.round(m.densityDpi*720f/mw));}
        }catch(Exception e){System.err.println("main screen size unavailable; using "+W+"x"+H);}
    }
    public Context context(){return context;}
    public int profileUserId(){return profile.userId();}
    public long profileSerial(){return profile.serial();}
    public int displayId(){VirtualDisplay d=display;return d==null?-1:d.getDisplay().getDisplayId();}
    public void requestCancellation(String execution){cancellation.request(execution);}
    /** OWN_DISPLAY_GROUP gives the owned display a power group of its own, with its own screen-off timeout, and only input
     *  injected into it counts as activity there. A model that reads, thinks or waits longer than the timeout (or a power-
     *  button sleep, after which only the main group wakes) leaves the group dozing: its activities stop, no task on it is
     *  visible, launches never arrive and every check looks like a screen that never settles. The owner keeps the group
     *  awake through this: wake it when it is not interactive, then report activity on it. Only this display's group is
     *  addressed, never the main screen. Own lock and binder calls only, so it never waits on a running tool; best effort,
     *  never throws. IPowerManager signatures as shipped on the Android 16 device. */
    public void keepAwake(){
        int id=displayId();if(id<=0)return;
        synchronized(powerLock){
            try{
                if(power==null){
                    IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"power");
                    Class<?> type=Class.forName("android.os.IPowerManager");
                    Object service=Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
                    if(service==null)throw new IllegalStateException("power service unavailable");
                    displayInteractive=type.getMethod("isDisplayInteractive",int.class);
                    wakeDisplay=type.getMethod("wakeUpWithDisplayId",long.class,int.class,String.class,String.class,int.class);
                    displayActivity=type.getMethod("userActivity",int.class,long.class,int.class,int.class);
                    power=service;
                }
                if(!(Boolean)displayInteractive.invoke(power,id))
                    wakeDisplay.invoke(power,SystemClock.uptimeMillis(),WAKE_REASON_APPLICATION,"dsh-cowork:owned-display","com.android.shell",id);
                displayActivity.invoke(power,id,SystemClock.uptimeMillis(),USER_ACTIVITY_EVENT_OTHER,0);
                powerFailure="";
            }catch(Throwable e){
                Throwable cause=e instanceof InvocationTargetException&&e.getCause()!=null?e.getCause():e;
                String why=cause.getClass().getSimpleName();
                // Logged once per distinct failure: this runs every two seconds while a turn owns the display.
                if(!why.equals(powerFailure)){powerFailure=why;System.err.println("owned display wake failed: "+why);}
            }
        }
    }
    /** PowerManager.WAKE_REASON_APPLICATION and USER_ACTIVITY_EVENT_OTHER (system API values). */
    private static final int WAKE_REASON_APPLICATION=2,USER_ACTIVITY_EVENT_OTHER=0;
    private final Object powerLock=new Object();
    private Object power;
    private Method displayInteractive,wakeDisplay,displayActivity;
    private String powerFailure="";
    /** A new owner of the standing display starts a fresh cancellation scope. Lock free: it is only an atomic swap. */
    public void beginScope(String execution){cancellation.begin(execution);}
    /** True while the standing display exists and still has the verified identity (not locked out, same profile). */
    public synchronized boolean ready(){if(display==null)return false;try{binding();return true;}catch(Throwable e){return false;}}
    private volatile boolean parked=true;
    /** True once an app was started on the display since the last reset (nothing to observe before that). */
    public synchronized boolean hasLaunched(){return !launched.isEmpty();}
    /** Ownership/idle check for an empty display: identity and cancellation only, no app window required. */
    public synchronized void guardIdle(){check(false,true);}
    /** phone_stop: the display stays and so do the apps on it. The AI desktop comes to the front and the profile tasks
     *  are parked behind it instead of removed. A removed task left its app process running (an app store finishing an
     *  install, a push process) with no activity on the owned display, and Android then puts whatever that app opens
     *  next without naming a display on the main screen: TaskLaunchParamsModifier falls back to the display of the
     *  launching process, then of the activity's own process, then of the real caller, and only then to the default
     *  display. A parked task keeps that anchor, so the system itself places such a screen here. Launcher entries are
     *  hidden only for apps without a task left here (a live launcher activity would be force-finished); every
     *  observation is invalidated. Skipped when a newer owner already took over. */
    public synchronized void resetContent(String execution){
        if(!execution.isEmpty()&&!cancellation.current(execution))return;
        userRelease();
        if(display!=null){
            try{if(startDesktop(recentsText())<0)System.err.println("AI desktop refused while parking the display");}
            catch(Exception e){System.err.println("display park failed: "+(e instanceof InvocationTargetException&&e.getCause()!=null?e.getCause():e));}
        }
        launched.clear();tree.bind(null);generation++;parked=true;cancellation.end();
        try{hideIdleLaunchers();launchers.hideUnseen();}catch(Exception hideFailure){}
    }
    /** While parked (after phone_stop, before the next launch): launcher entries of apps whose last task on the owned
     *  display is gone are hidden again. Under the workspace monitor, so it never races a launch (which leaves the
     *  parked state first). Called by resetContent and by the watchdog. */
    public synchronized void hideIdleLaunchers(){
        if(!parked||displayId()<=0)return;
        Set<String> live=new HashSet<>();
        for(ActivityManager.RunningTaskInfo t:tasks())
            if(taskInt(t,"displayId")==displayId()&&taskInt(t,"userId")==profile.userId()){
                if(t.baseActivity!=null)live.add(t.baseActivity.getPackageName());
                if(t.topActivity!=null)live.add(t.topActivity.getPackageName());
            }
        launchers.hideRevealedExcept(live);
    }
    public void clearCancellation(String execution){cancellation.clear(execution);}
    public boolean locked(){return context.getSystemService(KeyguardManager.class).isDeviceLocked();}
    public String mainPackage(){
        try{for(ActivityManager.RunningTaskInfo t:tasks())if(t.getClass().getField("displayId").getInt(t)==0&&t.getClass().getField("isVisible").getBoolean(t)&&t.topActivity!=null)return t.topActivity.getPackageName();}
        catch(Exception ignored){}return "";
    }
    private List<ActivityManager.RunningTaskInfo> tasks(){return context.getSystemService(ActivityManager.class).getRunningTasks(100);}
    private String identity()throws Exception{
        VirtualDisplay d=display;if(d==null)throw new IllegalStateException();
        Class<?> type=Class.forName("android.view.DisplayInfo");Object info=type.getConstructor().newInstance();
        boolean valid=(Boolean)Display.class.getMethod("getDisplayInfo",type).invoke(d.getDisplay(),info);
        int flags=type.getField("flags").getInt(info);
        int own=Display.class.getField("FLAG_OWN_FOCUS").getInt(null),noSteal=Display.class.getField("FLAG_STEAL_TOP_FOCUS_DISABLED").getInt(null);
        if((flags&own)==0||(flags&noSteal)==0)ActionGate.fail("SECURITY_UNAVAILABLE","Profile focus isolation unavailable");
        unlockedDisplay=(flags&(FLAG_OWN_DISPLAY_GROUP|FLAG_ALWAYS_UNLOCKED))==(FLAG_OWN_DISPLAY_GROUP|FLAG_ALWAYS_UNLOCKED);
        return OwnedDisplayIdentity.require(valid,type.getField("displayId").getInt(info),displayId(),type.getField("type").getInt(info),flags,
            (String)type.getField("uniqueId").get(info),type.getField("ownerUid").getInt(info),(String)type.getField("ownerPackageName").get(info));
    }
    /** The system server destroys the VirtualDisplay on its own restart while this broker keeps running, and the stale
     *  Display handle still reports its old id. Liveness therefore has to be asked of DisplayManager, not of the handle. */
    private boolean displayAlive(){
        VirtualDisplay d=display;
        if(d==null)return false;
        try{
            int id=d.getDisplay().getDisplayId();
            if(id<=0)return false;
            DisplayManager manager=(DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
            return manager!=null&&manager.getDisplay(id)!=null;
        }catch(Throwable e){return false;}
    }
    private void binding(){
        if(!displayAlive())ActionGate.fail("NO_DISPLAY","No owned work-profile display");
        if(locked()&&!unlockedDisplay)ActionGate.fail("LOCKED","Unlock before using the workspace");
        try{profile.current();if(!Objects.equals(displayIdentity,identity()))throw new IllegalStateException();secureSetting.requireSafe();}
        catch(ActionGate.Denied e){throw e;}catch(Exception e){ActionGate.fail("SECURITY_UNAVAILABLE","Profile or owned display identity changed");}
    }
    private static final Set<String> SYSTEM_DIALOG_HOSTS=Set.of("com.android.permissioncontroller","com.google.android.permissioncontroller","com.android.packageinstaller","com.google.android.packageinstaller","com.oplus.securitypermission","com.oplus.appdetail","com.oplus.packageinstaller","com.coloros.securitypermission");
    public synchronized void requirePackage(String pkg)throws Exception{
        try{profile.app(pkg);profile.launcher(pkg);}
        catch(PackageManager.NameNotFoundException e){ActionGate.fail("APP_UNKNOWN","App is not installed in the work profile; call phone_apps for the installed apps");}
    }
    /** Foreground identity only (work-profile UID/installed/enabled); system dialogs have no launcher activity. */
    public synchronized void requireForeground(String pkg)throws Exception{profile.app(pkg);}
    /** The AI display is a standing display: it is created once (at broker start / first claim) and kept. Ownership
     *  and the cancellation scope are per turn ({@link #beginScope}); only the content is reset between turns. */
    public static final String STANDING="standing";
    public synchronized void create(String label)throws Exception{
        if(display!=null)ActionGate.fail("DISPLAY_BUSY","Display already exists");
        profile.current();
        displayExecution=label;parked=true;
        try{
            reader=ImageReader.newInstance(W,H,PixelFormat.RGBA_8888,2);
            // Frames are drained and discarded unless a user-controller stream is attached (viewers>0);
            // then pollFrame() consumes them. Secure layers are never rendered into this unsecured display.
            reader.setOnImageAvailableListener(source->{try(Image i=source.acquireLatestImage()){}catch(Exception ignored){}},drainHandler);
            // PUBLIC|OWN_CONTENT_ONLY|SUPPORTS_TOUCH|ROTATES|DESTROY_CONTENT|TRUSTED|OWN_DISPLAY_GROUP|ALWAYS_UNLOCKED|OWN_FOCUS|STEAL_TOP_FOCUS_DISABLED.
            // ALWAYS_UNLOCKED is only honoured for a display in its own group, so the two travel together.
            int flags=1|8|64|128|256|1024|FLAG_OWN_DISPLAY_GROUP|FLAG_ALWAYS_UNLOCKED|16384|65536;
            display=createDisplay(flags,reader.getSurface());
            if(display==null||displayId()<=0)ActionGate.fail("DISPLAY_FAILED","Owned display unavailable");
            generation++;displayIdentity=identity();
            if(locked()&&!unlockedDisplay)ActionGate.fail("LOCKED","Unlock before starting");
            Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            Class<?> wmType=Class.forName("android.view.IWindowManager");
            wmType.getMethod("setDisplayImePolicy",int.class,int.class).invoke(wm,displayId(),2);
            if((Integer)wmType.getMethod("getDisplayImePolicy",int.class).invoke(wm,displayId())!=2)ActionGate.fail("IME_POLICY_UNAVAILABLE","Hidden IME policy unavailable");
            long until=SystemClock.elapsedRealtime()+3000;
            while(true){binding();try{protection.requireSafe(displayId(),false);break;}catch(ActionGate.Denied e){if(!e.code.equals("SECURITY_UNAVAILABLE")||SystemClock.elapsedRealtime()>=until)throw e;SystemClock.sleep(50);}}
        }catch(Exception e){closeDisplay();throw e;}
    }
    /** Builds the display through VirtualDisplayConfig so the requested refresh rate (120 Hz) is honoured; the
     *  legacy overload (no refresh rate) is the fallback when the config API is unavailable. */
    private VirtualDisplay createDisplay(int flags,android.view.Surface surface)throws Exception{
        DisplayManager manager=context.getSystemService(DisplayManager.class);
        try{
            Class<?> builderType=Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Object builder=builderType.getConstructor(String.class,int.class,int.class,int.class).newInstance("DSH-Cowork-Profile",W,H,DPI);
            builderType.getMethod("setFlags",int.class).invoke(builder,flags);
            builderType.getMethod("setSurface",android.view.Surface.class).invoke(builder,surface);
            builderType.getMethod("setRequestedRefreshRate",float.class).invoke(builder,(float)FPS);
            Object config=builderType.getMethod("build").invoke(builder);
            VirtualDisplay made=(VirtualDisplay)DisplayManager.class.getMethod("createVirtualDisplay",Class.forName("android.hardware.display.VirtualDisplayConfig")).invoke(manager,config);
            if(made!=null)return made;
        }catch(Exception e){System.err.println("virtual display config unavailable: "+e.getClass().getSimpleName()+": "+e.getMessage());}
        return manager.createVirtualDisplay("DSH-Cowork-Profile",W,H,DPI,surface,flags);
    }
    /** Personal foreground changes are allowed. Only profile ownership/display binding is authoritative. */
    public synchronized void guard(){guard(400);}
    /** One ownership check, retried only for a transient gap (app switch, dialog, animation) and only up to
     *  `budgetMs`. A foreign task, a cancellation or any other denial fails at once. Callers that poll on their
     *  own clock (phone.wait) pass 0 so a single gap cannot eat the caller's whole wait. */
    public synchronized void guard(long budgetMs){
        long until=SystemClock.elapsedRealtime()+Math.max(0,budgetMs);
        while(true){
            try{check(true,true);return;}
            catch(ActionGate.Denied e){
                boolean transientGap=(e.code.equals("APP_DENIED")||e.code.equals("APP_UNKNOWN")||e.code.equals("SECURITY_UNAVAILABLE"))&&!"Foreign task on profile display".equals(e.getMessage());
                if(!transientGap||SystemClock.elapsedRealtime()>=until){System.err.println("guard denied: "+e.code+": "+e.getMessage());throw e;}
                SystemClock.sleep(50);
            }
        }
    }
    /** The owned display is reserved for the work profile, but a system surface can still land there (a Settings
     *  page opened while the display had focus, a permission host, the trial launcher from an earlier build).
     *  Evicting it keeps the turn alive; only a task that refuses to leave stays a boundary violation. */
    private void sweepForeignTasks()throws Exception{
        for(int attempt=0;attempt<2;attempt++){
            boolean foreign=false;
            for(ActivityManager.RunningTaskInfo t:tasks()){
                int user=taskInt(t,"userId"),d=taskInt(t,"displayId");
                if(d==displayId()&&user!=profile.userId()){
                    foreign=true;
                    try{Object atm=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                        atm.getClass().getMethod("removeTask",int.class).invoke(atm,t.taskId);
                    }catch(Exception ignored){}
                }
            }
            if(!foreign)return;
            SystemClock.sleep(150);
        }
    }
    /** The reverse leak: a work-profile activity started without a display (by a service, a receiver, an installer or a
     *  notification) lands on the main screen, because the owned display never takes global focus. It is moved onto the
     *  owned display, as if it had started there; the rule is {@link StrayTaskSweep}. Runs inside every check and from
     *  the 1 Hz watchdog (also between turns), so it neither sits on the main screen nor pauses the turn as APP_CONFLICT.
     *  Returns how many tasks were moved; a task that cannot be moved stays where it is and is logged once. */
    public synchronized int pullBackStrayTasks(){
        int owned=displayId();if(owned<=0)return 0;
        ArrayList<StrayTaskSweep.Task> rows=new ArrayList<>();
        for(ActivityManager.RunningTaskInfo t:tasks())
            rows.add(new StrayTaskSweep.Task(t.taskId,taskInt(t,"parentTaskId"),taskInt(t,"userId"),taskInt(t,"displayId"),t.baseActivity==null?null:t.baseActivity.getPackageName()));
        int moved=0;
        for(int id:StrayTaskSweep.targets(rows,owned,profile.userId(),DESKTOP)){
            try{
                Object atm=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
                atm.getClass().getMethod("moveRootTaskToDisplay",int.class,int.class).invoke(atm,id,owned);
                moved++;unmovable.remove(id);
                System.err.println("moved work-profile task "+id+" from the main screen to the owned display");
            }catch(Exception e){
                Throwable cause=e instanceof InvocationTargetException&&e.getCause()!=null?e.getCause():e;
                if(unmovable.add(id))System.err.println("work-profile task "+id+" could not be moved to the owned display: "+cause.getClass().getSimpleName()+": "+cause.getMessage());
            }
        }
        if(moved>0)generation++; // the owned display got new content: every earlier observation is stale
        return moved;
    }
    private final Set<Integer> unmovable=new HashSet<>();

    private void check(boolean content,boolean checkCancel){
        binding();if(checkCancel&&cancellation.cancelled())ActionGate.fail("CONTROL_INTERRUPTED","User requested pause or stop");
        protection.requireSafe(displayId(),content);
        try{
            sweepForeignTasks();
            pullBackStrayTasks();
            for(ActivityManager.RunningTaskInfo t:tasks()){
                int user=t.getClass().getField("userId").getInt(t),d=t.getClass().getField("displayId").getInt(t);
                if(d==displayId()&&user!=profile.userId())ActionGate.fail("APP_DENIED","Foreign task on profile display");
                if(user==profile.userId()&&d!=displayId()&&((t.topActivity!=null&&launched.contains(t.topActivity.getPackageName()))||(t.baseActivity!=null&&launched.contains(t.baseActivity.getPackageName()))))
                    ActionGate.fail("APP_CONFLICT","Work-profile app left the owned display and could not be moved back; stop and review");
            }
            if(content)target();
            else {ProfileWindowPolicy.Snapshot snapshot=windows.awaitSnapshot(1500);boolean hasInput=snapshot.windows().stream().anyMatch(w->w.displayId()==displayId()&&!(w.windowToken()==null&&(w.inputConfig()&1)!=0));if(hasInput)ProfileWindowPolicy.requireInputTargets(snapshot,displayId(),profile.userId());}
        }catch(ActionGate.Denied e){throw e;}catch(Exception e){
            // Private root-only broker.log: exception class/message only, never window or UI content.
            Throwable cause=e instanceof java.lang.reflect.InvocationTargetException&&e.getCause()!=null?e.getCause():e;
            System.err.println("profile-check failed: "+cause.getClass().getName()+": "+cause.getMessage()+" at "+(cause.getStackTrace().length>0?cause.getStackTrace()[0]:"?"));
            ActionGate.fail("APP_DENIED","Profile task ownership unavailable");}
        binding();
    }
    /** target() that rides out a short app-transition gap (activity finishing/starting) before denying. */
    private ManagedProfileScope.App targetWait()throws Exception{
        long until=SystemClock.elapsedRealtime()+3000;
        while(true){
            try{return target();}
            catch(ActionGate.Denied e){if(!(e.code.equals("APP_DENIED")||e.code.equals("APP_UNKNOWN")||e.code.equals("SECURITY_UNAVAILABLE"))||SystemClock.elapsedRealtime()>=until)throw e;SystemClock.sleep(50);}
        }
    }
    private ManagedProfileScope.App target()throws Exception{
        String pkg=targetPackage();if(pkg.isEmpty())throw new ActionGate.Denied("APP_UNKNOWN","No verified work-profile foreground");
        ManagedProfileScope.App app=profile.app(pkg);
        List<ProfileWindowPolicy.Window> admitted=ProfileWindowPolicy.requireInputTargets(windows.awaitSnapshot(1500),displayId(),profile.userId());
        if(admitted.stream().noneMatch(w->w.ownerUid()==app.uid()))ActionGate.fail("APP_DENIED","Target UID has no admitted window");
        return app;
    }
    /** Lock-free (volatile display + binder query): the control plane reads it while a tool holds the workspace. */
    public String targetPackage(){
        if(displayId()<=0)return "";
        try{for(ActivityManager.RunningTaskInfo t:tasks())if(t.getClass().getField("displayId").getInt(t)==displayId()&&t.getClass().getField("userId").getInt(t)==profile.userId()&&t.getClass().getField("isVisible").getBoolean(t)&&t.topActivity!=null)return t.topActivity.getPackageName();}
        catch(Exception ignored){}return "";
    }
    public synchronized void launch(String pkg)throws Exception{
        // check() has already moved a task of this app that leaked to the main screen onto the owned display; starting
        // the app below then brings that task to the front. Only a task that could not be moved is still refused.
        parked=false;check(false,true);requirePackage(pkg);
        for(ActivityManager.RunningTaskInfo t:tasks())if(t.getClass().getField("userId").getInt(t)==profile.userId()&&t.getClass().getField("displayId").getInt(t)!=displayId()&&((t.topActivity!=null&&pkg.equals(t.topActivity.getPackageName()))||(t.baseActivity!=null&&pkg.equals(t.baseActivity.getPackageName()))))ActionGate.fail("APP_CONFLICT","This work-profile app is on another display and could not be moved to the owned display");
        // No MULTIPLE_TASK: switching back to an app that the AI desktop pushed to the background must reuse its task.
        Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(profile.launcher(pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        ActivityOptions options=ActivityOptions.makeBasic();options.setLaunchDisplayId(displayId());
        Object am=ActivityManager.class.getMethod("getService").invoke(null);
        Method start=am.getClass().getMethod("startActivityAsUser",Class.forName("android.app.IApplicationThread"),String.class,Intent.class,String.class,IBinder.class,String.class,int.class,int.class,Class.forName("android.app.ProfilerInfo"),Bundle.class,int.class);
        launched.add(pkg);
        // The launcher entry is enabled only while the app starts (the system refuses to start a disabled component)
        // and is hidden again once the app has established its window, or if the launch fails.
        ComponentName entry=intent.getComponent();boolean revealed=launchers.reveal(entry);int started;
        try{started=(Integer)start.invoke(am,null,"com.android.shell",intent,null,null,null,0,0,null,options.toBundle(),profile.userId());}
        catch(Exception e){if(revealed)launchers.hide(entry);throw e;}
        if(started<0){if(revealed)launchers.hide(entry);ActionGate.fail("APP_LAUNCH_DENIED","Profile launch rejected");}
        // The entry stays enabled while the app runs: disabling a live launcher activity force-finishes it (re-hidden in closeDisplay/housekeeping).
        // A first start can take many seconds (cold start, installer or first-run screens, ads). Wait longer, then
        // report a distinct retryable code: the display and the started activity stay, the model can wait and observe.
        long until=SystemClock.elapsedRealtime()+8000;
        do{try{check(false,true);String top=targetPackage();if(pkg.equals(top)||(!top.isEmpty()&&SYSTEM_DIALOG_HOSTS.contains(top))){guard();return;}}catch(ActionGate.Denied notReady){if(!Set.of("APP_DENIED","APP_UNKNOWN","SECURITY_UNAVAILABLE").contains(notReady.code))throw notReady;}SystemClock.sleep(50);}while(SystemClock.elapsedRealtime()<until);
        // Do NOT hide the entry here: disabling a live launcher activity force-finishes the app that is still starting.
        ActionGate.fail("LAUNCH_TIMEOUT","The app is starting but has not reached the owned display yet; call phone_wait and then phone_ui_tree");
    }

    /** Profile-owned desktop that stands in for the system home screen on the owned display. */
    public static final String DESKTOP="dev.dsh.pocket.isolation";
    /** Virtual HOME: the running app keeps running in the background and the profile desktop takes the display. */
    public synchronized void home()throws Exception{
        parked=false;check(false,true);
        String behind=recents();
        launched.add(DESKTOP);
        int started=startDesktop(behind);
        if(started<0)ActionGate.fail("APP_LAUNCH_DENIED","Profile launch rejected");
        long until=SystemClock.elapsedRealtime()+4000;
        do{try{check(false,true);if(DESKTOP.equals(targetPackage())){guard();return;}}catch(ActionGate.Denied notReady){if(!Set.of("APP_DENIED","APP_UNKNOWN","SECURITY_UNAVAILABLE").contains(notReady.code))throw notReady;}SystemClock.sleep(50);}while(SystemClock.elapsedRealtime()<until);
        ActionGate.fail("LAUNCH_TIMEOUT","The AI desktop has not reached the owned display yet; call phone_wait and then phone_ui_tree");
    }
    /** Starts the profile desktop on the owned display (HOME, and parking at phone_stop). The desktop lists what keeps
     *  running behind it, so neither the agent nor the human has to ask first. The app that is foreground right now is
     *  exactly the one the desktop pushes to the background. */
    private int startDesktop(String behind)throws Exception{
        Intent intent=new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(DESKTOP,DESKTOP+".HomeActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        if(!behind.startsWith("The AI screen"))intent.putExtra("recents",behind.replace("\uff0c\u524d\u53f0)","\uff0c\u540e\u53f0)"));
        ActivityOptions options=ActivityOptions.makeBasic();options.setLaunchDisplayId(displayId());
        Object am=ActivityManager.class.getMethod("getService").invoke(null);
        Method start=am.getClass().getMethod("startActivityAsUser",Class.forName("android.app.IApplicationThread"),String.class,Intent.class,String.class,IBinder.class,String.class,int.class,int.class,Class.forName("android.app.ProfilerInfo"),Bundle.class,int.class);
        return (Integer)start.invoke(am,null,"com.android.shell",intent,null,null,null,0,0,null,options.toBundle(),profile.userId());
    }
    /** Virtual RECENTS: the work-profile tasks on the owned display, newest first, without changing the screen. */
    public synchronized String recents()throws Exception{
        check(false,true);
        return recentsText();
    }
    private String recentsText(){
        StringBuilder text=new StringBuilder();Set<String> seen=new HashSet<>();
        for(ActivityManager.RunningTaskInfo t:tasks()){
            if(taskInt(t,"displayId")!=displayId()||taskInt(t,"userId")!=profile.userId())continue;
            ComponentName top=t.topActivity!=null?t.topActivity:t.baseActivity;if(top==null)continue;
            if(!seen.add(top.getPackageName()))continue;
            if(text.length()>0)text.append('\u3001');
            text.append(appLabel(top.getPackageName())).append(" (").append(top.getPackageName()).append(taskVisible(t)?"\uff0c\u524d\u53f0)":"\uff0c\u540e\u53f0)");
        }
        return text.length()==0?"The AI screen has no running app":text.toString();
    }
    private static int taskInt(ActivityManager.RunningTaskInfo t,String field){try{return t.getClass().getField(field).getInt(t);}catch(Exception e){return Integer.MIN_VALUE;}}
    private static boolean taskVisible(ActivityManager.RunningTaskInfo t){try{return t.getClass().getField("isVisible").getBoolean(t);}catch(Exception e){return false;}}
    private String structure(int uid){
        ArrayList<String> owned=new ArrayList<>();for(ProfileWindowPolicy.Window w:windows.awaitSnapshot(1500).windows())if(w.displayId()==displayId())owned.add(w.ownerUid()+":"+w.inputConfig()+":"+Objects.hashCode(w.windowToken()));
        Collections.sort(owned);return generation+"|"+displayIdentity+"|"+profile.serial()+"|"+uid+"|"+owned;
    }
    private Frame capture()throws Exception{return capture(null);}
    private Frame capture(ScreenZoom zoom)throws Exception{
        check(true,false);ManagedProfileScope.App app=target();String before=structure(app.uid());
        byte[] png=OwnedDisplayCapture.profilePng(displayId(),profile.userId(),app.uid(),W,H,zoom);long at=System.currentTimeMillis();
        check(true,false);if(target().uid()!=app.uid()||!before.equals(structure(app.uid())))ActionGate.fail("STALE_OBSERVATION","Profile window changed during capture");
        // Structural identity only: animated pixels must not invalidate every action, but UID/windows must match.
        return new Frame(png,at,before,app.uid());
    }
    public record Observation(JSONObject result,String fingerprint){}
    private JSONObject image(Frame f)throws JSONException{return new JSONObject().put("mimeType","image/png").put("base64",android.util.Base64.encodeToString(f.png(),android.util.Base64.NO_WRAP)).put("width",W).put("height",H).put("capturedAt",f.at());}
    private int verifiedUid=-1;
    /** Structural identity only (no pixels): same string a screenshot or tree observation carries. */
    private String structuralFingerprint()throws Exception{
        check(true,false);ManagedProfileScope.App app=target();String before=structure(app.uid());
        check(true,false);if(target().uid()!=app.uid()||!before.equals(structure(app.uid())))ActionGate.fail("STALE_OBSERVATION","Profile window changed during observation");
        verifiedUid=app.uid();return before;
    }
    /** UID bound by the latest consumed observation; input never re-chooses a target after the race. */
    public synchronized String fingerprint()throws Exception{return structuralFingerprint();}
    /** fingerprint() before a later stroke of phone_gesture, called right after guard(): the same read, full check and
     *  re-read, without the leading full check that would repeat guard()'s at once. Binds no input target: the gesture
     *  admitted its UID before the first stroke, and the structure carries that UID. */
    public synchronized String strokeFingerprint()throws Exception{
        ManagedProfileScope.App app=target();String before=structure(app.uid());
        check(true,false);if(target().uid()!=app.uid()||!before.equals(structure(app.uid())))ActionGate.fail("STALE_OBSERVATION","Profile window changed during observation");
        return before;
    }
    public synchronized String fingerprintIdle(){return generation+"|"+displayIdentity+"|"+profile.serial();}
    /** Bind the observation to the exact returned PNG, never two surrounding captures. */
    public synchronized Observation observe(boolean screenshot)throws Exception{
        guard();
        // A screenshot is about pixels; it does not invalidate the element ids of the latest phone_ui_tree.
        if(screenshot){Frame f=capture();verifiedUid=f.uid();return new Observation(image(f),f.fingerprint());}
        // Structure path: no pixels are read. Secure windows are refused by check(true,..) like screenshots.
        // Animated screens (carousels, video, countdowns) change their structure while it is being read; retry the
        // read and the stability check together for a bounded time instead of reporting a stale observation at once.
        ActionGate.Denied last=null;
        long until=SystemClock.elapsedRealtime()+2000;
        do{
            try{
                check(true,false);ManagedProfileScope.App app=target();String before=structure(app.uid());
                JSONObject result=tree.snapshot(displayId(),W,H).put("width",W).put("height",H).put("capturedAt",System.currentTimeMillis());
                check(true,false);if(target().uid()!=app.uid()||!before.equals(structure(app.uid())))ActionGate.fail("STALE_OBSERVATION","The screen is still changing; observe again");
                verifiedUid=app.uid();return new Observation(result,before);
            }catch(ActionGate.Denied moving){
                if(!moving.code.equals("STALE_OBSERVATION"))throw moving;
                last=moving;SystemClock.sleep(120);
            }
        }while(SystemClock.elapsedRealtime()<until);
        throw last;
    }
    /** phone_screenshot with a region: the same capture, checks and binding as observe(true), of that region enlarged
     *  with rulers (ScreenZoom). width/height stay the screen's, so coordinates read from the rulers are screen pixels. */
    public synchronized Observation zoom(int left,int top,int right,int bottom,int scale)throws Exception{
        ScreenZoom zoom=new ScreenZoom(left,top,right,bottom,scale,W,H);
        guard();Frame f=capture(zoom);verifiedUid=f.uid();
        JSONObject result=image(f).put("region",new JSONArray().put(left).put(top).put(right).put(bottom)).put("scale",scale)
            .put("margin",ScreenZoom.MARGIN).put("imageWidth",zoom.imageWidth()).put("imageHeight",zoom.imageHeight());
        return new Observation(result,f.fingerprint());
    }
    /** Called after gate.observe: element ids are valid only for this ui_tree observation. */
    public synchronized void bindTree(String observationId){tree.bind(observationId);}
    public synchronized void requireTree(String observationId){tree.requireBound(observationId);}
    /** phone_batch checks against the bound tree (never a new snapshot, which would unbind the remaining steps). */
    public synchronized void verifyTarget(String id){tree.verify(id,displayId());}
    public synchronized boolean tapAllowed(String id){return tree.tapAllowed(id);}
    public synchronized boolean enterAllowed(String id){return tree.enterAllowed(id);}
    public synchronized String[] outside(String... ids)throws Exception{return tree.outside(displayId(),W,H,ids);}
    public synchronized JSONObject preview()throws Exception{return image(capture());}
    public synchronized void tap(JSONObject a,boolean longPress)throws Exception{
        float x,y;
        if(a.has("elementId")){float[] c=tree.centre(a.getString("elementId"),displayId());x=c[0];y=c[1];}
        else{x=(float)a.getDouble("x");y=(float)a.getDouble("y");}
        touch(x,y,x,y,longPress?a.optInt("durationMs",650):80);
    }
    public synchronized void swipe(JSONObject a)throws Exception{touch((float)a.getDouble("x1"),(float)a.getDouble("y1"),(float)a.getDouble("x2"),(float)a.getDouble("y2"),a.optInt("durationMs",400));}
    private void touch(float x1,float y1,float x2,float y2,int duration)throws Exception{
        int uid=verifiedUid;verifiedUid=-1;
        if(uid<0)uid=admit();
        gesture(uid,x1,y1,x2,y2,duration);
    }
    /** Re-admit the foreground app when the pixel observation was already used (element actions are bound to the
     *  tree observation instead, so several taps may follow one phone_ui_tree). The guard runs exactly as before. */
    private int admit()throws Exception{guard();return targetWait().uid();}
    /** One guarded gesture bound to an already admitted UID (the observation binding is consumed by the caller). */
    private void gesture(int uid,float x1,float y1,float x2,float y2,int duration)throws Exception{
        int id=displayId();
        guard();if(targetWait().uid()!=uid)ActionGate.fail("APP_DENIED","Foreground app changed after observation");
        if(!Float.isFinite(x1)||!Float.isFinite(x2)||!Float.isFinite(y1)||!Float.isFinite(y2)||x1<0||x2<0||y1<0||y2<0||x1>=W||x2>=W||y1>=H||y2>=H||duration<40||duration>2000)ActionGate.fail("INVALID_GESTURE","Gesture bounds invalid");
        long down=SystemClock.uptimeMillis();boolean complete=false;
        try{
            // An injection timeout can occur after delivery. Every attempted DOWN is covered by CANCEL.
            ProfileInput.motion(id,profile.userId(),uid,down,down,MotionEvent.ACTION_DOWN,x1,y1);
            while(SystemClock.uptimeMillis()-down<duration){SystemClock.sleep(16);guard();if(targetWait().uid()!=uid)ActionGate.fail("APP_DENIED","Input target changed");float f=Math.min(1f,(SystemClock.uptimeMillis()-down)/(float)duration);ProfileInput.motion(id,profile.userId(),uid,down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x1+(x2-x1)*f,y1+(y2-y1)*f);}
            guard();if(targetWait().uid()!=uid)ActionGate.fail("APP_DENIED","Input target changed");
            ProfileInput.motion(id,profile.userId(),uid,down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x2,y2);complete=true;
        }finally{if(!complete)ProfileInput.motion(id,profile.userId(),uid,down,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,x2,y2);}
    }
    /** Target of a phone_gesture, admitted once for all of its strokes (consumes the observation binding like a swipe). */
    public synchronized int gestureTarget()throws Exception{int uid=verifiedUid;verifiedUid=-1;return uid<0?admit():uid;}
    /** One stroke of phone_gesture: one or two fingers, each along its own polyline (flat x,y pairs, see {@link StrokePath}),
     *  bound to the admitted UID. The fingers go down on their first points (DOWN, then POINTER_DOWN), rest holdStartMs,
     *  follow their lines together for durationMs (each at its own constant speed; when no finger moves they are pressed
     *  for durationMs), rest holdEndMs and lift on their last points in reverse order (POINTER_UP, then UP). Every frame
     *  checks {@code interrupted} (the user took over the screen) and cancellation; the display and the target UID are
     *  re-checked at least every CHECK_MS and less than CHECK_MS before the last event, counted from {@code checked}:
     *  the uptime when the caller's own full check ended (fingerprint before the stroke), 0 if none. So the first event
     *  follows a full check made at most CHECK_MS earlier without repeating one that just ran (see strokeCheck). The
     *  check that covers the lift runs before the DOWN or before the last MOVE (StrokePath.checkDue), never between the
     *  last MOVE and the UP, so a fling keeps its velocity and a press lasts as declared. Any failure cancels the touch. */
    public synchronized void stroke(int uid,int[][] fingers,int durationMs,int holdStartMs,int holdEndMs,java.util.function.BooleanSupplier interrupted,long checked)throws Exception{
        int id=displayId(),user=profile.userId(),n=fingers.length;
        StrokePath[] paths=new StrokePath[n];float[] x=new float[n],y=new float[n];boolean moves=false;
        for(int p=0;p<n;p++){
            int[] points=fingers[p];
            for(int i=0;i<points.length;i+=2)if(points[i]<0||points[i]>=W||points[i+1]<0||points[i+1]>=H)ActionGate.fail("INVALID_GESTURE","Gesture bounds invalid");
            paths[p]=new StrokePath(points);x[p]=paths[p].firstX();y[p]=paths[p].firstY();moves|=paths[p].length()>0;
        }
        checked=strokeCheck(uid,interrupted,checked,SystemClock.uptimeMillis()+holdStartMs+durationMs+holdEndMs,false);
        long down=SystemClock.uptimeMillis();int touching=0;boolean complete=false;
        try{
            // An injection timeout can occur after delivery: every attempted DOWN is covered by the CANCEL below.
            for(int p=0;p<n;p++){touching=p+1;ProfileInput.fingers(id,user,uid,down,p==0?MotionEvent.ACTION_DOWN:pointer(MotionEvent.ACTION_POINTER_DOWN,p),new long[]{down},new float[][]{x},new float[][]{y},p+1);}
            checked=rest(uid,holdStartMs,durationMs+holdEndMs,interrupted,checked);
            if(!moves)checked=rest(uid,durationMs,holdEndMs,interrupted,checked);
            else{
                long start=SystemClock.uptimeMillis(),sent=start,end=start+durationMs;double done=0,step=StrokePath.SAMPLE_MS/(double)durationMs;
                // A check that runs now, before the first frame, does not delay the movement: its samples follow on time.
                checked=strokeCheck(uid,interrupted,checked,end+holdEndMs,true);
                while(done<1){
                    // The last frame ends on the movement's end, so the UP follows its last sample at once.
                    SystemClock.sleep(Math.max(0,Math.min(16,end-SystemClock.uptimeMillis())));checked=strokeCheck(uid,interrupted,checked,end+holdEndMs,true);
                    long now=SystemClock.uptimeMillis();double f=Math.min(1,(now-start)/(double)durationMs);
                    double[] fractions=StrokePath.frame(paths,done,f,step);done=f;
                    if(fractions.length==0)continue;
                    int m=fractions.length;long[] times=new long[m];float[][] xs=new float[m][n],ys=new float[m][n];
                    for(int k=0;k<m;k++){
                        times[k]=sent=Math.max(sent,Math.min(now,start+Math.round(fractions[k]*durationMs)));
                        for(int p=0;p<n;p++){StrokePath.Sample s=paths[p].point(fractions[k]);xs[k][p]=s.x();ys[k][p]=s.y();}
                    }
                    ProfileInput.fingers(id,user,uid,down,MotionEvent.ACTION_MOVE,times,xs,ys,n);x=xs[m-1];y=ys[m-1];
                }
            }
            checked=rest(uid,holdEndMs,0,interrupted,checked);
            // The lift is now: a full check only if the last one is CHECK_MS old (normally it already covers the lift).
            strokeCheck(uid,interrupted,checked,SystemClock.uptimeMillis(),true);
            for(int p=n-1;p>=0;p--){ProfileInput.fingers(id,user,uid,down,p==0?MotionEvent.ACTION_UP:pointer(MotionEvent.ACTION_POINTER_UP,p),new long[]{SystemClock.uptimeMillis()},new float[][]{x},new float[][]{y},p+1);touching=p;}
            complete=true;
        }finally{if(!complete&&touching>0)ProfileInput.fingers(id,user,uid,down,MotionEvent.ACTION_CANCEL,new long[]{SystemClock.uptimeMillis()},new float[][]{x},new float[][]{y},touching);}
    }
    private static int pointer(int action,int index){return action|(index<<MotionEvent.ACTION_POINTER_INDEX_SHIFT);}
    /** Keeps a stroke's fingers still for {@code ms}, checking every frame; the fingers lift {@code after} ms later.
     *  Returns when the full check last ran. */
    private long rest(int uid,int ms,int after,java.util.function.BooleanSupplier interrupted,long checked)throws Exception{
        long until=SystemClock.uptimeMillis()+ms;
        for(long left=ms;left>0;left=until-SystemClock.uptimeMillis()){SystemClock.sleep(Math.min(16,left));checked=strokeCheck(uid,interrupted,checked,until+after,true);}
        return checked;
    }
    /** The per-frame check of a stroke. A takeover and a pause or stop are seen within one frame. The full ownership check
     *  (display, foreign tasks, main-screen protection, foreground UID) takes about 90 ms, so it runs only when CHECK_MS
     *  have passed since {@code checked} (0 forces it) or the lift at {@code lift} needs it now (StrokePath.checkDue,
     *  {@code down}: the fingers are down), again at once if the one that just ran still leaves the lift uncovered; in
     *  between every event is still UID-targeted and display-bound by the input dispatcher. Returns when the full check
     *  last ran. */
    private long strokeCheck(int uid,java.util.function.BooleanSupplier interrupted,long checked,long lift,boolean down)throws Exception{
        for(;;){
            if(interrupted.getAsBoolean())ActionGate.fail("USER_CONTROL","The user took over the screen");
            if(cancellation.cancelled())ActionGate.fail("CONTROL_INTERRUPTED","User requested pause or stop");
            if(!StrokePath.checkDue(SystemClock.uptimeMillis(),checked,lift,down))return checked;
            guard();if(targetWait().uid()!=uid)ActionGate.fail("APP_DENIED","Input target changed");
            checked=SystemClock.uptimeMillis();
        }
    }
    /** Scroll gesture with feedback: the reply says whether the visible content really moved, so a silent no-op
     *  (wrong point, end of page, non-scrollable area) is never reported as a plain success. Without x/y the centre
     *  of the largest scrollable region of the latest ui_tree is used. */
    public synchronized JSONObject scroll(JSONObject a)throws Exception{
        int delta=a.getInt("deltaY");
        int x,y;
        if(a.has("x")&&a.has("y")){x=a.getInt("x");y=a.getInt("y");}
        else{android.graphics.Rect r=tree.largestScrollable();if(r==null)ActionGate.fail("SCROLL_UNSUPPORTED","No scrollable region in the latest phone_ui_tree; pass x and y");x=r.centerX();y=r.centerY();}
        int travel=Math.max(-800,Math.min(800,delta));
        // Keep the whole stroke on screen: start further down for a downward page, further up for an upward one.
        int start=y,finish=y-travel;
        if(finish<16){start+=16-finish;finish=16;}
        if(finish>H-17){start-=finish-(H-17);finish=H-17;}
        start=Math.max(16,Math.min(H-17,start));
        if(finish==start)ActionGate.fail("SCROLL_UNSUPPORTED","Choose a point with room for the scroll gesture");
        int uid=verifiedUid;verifiedUid=-1;
        if(uid<0)uid=admit();
        String before=tree.contentSignature(displayId(),W,H);
        gesture(uid,x,start,x,finish,400);
        JSONObject result=new JSONObject();
        boolean moved=settledSignature(before)!=null;
        result.put("moved",moved);
        if(!moved)result.put("note","The visible content did not change: this point is not scrollable or the page is already at its end. Use phone_ui_tree (scrollable:true nodes carry flag s) or omit x and y.");
        return result;
    }
    /** Waits for the scroll fling to settle; returns the new signature, or null when it equals {@code before}. */
    private String settledSignature(String before)throws Exception{
        String last=before;
        for(int i=0;i<6;i++){
            SystemClock.sleep(i==0?300:120);
            String now=tree.contentSignature(displayId(),W,H);
            if(i>0&&now.equals(last))return now.equals(before)?null:now;
            last=now;
        }
        return last.equals(before)?null:last;
    }
    /** Signature of the latest phone_ui_tree snapshot, the screen the model last read; empty when none is bound. */
    public synchronized String lastTreeSignature(){return tree.lastSignature();}
    /** After an input, waits until the visible structure stops changing, in the rhythm of the scroll feedback: two
     *  equal content signatures 120 ms apart, the first read 300 ms after the input, at most six reads. A screen with
     *  no labelled node yet (the blank gap between two activities) never counts as settled. false when it was still
     *  changing at the last read (loading, animation, live content) or the turn was cancelled meanwhile. */
    public synchronized boolean settle(){
        String last=null;
        for(int i=0;i<6;i++){
            SystemClock.sleep(i==0?300:120);
            if(cancellation.cancelled())return false;
            String now;
            try{now=tree.contentSignature(displayId(),W,H);}catch(Exception moving){last=null;continue;}
            if(now.equals(last)&&!now.equals(ProfileStructure.NO_LABELS))return true;
            last=now;
        }
        return false;
    }
    /** Reads a scrollable page for the model: scrolls the largest scrollable region downward up to maxPages times and
     *  returns the de-duplicated visible text in reading order. One round trip instead of ui_tree+scroll per screen. */
    public synchronized JSONObject readPage(JSONObject a)throws Exception{
        int maxPages=a.optInt("maxPages",4);
        int uid=verifiedUid;verifiedUid=-1;
        if(uid<0)uid=admit();
        android.graphics.Rect area=tree.largestScrollable();
        if(area==null)area=new android.graphics.Rect(0,H/6,W,H-H/6);
        int x=area.centerX(),mid=area.centerY(),travel=Math.max(200,Math.min(800,area.height()*3/4));
        int from=Math.min(H-17,mid+travel/2),to=Math.max(16,from-travel);
        StringBuilder text=new StringBuilder();
        java.util.HashSet<String> previous=new java.util.HashSet<>(),seen=new java.util.HashSet<>();
        int pages=0;boolean end=false;
        String signature=tree.contentSignature(displayId(),W,H);
        for(int page=0;page<maxPages;page++){
            java.util.List<String> lines=tree.visibleLines(displayId(),W,H);
            java.util.HashSet<String> current=new java.util.HashSet<>(lines);
            // A page seam repeats the last lines of the previous page; only short labels may legitimately repeat.
            for(String line:lines)if(!previous.contains(line)&&(!seen.contains(line)||line.length()<=8)){if(text.length()>0)text.append('\n');text.append(line);seen.add(line);}
            previous=current;pages++;
            if(text.length()>60000){text.setLength(60000);break;}
            if(page==maxPages-1)break;
            gesture(uid,x,from,x,to,400);
            String next=settledSignature(signature);
            if(next==null){end=true;break;}
            signature=next;
        }
        guard();
        return new JSONObject().put("text",text.toString()).put("pages",pages).put("reachedEnd",end).put("title",tree.title());
    }
    /** Consumes the observation-bound UID exactly like a gesture; input never re-chooses its target. */
    private int boundUid()throws Exception{
        int uid=verifiedUid;verifiedUid=-1;
        if(uid<0)return admit();
        guard();if(targetWait().uid()!=uid)ActionGate.fail("APP_DENIED","Foreground app changed after observation");
        return uid;
    }
    /** Literal text, delivered as key events bound to the frozen work-profile UID on the owned display.
     * No clipboard, no accessibility, no shared/personal IME (display IME policy stays HIDE).
     * Every character is validated before the first event, so text is all-or-nothing in scope. */
    public synchronized void type(JSONObject a)throws Exception{
        String text=a.getString("text");
        if(a.has("elementId")){
            // Semantic REPLACE of the whole field (any language). Password fields are refused by ProfileStructure.
            if(text.indexOf('\u0000')>=0)ActionGate.fail("TEXT_UNSUPPORTED","Text contains an invalid character");
            int uid=boundUid();tree.setText(a.getString("elementId"),displayId(),text);guard();return;
        }
        if(text.isEmpty())return;
        ArrayList<KeyEvent[]> units=new ArrayList<>();
        for(int i=0;i<text.length();){
            int cp=text.codePointAt(i);i+=Character.charCount(cp);
            if(Character.isISOControl(cp))ActionGate.fail("TEXT_UNSUPPORTED","Control characters are not text; use phone_key ENTER or BACK");
            KeyEvent[] events=ProfileInput.events(new String(Character.toChars(cp)));
            if(events==null)ActionGate.fail("TEXT_UNSUPPORTED","This character needs an input method; profile input supports printable keyboard characters only");
            units.add(events);
        }
        int uid=boundUid(),id=displayId(),count=0;
        for(KeyEvent[] unit:units){
            if((count++&7)==0){guard();if(targetWait().uid()!=uid)ActionGate.fail("APP_DENIED","Input target changed");}
            ProfileInput.character(id,profile.userId(),uid,unit);
        }
        guard();
    }
    public synchronized void key(String key)throws Exception{
        int code=key.equals("BACK")?KeyEvent.KEYCODE_BACK:key.equals("ENTER")?KeyEvent.KEYCODE_ENTER:-1;
        if(code<0)ActionGate.fail("KEY_DENIED","Only display-local BACK and ENTER are permitted");
        int uid=boundUid();
        ProfileInput.key(displayId(),profile.userId(),uid,code);
    }
    /** Human-readable label of a work-profile package, falling back to the package name. */
    public String appLabel(String pkg){
        try{PackageManager pm=profile.context().getPackageManager();return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg,0)));}
        catch(Exception e){return pkg;}
    }
    public JSONArray availableApps(Set<String> ignored)throws Exception{
        JSONArray out=new JSONArray();PackageManager pm=profile.context().getPackageManager();
        for(ManagedProfileScope.App a:profile.launcherApps())out.put(new JSONObject().put("packageName",a.packageName()).put("label",String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(a.packageName(),0)))).put("approved",true));
        return out;
    }
    // ---- user takeover (human input, still display- and UID-bound) and live view ----
    /** Human takeover checks: ownership and identity only (a user may be entering a secret on a protected window). */
    public synchronized void guardUser(){
        long until=SystemClock.elapsedRealtime()+3000;
        while(true){
            try{check(false,false);return;}
            catch(ActionGate.Denied e){
                boolean transientGap=(e.code.equals("APP_DENIED")||e.code.equals("APP_UNKNOWN")||e.code.equals("SECURITY_UNAVAILABLE"))&&!"Foreign task on profile display".equals(e.getMessage());
                if(!transientGap||SystemClock.elapsedRealtime()>=until)throw e;
                SystemClock.sleep(50);
            }
        }
    }
    /** One touch event of the human; DOWN freezes the target UID for the whole gesture. */
    private static final long USER_RECHECK_MS=250;
    private long userChecked;
    public synchronized void userTouch(String action,float x,float y)throws Exception{
        if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||y<0||x>=W||y>=H)ActionGate.fail("INVALID_GESTURE","Gesture bounds invalid");
        int id=displayId(),user=profile.userId();
        int type;
        switch(action){case "down":type=MotionEvent.ACTION_DOWN;break;case "move":type=MotionEvent.ACTION_MOVE;break;case "up":type=MotionEvent.ACTION_UP;break;case "cancel":type=MotionEvent.ACTION_CANCEL;break;default:ActionGate.fail("ARGUMENTS_INVALID","Unknown touch action");return;}
        long now=SystemClock.uptimeMillis();
        if(type==MotionEvent.ACTION_DOWN){
            if(userUid>=0){try{ProfileInput.userMotion(id,user,userUid,userDown,now,MotionEvent.ACTION_CANCEL,userX,userY);}catch(Exception ignored){}userUid=-1;}
            guardUser();userUid=targetWait().uid();userDown=now;userX=x;userY=y;userChecked=now;
            try{ProfileInput.userMotion(id,user,userUid,userDown,now,type,x,y);}catch(Exception e){userUid=-1;throw e;}
            return;
        }
        if(userUid<0){if(type==MotionEvent.ACTION_CANCEL)return;ActionGate.fail("INVALID_GESTURE","Touch gesture has not started");}
        int uid=userUid;userX=x;userY=y;
        boolean last=type==MotionEvent.ACTION_UP||type==MotionEvent.ACTION_CANCEL;
        try{
            // The frozen-UID recheck is a window-snapshot + task binder query (~20 ms); at touch rate it throttled the finger,
            // so a gesture re-verifies its target at most every USER_RECHECK_MS (the DOWN already admitted it).
            if(!last&&now-userChecked>=USER_RECHECK_MS){userChecked=now;if(targetWait().uid()!=uid){type=MotionEvent.ACTION_CANCEL;last=true;ProfileInput.userMotion(id,user,uid,userDown,now,type,x,y);ActionGate.fail("APP_DENIED","Input target changed during the gesture");}}
            ProfileInput.userMotion(id,user,uid,userDown,now,type,x,y);
        }finally{if(last)userUid=-1;}
    }
    public synchronized void userKey(String key)throws Exception{
        // HOME is not a key event on a virtual display (it would address the main screen): the human HOME opens the same
        // profile desktop that the AI HOME key opens.
        if(key.equals("HOME")){home();return;}
        int code=key.equals("BACK")?KeyEvent.KEYCODE_BACK:key.equals("ENTER")?KeyEvent.KEYCODE_ENTER:key.equals("DEL")?KeyEvent.KEYCODE_DEL:-1;
        if(code<0)ActionGate.fail("KEY_DENIED","Only BACK, ENTER, DEL and HOME are permitted");
        guardUser();ProfileInput.userKey(displayId(),profile.userId(),targetWait().uid(),code);
    }
    /** Human-typed text: key events when every character has a key mapping, otherwise a semantic append to the focused field. */
    public synchronized void userText(String text)throws Exception{
        if(text==null||text.isEmpty()||text.length()>200)ActionGate.fail("ARGUMENTS_INVALID","Text length must be 1..200");
        ArrayList<KeyEvent[]> units=new ArrayList<>();boolean mappable=true;
        for(int i=0;i<text.length();){
            int cp=text.codePointAt(i);i+=Character.charCount(cp);
            if(Character.isISOControl(cp))ActionGate.fail("TEXT_UNSUPPORTED","Control characters are not text");
            KeyEvent[] events=ProfileInput.events(new String(Character.toChars(cp)));
            if(events==null){mappable=false;break;}
            units.add(events);
        }
        guardUser();int uid=targetWait().uid();
        if(mappable){for(KeyEvent[] unit:units)ProfileInput.character(displayId(),profile.userId(),uid,unit);}
        else tree.appendFocused(displayId(),text);
    }
    /** Release any half-finished human gesture (takeover hand-back, expiry, stop). */
    public synchronized void userRelease(){
        if(userUid>=0&&displayId()>0){try{ProfileInput.userMotion(displayId(),profile.userId(),userUid,userDown,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,userX,userY);}catch(Exception ignored){}}
        userUid=-1;
    }
    /** The first viewer starts the hardware encoder and points the display at its input Surface; the ImageReader
     *  stays the idle sink. No viewer = no encoder = no extra GPU/CPU work. */
    public void viewerAttach()throws Exception{
        LiveStream stream=new LiveStream(W,H,FPS);
        synchronized(this){
            VirtualDisplay d=display;if(d==null)throw new IllegalStateException("No display");
            viewers++;
            try{d.setSurface(stream.start());live=stream;}
            catch(Exception e){viewers--;stream.close();throw e;}
        }
    }
    public synchronized void viewerDetach(){
        if(viewers>0)viewers--;
        LiveStream stream=live;live=null;
        VirtualDisplay d=display;ImageReader r=reader;
        if(d!=null&&r!=null){try{d.setSurface(r.getSurface());}catch(Exception ignored){}}
        if(stream!=null)stream.close();
    }
    public LiveStream liveStream(){return live;}
    public static int width(){return W;}
    public static int height(){return H;}    /** Seed for a new viewer on a static screen: UID-filtered capture, PNG (the client decodes either format). */
    public synchronized byte[] initialFrame(){
        try{check(false,false);ManagedProfileScope.App app=targetWait();return OwnedDisplayCapture.profilePng(displayId(),profile.userId(),app.uid(),W,H);}catch(Exception e){return null;}
    }
    public long displayGeneration(){return generation;}
    /** Housekeeping for the personal user: launcher entries stay disabled. Task records of the profile are removed
     *  only while no owned display exists; a standing display keeps its content until the model clears it. */
    public synchronized void housekeeping(){
        if(displayId()>0)return; // a standing display keeps whatever is on it; never sweep its tasks or launcher state
        launchers.hideRevealed();
        launchers.reconcile();
        try{
            Object atm=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            Object slice=atm.getClass().getMethod("getRecentTasks",int.class,int.class,int.class).invoke(atm,50,1|2,profile.userId());
            List<?> rows=(List<?>)slice.getClass().getMethod("getList").invoke(slice);
            for(Object row:rows){
                // getRecentTasks returns the personal user AND its profiles: only ever remove tasks of the verified work profile.
                if(row.getClass().getField("userId").getInt(row)!=profile.userId())continue;
                int id=row.getClass().getField("persistentId").getInt(row);if(id<=0)continue;
                atm.getClass().getMethod("removeTask",int.class).invoke(atm,id);
            }
        }catch(Exception e){System.err.println("profile recents cleanup failed: "+(e instanceof InvocationTargetException&&e.getCause()!=null?e.getCause():e));}
    }
    /** Fast path from the watchdog: hide the launcher entry of every app that appeared in the work profile since the
     * last sweep. Runs at 1 Hz and while an owned display is open, so an install into the profile has no window in
     * which the personal launcher can put an icon of it on the home screen. A package change of the profile since the
     * previous call (also one the install watch does not see) hides again the entries we hid of that package that are
     * visible again (an app uninstalled from the profile and installed back) and closes background pop-ups at once
     * instead of at the next drift repair. Takes only the launcher-hider and pop-up gate locks. */
    public void hideNewInstalls(){sweepInstalls(false);}
    /** {@link #hideNewInstalls}, from the watchdog and the install watch. {@code installed}: an install session of the
     *  profile just finished, so background pop-ups are closed even when the change read fails. */
    private void sweepInstalls(boolean installed){
        try{launchers.hideUnseen();}catch(Exception e){System.err.println("launcher install sweep failed: "+e.getClass().getSimpleName());}
        List<String> changed=changedPackages();
        if(changed!=null)launchers.hideVisibleAgain(changed);
        if(installed||changed!=null)closeBackgroundPopups();
    }
    private final InstallWatch.Changes changes=new InstallWatch.Changes();
    private volatile String changesFailure="";
    /** The packages of the profile that changed since the previous read, null when none ({@link InstallWatch.Changes}).
     *  Read after the launcher sweep, so an entry it just hid is part of this read and not a change at the next one. */
    private List<String> changedPackages(){
        try{
            ChangedPackages changed=profile.context().getPackageManager().getChangedPackages(changes.from());
            changesFailure="";
            if(!changes.read(changed==null?null:changed.getSequenceNumber()))return null;
            List<String> names=changed.getPackageNames();
            System.err.println("install watch: work-profile packages changed: "+names);
            return names;
        }catch(Exception e){
            String why=e.getClass().getSimpleName()+": "+e.getMessage();
            // Logged once per distinct failure: this runs every second.
            if(!why.equals(changesFailure)){changesFailure=why;System.err.println("work-profile package change read failed: "+why);}
            return null;
        }
    }
    /** Sweep the work profile as soon as an install into it completes (rule: {@link InstallWatch}), instead of waiting for
     * the next sweep. Best effort: the sweep is the guarantee. The root broker (UID 0, no app process) cannot use the
     * usual package listeners on Android 16: LauncherApps.registerCallback requires the UID of the calling package to be
     * the caller's and no package has UID 0 (the system context's "android" is UID 1000: "Calling package name
     * mismatch"); Context.registerReceiver registers nothing for a caller without an app record; package-monitor
     * callbacks reach only the UIDs on a package's visibility allow list, which holds application UIDs only. A
     * PackageInstaller session callback needs just the cross-user right and package visibility, both granted to root.
     * Not seen, so left to the change read of {@link #hideNewInstalls} within a second: an install without a session
     * (install-existing into the profile) and an update committed by a session of the personal user. */
    private void watchInstalls(){
        try{
            PackageInstaller installer=profile.context().getPackageManager().getPackageInstaller();
            installs=new PackageInstaller.SessionCallback(){
                @Override public void onCreated(int session){installEvent(InstallWatch.Event.CREATED,session);}
                @Override public void onBadgingChanged(int session){installEvent(InstallWatch.Event.BADGING_CHANGED,session);}
                @Override public void onActiveChanged(int session,boolean active){installEvent(InstallWatch.Event.ACTIVE_CHANGED,session);}
                @Override public void onProgressChanged(int session,float progress){installEvent(InstallWatch.Event.PROGRESS_CHANGED,session);}
                @Override public void onFinished(int session,boolean success){installEvent(InstallWatch.finished(success),session);}
            };
            installer.registerSessionCallback(installs,drainHandler);
            System.err.println("work-profile install watch: package-installer sessions of user "+profile.userId());
        }catch(Exception e){System.err.println("work-profile install watch unavailable: "+e.getClass().getSimpleName()+": "+e.getMessage());}
    }
    private void installEvent(InstallWatch.Event event,int session){
        if(!InstallWatch.sweeps(event))return;
        System.err.println("install watch: work-profile session "+session+" installed");
        // The change read names the installed package, so an entry of an app installed back is hidden again at once too;
        // an app just installed into the profile gets no background pop-up right either.
        sweepInstalls(true);
    }
    /** Closes the display only if it still belongs to `execution` (an ordered async close never hits a successor). */
    public synchronized void closeDisplay(String execution){if(execution.equals(displayExecution))closeDisplay();}
    public synchronized void closeDisplay(){displayExecution="";userUid=-1;unlockedDisplay=false;LiveStream stream=live;live=null;if(stream!=null)stream.close();tree.disconnect();cancellation.end();generation++;displayIdentity=null;launched.clear();try{if(display!=null)display.release();}finally{display=null;if(reader!=null)reader.close();reader=null;}try{launchers.hideRevealed();launchers.hideUnseen();}catch(Exception hideFailure){}}
    @Override public synchronized void close(){closeDisplay();tree.close();windows.close();protection.close();secureSetting.close();drain.quitSafely();}
}
