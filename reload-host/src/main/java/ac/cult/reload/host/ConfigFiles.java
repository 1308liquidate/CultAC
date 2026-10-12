package ac.cult.reload.host;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Publication rolls back filesystem failures; live engines continue using their private snapshots. */
final class ConfigFiles implements AutoCloseable {
    private final Path root;
    private final Map<Path, byte[]> previous = new LinkedHashMap<>();
    private boolean committed;

    ConfigFiles(Path root, Map<String, byte[]> files) throws IOException {
        this.root = root;
        try {
            for (var entry : files.entrySet()) {
                Path target = root.resolve(entry.getKey()).normalize();
                if (!target.startsWith(root) || !entry.getKey().endsWith(".yml"))
                    throw new IOException("Invalid configuration path");
                byte[] old = Files.exists(target) ? Files.readAllBytes(target) : null;
                if (Arrays.equals(old, entry.getValue())) continue;
                previous.put(target, old);
                replace(target, entry.getValue());
            }
        } catch (IOException | RuntimeException failure) {
            try {
                close();
            } catch (IOException rollback) {
                failure.addSuppressed(rollback);
            }
            throw failure;
        }
    }

    void commit() {
        committed = true;
        previous.clear();
    }

    private void replace(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".cult-config-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void close() throws IOException {
        if (committed) return;
        IOException failure = null;
        var entries = new ArrayList<>(previous.entrySet());
        Collections.reverse(entries);
        for (var entry : entries) {
            try {
                if (entry.getValue() == null) Files.deleteIfExists(entry.getKey());
                else replace(entry.getKey(), entry.getValue());
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        previous.clear();
        if (failure != null) throw failure;
    }
}
