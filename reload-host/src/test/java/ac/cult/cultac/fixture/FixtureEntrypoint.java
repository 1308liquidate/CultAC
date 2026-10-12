package ac.cult.cultac.fixture;

import ac.cult.reload.api.*;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Compiled once, loaded from test jars through the real private classloader. */
public final class FixtureEntrypoint implements RuntimeEntrypoint, RuntimeInstance {
    private HostContext host;
    private Map<String, Object> controls;
    private List<String> events;
    private final Map<UUID, Object> users = new ConcurrentHashMap<>();
    private String variant;
    private Object configuration = "original";

    @SuppressWarnings("unchecked")
    public RuntimeInstance create(HostContext context) {
        host = context;
        controls = (Map<String, Object>) host.server();
        events = (List<String>) controls.get("events");
        return this;
    }

    public void load() throws Exception {
        Properties properties = new Properties();
        try (var stream = getClass().getResourceAsStream("/fixture.properties")) {
            properties.load(stream);
        }
        variant = properties.getProperty("variant");
        ac.grim.grimac.api.storage.verbose.Verbose.of("fixture={str}");
        controls.put("loader:" + variant, new WeakReference<>(getClass().getClassLoader()));
        events.add(variant + ":load");
        host.resources().track(this, () -> events.add(variant + ":resources"));
    }

    public void start() {
        if (variant.equals("broken")) throw new IllegalStateException("Broken fixture");
        events.add(variant + ":start");
    }

    public CompletionStage<Void> close() {
        events.add(variant + ":close");
        if (controls.containsKey("close-fail:" + variant))
            return CompletableFuture.failedFuture(new IllegalStateException("Stop failed"));
        return CompletableFuture.completedFuture(null);
    }

    public void attach(Object connection, Object player) {
        events.add(variant + ":attach:" + connection);
        block("attach:" + connection);
        if (player instanceof UUID uuid) {
            Object user = Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class<?>[] {GrimUser.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> uuid;
                        case "getName" -> variant;
                        default -> null;
                    });
            users.put(uuid, user);
            host.bindUser(connection, uuid, player, user);
        }
    }

    public CompletionStage<Void> disconnect(Object connection) {
        events.add(variant + ":disconnect:" + connection);
        host.unbindUser(connection);
        Object pending = controls.get("disconnect:" + connection);
        @SuppressWarnings("unchecked")
        var result = (CompletionStage<Void>) pending;
        return result == null ? CompletableFuture.completedFuture(null) : result;
    }

    public Object api() {
        return Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {GrimAbstractAPI.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "registerFunction" -> {
                        if ("fail".equals(args[0])) throw new IllegalArgumentException("Fixture rejected function");
                        yield null;
                    }
                    case "getGrimVersion" -> host.build();
                    case "getFunction" ->
                        (Function<Object, Object>) ignored -> {
                            block("function:" + variant);
                            return variant;
                        };
                    default -> null;
                });
    }

    private void block(String key) {
        if (controls.get(key) instanceof CompletableFuture<?> barrier) {
            if (controls.get("entered:" + key) instanceof CompletableFuture<?> entered) entered.complete(null);
            barrier.join();
        }
    }

    public CompletionStage<Void> execute(Object sender, String command) {
        events.add(variant + ":command:" + command);
        @SuppressWarnings("unchecked")
        var pending = (CompletionStage<Void>) controls.get("command:" + command);
        return pending == null ? CompletableFuture.completedFuture(null) : pending;
    }

    public Map<String, byte[]> configurationFiles() {
        return Map.of("config.yml", ("build: " + variant + "\n").getBytes(StandardCharsets.UTF_8));
    }

    public Object prepareReload(Object input) {
        events.add(variant + ":validate");
        if (controls.containsKey("reject:" + variant)) throw new IllegalArgumentException("Bad configuration");
        return "prepared";
    }

    public Object currentConfiguration() {
        return configuration;
    }

    public boolean applyReload(Object next) {
        events.add(variant + ":apply:" + next);
        configuration = next;
        return true;
    }
}
