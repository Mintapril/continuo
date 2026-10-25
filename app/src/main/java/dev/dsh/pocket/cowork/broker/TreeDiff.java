package dev.dsh.pocket.cowork.broker;

import java.util.*;

/** Element ids that stay the same across observations, and what changed since the last full screen the model got.
 * An input returns the settled screen; most inputs change a few elements of it (a field filled, a switch ticked, a
 * message added), so instead of the whole compact tree the model gets what changed against its base: the last full
 * screen (nodes) it received, whose elements keep their ids. Added and changed elements come complete with their ids,
 * removed ones as ids, and the rest is a count. A screen that changed a lot (half its elements or more), another app,
 * an explicit phone_ui_tree, or no base yet gets the full tree and becomes the new base.
 *
 * Ids are never reused within a task ({@link #reset} at task start): an id the model kept from an older screen can only
 * name nothing, never another element. A node keeps its id when it is the same element: the same label and place, the
 * same label moved (a list scrolled a little), or, for a field (resource id or editable), the same place with another
 * text (what the model typed). Diffs are relative to the base, not to the previous diff, so the model only ever combines
 * its base with the latest observation. No Android types, so it is unit tested on the JVM. */
public final class TreeDiff {
    /** One element as the model sees it, without its id. */
    public record Node(String text,String desc,String role,String rid,int left,int top,int right,int bottom,String flags){
        String label(){return text+"\u0001"+desc+"\u0001"+role+"\u0001"+rid;}
        String exact(){return label()+"\u0001"+left+","+top+","+right+","+bottom+"\u0001"+flags;}
        String moved(){return label()+"\u0001"+flags;}
        /** A field (resource id, or editable) at its place, whatever text it shows now; null for anything else. */
        String field(){return rid.isEmpty()&&!flags.contains("e")?null:role+"\u0001"+rid+"\u0001"+left+","+top+","+right+","+bottom;}
    }
    /** The ids of one read (in its order), and how it differs from the base: indexes into the read, removed ids. */
    public record Read(List<String> ids,boolean full,String base,List<Integer> added,List<Integer> changed,List<String> removed,int unchanged){}

    private int next;
    /** Every id given since the base, with the state it showed last. */
    private final LinkedHashMap<String,Node> known=new LinkedHashMap<>();
    /** The base: the last full screen the model got, as it was then. */
    private Map<String,Node> base=Map.of();
    private String basePkg="",baseObservation="";
    private boolean hasBase,lastFull;

    /** A new task: no base, ids start again. */
    public synchronized void reset(){next=0;known.clear();base=Map.of();basePkg="";baseObservation="";hasBase=false;lastFull=false;}

    /** Ids for a read of the screen in front ([pkg]): matched against the base when [full] is not required. */
    public synchronized Read read(String pkg,List<Node> now,boolean full){
        if(!full&&hasBase&&pkg.equals(basePkg)&&!now.isEmpty()){
            String[] matched=match(now);
            List<Integer> added=new ArrayList<>(),changed=new ArrayList<>();int unchanged=0;
            HashSet<String> present=new HashSet<>();
            for(int i=0;i<now.size();i++){
                Node was=matched[i]==null?null:base.get(matched[i]);if(matched[i]!=null)present.add(matched[i]);
                if(was==null)added.add(i);else if(!was.equals(now.get(i)))changed.add(i);else unchanged++;
            }
            List<String> removed=new ArrayList<>();for(String id:base.keySet())if(!present.contains(id))removed.add(id);
            if((added.size()+changed.size()+removed.size())*2<now.size()){
                // New elements get their ids only now: a read that turns out full gives every element a fresh one.
                List<String> ids=new ArrayList<>(now.size());
                for(int i=0;i<now.size();i++){String id=matched[i]!=null?matched[i]:"n"+(next++);ids.add(id);known.put(id,now.get(i));}
                lastFull=false;
                return new Read(List.copyOf(ids),false,baseObservation,List.copyOf(added),List.copyOf(changed),List.copyOf(removed),unchanged);
            }
        }
        List<String> ids=new ArrayList<>(now.size());LinkedHashMap<String,Node> fresh=new LinkedHashMap<>();
        known.clear();
        for(Node n:now){String id="n"+(next++);ids.add(id);known.put(id,n);fresh.put(id,n);}
        base=fresh;basePkg=pkg;baseObservation="";hasBase=false;lastFull=true;
        return new Read(List.copyOf(ids),true,"",List.of(),List.of(),List.of(),now.size());
    }

    /** The last read reached the model as [observationId]: when it was full, it is the base of the reads after it. A
     *  full read that never reached the model (a read retried, a failed observation) leaves no base, so the next read
     *  is full again. */
    public synchronized void delivered(String observationId){if(lastFull&&observationId!=null&&!observationId.isEmpty()){baseObservation=observationId;hasBase=true;}lastFull=false;}

    /** The known id each element of [now] is, or null for a new element. */
    private String[] match(List<Node> now){
        String[] ids=new String[now.size()];
        HashSet<String> used=new HashSet<>();
        for(int pass=0;pass<3;pass++){
            HashMap<String,ArrayDeque<String>> byKey=new HashMap<>();
            for(Map.Entry<String,Node> e:known.entrySet()){if(used.contains(e.getKey()))continue;String key=key(pass,e.getValue());if(key!=null)byKey.computeIfAbsent(key,k->new ArrayDeque<>()).add(e.getKey());}
            for(int i=0;i<ids.length;i++){
                if(ids[i]!=null)continue;String key=key(pass,now.get(i));if(key==null)continue;
                ArrayDeque<String> free=byKey.get(key);
                while(free!=null&&!free.isEmpty()){String id=free.poll();if(used.add(id)){ids[i]=id;break;}}
            }
        }
        return ids;
    }
    private static String key(int pass,Node n){return pass==0?n.exact():pass==1?n.moved():n.field();}
}
