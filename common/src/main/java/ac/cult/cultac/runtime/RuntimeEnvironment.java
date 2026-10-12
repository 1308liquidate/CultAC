package ac.cult.cultac.runtime;

import ac.cult.reload.api.HostContext;
import java.util.concurrent.CompletionStage;

/** This class is private to each generation; offline fixtures have no host context. */
public final class RuntimeEnvironment {
    private static HostContext context;

    private RuntimeEnvironment() {}

    public static void initialize(HostContext host) {
        if (context != null) throw new IllegalStateException("Runtime already bound");
        context = host;
    }

    public static HostContext host() {
        return context;
    }

    public static boolean owns(Object session) {
        return context == null || context.owns(session);
    }

    public static boolean pin(Object session) {
        return context == null || context.pin(session);
    }

    public static void release(Object session, CompletionStage<Void> cleanup) {
        if (context != null) context.release(session, cleanup);
    }

    public static <T> T track(T resource, Runnable cleanup) {
        return context == null ? resource : context.resources().track(resource, cleanup);
    }

    public static Runnable guard(Runnable task) {
        return context == null ? task : context.resources().guard(task);
    }

    public static Runnable once(Runnable task) {
        return context == null ? task : context.resources().once(task);
    }

    public static <A, B> java.util.function.BiConsumer<A, B> completion(java.util.function.BiConsumer<A, B> callback) {
        return context == null ? callback : ac.cult.reload.host.Callbacks.guard(context.resources(), callback);
    }

    public static java.net.http.HttpClient http(java.time.Duration timeout) {
        var client = context == null
                ? java.net.http.HttpClient.newBuilder().connectTimeout(timeout).build()
                : ac.cult.reload.host.HttpClients.create(timeout);
        return track(client, client::close);
    }

    public static ac.grim.grimac.api.GrimUser outwardUser(ac.grim.grimac.api.GrimUser user) {
        return context == null ? user : (ac.grim.grimac.api.GrimUser) context.wrap(user, user);
    }

    public static ac.grim.grimac.api.AbstractCheck outwardCheck(ac.grim.grimac.api.AbstractCheck check) {
        return context == null
                ? check
                : (ac.grim.grimac.api.AbstractCheck) context.wrap(
                        check, check instanceof ac.cult.cultac.checks.Check impl ? impl.getPlayer() : null);
    }

    @SuppressWarnings("unchecked")
    public static java.util.function.Supplier<String> outwardVerbose(java.util.function.Supplier<String> supplier) {
        return context == null ? supplier : (java.util.function.Supplier<String>) context.wrap(supplier);
    }
}
