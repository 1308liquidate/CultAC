package ac.cult.reload.host;

import java.util.Set;

/** Existing explicit player targets; self commands otherwise use the sender's session. */
public final class CommandRouting {
    private static final Set<String> TARGET =
            Set.of("profile", "log", "consoledebug", "javapacketlog", "geyserpacketlog", "spectate");
    private static final Set<String> GLOBAL = Set.of("list", "brands", "performance", "dump");

    private CommandRouting() {}

    public static String target(String[] args) {
        return args.length > 1 && TARGET.contains(args[0].toLowerCase(java.util.Locale.ROOT)) ? args[1] : null;
    }

    public static boolean aggregate(String[] args) {
        return args.length > 0 && GLOBAL.contains(args[0].toLowerCase(java.util.Locale.ROOT));
    }
}
