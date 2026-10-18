package dev.dsh.pocket.cowork.broker;

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;

/** Fresh compositor capture, explicit logical display, secure/protected capture always disabled. */
final class OwnedDisplayCapture {
    static byte[] png(int display)throws Exception{return capture(display,-1,AndroidWorkspace.WIDTH,AndroidWorkspace.HEIGHT);}
    /** Scoped view: other UIDs may be blank. No unfiltered fallback is permitted. */
    static byte[] profilePng(int display,int userId,int targetUid)throws Exception{return profilePng(display,userId,targetUid,AndroidWorkspace.WIDTH,AndroidWorkspace.HEIGHT);}
    static byte[] profilePng(int display,int userId,int targetUid,int width,int height)throws Exception{
        if(userId<=0||targetUid<0||targetUid/100000!=userId)ActionGate.fail("APP_DENIED","Capture UID is outside the verified profile");
        return capture(display,targetUid,width,height);
    }
    private static byte[] capture(int display,int targetUid,int width,int height)throws Exception{
        if(display<=0)ActionGate.fail("WRONG_DISPLAY","Only the owned secondary display may be captured");
        Class<?> capture=Class.forName("android.window.ScreenCapture");
        Class<?> args=Class.forName("android.window.ScreenCapture$CaptureArgs");
        Class<?> builderType=Class.forName("android.window.ScreenCapture$CaptureArgs$Builder");
        Class<?> listenerType=Class.forName("android.window.ScreenCapture$ScreenCaptureListener");
        Class<?> syncType=Class.forName("android.window.ScreenCapture$SynchronousScreenCaptureListener");
        Object builder=builderType.getConstructor().newInstance();
        builderType.getMethod("setCaptureSecureLayers",boolean.class).invoke(builder,false);
        builderType.getMethod("setAllowProtected",boolean.class).invoke(builder,false);
        if(targetUid>=0)builderType.getMethod("setUid",long.class).invoke(builder,(long)targetUid);
        Object options=builderType.getMethod("build").invoke(builder);
        Object listener=capture.getMethod("createSyncCaptureListener").invoke(null);
        Object wm=Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        wm.getClass().getMethod("captureDisplay",int.class,args,listenerType).invoke(wm,display,options,listener);
        Object result=syncType.getMethod("getBuffer").invoke(listener);
        if(result==null)ActionGate.fail("FRAME_UNAVAILABLE","Fresh owned-display capture unavailable");
        Class<?> bufferType=Class.forName("android.window.ScreenCapture$ScreenshotHardwareBuffer");
        HardwareBuffer buffer=(HardwareBuffer)bufferType.getMethod("getHardwareBuffer").invoke(result);
        Bitmap hardware=null,bitmap=null;
        try{
            if((Boolean)bufferType.getMethod("containsSecureLayers").invoke(result))ActionGate.fail("SECURE_WINDOW","Protected display cannot be captured");
            hardware=(Bitmap)bufferType.getMethod("asBitmap").invoke(result);
            if(hardware==null||hardware.getWidth()!=width||hardware.getHeight()!=height)
                ActionGate.fail("FRAME_UNAVAILABLE","Unexpected owned-display frame dimensions");
            bitmap=hardware.copy(Bitmap.Config.ARGB_8888,false);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(bitmap==null||!bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes)||bytes.size()>8*1024*1024)
                ActionGate.fail("FRAME_UNAVAILABLE","Owned-display frame unavailable or too large");
            return bytes.toByteArray();
        }finally{if(bitmap!=null)bitmap.recycle();if(hardware!=null&&hardware!=bitmap)hardware.recycle();if(buffer!=null)buffer.close();}
    }
}
