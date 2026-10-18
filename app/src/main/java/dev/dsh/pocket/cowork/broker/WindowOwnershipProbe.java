package dev.dsh.pocket.cowork.broker;

import dev.dsh.pocket.cowork.broker.ProfileWindowPolicy.Window;
import dev.dsh.pocket.cowork.broker.ProfileWindowPolicy.Snapshot;
import android.os.SystemClock;
import android.util.Pair;
import android.view.InputWindowHandle;
import android.window.WindowInfosListener;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Read-only Android16 SurfaceFlinger metadata adapter. Not wired into production authorization.
 * Never reads window titles, pixels, accessibility nodes, input-channel tokens or app contents.
 * A compositor snapshot is asynchronous evidence, NOT atomic input/semantic-node authorization.
 */
public final class WindowOwnershipProbe implements AutoCloseable {
    private final CountDownLatch first=new CountDownLatch(1);
    private final WindowInfosListener listener;
    private Snapshot latest;
    private boolean closed,invalid;
    private long sequence;
    private int rejected;
    public WindowOwnershipProbe() throws Exception {
        listener=new WindowInfosListener(){
            @Override public void onWindowInfosChanged(InputWindowHandle[] windows,DisplayInfo[] displays){
                accept(windows,displays,false);
            }
        };
        try {
            Pair<InputWindowHandle[],WindowInfosListener.DisplayInfo[]> initial=listener.register();
            if(initial!=null)accept(initial.first,initial.second,true);
        } catch(Throwable failure) {
            try{listener.unregister();}catch(Throwable ignored){}
            throw new IllegalStateException("Window ownership metadata unavailable");
        }
    }
    private synchronized void accept(InputWindowHandle[] handles,WindowInfosListener.DisplayInfo[] displays,boolean initial){
        if(closed || initial&&sequence!=0)return;
        try{
            if(handles==null||displays==null||handles.length>4096||displays.length>32)throw new IllegalStateException("bounds");
            Set<Integer> ids=new HashSet<>();
            // A locked/secure keyguard transition lists a placeholder display with id -1 (and its windows): it can never be the owned display (id>0), so it is skipped instead of voiding the whole snapshot.
            for(WindowInfosListener.DisplayInfo d:displays){if(d==null)throw new IllegalStateException("null display");if(d.mDisplayId<0)continue;if(!ids.add(d.mDisplayId))throw new IllegalStateException("duplicate display "+d.mDisplayId);}
            ArrayList<Window> copy=new ArrayList<>();
            for(InputWindowHandle h:handles){
                if(h==null)throw new IllegalStateException("null handle");
                // ownerUid<0 stays in the snapshot: on the target display ProfileWindowPolicy denies it (fail closed).
                // A window on a display the compositor does not list (another app's hidden/destroyed virtual display)
                // can never authorize anything: policy requires the target display to be listed and only reads that
                // display's windows. Skip it instead of discarding the whole snapshot for every display.
                if(h.displayId<0||!ids.contains(h.displayId))continue;
                copy.add(new Window(h.getWindowToken(),h.displayId,h.ownerUid,h.inputConfig));
            }
            // Empty caches establish no ownership; never retain a previous nonempty snapshot.
            if(ids.isEmpty()||copy.isEmpty()){latest=null;return;}
            latest=new Snapshot(++sequence,SystemClock.elapsedRealtime(),List.copyOf(copy),Set.copyOf(ids));
            // A later internally consistent compositor snapshot supersedes a transient inconsistent one
            // (for example a destroyed display whose windows are still listed for one callback).
            invalid=false;
        }catch(Throwable failure){invalid=true;latest=null;
            // Metadata-only diagnostic (counts, no window content) for the root-only broker.log.
            if(!initial&&sequence>0&&rejected++%100!=0)return;System.err.println("window-snapshot rejected: handles="+(handles==null?-1:handles.length)+" displays="+(displays==null?-1:displays.length)+" "+failure.getClass().getSimpleName()+": "+failure.getMessage());
        }
        finally{if(latest!=null||invalid)first.countDown();}
    }
    public Snapshot awaitInitial() throws Exception {
        if(!first.await(1500,TimeUnit.MILLISECONDS))throw new IllegalStateException("Window ownership snapshot unavailable");
        return snapshot();
    }
    /** Bounded wait for the next consistent snapshot; callers may retry transient unavailability. */
    public Snapshot awaitSnapshot(long timeoutMs) {
        long until=SystemClock.elapsedRealtime()+timeoutMs;
        while(true){
            try{return snapshot();}
            catch(IllegalStateException unavailable){if(SystemClock.elapsedRealtime()>=until)throw unavailable;}
            SystemClock.sleep(25);
        }
    }
    public synchronized Snapshot snapshot(){
        if(closed||invalid||latest==null)throw new IllegalStateException("Window ownership snapshot unavailable");
        return latest;
    }
    @Override public synchronized void close(){
        if(closed)return;closed=true;latest=null;first.countDown();listener.unregister();
    }
}
