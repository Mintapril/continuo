package dev.dsh.pocket.cowork.broker;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.PixelFormat;
import android.hardware.display.*;
import android.media.*;
import android.os.*;
import android.view.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Operator-only, fixed networkless fixture experiment. Not reachable through broker/agent tools.
 * No input, personal pixels, profile/policy creation, arbitrary target, or persistent display.
 * Exercises the candidate flags/user routing without relaxing the production workspace guard.
 */
public final class ProfileDisplayProbeMain {
    private static final String FIXTURE="dev.dsh.pocket.fixture";
    public static void main(String[] args){
        VirtualDisplay display=null;ImageReader reader=null;WindowOwnershipProbe windows=null;HandlerThread drain=null;
        int exit=1;JSONObject report=new JSONObject();
        try{
            if(args.length!=0)throw new IllegalArgumentException("No arguments accepted");
            Looper.prepareMainLooper();Class<?> at=Class.forName("android.app.ActivityThread");
            Object thread=at.getMethod("systemMain").invoke(null);
            Context context=(Context)at.getMethod("getSystemContext").invoke(thread);
            if(context.getSystemService(KeyguardManager.class).isDeviceLocked())throw new IllegalStateException("Unlock device first");
            ProfileContext profile=new ProfileContext(context);profile.current();
            ManagedProfileScope.App app=profile.app(FIXTURE);
            PackageInfo fixture=profile.context().getPackageManager().getPackageInfo(FIXTURE,PackageManager.GET_PERMISSIONS|PackageManager.GET_SIGNING_CERTIFICATES);
            if(fixture.requestedPermissions!=null&&fixture.requestedPermissions.length!=0)throw new SecurityException("Fixture must have no permissions");
            android.content.pm.Signature[] signatures=fixture.signingInfo.getApkContentsSigners();
            if(signatures.length!=1||!BrokerAuth.sha256(signatures[0].toByteArray()).equals(ManagedProfileScope.OWNER_SIGNER))throw new SecurityException("Fixture signer mismatch");
            // Fail if another secondary display already exists. Never use a dynamic ID from history.
            DisplayManager dm=context.getSystemService(DisplayManager.class);
            for(Display d:dm.getDisplays())if(d.getDisplayId()!=Display.DEFAULT_DISPLAY)throw new IllegalStateException("Another display exists");
            Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
            Class<?> wmType=Class.forName("android.view.IWindowManager");
            int baselineFocus=FocusMetadata.read(wm);
            if(baselineFocus!=Display.DEFAULT_DISPLAY)throw new IllegalStateException("Main display is not focused");
            windows=new WindowOwnershipProbe();windows.awaitInitial();
            drain=new HandlerThread("cowork-profile-fixture-drain");drain.start();
            reader=ImageReader.newInstance(720,1280,PixelFormat.RGBA_8888,3);
            reader.setOnImageAvailableListener(source->{try(Image image=source.acquireLatestImage()){}catch(Exception ignored){}},new Handler(drain.getLooper()));
            // Trusted, own focus, NO top-focus steal, destroy contents on removal. Never ALWAYS_UNLOCKED here (legacy owner-profile display); only the work-profile display ProfileWorkspace opts in, guarded by identity().
            int flags=1|8|64|128|256|1024|16384|65536;
            display=dm.createVirtualDisplay("DSH-Profile-Fixture-Probe",720,1280,240,reader.getSurface(),flags);
            if(display==null||display.getDisplay().getDisplayId()<=0)throw new IllegalStateException("Owned display missing");
            int id=display.getDisplay().getDisplayId();
            wmType.getMethod("setDisplayImePolicy",int.class,int.class).invoke(wm,id,2);
            if((Integer)wmType.getMethod("getDisplayImePolicy",int.class).invoke(wm,id)!=2)throw new IllegalStateException("IME hide rejected");
            Class<?> infoType=Class.forName("android.view.DisplayInfo");Object info=infoType.getConstructor().newInstance();
            if(!(Boolean)Display.class.getMethod("getDisplayInfo",infoType).invoke(display.getDisplay(),info))throw new IllegalStateException("Display info missing");
            int actual=infoType.getField("flags").getInt(info);
            int own=Display.class.getField("FLAG_OWN_FOCUS").getInt(null),noSteal=Display.class.getField("FLAG_STEAL_TOP_FOCUS_DISABLED").getInt(null);
            if((actual&own)==0||(actual&noSteal)==0)throw new IllegalStateException("Focus flags not retained");
            Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(profile.launcher(FIXTURE))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityOptions options=ActivityOptions.makeBasic();options.setLaunchDisplayId(id);
            Object manager=ActivityManager.class.getMethod("getService").invoke(null);
            Method start=manager.getClass().getMethod("startActivityAsUser",Class.forName("android.app.IApplicationThread"),String.class,Intent.class,String.class,
                IBinder.class,String.class,int.class,int.class,Class.forName("android.app.ProfilerInfo"),Bundle.class,int.class);
            int result=(Integer)start.invoke(manager,null,"com.android.shell",intent,null,null,null,0,0,null,options.toBundle(),profile.userId());
            if(result<0)throw new IllegalStateException("Fixture launch refused");
            long deadline=SystemClock.elapsedRealtime()+5000;List<ProfileWindowPolicy.Window> admitted=null;
            while(SystemClock.elapsedRealtime()<deadline){
                profile.current();if(context.getSystemService(KeyguardManager.class).isDeviceLocked())throw new IllegalStateException("Device locked");
                try{admitted=ProfileWindowPolicy.requireInputTargets(windows.snapshot(),id,profile.userId());}catch(ActionGate.Denied notReady){SystemClock.sleep(50);continue;}
                for(ProfileWindowPolicy.Window window:admitted)if(window.ownerUid()!=app.uid())throw new SecurityException("Non-fixture content");
                break;
            }
            if(admitted==null)throw new IllegalStateException("Profile window ownership unavailable");
            boolean taskFound=false;
            for(ActivityManager.RunningTaskInfo task:context.getSystemService(ActivityManager.class).getRunningTasks(100)){
                int taskUser=task.getClass().getField("userId").getInt(task),taskDisplay=task.getClass().getField("displayId").getInt(task);
                if(taskUser==profile.userId()&&task.topActivity!=null&&FIXTURE.equals(task.topActivity.getPackageName())){
                    if(taskDisplay!=id)throw new SecurityException("Fixture escaped owned display");taskFound=true;
                }
            }
            if(!taskFound)throw new IllegalStateException("Profile task not found");
            SystemClock.sleep(800);
            int afterFocus=FocusMetadata.read(wm);
            if(afterFocus!=baselineFocus)throw new SecurityException("Secondary display stole top focus");
            profile.current();
            JSONArray handles=new JSONArray();Set<Object> seenTokens=new HashSet<>();
            for(ProfileWindowPolicy.Window window:windows.snapshot().windows())if(window.displayId()==id)
                handles.put(new JSONObject().put("uid",window.ownerUid()).put("inputConfig",window.inputConfig())
                    .put("tokenPresent",window.windowToken()!=null).put("duplicateToken",window.windowToken()!=null&&!seenTokens.add(window.windowToken())));
            report.put("ownedWindowMetadata",handles).put("mainFocusBefore",baselineFocus).put("mainFocusAfter",afterFocus)
                .put("ownFocusRetained",true).put("noStealRetained",true).put("imePolicy",2).put("profileTaskVerified",true);
            for(ProfileWindowPolicy.Window window:ProfileWindowPolicy.requireInputTargets(windows.snapshot(),id,profile.userId()))
                if(window.ownerUid()!=app.uid())throw new SecurityException("Profile window changed");
            byte[] scopedPng=OwnedDisplayCapture.profilePng(id,profile.userId(),app.uid());
            report.put("fixturePng",android.util.Base64.encodeToString(scopedPng,android.util.Base64.NO_WRAP));
            report.put("uidFilteredCapture",true);
            report.put("ok",true).put("userId",profile.userId()).put("serial",profile.serial()).put("fixtureUid",app.uid())
                .put("ownedDisplayId",id).put("ownFocusRetained",true).put("noStealRetained",true).put("imePolicy",2)
                .put("mainFocusBefore",baselineFocus).put("mainFocusAfter",afterFocus).put("profileTaskVerified",true)
                .put("profileInputTargetsVerified",true).put("fullDisplayCaptureAuthorized",false).put("inputSent",false).put("pixelsRead",true).put("productionGuardChanged",false);
            exit=0;
        }catch(Throwable failure){
            if(display!=null&&windows!=null)try{
                JSONArray metadata=new JSONArray();Set<Object> tokens=new HashSet<>();
                for(ProfileWindowPolicy.Window window:windows.snapshot().windows())if(window.displayId()==display.getDisplay().getDisplayId())
                    metadata.put(new JSONObject().put("uid",window.ownerUid()).put("inputConfig",window.inputConfig())
                        .put("tokenPresent",window.windowToken()!=null).put("duplicateToken",window.windowToken()!=null&&!tokens.add(window.windowToken())));
                report.put("ownedWindowMetadata",metadata);
            }catch(Throwable ignored){}
            try{report.put("ok",false).put("error",failure.getClass().getSimpleName()).put("reason",failure.getMessage());}catch(Exception ignored){}
            for(StackTraceElement frame:failure.getStackTrace())if(frame.getClassName().startsWith("dev.dsh.pocket."))System.err.println(" at "+frame);
        }finally{
            if(display!=null)try{display.release();}catch(Throwable e){exit=1;}
            if(reader!=null)reader.close();if(windows!=null)try{windows.close();}catch(Exception ignored){}if(drain!=null)drain.quitSafely();
            try{report.put("displayReleased",display!=null);}catch(Exception ignored){}
        }
        System.out.println(report);System.exit(exit);
    }
}
