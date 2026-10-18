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
    public static void validate(String tool,JSONObject a){
        switch(tool){
            case "phone.status":case "phone.apps":case "phone.screenshot":case "phone.ui_tree":case "phone.pause":case "phone.stop":case "phone.intervention_status":fields(a,"","");break;
            case "phone.request_intervention":fields(a,"","reason");if(a.has("reason"))text(a,"reason",1,120);break;
            case "phone.start":fields(a,"","packageName");if(a.has("packageName"))pkg(a);break;
            case "phone.launch_app":fields(a,"packageName","observationId");if(a.has("observationId"))obs(a);pkg(a);break;
            case "phone.tap":fields(a,"observationId","x,y,elementId");obs(a);target(a);break;
            case "phone.long_press":fields(a,"observationId","x,y,elementId,durationMs");obs(a);target(a);if(a.has("durationMs"))number(a,"durationMs",500,2000);break;
            case "phone.swipe":fields(a,"observationId,x1,y1,x2,y2,durationMs","");obs(a);point(a,"x1","y1");point(a,"x2","y2");number(a,"durationMs",100,2000);break;
            case "phone.scroll":fields(a,"observationId,deltaY","x,y");obs(a);if(a.has("x")||a.has("y"))point(a,"x","y");if(number(a,"deltaY",-1000,1000)==0)fail();break;
            case "phone.read_page":fields(a,"observationId","maxPages");obs(a);if(a.has("maxPages"))number(a,"maxPages",1,12);break;
            case "phone.type_text":fields(a,"observationId,text","elementId");obs(a);text(a,"text",0,4000);if(a.has("elementId"))text(a,"elementId",1,30);break;
            case "phone.key":fields(a,"key","observationId");String navKey=text(a,"key",1,20);if(!Set.of("BACK","ENTER","HOME","RECENTS").contains(navKey))ActionGate.fail("KEY_DENIED","Only display-local BACK, HOME, RECENTS and ENTER are permitted");if(!navKey.equals("HOME")&&!navKey.equals("RECENTS"))obs(a);break;
            case "phone.wait":fields(a,"timeoutMs","");number(a,"timeoutMs",0,10000);break;
            default:ActionGate.fail("TOOL_DENIED","Unknown Cowork capability");
        }
    }
}
