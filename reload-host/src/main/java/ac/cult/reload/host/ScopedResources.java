package ac.cult.reload.host;

import ac.cult.reload.api.ResourceScope;
import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** One lifetime for calls, callbacks, accepted queued work, and registration cleanup. */
public final class ScopedResources implements ResourceScope {
    private final ArrayDeque<Runnable> cleanup = new ArrayDeque<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private boolean closing;
    private int inFlight;
    private Throwable failure;
    private volatile ClassLoader owner;

    public ScopedResources() {}

    public ScopedResources(ClassLoader owner) {
        this.owner = owner;
    }

    @Override
    public synchronized Lease acquire() {
        if (closing) throw RuntimeFailures.ended();
        return reserve();
    }

    private Lease reserve() {
        inFlight++;
        return new Ticket();
    }

    public synchronized int inFlight() {
        return inFlight;
    }

    public <T> T call(Callable<T> action) throws Exception {
        try (var ticket = acquire()) {
            return inContext(action);
        } catch (Throwable error) {
            throw RuntimeFailures.detached(error);
        }
    }

    public <T> CompletionStage<T> callAsync(Callable<? extends CompletionStage<T>> action) {
        Lease ticket;
        try {
            ticket = acquire();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(RuntimeFailures.detached(error));
        }
        var result = new CompletableFuture<T>();
        try {
            inContext(action).whenComplete((value, error) -> {
                ticket.close();
                if (error == null) result.complete(value);
                else result.completeExceptionally(RuntimeFailures.detached(error));
            });
        } catch (Throwable error) {
            ticket.close();
            result.completeExceptionally(RuntimeFailures.detached(error));
        }
        return result;
    }

    private <T> T inContext(Callable<T> action) throws Exception {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try {
            if (owner != null) thread.setContextClassLoader(owner);
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Override
    public <T> T track(T value, Runnable release) {
        synchronized (this) {
            if (!closing) {
                cleanup.push(release);
                return value;
            }
        }
        release.run();
        throw RuntimeFailures.ended();
    }

    @Override
    public Runnable guard(Runnable callback) {
        var guarded = new GuardedTask(callback);
        return track(guarded, () -> guarded.callback = null);
    }

    @Override
    public void run(Runnable callback) {
        Lease ticket;
        synchronized (this) {
            if (closing) return;
            ticket = reserve();
        }
        runAccepted(ticket, callback);
    }

    private void runAccepted(Lease ticket, Runnable callback) {
        try (ticket) {
            inContext(() -> {
                callback.run();
                return null;
            });
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public Runnable once(Runnable callback) {
        return new OnceTask(acquire(), callback);
    }

    @Override
    public CompletionStage<Void> close() {
        ArrayDeque<Runnable> releases;
        Lease cancellation;
        synchronized (this) {
            if (closing) return stopped.minimalCompletionStage();
            closing = true;
            cancellation = reserve();
            releases = new ArrayDeque<>(cleanup);
            cleanup.clear();
        }
        try (cancellation) {
            for (var release : releases) {
                try {
                    release.run();
                } catch (Throwable error) {
                    synchronized (this) {
                        if (failure == null) failure = error;
                        else failure.addSuppressed(error);
                    }
                }
            }
        }
        return stopped.minimalCompletionStage();
    }

    private final class Ticket implements Lease {
        private boolean released;

        @Override
        public void close() {
            Throwable error;
            synchronized (ScopedResources.this) {
                if (released) return;
                released = true;
                inFlight--;
                if (!closing || inFlight != 0) return;
                error = failure;
                if (error == null) owner = null;
            }
            if (error == null) stopped.complete(null);
            else stopped.completeExceptionally(error);
        }
    }

    private final class GuardedTask implements Runnable {
        private volatile Runnable callback;

        GuardedTask(Runnable callback) {
            this.callback = callback;
        }

        public void run() {
            var action = callback;
            if (action != null) ScopedResources.this.run(action);
        }
    }

    private final class OnceTask implements Runnable, AutoCloseable {
        private final Lease ticket;
        private Runnable callback;

        OnceTask(Lease ticket, Runnable callback) {
            this.ticket = ticket;
            this.callback = callback;
        }

        public void run() {
            Runnable action;
            synchronized (this) {
                action = callback;
                callback = null;
            }
            if (action != null) runAccepted(ticket, action);
        }

        public void close() {
            synchronized (this) {
                if (callback == null) return;
                callback = null;
            }
            ticket.close();
        }
    }
}
