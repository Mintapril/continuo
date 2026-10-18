package dev.dsh.pocket.cowork.broker;

/** Pins platform identity, including all capability flags, for one owned virtual display lifetime. */
public final class OwnedDisplayIdentity {
    private OwnedDisplayIdentity() {}
    public static String require(boolean valid,int id,int objectId,int type,int flags,String unique,int ownerUid,String ownerPackage) {
        // TYPE_VIRTUAL=5 is hidden from the SDK. Display.FLAG_SECURE=2 describes a capability,
        // not current window content. Secure export is independently blocked by the WMS probe
        // and compositor capture with captureSecureLayers=false / allowProtected=false.
        if(!valid||id<=0||id!=objectId||type!=5||unique==null||unique.isBlank())throw new IllegalStateException();
        return id+"|"+unique+"|"+type+"|"+ownerUid+"|"+ownerPackage+"|"+flags;
    }
}
