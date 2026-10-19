package dev.dsh.pocket.cowork.broker;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import java.nio.file.*;
import java.util.*;

/** Keeps the work-profile apps out of the user's launcher/home screen. Only the LAUNCHER activity components of
 * the verified work profile are disabled (component state, DONT_KILL_APP); apps, data and the ability to start them
 * through the broker are unchanged: launch() enables one component for the instant of startActivity and hides it again.
 * Every component we disable is recorded in BASE/hidden-launchers so it can be restored; creating
 * BASE/no-hide-launchers restores everything on the next reconcile. */
final class LauncherHider {
    private static final Path LIST=Path.of("/data/local/dsh-native/cowork/hidden-launchers");
    private static final Path OFF=Path.of("/data/local/dsh-native/cowork/no-hide-launchers");
    private final ProfileContext profile;
    private final TreeSet<String> hidden=new TreeSet<>();
    /** Entries enabled for a running app. Disabling the launcher activity of a LIVE app makes the system force-finish it,
     * so they stay enabled until the owned display is released ({@link #hideRevealed()}). */
    private final TreeSet<String> revealed=new TreeSet<>();
    /** Entries the broker started inside the owned display, ours or not (a just-installed app has no entry in the
     * hidden list yet). They are skipped by {@link #hideUnseen()} for as long as they run and are swept up by
     * {@link #hideRevealed()} when the display is released. */
    private final TreeSet<String> using=new TreeSet<>();
    LauncherHider(ProfileContext profile){
        this.profile=profile;
        try{if(Files.exists(LIST))hidden.addAll(Files.readAllLines(LIST));}catch(Exception ignored){}
        hidden.removeIf(String::isBlank);
        profile.hiddenByUs(c->owns(c));
    }
    synchronized boolean owns(ComponentName c){return hidden.contains(c.flattenToString());}
    private PackageManager pm()throws Exception{profile.current();return profile.context().getPackageManager();}
    private void save(){try{Files.write(LIST,hidden);}catch(Exception e){System.err.println("hidden-launchers save failed: "+e.getClass().getSimpleName());}}
    /** Disable launcher entries that are not hidden yet; restore all when the opt-out marker exists. */
    synchronized void reconcile(){
        try{
            PackageManager pm=pm();
            if(Files.exists(OFF)){
                for(String s:new ArrayList<>(hidden)){ComponentName c=ComponentName.unflattenFromString(s);if(c!=null)try{pm.setComponentEnabledSetting(c,PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,PackageManager.DONT_KILL_APP);}catch(Exception ignored){}hidden.remove(s);}
                save();return;
            }
            boolean changed=false;
            for(ComponentName c:profile.launcherComponents()){
                if(hidden.contains(c.flattenToString())){
                    // Drift repair: something re-enabled an entry we own (package update, manual pm enable, restart mid-run).
                    // reconcile() only runs while no owned display exists, so this cannot kill a running app.
                    if(!revealed.contains(c.flattenToString())&&!using.contains(c.flattenToString())&&pm.getComponentEnabledSetting(c)!=PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
                        try{pm.setComponentEnabledSetting(c,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);}catch(Exception ignored){}
                    continue;
                }
                if(pm.getComponentEnabledSetting(c)==PackageManager.COMPONENT_ENABLED_STATE_DISABLED)continue; // disabled by someone else: not ours to restore
                try{pm.setComponentEnabledSetting(c,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);}
                catch(SecurityException protectedPackage){continue;} // e.g. the profile owner itself: cannot be disabled, stays visible
                hidden.add(c.flattenToString());changed=true;
            }
            if(changed)save();
        }catch(Exception e){System.err.println("launcher reconcile failed: "+e.getClass().getSimpleName()+": "+e.getMessage());}
    }
    /** Hide every launcher entry of the profile that appeared since the last sweep, i.e. an app that was just
     * installed into it. Safe while an owned display is open: the decision ({@link LauncherEntrySweep#targets}) skips
     * entries the broker is running, so nothing live is force-finished. The personal launcher must not get a window
     * in which it can put an icon of a work-profile app on the home screen. */
    synchronized int hideUnseen(){
        try{
            if(Files.exists(OFF))return 0;
            PackageManager pm=pm();
            ArrayList<String> flats=new ArrayList<>();
            HashMap<String,ComponentName> byFlat=new HashMap<>();
            for(ComponentName c:profile.launcherComponents()){
                String flat=c.flattenToString();flats.add(flat);byFlat.put(flat,c);
            }
            List<String> todo=LauncherEntrySweep.targets(flats,hidden,using,
                flat->{try{return pm.getComponentEnabledSetting(byFlat.get(flat));}catch(Exception unknown){return LauncherEntrySweep.DISABLED;}});
            int done=0;
            for(String flat:todo){
                ComponentName c=byFlat.get(flat);
                try{pm.setComponentEnabledSetting(c,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);}
                catch(SecurityException protectedPackage){continue;} // e.g. the profile owner itself: cannot be disabled, stays visible
                catch(Exception failed){System.err.println("launcher sweep failed for "+flat+": "+failed.getClass().getSimpleName());continue;}
                hidden.add(flat);done++;
                System.err.println("hid launcher entry of a work-profile app that was still visible: "+flat);
            }
            if(done>0)save();
            return done;
        }catch(Exception e){System.err.println("launcher install sweep failed: "+e.getClass().getSimpleName()+": "+e.getMessage());return 0;}
    }
    /** Enable one of our hidden components so it can be started; returns whether it was ours. Either way the entry is
     * remembered as in use so a concurrent sweep does not disable it while the app runs. */
    synchronized boolean reveal(ComponentName c)throws Exception{
        using.add(c.flattenToString());
        if(!owns(c))return false;
        pm().setComponentEnabledSetting(c,PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,PackageManager.DONT_KILL_APP);revealed.add(c.flattenToString());return true;
    }
    /** Hide again every entry revealed for a run, ours or picked up by {@link #reveal}. Call only when no app of the
     * profile is running on the owned display. */
    synchronized void hideRevealed(){
        for(String s:new ArrayList<>(revealed)){ComponentName c=ComponentName.unflattenFromString(s);if(c!=null)hide(c);}
        for(String s:new ArrayList<>(using)){ComponentName c=ComponentName.unflattenFromString(s);if(c!=null)hide(c);}
    }
    /** {@link #hideRevealed()} for a parked display: an app that still has a task on the owned display keeps its entry
     *  (disabling its live launcher activity would force-finish it and drop the anchor the parked task provides). */
    synchronized void hideRevealedExcept(Set<String> livePackages){
        TreeSet<String> entries=new TreeSet<>(revealed);entries.addAll(using);
        for(String s:LauncherEntrySweep.idle(entries,livePackages)){ComponentName c=ComponentName.unflattenFromString(s);if(c!=null)hide(c);}
    }
    synchronized void hide(ComponentName c){
        String flat=c.flattenToString();
        revealed.remove(flat);using.remove(flat);
        try{
            if(Files.exists(OFF))return;
            pm().setComponentEnabledSetting(c,PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP);
            if(hidden.add(flat))save();
        }catch(Exception e){System.err.println("launcher re-hide failed: "+e.getClass().getSimpleName());}
    }
}