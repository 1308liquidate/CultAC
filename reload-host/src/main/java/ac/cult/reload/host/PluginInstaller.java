package ac.cult.reload.host;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.zip.*;

/** Promotes an engine into the installed bootstrap without adding another native plugin. */
final class PluginInstaller {
    static final String ENGINE = "cult-runtime/default.jar";

    private PluginInstaller() {}

    static void install(Path installed, Path runtime, RuntimeArtifact artifact, String platform) throws Exception {
        installed = installed.toAbsolutePath().normalize();
        if (!Files.isRegularFile(installed)) throw new IOException("Installed plugin jar is unavailable: " + installed);
        Path temporary = Files.createTempFile(installed.getParent(), ".cult-install-", ".tmp");
        String descriptor = PluginDescriptors.name(platform);
        boolean descriptorFound = false;
        try {
            Path distribution = runtime.resolveSibling("distribution.jar");
            if (Files.exists(distribution)) {
                Files.copy(distribution, temporary, StandardCopyOption.REPLACE_EXISTING);
            } else
                try (var source = new ZipFile(installed.toFile());
                        var output = new ZipOutputStream(Files.newOutputStream(temporary))) {
                    var entries = source.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.equals(ENGINE) || name.matches("META-INF/[^/]+\\.(SF|RSA|DSA)")) continue;
                        output.putNextEntry(new ZipEntry(name));
                        if (!entry.isDirectory())
                            try (var input = source.getInputStream(entry)) {
                                if (name.equals(descriptor)) {
                                    descriptorFound = true;
                                    output.write(PluginDescriptors.withVersion(
                                            input.readAllBytes(), platform, artifact.build()));
                                } else input.transferTo(output);
                            }
                        output.closeEntry();
                    }
                    if (!descriptorFound) throw new IOException("Installed plugin has no " + descriptor);
                    output.putNextEntry(new ZipEntry(ENGINE));
                    Files.copy(runtime, output);
                    output.closeEntry();
                }
            // Verify the copied payload and descriptor before replacing the working plugin.
            try (var staged = new ZipFile(temporary.toFile())) {
                if (!PluginDescriptors.version(
                                staged.getInputStream(staged.getEntry(descriptor))
                                        .readAllBytes(),
                                platform)
                        .equals(artifact.build())) throw new IOException("Staged plugin version mismatch");
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                try (var input = staged.getInputStream(staged.getEntry(ENGINE))) {
                    byte[] bytes = new byte[65536];
                    int read;
                    while ((read = input.read(bytes)) != -1) digest.update(bytes, 0, read);
                }
                if (!java.util.HexFormat.of().formatHex(digest.digest()).equals(artifact.digest()))
                    throw new IOException("Staged engine hash mismatch");
            }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, installed, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Only files in this staging directory are removed; symlink targets and subdirectories are untouched. */
    static int cleanUpdates(Path directory) throws IOException {
        int removed = 0;
        try (var files = Files.newDirectoryStream(directory)) {
            for (var file : files) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    Files.delete(file);
                    removed++;
                }
            }
        }
        return removed;
    }
}
