package dev.dsh.pocket.cowork.broker;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.*;
import android.view.accessibility.*;
import org.json.*;
import java.lang.reflect.Constructor;
import java.util.*;

/** Semantic page structure of the owned work-profile display (accessibility tree, read through UiAutomation).
 *  Scope: only windows reported on the owned display whose root package belongs to the verified work profile.
 *  Nodes are single-observation handles: a node must still be visible, enabled and at the recorded bounds
 *  before an action is derived from it. Password fields are redacted and never receive text. The automation
 *  connection exists only while the owned display exists. */
final class ProfileStructure implements AutoCloseable {
    static final int MAX_NODES=2000, MAX_DEPTH=40, MAX_TEXT=300;
    private static final long BUDGET_MS=5000;
    private final ProfileContext profile;
    private HandlerThread thread;
    private UiAutomation automation;
    private final Map<String,Entry> nodes=new HashMap<>();
    private String boundObservation="";
    private record Entry(AccessibilityNodeInfo node,Rect bounds,boolean scrollable){}
    private static final class Stats{int windows,emitted,interactive,unlabeled,skippedWindows;boolean truncated,custom;String title="",pkg="";}

    ProfileStructure(ProfileContext profile){this.profile=profile;}

    private synchronized UiAutomation automation()throws Exception{
        if(automation!=null)return automation;
        thread=new HandlerThread("cowork-profile-structure");thread.start();
        try{
            Object connection=Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance();
            Constructor<UiAutomation> constructor=UiAutomation.class.getConstructor(Looper.class,Class.forName("android.app.IUiAutomationConnection"));
            UiAutomation created=constructor.newInstance(thread.getLooper(),connection);
            UiAutomation.class.getMethod("connect",int.class).invoke(created,UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.accessibilityservice.AccessibilityServiceInfo info=created.getServiceInfo();
            info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.eventTypes=0;created.setServiceInfo(info);
            automation=created;
        }catch(Exception e){thread.quitSafely();thread=null;throw e;}
        return automation;
    }
    private List<AccessibilityWindowInfo> windows(int displayId)throws Exception{
        List<AccessibilityWindowInfo> list=automation().getWindowsOnAllDisplays().get(displayId,Collections.emptyList());
        ArrayList<AccessibilityWindowInfo> copy=new ArrayList<>(list);
        copy.sort((a,b)->Integer.compare(b.getLayer(),a.getLayer()));
        return copy;
    }
    /** One visible node found by a walk, before compaction. */
    private static final class Cand{
        AccessibilityNodeInfo node;Rect bounds;String text="",desc="",role="",rid="";
        boolean clickable,editable,scrollable,focused,checked,checkable,labeled,labeledBelow;int parent=-1;
    }
    /** Read the structure; ids "n<index>" are valid only for the observation that is bound next. The result is the
     *  compact form the model reads: unlabelled wrappers are dropped (a clickable container with labelled
     *  descendants is reached through them), empty fields are omitted, bounds are [l,t,r,b] and the state flags a
     *  single string (c clickable, e editable, s scrollable, f focused, k checked). */
    synchronized JSONObject snapshot(int displayId,int width,int height)throws Exception{
        nodes.clear();boundObservation="";
        Stats stats=new Stats();
        ArrayList<Cand> kept=collect(displayId,width,height,stats);
        title=stats.title;pkg=stats.pkg;
        JSONArray out=new JSONArray();
        for(Cand c:kept){
            String id="n"+out.length();
            JSONObject o=new JSONObject().put("id",id);
            if(!c.text.isEmpty())o.put("text",c.text);
            if(!c.desc.isEmpty())o.put("desc",c.desc);
            if(!c.role.isEmpty())o.put("role",c.role);
            if(!c.labeled&&!c.rid.isEmpty())o.put("rid",c.rid);
            o.put("bounds",new JSONArray().put(c.bounds.left).put(c.bounds.top).put(c.bounds.right).put(c.bounds.bottom));
            String flags=(c.clickable?"c":"")+(c.editable?"e":"")+(c.scrollable?"s":"")+(c.focused?"f":"")+(c.checked?"k":"");
            if(!flags.isEmpty())o.put("flags",flags);
            out.put(o);nodes.put(id,new Entry(c.node,new Rect(c.bounds),c.scrollable));
            boolean interactive=c.clickable||c.editable||c.checkable;
            if(interactive){stats.interactive++;if(!c.labeled)stats.unlabeled++;}
        }
        stats.emitted=out.length();
        lastSignature=signature(kept);
        JSONObject result=new JSONObject().put("title",title).put("package",pkg).put("nodes",out);
        String advice="";boolean complete=true;
        if(stats.windows==0){complete=false;advice="No accessible window on the work-profile display; use phone_screenshot.";}
        else if(stats.truncated){complete=false;advice="Structure is truncated (too many nodes or time limit); scroll or use phone_screenshot.";}
        else if(stats.emitted==0){complete=false;advice="No labelled or interactive nodes; the screen is probably custom-drawn; use phone_screenshot.";}
        else if(stats.custom){complete=false;advice="Screen contains custom-drawn or WebView content missing from the tree; use phone_screenshot for it.";}
        else if(stats.interactive>=3&&stats.unlabeled*2>stats.interactive){complete=false;advice="Many interactive nodes have no label; use phone_screenshot to identify them.";}
        return result.put("complete",complete).put("advice",advice);
    }
    /** Identity of what is visible (texts + positions) without touching the bound node handles. */
    synchronized String contentSignature(int displayId,int width,int height)throws Exception{return signature(collect(displayId,width,height,new Stats()));}
    /** Signature of the structure the latest snapshot() saw (the "before" of a following gesture). */
    synchronized String lastSignature(){return lastSignature;}
    private String lastSignature="",title="",pkg="";
    private static String signature(List<Cand> list){
        java.security.MessageDigest digest;
        try{digest=java.security.MessageDigest.getInstance("SHA-1");}catch(Exception e){throw new IllegalStateException(e);}
        for(Cand c:list){
            if(!c.labeled)continue;
            digest.update((c.text+"\u0001"+c.desc+"\u0001"+c.bounds.left+","+c.bounds.top+","+c.bounds.right+","+c.bounds.bottom+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b));return hex.toString();
    }
    /** Visible text lines (text, else description) of the compact structure in reading order, for page reading. */
    synchronized List<String> visibleLines(int displayId,int width,int height)throws Exception{
        ArrayList<String> lines=new ArrayList<>();
        for(Cand c:collect(displayId,width,height,new Stats()))if(c.labeled)lines.add(c.text.isEmpty()?c.desc:c.text);
        return lines;
    }
    private ArrayList<Cand> collect(int displayId,int width,int height,Stats stats)throws Exception{
        ArrayList<Cand> all=new ArrayList<>();
        long deadline=SystemClock.elapsedRealtime()+BUDGET_MS;
        ArrayList<Rect> covering=new ArrayList<>();
        for(AccessibilityWindowInfo w:windows(displayId)){
            int type=w.getType();
            if(type!=AccessibilityWindowInfo.TYPE_APPLICATION&&type!=AccessibilityWindowInfo.TYPE_SYSTEM)continue;
            AccessibilityNodeInfo root=w.getRoot();
            if(root==null||root.getPackageName()==null){stats.skippedWindows++;continue;}
            try{profile.app(root.getPackageName().toString());}catch(Exception foreign){stats.skippedWindows++;continue;}
            stats.windows++;
            if(stats.pkg.isEmpty()&&type==AccessibilityWindowInfo.TYPE_APPLICATION){stats.pkg=root.getPackageName().toString();CharSequence title=w.getTitle();if(title!=null)stats.title=bounded(title);}
            Rect window=new Rect();w.getBoundsInScreen(window);
            walk(root,0,-1,all,stats,covering,width,height,deadline);
            covering.add(window);
        }
        for(int i=all.size()-1;i>=0;i--){Cand c=all.get(i);if((c.labeled||c.labeledBelow)&&c.parent>=0)all.get(c.parent).labeledBelow=true;}
        ArrayList<Cand> kept=new ArrayList<>();
        // Two stacked windows can expose the same bar twice (for example a duplicated bottom action bar):
        // identical text inside a nearly identical box is one element for the model.
        java.util.HashSet<String> boxes=new java.util.HashSet<>();
        for(Cand c:all){
            if(c.labeled&&!boxes.add(c.text+"|"+(c.bounds.centerY()/24)+"|"+c.bounds.centerX()/120))continue;
            boolean wrapper=!c.labeled&&!c.editable&&!c.scrollable&&!c.checkable&&c.role.isEmpty();
            if(wrapper&&c.clickable&&c.labeledBelow)continue; // reached through its labelled descendants
            kept.add(c);
        }
        return kept;
    }
    private void walk(AccessibilityNodeInfo n,int depth,int parent,List<Cand> out,Stats stats,List<Rect> covering,int width,int height,long deadline)throws Exception{
        if(n==null)return;
        if(depth>MAX_DEPTH||out.size()>=MAX_NODES||SystemClock.elapsedRealtime()>deadline){stats.truncated=true;return;}
        String cls=String.valueOf(n.getClassName());
        boolean visible=n.isVisibleToUser();
        int mine=parent;
        if(visible){
            Rect b=new Rect();n.getBoundsInScreen(b);
            if(b.intersect(0,0,width,height)&&!b.isEmpty()){
                boolean covered=false;for(Rect c:covering)if(c.contains(b.centerX(),b.centerY())){covered=true;break;}
                if(!covered){
                    boolean secret=n.isPassword();
                    String text=secret?"[REDACTED]":bounded(n.getText());
                    String description=secret?"":bounded(n.getContentDescription());
                    boolean clickable=n.isClickable()||n.isLongClickable();
                    String role=role(cls);
                    // A large unlabelled picture is content the structure cannot describe: surface it as an Image node.
                    boolean picture=role.equals("Image")&&text.isEmpty()&&description.isEmpty()&&b.width()>=120&&b.height()>=120;
                    boolean interesting=!text.isEmpty()||!description.isEmpty()||clickable||n.isEditable()||n.isCheckable()||n.isScrollable()||picture;
                    if(interesting){
                        Cand c=new Cand();c.node=n;c.bounds=new Rect(b);c.text=text;c.desc=description;c.role=role;
                        c.rid=shortId(n.getViewIdResourceName());c.clickable=clickable;c.editable=n.isEditable();c.scrollable=n.isScrollable();c.focused=n.isFocused();
                        c.checked=n.isChecked();c.checkable=n.isCheckable();c.labeled=!text.isEmpty()||!description.isEmpty();c.parent=parent;
                        out.add(c);mine=out.size()-1;
                    }
                    // Only large surface-backed views (video/game/canvas) hide content from the tree; small ones are decoration.
                    boolean surface=cls.contains("SurfaceView")||cls.contains("TextureView")||cls.contains("UnityPlayer")||cls.contains("GLSurface");
                    if((surface&&(long)b.width()*b.height()>width*(long)height/4)||(cls.endsWith("WebView")&&n.getChildCount()==0))stats.custom=true;
                }
            }
        }
        for(int i=0;i<n.getChildCount();i++)walk(n.getChild(i),depth+1,mine,out,stats,covering,width,height,deadline);
    }
    /** Short model-facing role of an Android class; plain text and layout wrappers have none. */
    private static String role(String cls){
        String s=cls.substring(cls.lastIndexOf('.')+1);
        switch(s){
            case "View":case "ViewGroup":case "FrameLayout":case "LinearLayout":case "RelativeLayout":case "ConstraintLayout":case "TextView":case "CardView":return "";
            case "ImageView":case "AppCompatImageView":return "Image";
            case "RecyclerView":case "ListView":case "ScrollView":case "NestedScrollView":case "ViewPager":case "ViewPager2":return "List";
            default:return s;
        }
    }
    private static String shortId(String resource){if(resource==null)return "";int slash=resource.lastIndexOf('/');return slash>=0?resource.substring(slash+1):resource;}    private static String bounded(CharSequence value){if(value==null)return "";String s=value.toString().replace('\u0000',' ');return s.substring(0,Math.min(s.length(),MAX_TEXT));}
    synchronized void bind(String observationId){boundObservation=observationId==null?"":observationId;if(observationId==null){release();lastSignature="";}}
    synchronized String title(){return title;}
    private void release(){nodes.clear();}
    /** Previously bound element bounds (for a focused re-read); null when unknown. */
    synchronized Rect boundsOf(String id){Entry e=nodes.get(id);return e==null?null:new Rect(e.bounds());}
    synchronized void requireBound(String observationId){if(observationId==null||observationId.isEmpty()||!observationId.equals(boundObservation)||nodes.isEmpty())ActionGate.fail("NODE_STALE","Element ids belong to the latest phone_ui_tree observation");}
    private AccessibilityNodeInfo live(String id,int displayId,boolean enabled){
        Entry e=nodes.get(id);
        if(e==null)ActionGate.fail("NODE_STALE","Unknown element id; observe the structure again");
        AccessibilityNodeInfo n=e.node();
        boolean live=n.refresh();
        if(!live){SystemClock.sleep(60);live=n.refresh();}
        if(!live||!n.isVisibleToUser()||(enabled&&!n.isEnabled()))ActionGate.fail("NODE_STALE","Element is no longer available; observe again");
        Rect b=new Rect();n.getBoundsInScreen(b);b.intersect(0,0,ProfileWorkspace.W,ProfileWorkspace.H);
        // Animated content (carousels, video overlays) can shift a node by a pixel or two. The tap still lands on the
        // same element; a larger move means the screen really changed.
        if(Math.abs(b.left-e.bounds().left)>3||Math.abs(b.top-e.bounds().top)>3||Math.abs(b.right-e.bounds().right)>3||Math.abs(b.bottom-e.bounds().bottom)>3)ActionGate.fail("NODE_STALE","Element moved; observe again");
        AccessibilityWindowInfo w=n.getWindow();
        if(w==null||w.getDisplayId()!=displayId)ActionGate.fail("WRONG_DISPLAY","Element is not on the owned display");
        return n;
    }
    /** Bounds of the largest scrollable region of the bound observation, or null (page reading and default scroll point). */
    synchronized Rect largestScrollable(){
        Rect best=null;long area=0;
        for(Entry e:nodes.values())if(e.scrollable()){long a=(long)e.bounds().width()*e.bounds().height();if(a>area){area=a;best=new Rect(e.bounds());}}
        return best;
    }
    /** Centre of the recorded, still-matching element bounds in display pixels. */
    synchronized float[] centre(String id,int displayId){
        AccessibilityNodeInfo n=live(id,displayId,true);Rect b=new Rect();n.getBoundsInScreen(b);b.intersect(0,0,ProfileWorkspace.W,ProfileWorkspace.H);
        return new float[]{Math.min(ProfileWorkspace.W-1f,b.exactCenterX()),Math.min(ProfileWorkspace.H-1f,b.exactCenterY())};
    }
    /** Semantic REPLACE of the whole field content. */
    synchronized void setText(String id,int displayId,String text){
        AccessibilityNodeInfo n=live(id,displayId,true);
        if(n.isPassword())ActionGate.fail("SECRET_INPUT","Password entry requires user takeover");
        if(!n.isEditable())ActionGate.fail("NOT_EDITABLE","Element is not an editable field");
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);
        if(!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))ActionGate.fail("TEXT_UNSUPPORTED","This field does not support semantic text input");
    }
    /** User-takeover helper for characters without a key mapping: append to the focused editable field. */
    synchronized void appendFocused(int displayId,String text)throws Exception{
        for(AccessibilityWindowInfo w:windows(displayId)){
            AccessibilityNodeInfo root=w.getRoot();if(root==null)continue;
            AccessibilityNodeInfo focus=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if(focus!=null&&focus.isEditable()){
                CharSequence current=focus.isShowingHintText()||focus.getText()==null?"":focus.getText();
                Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,current+text);
                if(focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))return;
            }
        }
        ActionGate.fail("TEXT_UNSUPPORTED","No focused editable field accepts this text");
    }
    synchronized void disconnect(){
        nodes.clear();boundObservation="";
        if(automation!=null){try{UiAutomation.class.getMethod("disconnect").invoke(automation);}catch(Exception ignored){}automation=null;}
        if(thread!=null){thread.quitSafely();thread=null;}
    }
    @Override public void close(){disconnect();}
}
