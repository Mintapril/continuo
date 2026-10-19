package dev.dsh.pocket.cowork.broker;

import android.os.SystemClock;
import android.view.InputEvent;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Each event is bound at InputDispatcher to the owned display AND the frozen target UID.
 * No accessibility/untargeted fallback, no clipboard, no shared keyboard and no global keys. */
final class ProfileInput {
    /** INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH: returns after the target app consumed the event (agent actions need that ordering). */
    private static final int WAIT_FOR_FINISH=2;
    /** INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT: returns once the dispatcher accepted the event (human takeover: no per-event app round trip). */
    private static final int WAIT_FOR_RESULT=1;
    private static java.lang.reflect.Method setDisplay,injectMethod,getInstance;
    private static Object manager;
    private static synchronized void bind()throws Exception{
        if(injectMethod!=null)return;
        Class<?> type=Class.forName("android.hardware.input.InputManagerGlobal");
        setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);
        getInstance=type.getMethod("getInstance");manager=getInstance.invoke(null);
        injectMethod=type.getMethod("injectInputEvent",InputEvent.class,int.class,int.class);
    }
    private static void inject(InputEvent event,int display,int userId,int uid)throws Exception{inject(event,display,userId,uid,WAIT_FOR_FINISH);}
    private static void inject(InputEvent event,int display,int userId,int uid,int mode)throws Exception{
        if(display<=0||userId<=0||uid<0||uid/100000!=userId)ActionGate.fail("APP_DENIED","Input target is outside the profile");
        bind();
        setDisplay.invoke(event,display);
        if(!(Boolean)injectMethod.invoke(manager,event,mode,uid))
            ActionGate.fail("INPUT_REJECTED","Profile UID targeted input rejected");
    }
    static void motion(int display,int userId,int uid,long down,long at,int action,float x,float y)throws Exception{motion(display,userId,uid,down,at,action,x,y,WAIT_FOR_FINISH);}
    /** Human touch: dispatcher-accepted is enough, the finger never waits for the app to finish each sample. */
    static void userMotion(int display,int userId,int uid,long down,long at,int action,float x,float y)throws Exception{motion(display,userId,uid,down,at,action,x,y,WAIT_FOR_RESULT);}
    private static void motion(int display,int userId,int uid,long down,long at,int action,float x,float y,int mode)throws Exception{
        if(display<=0||userId<=0||uid<0||uid/100000!=userId)ActionGate.fail("APP_DENIED","Input target is outside the profile");
        MotionEvent event=MotionEvent.obtain(down,at,action,x,y,0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{inject(event,display,userId,uid,mode);}finally{event.recycle();}
    }
    /** One MOVE carrying several samples in time order (the earlier ones as history), so the app receives every point
     *  of a phone_gesture stroke, not only the latest per frame. */
    static void moves(int display,int userId,int uid,long down,long[] times,float[] xs,float[] ys)throws Exception{
        if(display<=0||userId<=0||uid<0||uid/100000!=userId)ActionGate.fail("APP_DENIED","Input target is outside the profile");
        MotionEvent event=MotionEvent.obtain(down,times[0],MotionEvent.ACTION_MOVE,xs[0],ys[0],0);
        for(int i=1;i<times.length;i++)event.addBatch(times[i],xs[i],ys[i],1f,1f,0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{inject(event,display,userId,uid,WAIT_FOR_FINISH);}finally{event.recycle();}
    }
    /** Key events the virtual keyboard map produces for one text character, or null when the
     * character cannot be expressed without an IME (for example CJK). Pure; testable on the JVM only
     * through the Android stub, so callers must treat null as TEXT_UNSUPPORTED. */
    static KeyEvent[] events(String character){
        if(character==null||character.isEmpty())return null;
        KeyEvent[] events=KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(character.toCharArray());
        return events==null||events.length==0?null:events;
    }
    /** Inject one pre-validated character. A DOWN that was attempted is always followed by its UP. */
    static void character(int display,int userId,int uid,KeyEvent[] events)throws Exception{
        for(KeyEvent event:events){event.setSource(InputDevice.SOURCE_KEYBOARD);inject(event,display,userId,uid);}
    }
    /** Only display-local BACK and ENTER; the caller has already verified the frozen UID. */
    static void key(int display,int userId,int uid,int keyCode)throws Exception{
        if(keyCode!=KeyEvent.KEYCODE_BACK&&keyCode!=KeyEvent.KEYCODE_ENTER)ActionGate.fail("KEY_DENIED","Only display-local BACK and ENTER are permitted");
        press(display,userId,uid,keyCode);
    }
    /** Human takeover keys: BACK, ENTER and DEL only, still display- and UID-bound. */
    static void userKey(int display,int userId,int uid,int keyCode)throws Exception{
        if(keyCode!=KeyEvent.KEYCODE_BACK&&keyCode!=KeyEvent.KEYCODE_ENTER&&keyCode!=KeyEvent.KEYCODE_DEL)ActionGate.fail("KEY_DENIED","Only BACK, ENTER and DEL are permitted");
        press(display,userId,uid,keyCode);
    }
    private static void press(int display,int userId,int uid,int keyCode)throws Exception{
        long now=SystemClock.uptimeMillis();
        KeyEvent down=new KeyEvent(now,now,KeyEvent.ACTION_DOWN,keyCode,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD);
        Exception failure=null;
        try{inject(down,display,userId,uid);}catch(Exception e){failure=e;}
        try{inject(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,keyCode,0,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_KEYBOARD),display,userId,uid);}
        catch(Exception e){if(failure==null)failure=e;}
        if(failure!=null)throw failure;
    }
}
