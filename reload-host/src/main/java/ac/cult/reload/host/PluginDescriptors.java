package ac.cult.reload.host;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class PluginDescriptors {
    private PluginDescriptors() {}

    static String name(String platform) {
        return platform.equals("paper") ? "plugin.yml" : "velocity-plugin.json";
    }

    static String version(byte[] bytes, String platform) throws IOException {
        return field(bytes, platform, "version");
    }

    static String loaderVersion(byte[] bytes, String platform) throws IOException {
        return field(bytes, platform, "cult-loader-version");
    }

    private static String field(byte[] bytes, String platform, String key) throws IOException {
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            Object value = platform.equals("paper")
                    ? yaml().<Map<String, Object>>load(text).get(key)
                    : JsonParser.parseString(text).getAsJsonObject().get(key).getAsString();
            if (value == null || value.toString().isBlank())
                throw new IOException("Missing " + key + " in plugin descriptor");
            return value.toString();
        } catch (RuntimeException error) {
            throw new IOException("Invalid plugin descriptor", error);
        }
    }

    static byte[] withVersion(byte[] bytes, String platform, String version) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (platform.equals("paper")) {
            Map<String, Object> descriptor = yaml().load(text);
            descriptor.put("version", version);
            text = yaml().dump(descriptor);
        } else {
            var descriptor = JsonParser.parseString(text).getAsJsonObject();
            descriptor.addProperty("version", version);
            text = descriptor.toString();
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Yaml yaml() {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options));
    }
}
