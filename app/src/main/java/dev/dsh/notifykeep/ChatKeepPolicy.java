package dev.dsh.notifykeep;

/**
 * Which work-profile processes the keeper pins: a short list of apps the owner picks (default QQ and WeChat), and in
 * each only the main process plus its long-link process, never every process (WeChat alone also runs :br, :patch and
 * WebView sandboxes).
 */
public final class ChatKeepPolicy {
    public static final int USER = 10;
    public static final String OWNER = "dev.dsh.pocket.isolation/.ProfileAdminReceiver";
    /** Root-owned, one package per line, written from 设置 → 它的手机; when absent the keeper uses {@link #DEFAULT_PACKAGES}. */
    public static final String LIST_FILE = "/data/local/dsh-native/chat-notify-keeper/packages";
    public static final java.util.List<String> DEFAULT_PACKAGES =
            java.util.Collections.unmodifiableList(java.util.Arrays.asList("com.tencent.mobileqq", "com.tencent.mm"));
    /** Every pinned app stays resident (10-26: eleven restored apps held 3–4 GB), so the list stays short. */
    public static final int MAX_PACKAGES = 8;
    private static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private ChatKeepPolicy() {}

    /** An Android package name; also what makes a name safe to pass through a root shell. */
    public static boolean validName(String name) {
        return name != null && name.length() <= 200 && NAME.matcher(name).matches();
    }

    /** The list file: blank lines, comments and invalid names are skipped, duplicates dropped, at most {@link #MAX_PACKAGES}. */
    public static java.util.List<String> parsePackages(String text) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String line : text.split("\n")) {
            String name = line.trim();
            if (name.isEmpty() || name.startsWith("#") || !validName(name)) continue;
            out.add(name);
            if (out.size() == MAX_PACKAGES) break;
        }
        return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(out));
    }

    /** A process of a listed app: the main process, QQ's ":MSF", or a process whose name says push (WeChat's ":push"). */
    public static boolean accepts(String pkg, String process, int uid, int expectedUid,
                                  boolean enabled, boolean stopped, boolean suspended) {
        if (uid != expectedUid || uid / 100000 != USER || !enabled || stopped || suspended || process == null) return false;
        if (process.equals(pkg)) return true;
        if (!process.startsWith(pkg + ":")) return false;
        String suffix = process.substring(pkg.length() + 1);
        return suffix.equals("MSF") || suffix.toLowerCase(java.util.Locale.ROOT).contains("push");
    }
    public record ProcessState(boolean ours, boolean other, boolean fgs) {}
    public static java.util.Map<Integer, ProcessState> parseStates(String dump, String reason) {
        java.util.Map<Integer, ProcessState> result = new java.util.HashMap<>();
        var header = java.util.regex.Pattern.compile("(?m)^  \\*APP\\* UID \\d+ ProcessRecord\\{[^\\n]*? (\\d+):[^\\n]*\\}");
        var matches = header.matcher(dump);
        java.util.ArrayList<Integer> pids = new java.util.ArrayList<>(), offsets = new java.util.ArrayList<>();
        while (matches.find()) { pids.add(Integer.parseInt(matches.group(1))); offsets.add(matches.start()); }
        for (int i = 0; i < pids.size(); i++) {
            String block = dump.substring(offsets.get(i), i + 1 < offsets.size() ? offsets.get(i + 1) : dump.length());
            var section = java.util.regex.Pattern.compile("(?m)^  \\S").matcher(block);
            if (section.find(1)) block = block.substring(0, section.start());
            var forcing = java.util.regex.Pattern.compile("(?m)^    mHasForegroundServices=(true|false) forcingToImportant=([^\\r\\n]*)").matcher(block);
            String importance = "null"; boolean fgs = false;
            if (forcing.find()) { fgs = forcing.group(1).equals("true"); importance = forcing.group(2).trim(); }
            boolean ours = importance.contains(" " + reason + " " + pids.get(i) + " ");
            boolean other = !importance.equals("null") && !ours;
            result.put(pids.get(i), new ProcessState(ours, other, fgs));
        }
        return result;
    }
    public static long startTicks(String stat) {
        int end = stat.lastIndexOf(')');
        if (end < 0) throw new IllegalArgumentException("bad proc stat");
        String[] fields = stat.substring(end + 1).trim().split("\\s+");
        if (fields.length < 20) throw new IllegalArgumentException("short proc stat");
        return Long.parseLong(fields[19]); // /proc/<pid>/stat field 22
    }
}
