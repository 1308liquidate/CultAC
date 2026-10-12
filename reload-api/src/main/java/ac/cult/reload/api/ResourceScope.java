package ac.cult.reload.api;

import java.util.concurrent.CompletionStage;

public interface ResourceScope {
    /** Reserve accepted work until it completes, including asynchronous completion. */
    Lease acquire();

    interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    /** Register cleanup immediately after acquiring a resource. */
    <T> T track(T value, Runnable cleanup);

    Runnable guard(Runnable callback);

    Runnable once(Runnable callback);

    void run(Runnable callback);

    CompletionStage<Void> close();
}
