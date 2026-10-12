package ac.cult.reload.host;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

public record RuntimeArtifact(String build, String entrypoint, String digest) {
    public String identity() {
        return build + "@" + digest;
    }

    public String display() {
        return build + "@" + digest.substring(0, 12);
    }

    static void validateHost(Path jar, String hostVersion) throws Exception {
        if (hostVersion == null) return;
        try (var archive = new JarFile(jar.toFile())) {
            String required = archive.getManifest().getMainAttributes().getValue("Cult-Runtime-Host-Version");
            if (required == null || !required.equals(hostVersion))
                throw new IOException("Runtime host version " + required + " does not match " + hostVersion);
        }
    }

    public static RuntimeArtifact inspect(Path jar, String platform, String loaderVersion) throws Exception {
        try (var archive = new JarFile(jar.toFile(), true)) {
            var manifest = archive.getManifest();
            if (manifest == null) throw new IOException("Missing runtime manifest");
            var fields = manifest.getMainAttributes();
            if (!"1".equals(fields.getValue("Cult-Runtime-SPI"))) throw new IOException("Incompatible runtime SPI");
            if (!platform.equals(fields.getValue("Cult-Runtime-Platform")))
                throw new IOException("Wrong runtime platform");
            int minimumJava = Integer.parseInt(fields.getValue("Cult-Runtime-Java"));
            if (minimumJava > Runtime.version().feature())
                throw new IOException("Runtime requires Java " + minimumJava);
            String entry = fields.getValue("Cult-Runtime-Entrypoint");
            var descriptor = archive.getJarEntry(PluginDescriptors.name(platform));
            if (descriptor == null) throw new IOException("Missing plugin descriptor");
            String build;
            try (var input = archive.getInputStream(descriptor)) {
                var bytes = input.readAllBytes();
                build = PluginDescriptors.version(bytes, platform);
                LoaderVersions.require(PluginDescriptors.loaderVersion(bytes, platform), loaderVersion);
            }
            if (entry == null
                    || build == null
                    || build.isBlank()
                    || archive.getJarEntry(entry.replace('.', '/') + ".class") == null) {
                throw new IOException("Incomplete runtime manifest");
            }
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var name = entries.nextElement().getName();
                if (name.startsWith("ac/cult/reload/") || name.startsWith("ac/grim/grimac/api/")) {
                    throw new IOException("Runtime bundles shared ABI: " + name);
                }
            }
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = java.nio.file.Files.newInputStream(jar)) {
                var buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return new RuntimeArtifact(build, entry, java.util.HexFormat.of().formatHex(digest.digest()));
        }
    }
}
