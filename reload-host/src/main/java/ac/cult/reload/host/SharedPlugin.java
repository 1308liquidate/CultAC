package ac.cult.reload.host;

import ac.grim.grimac.api.plugin.*;
import java.io.File;
import java.util.List;
import java.util.logging.Logger;

/** Native plugin metadata has no dependency on a generation's platform implementation. */
public record SharedPlugin(File getDataFolder, Logger getLogger, GrimPluginDescription getDescription)
        implements GrimPlugin {
    public static SharedPlugin create(java.nio.file.Path directory, Object nativeLogger, String build) {
        Logger logger;
        if (nativeLogger instanceof Logger jdk) logger = jdk;
        else {
            logger = new Logger("CultAC", null) {};
            logger.setUseParentHandlers(false);
            logger.addHandler(new java.util.logging.Handler() {
                public void publish(java.util.logging.LogRecord record) {
                    String method = record.getLevel().intValue() >= 1000
                            ? "error"
                            : record.getLevel().intValue() >= 900 ? "warn" : "info";
                    try {
                        var contract = nativeLogger.getClass().getClassLoader().loadClass("org.slf4j.Logger");
                        contract.getMethod(method, String.class, Throwable.class)
                                .invoke(nativeLogger, record.getMessage(), record.getThrown());
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException(failure);
                    }
                }

                public void flush() {}

                public void close() {}
            });
        }
        GrimPluginDescription description = new GrimPluginDescription() {
            public String getVersion() {
                return build;
            }

            public String getDescription() {
                return "Compensated movement anticheat";
            }

            public List<String> getAuthors() {
                return List.of("GrimCult");
            }
        };
        return new SharedPlugin(directory.toFile(), logger, description);
    }
}
