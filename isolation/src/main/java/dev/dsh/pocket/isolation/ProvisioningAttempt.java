package dev.dsh.pocket.isolation;

/** One disposable attempt across launcher instances/restarts. No automatic reset or retry. */
final class ProvisioningAttempt {
    interface Store {
        boolean attempted();
        boolean persistAttempt();
    }
    private ProvisioningAttempt() {}
    static synchronized boolean available(Store store) {
        try { return !store.attempted(); } catch (RuntimeException e) { return false; }
    }
    static synchronized boolean claim(Store store) {
        try { return !store.attempted() && store.persistAttempt(); }
        catch (RuntimeException e) { return false; }
    }
}
