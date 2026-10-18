package dev.dsh.pocket.cowork.broker;

import android.os.*;
import java.io.*;
import java.util.concurrent.*;

/** Bounded WMS metadata only, never shell execution or main-display pixel capture. */
final class SecureWindowProbe implements AutoCloseable {
    private final IBinder service;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2),r->{Thread t=new Thread(r,"cowork-window-protection");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private Future<?> pendingDump;
    private Future<byte[]> pendingRead;
    SecureWindowProbe()throws Exception{
        Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        service=((IInterface)wm).asBinder();
        if(!"android.view.IWindowManager".equals(service.getInterfaceDescriptor()))throw new SecurityException("Window service unavailable");
    }
    synchronized void requireSafe(int display,boolean content){
        // Future.cancel cannot stop a Binder transaction. Quarantine until BOTH actual workers exit.
        // A dump that is still running is a slow binder round trip, not a verdict: give it the same budget as the
        // fresh attempt below before quarantining, otherwise one sluggish call pauses the whole turn.
        long quarantineUntil=SystemClock.elapsedRealtime()+1500;
        while((pendingDump!=null&&!pendingDump.isDone())||(pendingRead!=null&&!pendingRead.isDone())){
            if(SystemClock.elapsedRealtime()>=quarantineUntil)unavailable();
            SystemClock.sleep(20);
        }
        ParcelFileDescriptor[] pipe=null;
        try{
            if(display<=0)ActionGate.fail("NO_DISPLAY","No owned secondary display");
            long deadline=SystemClock.elapsedRealtime()+1500;
            pipe=ParcelFileDescriptor.createPipe();final ParcelFileDescriptor input=pipe[0],output=pipe[1];
            pendingRead=workers.submit(()->{
                try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(input)){
                    byte[] value=in.readNBytes(SecureWindowSnapshot.MAX_BYTES+1);
                    if(value.length>SecureWindowSnapshot.MAX_BYTES)throw new IOException("Window metadata limit");return value;
                }
            });
            pendingDump=workers.submit(()->{try{service.dump(output.getFileDescriptor(),new String[]{"--proto"});return null;}finally{output.close();}});
            pendingDump.get(Math.max(1,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
            byte[] bytes=pendingRead.get(Math.max(1,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
            if(SystemClock.elapsedRealtime()>deadline)unavailable();
            SecureWindowSnapshot.requireSafe(bytes,display,content);
        }catch(ActionGate.Denied e){throw e;}
        catch(Exception e){
            System.err.println("Cowork protection transport: "+e.getClass().getName());
            if(e.getCause()!=null)System.err.println(" cause "+e.getCause().getClass().getName());
            unavailable();
        }
        finally{if(pipe!=null)for(ParcelFileDescriptor p:pipe)try{p.close();}catch(IOException ignored){}}
    }
    private static void unavailable(){ActionGate.fail("SECURITY_UNAVAILABLE","Cannot verify owned-display window protection");}
    @Override public void close(){workers.shutdownNow();}
}
