package dev.dsh.pocket.cowork.broker;

import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Read-only Android16 adapter. No user creation, policy mutation, shell, fallback user or app allowlist.
 * IUserManager signatures: AOSP android16-release/core/java/android/os/IUserManager.aidl.
 * A matching installation alone is NOT permission to read or input to an accessibility window.
 */
public final class ProfileContext {
    private final Object users, policies;
    private final Class<?> usersType, policiesType;
    private final Context system;
    private final ManagedProfileScope scope;
    private final Context profile;
    private volatile java.util.function.Predicate<ComponentName> hiddenByUs=c->false;
    void hiddenByUs(java.util.function.Predicate<ComponentName> value){hiddenByUs=value;}
    /** Every launcher activity of the profile including those we disabled to keep them off the home screen. */
    List<ComponentName> launcherComponents() throws Exception {
        current();Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> rows=profile.getPackageManager().queryIntentActivities(intent,PackageManager.MATCH_DISABLED_COMPONENTS);
        if(rows.size()>2000)throw unavailable();
        ArrayList<ComponentName> out=new ArrayList<>();
        for(ResolveInfo row:rows){ActivityInfo a=row.activityInfo;if(a!=null&&a.exported)out.add(new ComponentName(a.packageName,a.name));}
        return out;
    }
    private boolean usable(ActivityInfo a){return a!=null&&a.exported&&(a.enabled||hiddenByUs.test(new ComponentName(a.packageName,a.name)));}
    public ProfileContext(Context context) throws Exception {
        system=context;
        usersType=Class.forName("android.os.IUserManager");
        policiesType=Class.forName("android.app.admin.IDevicePolicyManager");
        users=service("user",usersType);policies=service("device_policy",policiesType);
        Object raw=call(usersType,users,"getProfiles",new Class<?>[]{int.class,boolean.class},0,false);
        if(!(raw instanceof List<?> rows)||rows.size()>32)throw unavailable();
        ManagedProfileScope.Identity found=null;
        for(Object row:rows){
            if(row==null)throw unavailable();
            int id=intField(row,"id");
            if(id<=0||!"android.os.usertype.profile.MANAGED".equals(field(row,"userType")))continue;
            ComponentName owner=owner(id);
            if(owner==null||!ManagedProfileScope.OWNER.equals(owner.flattenToShortString()))continue;
            ManagedProfileScope.Identity candidate=identity(id);
            new ManagedProfileScope(candidate);
            if(found!=null)throw unavailable();
            found=candidate;
        }
        if(found==null)throw unavailable();
        scope=new ManagedProfileScope(found);profile=forUser(found.userId());
        current();
    }
    public int userId(){return scope.userId();}
    public long serial(){return scope.serial();}
    public Context context(){return profile;}
    public ManagedProfileScope.Identity current() throws Exception {
        ManagedProfileScope.Identity value=identity(scope.userId());scope.requireCurrent(value);return value;
    }
    public ManagedProfileScope.App app(String pkg) throws Exception {
        if(pkg==null||!pkg.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))throw unavailable();
        ManagedProfileScope.Identity identity=current();
        ApplicationInfo info=profile.getPackageManager().getApplicationInfo(pkg,0);
        ManagedProfileScope.App app=new ManagedProfileScope.App(info.packageName,userId(),info.uid,
            (info.flags&ApplicationInfo.FLAG_INSTALLED)!=0,info.enabled,
            (info.flags&ApplicationInfo.FLAG_SUSPENDED)!=0);
        scope.requireApp(identity,app);return app;
    }
    /** Bounded launcher metadata only. No process-wide PackageManager/current-user fallback. */
    public List<ManagedProfileScope.App> launcherApps() throws Exception {
        current();Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> rows=profile.getPackageManager().queryIntentActivities(intent,PackageManager.MATCH_DISABLED_COMPONENTS);
        if(rows.size()>2000)throw unavailable();
        TreeMap<String,ManagedProfileScope.App> apps=new TreeMap<>();
        for(ResolveInfo row:rows){
            ActivityInfo activity=row.activityInfo;
            if(!usable(activity))continue;
            apps.put(activity.packageName,app(activity.packageName));
            if(apps.size()>512)throw unavailable();
        }
        current();return List.copyOf(apps.values());
    }
    public ComponentName launcher(String pkg) throws Exception {
        ManagedProfileScope.App allowed=app(pkg);
        Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
        List<ResolveInfo> rows=profile.getPackageManager().queryIntentActivities(intent,PackageManager.MATCH_DISABLED_COMPONENTS);
        if(rows.size()>128)throw unavailable();
        for(ResolveInfo row:rows){ActivityInfo a=row.activityInfo;
            if(usable(a)&&pkg.equals(a.packageName)&&a.applicationInfo!=null&&a.applicationInfo.uid==allowed.uid())
                return new ComponentName(a.packageName,a.name);
        }
        throw unavailable();
    }
    private ManagedProfileScope.Identity identity(int id) throws Exception {
        Object info=call(usersType,users,"getUserInfo",new Class<?>[]{int.class},id);
        if(info==null||intField(info,"id")!=id)throw unavailable();
        Object parent=call(usersType,users,"getProfileParent",new Class<?>[]{int.class},id);
        ComponentName owner=owner(id);
        if(parent==null||owner==null)throw unavailable();
        long serial=((Number)call(usersType,users,"getUserSerialNumber",new Class<?>[]{int.class},id)).longValue();
        if(serial!=((Number)field(info,"serialNumber")).longValue())throw unavailable();
        PackageInfo pkg=forUser(id).getPackageManager().getPackageInfo(owner.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        SigningInfo signing=pkg.signingInfo;
        if(signing==null)throw unavailable();
        Signature[] signatures=signing.getApkContentsSigners();
        if(signatures==null||signatures.length!=1)throw unavailable();
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
        StringBuilder digest=new StringBuilder();for(byte b:bytes)digest.append(String.format(Locale.ROOT,"%02x",b&255));
        return new ManagedProfileScope.Identity(id,serial,intField(parent,"id"),owner.flattenToShortString(),digest.toString(),
            "android.os.usertype.profile.MANAGED".equals(field(info,"userType")),
            bool("isUserRunning",id),bool("isUserUnlocked",id),bool("isQuietModeEnabled",id),(Boolean)field(info,"partial"));
    }
    private ComponentName owner(int id) throws Exception {
        return (ComponentName)call(policiesType,policies,"getProfileOwnerAsUser",new Class<?>[]{int.class},id);
    }
    private boolean bool(String method,int id)throws Exception{return (Boolean)call(usersType,users,method,new Class<?>[]{int.class},id);}
    private Context forUser(int id)throws Exception{
        UserHandle user=(UserHandle)UserHandle.class.getMethod("of",int.class).invoke(null,id);
        return (Context)Context.class.getMethod("createContextAsUser",UserHandle.class,int.class).invoke(system,user,0);
    }
    private static Object service(String name,Class<?> type)throws Exception{
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
        if(binder==null)throw unavailable();
        Object result=Class.forName(type.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        if(result==null)throw unavailable();return result;
    }
    private static Object call(Class<?> type,Object instance,String method,Class<?>[] signature,Object... args)throws Exception{
        return type.getMethod(method,signature).invoke(instance,args);
    }
    private static Object field(Object object,String name)throws Exception{return object.getClass().getField(name).get(object);}
    private static int intField(Object object,String name)throws Exception{return ((Number)field(object,name)).intValue();}
    private static ActionGate.Denied unavailable(){return new ActionGate.Denied("APP_DENIED","Verified work profile unavailable; no personal-user fallback");}
}
