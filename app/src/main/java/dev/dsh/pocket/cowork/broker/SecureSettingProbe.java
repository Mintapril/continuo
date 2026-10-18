package dev.dsh.pocket.cowork.broker;

import android.app.ActivityManager;
import android.content.AttributionSource;
import android.os.*;
import java.lang.reflect.Method;
import java.util.concurrent.*;

/** Read-only, fixed Settings provider query for app_process (which has no application thread).
 * No shell, settings writes, caller-selected keys/users or ContentResolver fallback.
 */
final class SecureSettingProbe implements AutoCloseable {
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"cowork-secure-setting");t.setDaemon(true);return t;});
    private Future<Boolean> pending;
    synchronized void requireSafe(){
        // Binder is not cancellable: keep its worker quarantined after a timeout.
        if(pending!=null&&!pending.isDone())unavailable();
        try {
            pending=worker.submit(SecureSettingProbe::readSafe);
            if(!pending.get(1500,TimeUnit.MILLISECONDS))unavailable();
        } catch(ActionGate.Denied e){throw e;}
        catch(Exception e){unavailable();}
    }
    private static boolean readSafe()throws Exception {
        Object manager=ActivityManager.class.getMethod("getService").invoke(null);
        Method acquire=manager.getClass().getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class);
        Method release=manager.getClass().getMethod("removeContentProviderExternal",String.class,IBinder.class);
        IBinder token=new Binder();
        Object holder=acquire.invoke(manager,"settings",0,token,"dsh-cowork-protection");
        if(holder==null)throw new IllegalStateException();
        try {
            Object provider=holder.getClass().getField("provider").get(holder);
            if(provider==null)throw new IllegalStateException();
            AttributionSource source=new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build();
            Bundle arguments=new Bundle();arguments.putInt("_user",0);
            Bundle result=(Bundle)provider.getClass().getMethod("call",AttributionSource.class,String.class,String.class,String.class,Bundle.class)
                .invoke(provider,source,"settings","GET_secure","disable_secure_windows",arguments);
            if(result==null||!result.containsKey("value"))throw new IllegalStateException();
            Object value=result.get("value");
            // AOSP's absent value means disabled; malformed/nonzero values are never accepted.
            return value==null||"0".equals(value);
        } finally {release.invoke(manager,"settings",token);}
    }
    private static void unavailable(){ActionGate.fail("SECURITY_UNAVAILABLE","Cannot verify system capture protection");}
    @Override public void close(){worker.shutdownNow();}
}
