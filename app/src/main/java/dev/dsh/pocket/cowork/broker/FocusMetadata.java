package dev.dsh.pocket.cowork.broker;

import android.os.*;
import java.io.*;
import java.util.concurrent.*;

/** Operator probe only: bounded WMS proto, reports only the top focused display number.
 * AOSP Android16 WindowManagerServiceDumpProto.focused_display_id = 9 (default 0).
 * No text dump parsing, titles, screenshot or retained cross-display metadata.
 */
final class FocusMetadata {
    static int read(Object wm)throws Exception{
        IBinder service=((IInterface)wm).asBinder();
        if(!"android.view.IWindowManager".equals(service.getInterfaceDescriptor()))throw new SecurityException();
        ExecutorService workers=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"cowork-focus-probe");t.setDaemon(true);return t;});
        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
        try{
            long deadline=SystemClock.elapsedRealtime()+1500;
            Future<byte[]> input=workers.submit(()->{try(InputStream stream=new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])){
                byte[] data=stream.readNBytes(4*1024*1024+1);if(data.length>4*1024*1024)throw new IOException("Metadata too large");return data;
            }});
            Future<?> dump=workers.submit(()->{try{service.dump(pipe[1].getFileDescriptor(),new String[]{"--proto"});return null;}finally{pipe[1].close();}});
            dump.get(Math.max(1,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
            byte[] bytes=input.get(Math.max(1,deadline-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
            if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Metadata deadline");
            Cursor c=new Cursor(bytes);boolean root=false,seen=false;int focus=0,count=0;
            while(c.at<bytes.length){
                if(++count>100000)throw new IOException("Metadata field limit");
                long tag=c.varint();int field=(int)(tag>>>3),wire=(int)(tag&7);if(field<=0)throw new IOException("Invalid field");
                if(field==9){if(seen||wire!=0)throw new IOException("Ambiguous focus");seen=true;long value=c.varint();if(value<0||value>Integer.MAX_VALUE)throw new IOException("Invalid focus");focus=(int)value;}
                else{if(field==2){if(root||wire!=2)throw new IOException("Ambiguous hierarchy");root=true;}c.skip(wire);}
            }
            if(!root)throw new IOException("Missing hierarchy");return focus;
        }finally{for(ParcelFileDescriptor fd:pipe)try{fd.close();}catch(IOException ignored){}workers.shutdownNow();}
    }
    private static final class Cursor {
        final byte[] data;int at;
        Cursor(byte[] data){this.data=data;}
        long varint()throws IOException{long value=0;for(int i=0;i<10;i++){if(at>=data.length)throw new IOException("Truncated");int b=data[at++]&255;if(i==9&&(b&254)!=0)throw new IOException("Overflow");value|=(long)(b&127)<<(7*i);if((b&128)==0)return value;}throw new IOException("Varint");}
        void skip(int wire)throws IOException{long length;switch(wire){case 0:varint();return;case 1:length=8;break;case 2:length=varint();break;case 5:length=4;break;default:throw new IOException("Wire type");}if(length<0||length>data.length-at)throw new IOException("Truncated field");at+=(int)length;}
    }
}
