package ac.cult.reload.host;

import java.io.IOException;
import java.io.InputStream;

/** An explicit compatibility contract, independent of loader implementation edits. */
public final class LoaderVersions {
    private LoaderVersions() {}

    public static String read(InputStream descriptor, String platform) throws IOException {
        try (descriptor) {
            if (descriptor == null) throw new IOException("Missing native plugin descriptor");
            return PluginDescriptors.loaderVersion(descriptor.readAllBytes(), platform);
        }
    }

    static void require(String required, String installed) throws IOException {
        if (!required.equals(installed))
            throw new IOException("Update requires cult-loader-version " + required + "; installed loader is "
                    + installed + ". Install the complete plugin and restart.");
    }
}
