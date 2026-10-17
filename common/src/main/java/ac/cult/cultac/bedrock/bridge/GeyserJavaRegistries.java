package ac.cult.cultac.bedrock.bridge;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.geysermc.geyser.registry.java.reader.JavaRegistryReaders;
import org.geysermc.geyser.session.GeyserSession;

/**
 * The Java registry entries a Geyser session received, by registry name. Geyser 2.12 replaced
 * {@code GeyserSession#getRegistryCache()} and {@code RegistryCache.READERS} with
 * {@code GeyserSession#javaRegistries()} and {@code JavaRegistryReaders#networkRegistries()};
 * builds before that change are read reflectively.
 */
final class GeyserJavaRegistries {
    private interface Source {
        Map<String, List<String>> received(GeyserSession session) throws ReflectiveOperationException;
    }

    private static final Source SOURCE = source();

    private GeyserJavaRegistries() {}

    static Map<String, List<String>> received(GeyserSession session) {
        try {
            return SOURCE.received(session);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read Geyser Java registries", failure);
        }
    }

    private static Source source() {
        var loader = GeyserSession.class.getClassLoader();
        try {
            Class.forName("org.geysermc.geyser.registry.java.reader.JavaRegistryReaders", false, loader);
            return Current::received;
        } catch (ClassNotFoundException absent) {
            try {
                return Legacy.load(loader);
            } catch (ReflectiveOperationException failure) {
                return session -> {
                    throw new IllegalStateException("Unsupported Geyser Java registry API", failure);
                };
            }
        }
    }

    // Geyser-Spigot relocates Adventure; only strings cross that boundary.
    private static String name(Object key) throws ReflectiveOperationException {
        return key.getClass().getMethod("registryKey").invoke(key).toString();
    }

    private static List<String> entries(Collection<?> keys) {
        return keys.stream().map(Object::toString).toList();
    }

    /** Geyser 2.12 and newer. Loaded only when its registry API is present. */
    private static final class Current {
        static Map<String, List<String>> received(GeyserSession session) throws ReflectiveOperationException {
            var registries = session.javaRegistries();
            var received = new HashMap<String, List<String>>();
            for (var key : JavaRegistryReaders.networkRegistries()) {
                var registry = registries.registry(key);
                if (registry != null) received.put(name(key), entries(registry.keys()));
            }
            return received;
        }
    }

    /** Geyser before 2.12: {@code session.getRegistryCache().registry(key).keys()} over {@code READERS}. */
    private record Legacy(Map<?, ?> readers, Method cache, Method registry, Method keys) implements Source {
        static Legacy load(ClassLoader loader) throws ReflectiveOperationException {
            var cache = Class.forName("org.geysermc.geyser.session.cache.RegistryCache", true, loader);
            var key = Class.forName("org.geysermc.geyser.session.cache.registry.JavaRegistryKey", false, loader);
            var registry = Class.forName("org.geysermc.geyser.session.cache.registry.JavaRegistry", false, loader);
            return new Legacy(
                    (Map<?, ?>) cache.getField("READERS").get(null),
                    GeyserSession.class.getMethod("getRegistryCache"),
                    cache.getMethod("registry", key),
                    registry.getMethod("keys"));
        }

        @Override
        public Map<String, List<String>> received(GeyserSession session) throws ReflectiveOperationException {
            var registries = cache.invoke(session);
            var received = new HashMap<String, List<String>>();
            for (var key : readers.keySet()) {
                received.put(name(key), entries((Collection<?>) keys.invoke(registry.invoke(registries, key))));
            }
            return received;
        }
    }
}
