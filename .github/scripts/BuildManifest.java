import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Writes and signs the manifest of a numbered build, as {@code /cult update <N>} reads it.
 *
 * <pre>
 * java BuildManifest.java --build N --branch B --commit SHA --velocity cultac-velocity.jar
 *     --companion cultac-companion.jar --out DIR
 * </pre>
 *
 * The manifest is compact JSON, signed with Ed25519 over its exact bytes; {@code manifest.json.sig} holds the base64
 * signature. CULTAC_SIGNING_KEY is the private key, one-line base64 of its PKCS#8 DER. When CULTAC_UPDATE_PUBKEY (base64
 * X.509 DER) is set, the key must be its private half, so a build is never signed with a key the plugin would refuse.
 */
public final class BuildManifest {
    private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/-]{1,200}");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");

    public static void main(String[] args) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) options.put(args[i], args[i + 1]);
        long build = Long.parseLong(option(options, "--build"));
        String branch = option(options, "--branch");
        String commit = option(options, "--commit");
        Path velocity = Path.of(option(options, "--velocity"));
        Path companion = Path.of(option(options, "--companion"));
        Path out = Path.of(option(options, "--out"));
        if (build <= 0) fail("--build must be positive");
        if (!BRANCH.matcher(branch).matches()) fail("--branch " + branch + " is not a plain branch name");
        if (!COMMIT.matcher(commit).matches()) fail("--commit must be the full lowercase sha");

        String descriptor;
        byte[] runtime;
        try (var jar = new ZipFile(velocity.toFile())) {
            descriptor = read(jar, "velocity-plugin.json");
            try (var input = jar.getInputStream(jar.getEntry("cult-runtime/default.jar"))) {
                runtime = input.readAllBytes();
            }
        }
        String version = field(descriptor, "version");
        String loader = field(descriptor, "cult-loader-version");
        if (!loader.matches("[0-9]{1,9}")) fail("cult-loader-version " + loader + " is not a number");
        if (!version.contains("-b" + build + "-"))
            fail("the jar's version " + version + " is not build " + build + "; build it with CULT_BUILD_NUMBER");
        String runtimeDescriptor = nested(runtime, "velocity-plugin.json");
        if (!field(runtimeDescriptor, "version").equals(version))
            fail("cult-runtime/default.jar's version differs from the plugin's " + version);
        if (!field(runtimeDescriptor, "cult-loader-version").equals(loader))
            fail("cult-runtime/default.jar's cult-loader-version differs from the plugin's " + loader);

        String manifest = "{\"build\":" + build
                + ",\"branch\":" + quote(branch)
                + ",\"version\":" + quote(version)
                + ",\"commit\":" + quote(commit)
                + ",\"jar\":\"cultac-velocity.jar\""
                + ",\"sha256\":" + quote(sha256(velocity))
                + ",\"size\":" + Files.size(velocity)
                + ",\"loader_version\":" + Integer.parseInt(loader)
                + ",\"companion_sha256\":" + quote(sha256(companion))
                + ",\"published_at\":" + quote(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
                + "}";
        byte[] bytes = manifest.getBytes(StandardCharsets.UTF_8);

        String key = System.getenv("CULTAC_SIGNING_KEY");
        if (key == null || key.isBlank()) fail("CULTAC_SIGNING_KEY is not set");
        var privateKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(key.strip())));
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(bytes);
        byte[] signature = signer.sign();

        byte[] publicKey = publicHalf((EdECPrivateKey) privateKey);
        String publicText = Base64.getEncoder().encodeToString(publicKey);
        String expected = System.getenv("CULTAC_UPDATE_PUBKEY");
        if (expected != null && !expected.isBlank() && !expected.strip().equals(publicText))
            fail("CULTAC_SIGNING_KEY is not the private half of CULTAC_UPDATE_PUBKEY " + expected.strip());
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(publicKey)));
        verifier.update(bytes);
        if (!verifier.verify(signature)) fail("the signature does not verify");

        Files.createDirectories(out);
        Files.write(out.resolve("manifest.json"), bytes);
        Files.writeString(out.resolve("manifest.json.sig"), Base64.getEncoder().encodeToString(signature));
        System.out.println("Signing key's public half: " + publicText);
        System.out.println(manifest);
    }

    /** Ed25519 generation takes the private key from its random source, so a fixed source derives the public half. */
    private static byte[] publicHalf(EdECPrivateKey key) throws Exception {
        byte[] seed = key.getBytes().orElseThrow(() -> new IllegalStateException("The signing key has no seed"));
        var generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                System.arraycopy(seed, 0, bytes, 0, bytes.length);
            }
        });
        return generator.generateKeyPair().getPublic().getEncoded();
    }

    private static String option(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) fail(name + " is required");
        return value;
    }

    private static String read(ZipFile jar, String name) throws IOException {
        var entry = jar.getEntry(name);
        if (entry == null) fail(jar.getName() + " has no " + name);
        try (var input = jar.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String nested(byte[] jar, String name) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                if (entry.getName().equals(name)) return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
        }
        fail("cult-runtime/default.jar has no " + name);
        return null;
    }

    private static String field(String json, String key) {
        var matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!matcher.find()) fail("the plugin descriptor has no " + key);
        return matcher.group(1);
    }

    private static String quote(String value) {
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) != -1; ) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void fail(String message) {
        System.err.println("BuildManifest: " + message);
        System.exit(1);
    }
}
