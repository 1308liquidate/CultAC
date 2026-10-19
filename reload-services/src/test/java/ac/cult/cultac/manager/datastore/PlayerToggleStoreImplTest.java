package ac.cult.cultac.manager.datastore;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PlayerToggleStoreImplTest {
    @Test
    void lazyFlushWorkerDoesNotInheritTheCallingRuntimeClassloader() throws Exception {
        var scheduler = PlayerToggleStoreImpl.defaultScheduler();
        var caller = Thread.currentThread();
        var previous = caller.getContextClassLoader();
        try (var runtime = new URLClassLoader(new URL[0], previous)) {
            caller.setContextClassLoader(runtime);
            Thread worker = scheduler
                    .schedule(Thread::currentThread, 0, TimeUnit.MILLISECONDS)
                    .get(5, TimeUnit.SECONDS);
            assertSame(PlayerToggleStoreImpl.class.getClassLoader(), worker.getContextClassLoader());
        } finally {
            caller.setContextClassLoader(previous);
            scheduler.shutdownNow();
        }
    }
}
