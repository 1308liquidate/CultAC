package ac.cult.reload.host;

import java.io.IOException;
import java.lang.reflect.Method;

/**
 * {@link java.lang.runtime.ObjectMethods} adapts shared method handles to each linked record type and keeps the latest
 * adaptation for a non-built-in loader in a soft reference. The generation that linked a record last therefore stays
 * reachable until memory runs short. Linking a host-owned record over the same handles releases it.
 */
final class RecordMethodCaches {
    private static final String LINKS = RecordLinks.class.getName();
    private static Method relink;

    private RecordMethodCaches() {}

    /** Defines one small hidden class per call; call once per unloaded generation. */
    static synchronized void release() {
        try {
            if (relink == null) {
                var loader = new LinksLoader(RecordMethodCaches.class.getClassLoader());
                relink = Class.forName(LINKS, true, loader).getMethod("relink");
            }
            relink.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            // A JDK that links records differently keeps no such cache.
        }
    }

    /** Keeps the linked types out of the built-in loaders, which the JDK cache never retains. */
    private static final class LinksLoader extends ClassLoader {
        LinksLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(LINKS) && !name.startsWith(LINKS + "$")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                var loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (input == null) throw new ClassNotFoundException(name);
                    byte[] bytes = input.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException failure) {
                    throw new ClassNotFoundException(name, failure);
                }
            }
        }
    }
}
