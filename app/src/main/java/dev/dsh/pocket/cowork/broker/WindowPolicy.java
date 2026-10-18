package dev.dsh.pocket.cowork.broker;

/** Fail closed when any visible window belongs to an unapproved app, including overlays/IME. */
public final class WindowPolicy {
    private WindowPolicy() {}
    /** Only the controlled, networkless fixture exports accessibility content. */
    public static boolean mayExportText(String pkg) { return "dev.dsh.pocket.fixture".equals(pkg); }
    public static void requireAllowed(ActionGate gate, Iterable<String> packages, boolean requireContent) {
        boolean found=false;
        for(String pkg:packages) { gate.requirePackage(pkg);found=true; }
        if(requireContent&&!found)ActionGate.fail("APP_UNKNOWN","Cannot identify the owned display content");
    }
}
