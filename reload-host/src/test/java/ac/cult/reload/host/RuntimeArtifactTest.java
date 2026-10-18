package ac.cult.reload.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeArtifactTest {
    @TempDir
    Path directory;

    @Test
    void acceptsEveryHostInTheRange() {
        for (String host : new String[] {"1.21.2", "1.21.4", "1.21.11", "26.1", "26.2", "26.3", "26.3.1"})
            assertTrue(RuntimeArtifact.supports("1.21.2", "26.3", host), host);
    }

    @Test
    void refusesHostsOutsideTheRange() {
        for (String host : new String[] {"1.21.1", "1.21", "1.20.6", "26.4", "27.0"})
            assertFalse(RuntimeArtifact.supports("1.21.2", "26.3", host), host);
    }

    @Test
    void ignoresPreReleaseSuffixes() {
        assertTrue(RuntimeArtifact.supports("1.21.2", "26.3", "26.3-rc-2"));
    }

    @Test
    void loadsAnOlderHostThanTheBuildBundle() throws Exception {
        var jar = jar(Map.of(
                "Cult-Runtime-Host-Version", "26.3",
                "Cult-Runtime-Host-Min", "1.21.2",
                "Cult-Runtime-Host-Max", "26.3"));
        RuntimeArtifact.validateHost(jar, "26.2");
        assertThrows(IOException.class, () -> RuntimeArtifact.validateHost(jar, "26.4"));
    }

    @Test
    void requiresAnExactHostWithoutARange() throws Exception {
        var jar = jar(Map.of("Cult-Runtime-Host-Version", "26.3"));
        RuntimeArtifact.validateHost(jar, "26.3");
        assertThrows(IOException.class, () -> RuntimeArtifact.validateHost(jar, "26.2"));
    }

    private Path jar(Map<String, String> attributes) throws Exception {
        var manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        attributes.forEach(manifest.getMainAttributes()::putValue);
        var path = Files.createTempFile(directory, "runtime", ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(path), manifest)) {}
        return path;
    }
}
