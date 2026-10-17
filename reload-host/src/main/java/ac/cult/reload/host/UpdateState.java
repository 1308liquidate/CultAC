package ac.cult.reload.host;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The build {@code /cult update} last installed, so the next boot starts on it, and the bundled jar's version at that
 * time, so a deploy of another bundled jar supersedes it: {@code {"build":N,"bundled":"<version>"}}.
 */
public final class UpdateState {
    private UpdateState() {}

    /** A recorded selection; {@code bundled} is empty when it was not recorded. */
    public record Entry(long build, String bundled) {}

    /** @return the recorded selection; null when the file is missing, unreadable or names no build */
    public static Entry read(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            var fields = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            long build = fields.get("build").getAsLong();
            var bundled = fields.get("bundled");
            return build > 0
                    ? new Entry(build, bundled == null || bundled.isJsonNull() ? "" : bundled.getAsString())
                    : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    public static void write(Path file, long build, String bundled) throws IOException {
        var fields = new JsonObject();
        fields.addProperty("build", build);
        fields.addProperty("bundled", bundled);
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        Path temporary = directory.resolve(file.getFileName() + ".tmp");
        Files.writeString(temporary, fields + "\n", StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Forgets the recorded build, so the next boot starts on the bundled or persisted runtime. */
    public static void clear(Path file) throws IOException {
        Files.deleteIfExists(file);
    }
}
