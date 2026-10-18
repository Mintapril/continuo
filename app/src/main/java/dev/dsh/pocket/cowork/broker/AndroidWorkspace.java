package dev.dsh.pocket.cowork.broker;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.util.SparseArray;
import android.view.*;
import android.view.accessibility.*;
import org.json.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Android-only driver. No command interpreter, Intent strings, clipboard or default-display input. */
public final class AndroidWorkspace implements AutoCloseable {
    public static final int WIDTH=720, HEIGHT=1280, DPI=240;
    private final Context context;
    private final UiAutomation automation;
    private final ActionGate gate;
    private final HandlerThread thread;
    private volatile VirtualDisplay display;
    private String displayIdentity;
    private VirtualDisplay pinnedDisplay;
    private long displayGeneration;
    private ImageReader reader;
    private final Map<String, AccessibilityNodeInfo> nodes=new HashMap<>();
    private String baselineMain;
    private final ExecutionCancellation cancellation=new ExecutionCancellation();
    public void requestCancellation(String execution){cancellation.request(execution);}
    public void clearCancellation(String execution){cancellation.clear(execution);}
    private final java.util.concurrent.atomic.AtomicLong screenEpoch=new java.util.concurrent.atomic.AtomicLong();
    private final SecureWindowProbe protection;
    private final SecureSettingProbe secureSetting=new SecureSettingProbe();
    private record CapturedFrame(byte[] png,long wallTime,String fingerprint) {}
    public AndroidWorkspace(ActionGate gate) throws Exception {
        this.gate=gate;protection=new SecureWindowProbe();
        Class<?> at=Class.forName("android.app.ActivityThread");
        Object activityThread=at.getMethod("systemMain").invoke(null);
        context=(Context)at.getMethod("getSystemContext").invoke(activityThread);
        thread=new HandlerThread("cowork-accessibility"); thread.start();
        Object connection=Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
        Constructor<UiAutomation> constructor=UiAutomation.class.getConstructor(Looper.class, Class.forName("android.app.IUiAutomationConnection"));
        automation=constructor.newInstance(thread.getLooper(),connection);
        UiAutomation.class.getMethod("connect",int.class).invoke(automation,UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfoCompat.configure(automation);
        automation.setOnAccessibilityEventListener(event -> { if (event.getEventType()!=AccessibilityEvent.TYPE_VIEW_HOVER_ENTER && event.getEventType()!=AccessibilityEvent.TYPE_VIEW_HOVER_EXIT) { for(AccessibilityWindowInfo w:windows()) if(w.getId()==event.getWindowId()) { screenEpoch.incrementAndGet();break; } } });
    }
    private static final class AccessibilityServiceInfoCompat {
        static void configure(UiAutomation a) {
            android.accessibilityservice.AccessibilityServiceInfo info=a.getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.eventTypes=AccessibilityEvent.TYPES_ALL_MASK; a.setServiceInfo(info);
        }
    }
    public Context context() { return context; }
    public int displayId() { VirtualDisplay current=display;return current==null ? -1 : current.getDisplay().getDisplayId(); }
    private String currentDisplayIdentity()throws Exception{
        VirtualDisplay current=display;if(current==null)throw new IllegalStateException();
        Class<?> infoType=Class.forName("android.view.DisplayInfo");Object info=infoType.getConstructor().newInstance();
        boolean valid=(Boolean)Display.class.getMethod("getDisplayInfo",infoType).invoke(current.getDisplay(),info);
        int id=infoType.getField("displayId").getInt(info),type=infoType.getField("type").getInt(info),flags=infoType.getField("flags").getInt(info);
        String unique=(String)infoType.getField("uniqueId").get(info);
        return OwnedDisplayIdentity.require(valid,id,current.getDisplay().getDisplayId(),type,flags,unique,
            infoType.getField("ownerUid").getInt(info),(String)infoType.getField("ownerPackageName").get(info));
    }
    private void requireDisplayIdentity(){String stage="lifetime";try{
        if(display==null||display!=pinnedDisplay||displayIdentity==null)throw new IllegalStateException();
        stage="identity";if(!displayIdentity.equals(currentDisplayIdentity()))throw new IllegalStateException();
        stage="secure-setting";secureSetting.requireSafe();
    }catch(Exception e){System.err.println("Cowork identity protection: "+stage+" "+e.getClass().getName());
        for(StackTraceElement location:e.getStackTrace())System.err.println(" at "+location);
        nodes.clear();ActionGate.fail("SECURITY_UNAVAILABLE","Owned display identity or protection changed");}}
    private void requireProtection(boolean content){
        VirtualDisplay current=display;long generation=displayGeneration;
        requireDisplayIdentity();try{
            protection.requireSafe(displayId(),content);requireDisplayIdentity();
            if(display!=current||displayGeneration!=generation)ActionGate.fail("SECURITY_UNAVAILABLE","Owned display lifetime changed");
        }catch(ActionGate.Denied e){nodes.clear();throw e;}
    }
    public synchronized boolean locked() { return context.getSystemService(KeyguardManager.class).isDeviceLocked(); }
    public synchronized String mainPackage() {
        // Accessibility isActive/isFocused can move globally to the virtual display.
        // Query authoritative task ordering, filter physical display before selecting top.
        try {
            for(ActivityManager.RunningTaskInfo task:context.getSystemService(ActivityManager.class).getRunningTasks(100)) {
                if(task.getClass().getField("displayId").getInt(task)==Display.DEFAULT_DISPLAY
                    &&task.getClass().getField("isVisible").getBoolean(task)&&task.topActivity!=null)
                    return task.topActivity.getPackageName();
            }
        } catch(Exception ignored) { /* Unknown must still fail closed. */ }
        return "";
    }
    private String mainIdentity() {
        try { for(ActivityManager.RunningTaskInfo task:context.getSystemService(ActivityManager.class).getRunningTasks(100))
            if(task.getClass().getField("displayId").getInt(task)==Display.DEFAULT_DISPLAY&&task.getClass().getField("isVisible").getBoolean(task)&&task.topActivity!=null)
                return task.taskId+"|"+task.topActivity.flattenToString();
        }catch(Exception ignored){}return "";
    }
    public synchronized void create(String execution) {
        if(display!=null) ActionGate.fail("DISPLAY_BUSY","Display already exists");
        if(locked()) ActionGate.fail("LOCKED","Unlock phone before starting");
        baselineMain=mainIdentity();cancellation.begin(execution);
        if(baselineMain.isEmpty()) ActionGate.fail("MAIN_UNKNOWN","Cannot identify main display foreground");
        reader=ImageReader.newInstance(WIDTH,HEIGHT,PixelFormat.RGBA_8888,3);
        // Drain only: never read planes/copy/encode/export consumer frames. Some OEM displays
        // report secure capability even when not requested; this is NOT a guarantee that secure
        // pixels never enter these buffers. Every exported frame uses OwnedDisplayCapture only.
        reader.setOnImageAvailableListener(source -> {
            try(Image image=source.acquireLatestImage()){}catch(Exception ignored){}
        },new Handler(thread.getLooper()));
        // Own content, touch, rotate, destroy content on removal, trusted, own focus. Never ALWAYS_UNLOCKED here (legacy owner-profile display); only the work-profile display ProfileWorkspace opts in, guarded by identity().
        int flags=1|8|64|128|256|1024|16384;
        display=context.getSystemService(DisplayManager.class).createVirtualDisplay("DSH-Cowork",WIDTH,HEIGHT,DPI,reader.getSurface(),flags);
        if(display==null || displayId()==Display.DEFAULT_DISPLAY) { closeDisplay(); ActionGate.fail("DISPLAY_FAILED","Failed to create dedicated display"); }
        String protectionStage="display-identity";
        try {pinnedDisplay=display;displayGeneration++;displayIdentity=currentDisplayIdentity();protectionStage="window-protection";
            // DisplayManager may return before WMS publishes the display branch. During this
            // bounded startup-only wait no app launch, observation or input is allowed.
            long deadline=SystemClock.elapsedRealtime()+3000;
            while(true){
                if(locked()||!baselineMain.equals(mainIdentity())||cancellation.cancelled())throw new IllegalStateException();
                requireDisplayIdentity();
                try{requireProtection(false);break;}
                catch(ActionGate.Denied e){if(!e.code.equals("SECURITY_UNAVAILABLE")||SystemClock.elapsedRealtime()>=deadline)throw e;SystemClock.sleep(50);}
            }
        }
        catch(Exception e){
            System.err.println("Cowork protection startup: "+protectionStage+" "+e.getClass().getName());
            for(StackTraceElement location:e.getStackTrace())if(location.getClassName().startsWith("dev.dsh.pocket."))System.err.println(" at "+location);
            closeDisplay();throw new ActionGate.Denied("SECURITY_UNAVAILABLE","Cannot establish owned-display protection");
        }
        try {
            Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            wm.getClass().getMethod("setDisplayImePolicy",int.class,int.class).invoke(wm,displayId(),0); // LOCAL
        } catch(Exception e) { closeDisplay(); throw new ActionGate.Denied("IME_POLICY_UNAVAILABLE","Cannot guarantee local display input method"); }
    }
    public synchronized void guard() { guard(true); }
    private void guard(boolean requireContent) {
        if(display==null || displayId()<=0) ActionGate.fail("NO_DISPLAY","No owned secondary display");
        if(locked()) ActionGate.fail("LOCKED","Device locked; user must resume");
        if(cancellation.cancelled())ActionGate.fail("CONTROL_INTERRUPTED","User pause or stop requested");
        String main=mainIdentity();
        if(main.isEmpty() || !main.equals(baselineMain)) ActionGate.fail("MAIN_CHANGED","Main display changed; user must resume");
        requireAllowedWindows(requireContent);
        String target=targetPackage();
        if(!target.isEmpty() && mainHasPackage(target)) ActionGate.fail("APP_CONFLICT","Target app also occurs on main display");
    }
    private void requireAllowedWindows(boolean requireContent) {
        requireProtection(requireContent);
        List<String> packages=new ArrayList<>();
        for(AccessibilityWindowInfo w:windows()) {
            AccessibilityNodeInfo root=w.getRoot();
            packages.add(root==null||root.getPackageName()==null?"":root.getPackageName().toString());
        }
        requireProtection(requireContent);
        WindowPolicy.requireAllowed(gate,packages,requireContent);
    }
    private boolean mainHasPackage(String pkg) {
        // Include hidden physical-display tasks; checking only visible accessibility windows
        // would miss a stopped same-package activity that Android might reuse across displays.
        try {
            for(ActivityManager.RunningTaskInfo task:context.getSystemService(ActivityManager.class).getRunningTasks(100)) {
                if(task.getClass().getField("displayId").getInt(task)!=Display.DEFAULT_DISPLAY)continue;
                if((task.topActivity!=null&&pkg.equals(task.topActivity.getPackageName()))
                    ||(task.baseActivity!=null&&pkg.equals(task.baseActivity.getPackageName())))return true;
            }
        }catch(Exception e){ActionGate.fail("MAIN_UNKNOWN","Cannot inspect physical-display tasks");}
        return false;
    }
    public synchronized String targetPackage() {
        int owned=displayId();if(owned<=0)return "";
        try{for(ActivityManager.RunningTaskInfo task:context.getSystemService(ActivityManager.class).getRunningTasks(100))
            if(task.getClass().getField("displayId").getInt(task)==owned&&task.getClass().getField("isVisible").getBoolean(task)&&task.topActivity!=null)
                return task.topActivity.getPackageName();
        }catch(Exception ignored){}return "";
    }
    private List<AccessibilityWindowInfo> windows() {
        if(displayId()<=0) return Collections.emptyList();
        return automation.getWindowsOnAllDisplays().get(displayId(),Collections.emptyList());
    }
    public synchronized void launch(String pkg) throws Exception {
        guard(false); if(mainHasPackage(pkg)) ActionGate.fail("APP_CONFLICT","App exists on main display");
        Intent intent=context.getPackageManager().getLaunchIntentForPackage(pkg);
        if(intent==null) ActionGate.fail("APP_UNAVAILABLE","No launcher activity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        ActivityOptions options=ActivityOptions.makeBasic(); options.setLaunchDisplayId(displayId());
        // An app_process system Context has no registered application thread. Do not pass
        // that synthetic thread through Instrumentation: use the trusted service identity,
        // an explicit resolved launcher Intent and the owned display, never a shell command.
        Object manager=ActivityManager.class.getMethod("getService").invoke(null);
        Method start=manager.getClass().getMethod("startActivityAsUser",
            Class.forName("android.app.IApplicationThread"),String.class,Intent.class,String.class,
            IBinder.class,String.class,int.class,int.class,Class.forName("android.app.ProfilerInfo"),Bundle.class,int.class);
        int result=(Integer)start.invoke(manager,null,"com.android.shell",intent,null,null,null,0,0,null,options.toBundle(),-2);
        if(result<0)ActionGate.fail("APP_LAUNCH_DENIED","Android rejected the display-local launch");
        // startActivity returns before the first owned accessibility window exists. Keep the
        // broker's operation lock until it appears; never expose an active but empty workspace.
        long deadline=SystemClock.elapsedRealtime()+3000;
        do {
            guard(false); // main/lock/cancellation/protection checks remain active while loading
            if(pkg.equals(targetPackage())&&!windows().isEmpty()){guard();return;}
            SystemClock.sleep(50);
        } while(SystemClock.elapsedRealtime()<deadline);
        ActionGate.fail("APP_UNKNOWN","Launched app did not establish owned display content");
    }
    public synchronized JSONObject inspect() throws Exception {
        guard(); nodes.clear(); JSONArray elements=new JSONArray(); JSONArray win=new JSONArray();
        for(AccessibilityWindowInfo w:windows()) {
            Rect bounds=new Rect(); w.getBoundsInScreen(bounds);
            win.put(new JSONObject().put("windowId",w.getId()).put("type",w.getType()).put("bounds",rect(bounds)));
            AccessibilityNodeInfo root=w.getRoot(); if(root!=null) walk(root,elements,0);
        }
        requireProtection(true);
        return new JSONObject().put("nodes",elements).put("width",WIDTH).put("height",HEIGHT);
    }
    private void walk(AccessibilityNodeInfo n, JSONArray out,int depth) throws Exception {
        if(depth>24 || out.length()>=500) return;
        if(n.isVisibleToUser()) {
            Rect b=new Rect(); n.getBoundsInScreen(b); String id="n"+out.length(); nodes.put(id,n);
            boolean secret=n.isPassword();
            // WMS snapshots cannot atomically guard arbitrary app accessibility text. Real-app
            // trees therefore export geometry/roles only; screen content uses protected capture.
            boolean exportText=WindowPolicy.mayExportText(n.getPackageName()==null?"":n.getPackageName().toString());
            out.put(new JSONObject().put("id",id).put("className",String.valueOf(n.getClassName())).put("text",secret?"[REDACTED]":exportText?bounded(n.getText()):"").put("description",!secret&&exportText?bounded(n.getContentDescription()):"").put("bounds",rect(b)).put("clickable",n.isClickable()).put("editable",n.isEditable()));
        }
        for(int i=0;i<n.getChildCount();i++) { AccessibilityNodeInfo child=n.getChild(i); if(child!=null) walk(child,out,depth+1); }
    }
    private static String bounded(CharSequence value) { if(value==null)return ""; String s=value.toString();return s.substring(0,Math.min(s.length(),300)); }
    private static JSONObject rect(Rect r) throws JSONException { return new JSONObject().put("left",r.left).put("top",r.top).put("right",r.right).put("bottom",r.bottom); }
    private String structureFingerprint() throws Exception {
        // Window metadata only; never retrieve a node before checking protection.
        JSONArray windows=new JSONArray();
        for(AccessibilityWindowInfo w:windows()) { Rect b=new Rect();w.getBoundsInScreen(b); windows.put(w.getId()+":"+w.getType()+":"+b+":"+w.isFocused()); }
        return displayGeneration+"|"+displayIdentity+"|"+targetPackage()+"|"+windows+"|"+screenEpoch.get();
    }
    public synchronized String fingerprint() throws Exception {return captureFrame().fingerprint();}
    private CapturedFrame captureFrame()throws Exception{
        checkCapturePrivacy();String before=structureFingerprint();
        byte[] png=OwnedDisplayCapture.png(displayId());long at=System.currentTimeMillis();
        checkCapturePrivacy();String after=structureFingerprint();
        if(!before.equals(after))ActionGate.fail("STALE_OBSERVATION","Display changed during capture; observe again");
        String pixels=BrokerAuth.sha256(png);
        return new CapturedFrame(png,at,after+"|"+pixels);
    }
    private AccessibilityNodeInfo node(String id) {
        AccessibilityNodeInfo n=nodes.get(id);
        if(n==null||!n.refresh()||!n.isVisibleToUser()||!n.isEnabled()) ActionGate.fail("NODE_STALE","Target node no longer available");
        if(n.isPassword()) ActionGate.fail("SECRET_INPUT","Password entry requires user takeover");
        if(n.getWindow()==null || n.getWindow().getDisplayId()!=displayId()) ActionGate.fail("WRONG_DISPLAY","Node is not in owned display");
        return n;
    }
    public synchronized void tap(JSONObject a,boolean longPress) throws Exception {
        guard(); float x,y;
        if(a.has("elementId")) { Rect r=new Rect();node(a.getString("elementId")).getBoundsInScreen(r);x=r.centerX();y=r.centerY(); }
        else { x=(float)a.getDouble("x");y=(float)a.getDouble("y"); }
        touch(x,y,x,y,longPress?a.optInt("durationMs",650):80);
    }
    public synchronized void swipe(JSONObject a) throws Exception { guard();touch((float)a.getDouble("x1"),(float)a.getDouble("y1"),(float)a.getDouble("x2"),(float)a.getDouble("y2"),a.optInt("durationMs",400)); }
    private void touch(float x1,float y1,float x2,float y2,int duration) throws Exception {
        if(!Float.isFinite(x1)||!Float.isFinite(x2)||!Float.isFinite(y1)||!Float.isFinite(y2)||x1<0||x2<0||y1<0||y2<0||x1>=WIDTH||x2>=WIDTH||y1>=HEIGHT||y2>=HEIGHT||duration<40||duration>2000) ActionGate.fail("INVALID_GESTURE","Gesture bounds/duration invalid");
        long down=SystemClock.uptimeMillis(); injectMotion(down,down,MotionEvent.ACTION_DOWN,x1,y1);
        boolean completed=false;
        try { while(SystemClock.uptimeMillis()-down<duration){SystemClock.sleep(16);guard();float fraction=Math.min(1f,(SystemClock.uptimeMillis()-down)/(float)duration);injectMotion(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,x1+(x2-x1)*fraction,y1+(y2-y1)*fraction);}guard();completed=true; }
        finally { injectMotion(down,SystemClock.uptimeMillis(),completed?MotionEvent.ACTION_UP:MotionEvent.ACTION_CANCEL,x2,y2); }
    }
    private void injectMotion(long down,long at,int action,float x,float y) throws Exception {
        MotionEvent e=MotionEvent.obtain(down,at,action,x,y,0);e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        InputEvent.class.getMethod("setDisplayId",int.class).invoke(e,displayId());
        try { if(!automation.injectInputEvent(e,true)) ActionGate.fail("INPUT_REJECTED","Input injection failed"); } finally {e.recycle();}
    }
    public synchronized void type(JSONObject a) throws Exception {
        guard();String text=a.getString("text");if(text.length()>4000)ActionGate.fail("TEXT_LIMIT","Text exceeds 4000 characters");
        AccessibilityNodeInfo n=null;
        if(a.has("elementId"))n=node(a.getString("elementId"));
        else for(Map.Entry<String,AccessibilityNodeInfo> entry:nodes.entrySet())if(entry.getValue().isFocused()&&entry.getValue().isEditable()){n=node(entry.getKey());break;}
        if(n==null||!n.isEditable())ActionGate.fail("NOT_EDITABLE","Focus an editable field in the owned display first");
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
        if(!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))ActionGate.fail("TEXT_UNSUPPORTED","App does not support semantic text input; no clipboard fallback");
    }
    public synchronized void scroll(JSONObject a) throws Exception {
        guard();int x=a.getInt("x"),y=a.getInt("y"),delta=a.getInt("deltaY");
        AccessibilityNodeInfo selected=null;int area=Integer.MAX_VALUE;
        for(Map.Entry<String,AccessibilityNodeInfo> entry:nodes.entrySet()) { AccessibilityNodeInfo n=entry.getValue();Rect b=new Rect();n.getBoundsInScreen(b);if(n.isScrollable()&&b.contains(x,y)&&b.width()*b.height()<area){selected=node(entry.getKey());area=b.width()*b.height();} }
        if(selected==null||!selected.performAction(delta>0?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))ActionGate.fail("SCROLL_UNSUPPORTED","Target cannot scroll in that direction");
    }
    public synchronized void key(String key) throws Exception {
        guard();int code=key.equals("BACK")?KeyEvent.KEYCODE_BACK:KeyEvent.KEYCODE_ENTER;
        long at=SystemClock.uptimeMillis();for(int action:new int[]{KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP}){
            KeyEvent e=new KeyEvent(at,at,action,code,0);InputEvent.class.getMethod("setDisplayId",int.class).invoke(e,displayId());
            if(!automation.injectInputEvent(e,true))ActionGate.fail("INPUT_REJECTED","Back injection failed");
        }
    }
    private void checkCapturePrivacy() {
        if(displayId()<=0)ActionGate.fail("NO_DISPLAY","No owned secondary display");
        if(locked())ActionGate.fail("LOCKED","Unlock before viewing the workspace");
        String target=targetPackage();if(!target.isEmpty()&&mainHasPackage(target))ActionGate.fail("APP_CONFLICT","Target app also occurs on main display");
        requireAllowedWindows(true);
        int[] count={0};for(AccessibilityWindowInfo w:windows()){AccessibilityNodeInfo root=w.getRoot();if(root!=null)checkPassword(root,0,count);}
        requireProtection(true);
    }
    private void checkPassword(AccessibilityNodeInfo n,int depth,int[] count) {
        if(depth>32||++count[0]>2000)ActionGate.fail("SECRET_SCREEN","Cannot establish screen privacy");
        if(n.isVisibleToUser()&&n.isPassword())ActionGate.fail("SECRET_SCREEN","Screenshot blocked for password screen");
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);if(child!=null)checkPassword(child,depth+1,count);}
    }
    public synchronized JSONObject screenshot() throws Exception {guard();return preview();}
    /** Read-only controller preview can view a paused owned screen, never rebase or issue observations. */
    public synchronized JSONObject preview() throws Exception {
        CapturedFrame frame=captureFrame();
        return new JSONObject().put("mimeType","image/png").put("base64",android.util.Base64.encodeToString(frame.png(),android.util.Base64.NO_WRAP)).put("width",WIDTH).put("height",HEIGHT).put("capturedAt",frame.wallTime());
    }
    public synchronized JSONArray availableApps(Set<String> approved) throws Exception {
        Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        TreeMap<String,String> packages=new TreeMap<>();PackageManager pm=context.getPackageManager();
        for(ResolveInfo item:pm.queryIntentActivities(intent,0)){String pkg=item.activityInfo.packageName;if(ActionGate.eligiblePackage(pkg))packages.put(pkg,String.valueOf(item.loadLabel(pm)));}
        JSONArray result=new JSONArray();for(Map.Entry<String,String> item:packages.entrySet())result.put(new JSONObject().put("packageName",item.getKey()).put("label",item.getValue()).put("approved",approved.contains(item.getKey())));
        return result;
    }
    public synchronized void closeDisplay(){ cancellation.end();nodes.clear();displayGeneration++;displayIdentity=null;pinnedDisplay=null;if(display!=null){display.release();display=null;}if(reader!=null){reader.close();reader=null;} }
    @Override public synchronized void close(){closeDisplay();try{UiAutomation.class.getMethod("disconnect").invoke(automation);}catch(Exception ignored){}protection.close();secureSetting.close();thread.quitSafely();}
}
