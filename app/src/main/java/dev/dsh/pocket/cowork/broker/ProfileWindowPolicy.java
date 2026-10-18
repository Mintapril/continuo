package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** Pure host-window admission only. Tokens must originate in SurfaceFlinger/Binder, not JSON. */
public final class ProfileWindowPolicy {
    public record Window(Object windowToken,int displayId,int ownerUid,int inputConfig) {}
    public record Snapshot(long sequence,long receivedAt,List<Window> windows,Set<Integer> displays) {
        public Snapshot { windows=List.copyOf(windows);displays=Set.copyOf(displays); }
    }
    private ProfileWindowPolicy(){}
    /** All handles, no user0/SystemUI/IME/overlay exemption or package-name fallback. */
    public static List<Window> requireProfileDisplay(Snapshot s,int display,int userId){
        if(s==null||display<=0||userId<=0||userId>21473||!s.displays().contains(display))deny();
        Set<Object> tokens=new HashSet<>();ArrayList<Window> result=new ArrayList<>();
        for(Window w:s.windows())if(w.displayId()==display){
            if(w.ownerUid()<0||w.ownerUid()/100000!=userId||w.windowToken()==null||!tokens.add(w.windowToken()))deny();
            result.add(w);
        }
        if(result.isEmpty())deny();return List.copyOf(result);
    }
    /** Input routing only, NOT permission to capture the whole display.
     * Compositor bookkeeping surfaces with NO_INPUT_CHANNEL and no IWindow cannot receive
     * injected input. They may still contain pixels: captures must independently filter by UID.
     * Every dispatch must additionally bind targetUid at InputDispatcher, never an untargeted retry.
     */
    public static List<Window> requireInputTargets(Snapshot s,int display,int userId){
        if(s==null||display<=0||userId<=0||userId>21473||!s.displays().contains(display))deny();
        Set<Object> tokens=new HashSet<>();ArrayList<Window> result=new ArrayList<>();
        for(Window w:s.windows())if(w.displayId()==display){
            if((w.inputConfig()&1)!=0&&w.windowToken()==null)continue; // NO_INPUT_CHANNEL
            if(w.ownerUid()<0||w.ownerUid()/100000!=userId||w.windowToken()==null||!tokens.add(w.windowToken()))deny();
            result.add(w);
        }
        if(result.isEmpty())deny();return List.copyOf(result);
    }
    /** Host window only. Does NOT attest ownership of an embedded accessibility provider. */
    public static Window joinHostWindow(List<Window> windows,Object exactWindowToken){
        if(windows==null||exactWindowToken==null)deny();Window found=null;
        for(Window w:windows)if(exactWindowToken.equals(w.windowToken())){if(found!=null)deny();found=w;}
        if(found==null)deny();return found;
    }
    private static void deny(){ActionGate.fail("APP_DENIED","Exact profile window ownership unavailable");}
}
