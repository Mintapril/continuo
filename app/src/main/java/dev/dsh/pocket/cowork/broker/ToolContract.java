package dev.dsh.pocket.cowork.broker;

import org.json.*;
import java.util.*;

/** Strict typed wire schema shared by every native entry point. No coercion or extra arguments. */
public final class ToolContract {
    private static void fail(){ActionGate.fail("ARGUMENTS_INVALID","Arguments do not match fixed tool schema");}
    private static void fields(JSONObject a,String required,String optional){
        Set<String> allowed=new HashSet<>();
        for(String k:required.split(","))if(!k.isEmpty()){allowed.add(k);if(!a.has(k))fail();}
        for(String k:optional.split(","))if(!k.isEmpty())allowed.add(k);
        Iterator<String> keys=a.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))fail();
    }
    private static String text(JSONObject a,String k,int min,int max){Object v=a.opt(k);if(!(v instanceof String))fail();String s=(String)v;if(s.length()<min||s.length()>max)fail();return s;}
    private static int number(JSONObject a,String k,int min,int max){Object v=a.opt(k);if(!(v instanceof Number))fail();double d=((Number)v).doubleValue();if(!Double.isFinite(d)||d!=Math.rint(d)||d<min||d>max)fail();return (int)d;}
    private static void obs(JSONObject a){if(!text(a,"observationId",1,100).matches("[A-Za-z0-9_-]+"))fail();}
    private static void point(JSONObject a,String x,String y){number(a,x,0,719);number(a,y,0,1999);}
    /** Exactly one mode: both x and y, or a semantic elementId from the latest ui_tree observation. */
    private static void target(JSONObject a){
        if(a.has("elementId")){if(a.has("x")||a.has("y"))fail();if(!text(a,"elementId",2,6).matches("n[0-9]{1,4}"))fail();}
        else point(a,"x","y");
    }
    private static void pkg(JSONObject a){if(!text(a,"packageName",3,200).matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))fail();}
    /** Steps of one observation: element inputs only, each target once, a key only as the last step. The text limit
     *  keeps the request inside the 64 KiB body limit (5 x 4000 CJK characters would not fit). */
    static final int BATCH_MIN=2,BATCH_MAX=5,BATCH_TEXT=8000;
    private static void batch(JSONObject a){
        fields(a,"observationId,steps","");obs(a);
        if(!(a.opt("steps") instanceof JSONArray))fail();
        JSONArray steps=a.optJSONArray("steps");if(steps.length()<BATCH_MIN||steps.length()>BATCH_MAX)fail();
        Set<String> targets=new HashSet<>();int text=0;
        for(int i=0;i<steps.length();i++){
            if(!(steps.opt(i) instanceof JSONObject))fail();
            JSONObject step=steps.optJSONObject(i);
            switch(text(step,"tool",1,20)){
                case "tap":case "long_press":fields(step,"tool,elementId","");break;
                case "type_text":fields(step,"tool,elementId,text","");text+=text(step,"text",0,4000).length();break;
                case "key":fields(step,"tool,key","");if(i!=steps.length()-1||!Set.of("ENTER","BACK").contains(text(step,"key",1,20)))fail();continue;
                default:fail();
            }
            String id=text(step,"elementId",2,6);if(!id.matches("n[0-9]{1,4}")||!targets.add(id))fail();
        }
        if(text>BATCH_TEXT)fail();
    }
    /** phone_gesture as the plugin sends it (it flattens the model's SVG paths, PROTOCOL.md): 1..MAX_STROKES strokes of
     *  1..MAX_FINGERS fingers, each finger integer points [x0,y0,x1,y1,...], every stroke with its movement time and
     *  holds, within StrokePath's limits (the point limit counts every finger). */
    private static void gesture(JSONObject a){
        fields(a,"observationId,strokes","");obs(a);
        if(!(a.opt("strokes") instanceof JSONArray))fail();
        JSONArray strokes=a.optJSONArray("strokes");if(strokes.length()<1||strokes.length()>StrokePath.MAX_STROKES)fail();
        int points=0;long total=(long)StrokePath.GAP_MS*(strokes.length()-1);
        for(int i=0;i<strokes.length();i++){
            if(!(strokes.opt(i) instanceof JSONObject))fail();
            JSONObject stroke=strokes.optJSONObject(i);
            fields(stroke,"fingers,durationMs,holdStartMs,holdEndMs","");
            if(!(stroke.opt("fingers") instanceof JSONArray))fail();
            JSONArray fingers=stroke.optJSONArray("fingers");if(fingers.length()<1||fingers.length()>StrokePath.MAX_FINGERS)fail();
            for(int f=0;f<fingers.length();f++){
                if(!(fingers.opt(f) instanceof JSONArray))fail();
                JSONArray p=fingers.optJSONArray(f);
                points+=p.length()/2;if(p.length()<2||p.length()%2!=0||points>StrokePath.MAX_POINTS)fail();
                for(int j=0;j<p.length();j++){Object v=p.opt(j);if(!(v instanceof Number))fail();double d=((Number)v).doubleValue();if(!Double.isFinite(d)||d!=Math.rint(d)||d<0||d>(j%2==0?719:1999))fail();}
            }
            total+=number(stroke,"durationMs",StrokePath.MIN_DURATION_MS,StrokePath.MAX_DURATION_MS)+number(stroke,"holdStartMs",0,StrokePath.MAX_HOLD_MS)+number(stroke,"holdEndMs",0,StrokePath.MAX_HOLD_MS);
        }
        if(total>StrokePath.MAX_TOTAL_MS)fail();
    }
    /** phone_screenshot: nothing, or a zoom as the plugin sends it (scale resolved): region [left, top, right, bottom] of
     *  integer screen pixels and scale 1..MAX_SCALE. ScreenZoom checks the region against the screen and the image size. */
    private static void screenshot(JSONObject a){
        fields(a,"","region,scale");
        if(a.has("region")!=a.has("scale"))fail();
        if(!a.has("region"))return;
        if(!(a.opt("region") instanceof JSONArray))fail();
        JSONArray r=a.optJSONArray("region");if(r.length()!=4)fail();
        for(int i=0;i<4;i++){Object v=r.opt(i);if(!(v instanceof Number))fail();double d=((Number)v).doubleValue();if(!Double.isFinite(d)||d!=Math.rint(d)||d<0||d>2000)fail();}
        number(a,"scale",1,ScreenZoom.MAX_SCALE);
    }
    public static void validate(String tool,JSONObject a){
        switch(tool){
            case "phone.status":case "phone.apps":case "phone.ui_tree":case "phone.pause":case "phone.stop":case "phone.intervention_status":fields(a,"","");break;
            case "phone.screenshot":screenshot(a);break;
            case "phone.request_intervention":fields(a,"","reason");if(a.has("reason"))text(a,"reason",1,120);break;
            case "phone.notify_owner":fields(a,"text","");if(text(a,"text",1,OwnerNotices.MAX_TEXT).isBlank())fail();break;
            case "phone.start":fields(a,"","packageName");if(a.has("packageName"))pkg(a);break;
            case "phone.grant_permission":fields(a,"packageName,permission","");pkg(a);if(!ProfileAppPolicy.GROUPS.containsKey(text(a,"permission",1,20)))fail();break;
            case "phone.uninstall_app":fields(a,"packageName","");pkg(a);break;
            case "phone.launch_app":fields(a,"packageName","observationId");if(a.has("observationId"))obs(a);pkg(a);break;
            case "phone.tap":fields(a,"observationId","x,y,elementId");obs(a);target(a);break;
            case "phone.long_press":fields(a,"observationId","x,y,elementId,durationMs");obs(a);target(a);if(a.has("durationMs"))number(a,"durationMs",500,2000);break;
            case "phone.swipe":fields(a,"observationId,x1,y1,x2,y2,durationMs","");obs(a);point(a,"x1","y1");point(a,"x2","y2");number(a,"durationMs",100,2000);break;
            case "phone.scroll":fields(a,"observationId,deltaY","x,y");obs(a);if(a.has("x")||a.has("y"))point(a,"x","y");if(number(a,"deltaY",-1000,1000)==0)fail();break;
            case "phone.read_page":fields(a,"observationId","maxPages");obs(a);if(a.has("maxPages"))number(a,"maxPages",1,12);break;
            case "phone.type_text":fields(a,"observationId,text","elementId");obs(a);text(a,"text",0,4000);if(a.has("elementId"))text(a,"elementId",1,30);break;
            case "phone.key":fields(a,"key","observationId");String navKey=text(a,"key",1,20);if(!Set.of("BACK","ENTER","HOME","RECENTS").contains(navKey))ActionGate.fail("KEY_DENIED","Only display-local BACK, HOME, RECENTS and ENTER are permitted");if(!navKey.equals("HOME")&&!navKey.equals("RECENTS"))obs(a);break;
            case "phone.wait":fields(a,"timeoutMs","");number(a,"timeoutMs",0,10000);break;
            case "phone.batch":batch(a);break;
            case "phone.gesture":gesture(a);break;
            default:ActionGate.fail("TOOL_DENIED","Unknown Cowork capability");
        }
    }
}
