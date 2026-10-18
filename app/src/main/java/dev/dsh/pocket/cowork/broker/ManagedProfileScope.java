package dev.dsh.pocket.cowork.broker;

import java.util.Objects;

/**
 * Pure identity boundary for the forthcoming managed-profile driver. This is NOT an app allowlist.
 * Callers must obtain every claim from trusted Android services, never model arguments, labels,
 * accessibility package strings, window titles or the existence of an installation in this profile.
 * Not enabled in the legacy shared-user driver until window/input ownership can be attested.
 */
public final class ManagedProfileScope {
    public static final String OWNER="dev.dsh.pocket.isolation/.ProfileAdminReceiver";
    public static final String OWNER_SIGNER="131906a5339de6283c227af40f6bf06f9abcfd10736d453bd203c1543dc2bf81";
    public record Identity(int userId,long serial,int parentId,String owner,String signer,
                           boolean managed,boolean running,boolean unlocked,boolean quiet,boolean partial) {}
    public record App(String packageName,int userId,int uid,boolean installed,boolean enabled,boolean suspended) {}
    public record WindowOwner(int displayId,int userId,int uid,String packageName) {}
    private final Identity pinned;
    public ManagedProfileScope(Identity identity) { requireUsable(identity);pinned=identity; }
    public int userId(){return pinned.userId();}
    public long serial(){return pinned.serial();}
    public void requireCurrent(Identity current) {
        requireUsable(current);
        if(current.userId()!=pinned.userId()||current.serial()!=pinned.serial()||current.parentId()!=pinned.parentId()
            ||!Objects.equals(current.owner(),pinned.owner())||!Objects.equals(current.signer(),pinned.signer()))deny();
    }
    public void requireApp(Identity current,App app) {
        requireCurrent(current);
        if(app==null||!packageName(app.packageName())||app.userId()!=pinned.userId()||app.uid()<0
            ||app.uid()/100000!=pinned.userId()||!app.installed()||!app.enabled()||app.suspended())deny();
    }
    /** The producer must bind this owner to the exact window/input token being acted upon. */
    public void requireWindow(Identity current,App app,WindowOwner window,int ownedDisplay) {
        requireApp(current,app);
        if(window==null||ownedDisplay<=0||window.displayId()!=ownedDisplay||window.userId()!=pinned.userId()
            ||window.uid()!=app.uid()||!Objects.equals(window.packageName(),app.packageName()))deny();
    }
    private static void requireUsable(Identity identity) {
        if(identity==null||identity.userId()<=0||identity.userId()>21473||identity.serial()<0||identity.parentId()!=0
            ||!identity.managed()||!identity.running()||!identity.unlocked()||identity.quiet()||identity.partial()
            ||!OWNER.equals(identity.owner())||!OWNER_SIGNER.equals(identity.signer()))deny();
    }
    private static boolean packageName(String name) {
        return name!=null&&name.matches("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }
    private static void deny(){ActionGate.fail("APP_DENIED","Verified managed-profile identity and exact window ownership required");}
}
