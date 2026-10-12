package ac.cult.reload.host;

import java.util.function.Consumer;

/** Native schedulers may retain cancelled entries; their callbacks must remain host-owned. */
public final class Callbacks {
    public static io.netty.util.concurrent.GenericFutureListener<io.netty.util.concurrent.Future<Object>> future(
            ac.cult.reload.api.ResourceScope scope, Runnable callback) {
        var guarded = scope.guard(callback);
        return ignored -> guarded.run();
    }

    private Callbacks() {}

    public static <T> Consumer<T> run(Runnable guarded) {
        return ignored -> guarded.run();
    }

    public static <A, B> java.util.function.BiConsumer<A, B> guard(
            ac.cult.reload.api.ResourceScope scope, java.util.function.BiConsumer<A, B> callback) {
        var guarded = new Completion<A, B>(scope, callback);
        scope.track(guarded, () -> guarded.callback = null);
        return guarded;
    }

    private static final class Completion<A, B> implements java.util.function.BiConsumer<A, B> {
        private final ac.cult.reload.api.ResourceScope scope;
        private volatile java.util.function.BiConsumer<A, B> callback;

        Completion(ac.cult.reload.api.ResourceScope scope, java.util.function.BiConsumer<A, B> callback) {
            this.scope = scope;
            this.callback = callback;
        }

        public void accept(A first, B second) {
            var action = callback;
            if (action != null) scope.run(() -> action.accept(first, second));
        }
    }
}
