package dev.dsh.pocket.cowork.broker;

import android.content.Context;
import android.os.Looper;
import org.json.JSONObject;

/** Operator-only read-only compatibility probe. Never launched by the agent or the HTTP broker. */
public final class ProfileProbeMain {
    public static void main(String[] args) {
        WindowOwnershipProbe windows=null;
        int exit=1;
        try {
            Looper.prepareMainLooper();
            Class<?> at=Class.forName("android.app.ActivityThread");
            Object thread=at.getMethod("systemMain").invoke(null);
            Context context=(Context)at.getMethod("getSystemContext").invoke(thread);
            ProfileContext profile=new ProfileContext(context);
            ManagedProfileScope.Identity current=profile.current();
            int apps=profile.launcherApps().size();
            windows=new WindowOwnershipProbe();
            ProfileWindowPolicy.Snapshot snapshot=windows.awaitInitial();
            System.out.println(new JSONObject().put("ok",true).put("userId",current.userId()).put("serial",current.serial())
                .put("parentId",current.parentId()).put("ownerVerified",true).put("signerVerified",true)
                .put("running",current.running()).put("unlocked",current.unlocked()).put("launcherCount",apps)
                .put("windowMetadataAvailable",true).put("windowCount",snapshot.windows().size())
                .put("displayCount",snapshot.displays().size()).put("displayCreated",false).put("inputSent",false)
                .put("personalContentRead",false));
            exit=0;
        }catch(Throwable e){
            System.err.println("PROFILE_PROBE_FAILED "+e.getClass().getSimpleName());
            for(StackTraceElement frame:e.getStackTrace())if(frame.getClassName().startsWith("dev.dsh.pocket."))System.err.println(" at "+frame);
        }finally{
            if(windows!=null)try{windows.close();}catch(Exception ignored){}
        }
        System.exit(exit);
    }
}
