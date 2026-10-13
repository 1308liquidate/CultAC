package ac.cult.reload.host;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.fixture.FixtureEntrypoint;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.jar.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationManagerTest {
    private static final String LOADER_VERSION = "2";

    @TempDir
    Path directory;

    final Map<String, Object> controls = new ConcurrentHashMap<>();
    final List<String> events = new CopyOnWriteArrayList<>();
    final List<String> reports = new CopyOnWriteArrayList<>();

    private GenerationManager start() throws Exception {
        controls.put("events", events);
        var manager = new GenerationManager(
                directory.resolve("CultAC"),
                "paper",
                this,
                controls,
                null,
                getClass().getClassLoader(),
                reports::add);
        manager.connectionResolver(connection -> controls.get("upstream:" + connection));
        manager.loadInitial(Files.newInputStream(artifact("initial.jar", "1.0", "initial", "paper")));
        manager.startInitial();
        return manager;
    }

    private Path artifact(String filename, String version, String variant, String platform) throws Exception {
        Path path = directory.resolve("CultAC/updates").resolve(filename);
        Files.createDirectories(path.getParent());
        var manifest = new Manifest();
        var fields = manifest.getMainAttributes();
        fields.putValue("Manifest-Version", "1.0");
        fields.putValue("Cult-Runtime-SPI", "1");
        fields.putValue("Cult-Runtime-Platform", platform);
        fields.putValue("Cult-Runtime-Java", "21");
        fields.putValue("Cult-Runtime-Entrypoint", FixtureEntrypoint.class.getName());
        try (var out = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            String type = FixtureEntrypoint.class.getName().replace('.', '/') + ".class";
            out.putNextEntry(new JarEntry(type));
            try (var input = getClass().getClassLoader().getResourceAsStream(type)) {
                input.transferTo(out);
            }
            out.closeEntry();
            put(
                    out,
                    PluginDescriptors.name(platform),
                    platform.equals("paper")
                            ? "name: CultAC\nversion: '" + version + "'\ncult-loader-version: '2'\n"
                            : "{\"version\":\"" + version + "\",\"cult-loader-version\":\"2\"}");
            put(out, "fixture.properties", "variant=" + variant);
        }
        return path;
    }

    private static void put(JarOutputStream jar, String path, String text) throws Exception {
        jar.putNextEntry(new JarEntry(path));
        jar.write(text.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void eventually(BooleanSupplier predicate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(predicate.getAsBoolean());
    }

    @Test
    void pinnedSessionsSurviveUnlimitedGenerationsAndPendingDisconnect() throws Exception {
        var manager = start();
        try {
            manager.attach("old", null);
            var pending = new CompletableFuture<Void>();
            controls.put("disconnect:old", pending);
            for (int i = 0; i < 5; i++) {
                artifact("v" + i + ".jar", "2." + i, "v" + i, "paper");
                await(manager.update("v" + i + ".jar"));
                manager.attach("player" + i, null);
            }
            assertEquals(6, manager.status().size());
            assertTrue(events.contains("initial:attach:old"));
            assertTrue(events.contains("v4:attach:player4"));
            manager.disconnect("old");
            assertFalse(events.contains("initial:close"));
            pending.complete(null);
            eventually(() -> manager.status().size() == 5);
            assertTrue(events.indexOf("initial:disconnect:old") < events.indexOf("initial:close"));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void descriptorVersionAndHashAreBothPartOfIdentity() throws Exception {
        var manager = start();
        try {
            Path first = artifact("same-version.jar", "1.0", "different", "paper");
            String identity =
                    RuntimeArtifact.inspect(first, "paper", LOADER_VERSION).display();
            assertTrue(await(manager.update("same-version.jar")).contains(identity));
            int starts = (int) events.stream()
                    .filter(event -> event.equals("different:start"))
                    .count();
            Files.copy(first, first.resolveSibling("duplicate.jar"));
            assertTrue(await(manager.update("duplicate.jar")).startsWith("Already running"));
            assertEquals(
                    starts,
                    events.stream()
                            .filter(event -> event.equals("different:start"))
                            .count());
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void externalBuildSymlinkIsSnapshottedAndSurvivesCleanup() throws Exception {
        var manager = start();
        try {
            Path build = directory.resolve("build/CultAC-dev.jar");
            Files.createDirectories(build.getParent());
            Files.move(artifact("first.jar", "2", "first", "paper"), build);
            Path link = directory.resolve("CultAC/updates/dev.jar");
            Files.createSymbolicLink(link, build);
            byte[] first = Files.readAllBytes(build);
            await(manager.update("dev.jar"));
            manager.attach("old", null);
            var marker = new Properties();
            try (var input = Files.newInputStream(directory.resolve("CultAC/runtime/current.properties"))) {
                marker.load(input);
            }
            Path snapshot = directory.resolve("CultAC/runtime").resolve(marker.getProperty("artifact"));

            Files.move(artifact("second.jar", "2", "second", "paper"), build, StandardCopyOption.REPLACE_EXISTING);
            assertArrayEquals(first, Files.readAllBytes(snapshot));
            await(manager.update("dev.jar"));
            manager.attach("new", null);
            assertTrue(events.contains("first:attach:old"));
            assertTrue(events.contains("second:attach:new"));
            assertFalse(events.contains("first:close"));
            await(manager.disconnect("old"));
            eventually(() -> events.contains("first:close"));

            byte[] rebuilt = Files.readAllBytes(build);
            PluginInstaller.cleanUpdates(link.getParent());
            assertTrue(Files.isSymbolicLink(link));
            assertEquals(build, link.toRealPath());
            assertArrayEquals(rebuilt, Files.readAllBytes(build));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void explicitLoaderRevisionRejectsOnlyIncompatibleReleases() throws Exception {
        var manager = start();
        try {
            Path incompatible = artifact("incompatible.jar", "2", "new", "paper");
            String incompatibleVersion = Integer.toString(Integer.parseInt(LOADER_VERSION) + 1);
            rewriteDescriptor(
                    incompatible, "name: CultAC\nversion: '2'\ncult-loader-version: '" + incompatibleVersion + "'\n");
            var error = assertThrows(ExecutionException.class, () -> await(manager.update("incompatible.jar")));
            assertTrue(error.getCause().getMessage().contains("cult-loader-version " + incompatibleVersion));
            manager.attach("original", null);
            assertTrue(events.contains("initial:attach:original"));
            artifact("compatible.jar", "2", "compatible-fix", "paper");
            await(manager.update("compatible.jar"));
            manager.attach("new", null);
            assertTrue(events.contains("compatible-fix:attach:new"));
        } finally {
            await(manager.stop());
        }
    }

    private void rewriteDescriptor(Path artifact, String descriptor) throws Exception {
        Path temporary = artifact.resolveSibling("rewrite.tmp");
        try (var source = new JarFile(artifact.toFile());
                var output = new JarOutputStream(Files.newOutputStream(temporary))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                output.putNextEntry(new JarEntry(entry.getName()));
                if (entry.getName().equals("plugin.yml")) output.write(descriptor.getBytes(StandardCharsets.UTF_8));
                else
                    try (var input = source.getInputStream(entry)) {
                        input.transferTo(output);
                    }
                output.closeEntry();
            }
        }
        Files.move(temporary, artifact, StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    void fullReleaseIsPreservedAndInstalledEvenAfterStagedSourceIsRemoved() throws Exception {
        var manager = start();
        Path runtime = artifact("engine.jar", "2", "new", "paper");
        Path release = runtime.resolveSibling("release.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(release))) {
            put(output, "plugin.yml", "name: CultAC\nversion: '2'\ncult-loader-version: '2'\nmain: loader.Main\n");
            put(output, "loader/Main.class", "compatible-loader-bugfix");
            output.putNextEntry(new JarEntry(PluginInstaller.ENGINE));
            Files.copy(runtime, output);
            output.closeEntry();
        }
        byte[] expected = Files.readAllBytes(release);
        Path installed = directory.resolve("plugins/CultAC.jar");
        Files.createDirectories(installed.getParent());
        Files.copy(release, installed);
        await(manager.update("release.jar"));
        Files.delete(release);
        await(manager.shutdown(installed));
        assertArrayEquals(expected, Files.readAllBytes(installed));
        assertTrue(events.contains("new:close"));
    }

    @Test
    void failedUpdateAndReloadValidationPreservePublishedState() throws Exception {
        var manager = start();
        try {
            byte[] marker = Files.readAllBytes(directory.resolve("CultAC/runtime/current.properties"));
            byte[] config = Files.readAllBytes(directory.resolve("CultAC/config.yml"));
            artifact("broken.jar", "3", "broken", "paper");
            assertThrows(ExecutionException.class, () -> await(manager.update("broken.jar")));
            artifact("wrong.jar", "3", "wrong", "velocity");
            assertThrows(ExecutionException.class, () -> await(manager.update("wrong.jar")));
            assertThrows(ExecutionException.class, () -> await(manager.update("../initial.jar")));
            assertArrayEquals(marker, Files.readAllBytes(directory.resolve("CultAC/runtime/current.properties")));
            assertArrayEquals(config, Files.readAllBytes(directory.resolve("CultAC/config.yml")));
            controls.put("reject:initial", true);
            assertFalse(await(manager.reload(null)));
            assertFalse(events.contains("initial:apply:original"));
            assertFalse(events.contains("initial:apply:prepared"));
            manager.attach("survivor", null);
            assertTrue(events.contains("initial:attach:survivor"));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void downstreamUsesPinnedUpstreamAfterUpdate() throws Exception {
        var manager = start();
        try {
            manager.attach("dummy", null, true);
            assertFalse(events.contains("initial:attach:dummy"));
            manager.attach("upstream", null);
            controls.put("upstream:downstream", "upstream");
            artifact("new.jar", "2", "new", "paper");
            await(manager.update("new.jar"));
            manager.attach("downstream", null, true);
            assertTrue(events.contains("initial:attach:downstream"));
            await(manager.disconnect("upstream"));
            assertFalse(events.contains("initial:close"));
            await(manager.disconnect("downstream"));
            eventually(() -> events.contains("initial:close"));
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void shutdownInstallsLatestAndCleansUpdatesBeforeUnload() throws Exception {
        var manager = start();
        Path installed = directory.resolve("plugins/CultAC.jar");
        Files.createDirectories(installed.getParent());
        try (var output = new JarOutputStream(Files.newOutputStream(installed))) {
            put(output, "plugin.yml", "name: CultAC\nversion: '1.0'\nmain: loader.Main\n");
            put(output, "loader/Main.class", "bootstrap-stays");
            output.putNextEntry(new JarEntry(PluginInstaller.ENGINE));
            Files.copy(directory.resolve("CultAC/updates/initial.jar"), output);
            output.closeEntry();
        }
        Path latest = artifact("new.jar", "2.7", "new", "paper");
        byte[] bytes = Files.readAllBytes(latest);
        await(manager.update("new.jar"));
        manager.attach("current", null);
        var pending = new CompletableFuture<Void>();
        controls.put("disconnect:current", pending);
        CompletionStage<Void> shutdown = manager.shutdown(installed);
        eventually(() -> !Files.exists(latest));
        assertFalse(events.contains("new:close"));
        try (var archive = new ZipFile(installed.toFile())) {
            assertEquals(
                    "2.7",
                    PluginDescriptors.version(
                            archive.getInputStream(archive.getEntry("plugin.yml"))
                                    .readAllBytes(),
                            "paper"));
            assertArrayEquals(
                    bytes,
                    archive.getInputStream(archive.getEntry(PluginInstaller.ENGINE))
                            .readAllBytes());
            assertEquals(
                    "bootstrap-stays",
                    new String(
                            archive.getInputStream(archive.getEntry("loader/Main.class"))
                                    .readAllBytes(),
                            StandardCharsets.UTF_8));
        }
        assertFalse(Files.exists(directory.resolve("CultAC/runtime/current.properties")));
        pending.complete(null);
        await(shutdown);
        assertTrue(events.contains("new:close"));
        try (var archive = new ZipFile(installed.toFile());
                var restarted = new GenerationManager(
                        directory.resolve("CultAC"),
                        "paper",
                        this,
                        controls,
                        null,
                        getClass().getClassLoader(),
                        reports::add)) {
            restarted.loadInitial(archive.getInputStream(archive.getEntry(PluginInstaller.ENGINE)));
            restarted.startInitial();
            assertTrue(restarted.status().getFirst().startsWith("2.7@"));
        }
    }

    @Test
    void failedPromotionKeepsRecoveryArtifactAndUpdateFiles() throws Exception {
        var manager = start();
        Path update = artifact("new.jar", "2", "new", "paper");
        await(manager.update("new.jar"));
        assertThrows(ExecutionException.class, () -> await(manager.shutdown(directory.resolve("missing.jar"))));
        assertTrue(Files.exists(update));
        var marker = new Properties();
        try (var input = Files.newInputStream(directory.resolve("CultAC/runtime/current.properties"))) {
            marker.load(input);
        }
        assertTrue(Files.exists(directory.resolve("CultAC/runtime").resolve(marker.getProperty("artifact"))));
    }

    @Test
    void cachedApiHandlesReleasePrivateLoaderAfterDisconnect() throws Exception {
        var manager = start();
        GrimAbstractAPI api = (GrimAbstractAPI) manager.publicApi();
        UUID uuid = UUID.randomUUID();
        manager.attach("old", uuid);
        GrimUser cached = api.getGrimUser(uuid);
        var function = api.getFunction("fixture");
        assertEquals("initial", cached.getName());
        assertEquals("initial", function.apply(null));
        @SuppressWarnings("unchecked")
        var loader = (WeakReference<ClassLoader>) controls.get("loader:initial");
        artifact("new.jar", "2", "new", "paper");
        await(manager.update("new.jar"));
        await(manager.disconnect("old"));
        eventually(() -> manager.status().size() == 1);
        assertThrows(RuntimeException.class, cached::getName);
        assertThrows(RuntimeException.class, () -> function.apply(null));
        eventually(() -> {
            System.gc();
            return loader.get() == null;
        });
        assertTrue(api.getGrimVersion().startsWith("2@"));
        await(manager.stop());
    }

    @Test
    void admittedConnectionKeepsItsOwnerWhenPublicationRacesWithAttach() throws Exception {
        var manager = start();
        var release = new CompletableFuture<Void>();
        var entered = new CompletableFuture<Void>();
        controls.put("attach:racing", release);
        controls.put("entered:attach:racing", entered);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var attach = threads.submit(() -> {
                manager.attach("racing", UUID.randomUUID());
                return null;
            });
            try {
                await(entered);
                artifact("new.jar", "2", "new", "paper");
                await(manager.update("new.jar"));
                assertFalse(events.contains("initial:close"));
                release.complete(null);
                attach.get(5, TimeUnit.SECONDS);
                await(manager.disconnect("racing"));
                eventually(() -> events.contains("initial:close"));
            } finally {
                release.complete(null);
                await(manager.stop());
            }
        }
    }

    @Test
    void retiredGenerationWaitsForApiCallAndAsynchronousCommand() throws Exception {
        var manager = start();
        var api = (GrimAbstractAPI) manager.publicApi();
        var function = api.getFunction("fixture");
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        var command = new CompletableFuture<Void>();
        controls.put("function:initial", release);
        controls.put("entered:function:initial", entered);
        controls.put("command:pending", command);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var call = threads.submit(() -> function.apply(null));
            try {
                await(entered);
                var executed = manager.execute(null, "pending", null, false);
                artifact("new.jar", "2", "new", "paper");
                await(manager.update("new.jar"));
                eventually(() -> manager.status().stream().anyMatch(line -> line.contains("STOPPING")));
                assertFalse(events.contains("initial:close"));
                release.complete(null);
                assertEquals("initial", call.get(5, TimeUnit.SECONDS));
                assertFalse(events.contains("initial:close"));
                command.complete(null);
                await(executed);
                eventually(() -> events.contains("initial:close"));
                assertThrows(RuntimeException.class, () -> function.apply(null));
            } finally {
                release.complete(null);
                command.complete(null);
                await(manager.stop());
            }
        }
    }

    @Test
    void reconnectBindingAndRepeatedDisconnectCannotRemoveReplacement() throws Exception {
        var manager = start();
        UUID uuid = UUID.randomUUID();
        try {
            var api = (GrimAbstractAPI) manager.publicApi();
            manager.attach("old", uuid);
            var old = api.getGrimUser(uuid);
            artifact("new.jar", "2", "new", "paper");
            await(manager.update("new.jar"));
            manager.attach("replacement", uuid);
            var replacement = api.getGrimUser(uuid);
            assertEquals("new", replacement.getName());
            var pending = new CompletableFuture<Void>();
            controls.put("disconnect:old", pending);
            var first = manager.disconnect("old");
            var second = manager.disconnect("old");
            assertThrows(RuntimeException.class, old::getName);
            assertSame(replacement, api.getGrimUser(uuid));
            pending.complete(null);
            await(first);
            await(second);
            assertEquals(
                    1, events.stream().filter("initial:disconnect:old"::equals).count());
            assertEquals("new", replacement.getName());
        } finally {
            await(manager.stop());
        }
    }

    @Test
    void stopFailureRetainsGenerationAndDoesNotCloseItsLoader() throws Exception {
        var manager = start();
        controls.put("close-fail:initial", true);
        artifact("new.jar", "2", "new", "paper");
        await(manager.update("new.jar"));
        eventually(() -> manager.status().stream().anyMatch(line -> line.contains("CLEANUP_FAILED")));
        assertFalse(reports.stream().anyMatch(line -> line.startsWith("Unloaded")));
        assertThrows(ExecutionException.class, () -> await(manager.stop()));
    }

    @Test
    void stagingCleanupPreservesDirectoriesAndSymlinks() throws Exception {
        Path updates = Files.createDirectory(directory.resolve("updates"));
        Path target = Files.writeString(directory.resolve("target.jar"), "keep");
        Files.createSymbolicLink(updates.resolve("linked.jar"), target);
        Files.createDirectory(updates.resolve("nested"));
        Files.writeString(updates.resolve("staged.jar"), "remove");
        assertEquals(1, PluginInstaller.cleanUpdates(updates));
        assertTrue(Files.isSymbolicLink(updates.resolve("linked.jar")));
        assertTrue(Files.isDirectory(updates.resolve("nested")));
        assertEquals("keep", Files.readString(target));
    }

    @Test
    void failedConnectionCleanupFailsShutdownWithoutUnsafeUnloadOrHanging() throws Exception {
        var manager = start();
        manager.attach("failed", null);
        controls.put("disconnect:failed", CompletableFuture.failedFuture(new IllegalStateException("fixture")));
        assertThrows(ExecutionException.class, () -> await(manager.disconnect("failed")));
        assertThrows(ExecutionException.class, () -> await(manager.stop()));
        assertTrue(manager.status().getFirst().contains("CLEANUP_FAILED"));
        assertFalse(events.contains("initial:close"));
    }

    @Test
    void callerCanRetainRejectedRegistrationExceptionWithoutRetainingLoader() throws Exception {
        var manager = start();
        try {
            var api = (GrimAbstractAPI) manager.publicApi();
            var cachedFailure =
                    assertThrows(IllegalArgumentException.class, () -> api.registerFunction("fail", value -> value));
            @SuppressWarnings("unchecked")
            var loader = (WeakReference<ClassLoader>) controls.get("loader:initial");
            artifact("new.jar", "2", "new", "paper");
            await(manager.update("new.jar"));
            eventually(() -> manager.status().size() == 1);
            eventually(() -> {
                System.gc();
                return loader.get() == null;
            });
            assertTrue(cachedFailure.getMessage().contains("Fixture rejected function"));
        } finally {
            await(manager.stop());
        }
    }
}
