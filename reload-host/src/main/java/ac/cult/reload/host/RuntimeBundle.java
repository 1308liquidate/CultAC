package ac.cult.reload.host;

import java.io.IOException;
import java.nio.file.*;
import java.util.jar.JarFile;

/** Accepts either an engine artifact or a compatible complete native plugin release. */
final class RuntimeBundle {
    static RuntimeArtifact stage(Path source, Path home, String platform, String loaderVersion) throws Exception {
        Path snapshot = home.resolve("source.jar");
        Files.copy(source, snapshot);
        Path engine = home.resolve("runtime.jar");
        String outerVersion = null;
        try (var archive = new JarFile(snapshot.toFile())) {
            var embedded = archive.getJarEntry(PluginInstaller.ENGINE);
            if (embedded == null) {
                // The snapshot is the engine itself.
            } else {
                var descriptor = archive.getJarEntry(PluginDescriptors.name(platform));
                if (descriptor == null) throw new IOException("Missing native plugin descriptor");
                try (var input = archive.getInputStream(descriptor)) {
                    var bytes = input.readAllBytes();
                    outerVersion = PluginDescriptors.version(bytes, platform);
                    LoaderVersions.require(PluginDescriptors.loaderVersion(bytes, platform), loaderVersion);
                }
                try (var input = archive.getInputStream(embedded)) {
                    Files.copy(input, engine);
                }
            }
        }
        if (outerVersion == null) Files.move(snapshot, engine);
        var artifact = RuntimeArtifact.inspect(engine, platform, loaderVersion);
        if (outerVersion != null) {
            if (!outerVersion.equals(artifact.build()))
                throw new IOException("Native plugin and engine versions differ");
            Files.move(snapshot, home.resolve("distribution.jar"));
        }
        return artifact;
    }
}
