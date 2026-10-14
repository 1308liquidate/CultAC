package ac.cult.reload.host;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ac.cult.reload.host.fixture.LinkedRecord;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import org.junit.jupiter.api.Test;

class RecordMethodCachesTest {
    @Test
    void releaseFreesTheLoaderThatLinkedARecordLast() throws Exception {
        var loader = linkInOwnLoader();
        assumeTrue(!collected(loader), "this JDK keeps no record links for other loaders");
        RecordMethodCaches.release();
        assertTrue(collected(loader), "the loader that linked a record is still reachable");
    }

    private static WeakReference<ClassLoader> linkInOwnLoader() throws Exception {
        URL classes = LinkedRecord.class.getProtectionDomain().getCodeSource().getLocation();
        var loader = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader());
        loader.loadClass(LinkedRecord.class.getName()).getMethod("link").invoke(null);
        loader.close();
        return new WeakReference<>(loader);
    }

    private static boolean collected(WeakReference<ClassLoader> loader) throws InterruptedException {
        for (int attempt = 0; attempt < 10 && loader.get() != null; attempt++) {
            System.gc();
            Thread.sleep(20);
        }
        return loader.get() == null;
    }
}
