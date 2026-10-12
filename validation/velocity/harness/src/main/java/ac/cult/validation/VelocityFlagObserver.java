package ac.cult.validation;

import com.google.inject.Inject;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;

/** Test-only equivalent of the Paper harness's public FlagEvent observer. */
@Plugin(
        id = "cult-smoke-observer",
        name = "CultAC Smoke Observer",
        version = "1.0.0",
        dependencies = @Dependency(id = "cultac"))
public final class VelocityFlagObserver implements SimpleCommand {
    private record Flag(String player, String check, double violations, boolean setback) {}

    private final ProxyServer proxy;
    private final Logger logger;
    private final Map<UUID, CopyOnWriteArrayList<Flag>> flags = new ConcurrentHashMap<>();

    @Inject
    public VelocityFlagObserver(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe(order = PostOrder.LAST)
    public void initialize(ProxyInitializeEvent ignored) throws Exception {
        Object bootstrap = proxy.getPluginManager()
                .getPlugin("cultac")
                .orElseThrow()
                .getInstance()
                .orElseThrow();
        // Only the test adapter crosses the bootstrap's private loader boundary. Event
        // subscription and all observations use the same public API as the Paper harness.
        var field = bootstrap.getClass().getDeclaredField("engine");
        field.setAccessible(true);
        ClassLoader loader = java.util.Objects.requireNonNull(field.get(bootstrap), "CultAC failed to start")
                .getClass()
                .getClassLoader();
        Class<?> apiClass = Class.forName("ac.grim.grimac.api.GrimAbstractAPI", false, loader);
        Object api = field.get(bootstrap).getClass().getMethod("publicApi").invoke(field.get(bootstrap));
        Object owner = apiClass.getMethod("getGrimPlugin", Object.class).invoke(api, this);
        Object bus = apiClass.getMethod("getEventBus").invoke(api);
        Class<?> eventType = Class.forName("ac.grim.grimac.api.event.events.FlagEvent", false, loader);
        Class<?> listenerType = Class.forName("ac.grim.grimac.api.event.GrimEventListener", false, loader);
        Class<?> ownerType = Class.forName("ac.grim.grimac.api.plugin.GrimPlugin", false, loader);
        Class<?> userType = Class.forName("ac.grim.grimac.api.GrimUser", false, loader);
        Class<?> checkType = Class.forName("ac.grim.grimac.api.AbstractCheck", false, loader);
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[] {listenerType}, (object, method, args) -> {
            if (method.getDeclaringClass() == Object.class)
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(object);
                    case "equals" -> object == args[0];
                    default -> "Velocity smoke flag observer";
                };
            Object event = args[0];
            Object user = eventType.getMethod("getUser").invoke(event);
            Object check = eventType.getMethod("getCheck").invoke(event);
            UUID uuid = (UUID) userType.getMethod("getUniqueId").invoke(user);
            var flag = new Flag(
                    (String) userType.getMethod("getName").invoke(user),
                    (String) checkType.getMethod("getCheckName").invoke(check),
                    ((Number) eventType.getMethod("getViolations").invoke(event)).doubleValue(),
                    (boolean) eventType.getMethod("isSetback").invoke(event));
            flags.computeIfAbsent(uuid, unused -> new CopyOnWriteArrayList<>()).add(flag);
            return null;
        });
        bus.getClass()
                .getMethod("subscribe", ownerType, Class.class, listenerType, int.class, boolean.class, Class.class)
                .invoke(bus, owner, eventType, listener, Integer.MAX_VALUE, true, getClass());
        proxy.getCommandManager()
                .register(
                        proxy.getCommandManager()
                                .metaBuilder("smoketestpacket")
                                .plugin(this)
                                .build(),
                        this);
        logger.info("Registered public Cult FlagEvent observer for smoketest validation");
    }

    @Subscribe
    public void disconnect(DisconnectEvent event) {
        flags.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void connected(ServerPostConnectEvent event) {
        logger.info(
                "Smoketest backend connected player={} server={}",
                event.getPlayer().getUsername(),
                event.getPlayer()
                        .getCurrentServer()
                        .orElseThrow()
                        .getServerInfo()
                        .getName());
    }

    @Override
    public void execute(Invocation invocation) {
        if (invocation.source() != proxy.getConsoleCommandSource()) return;
        String[] args = invocation.arguments();
        if (args.length == 4 && args[0].equals("cultswitch")) {
            var player = proxy.getPlayer(args[1]).orElseThrow();
            var target = proxy.getServer(args[2]).orElseThrow();
            player.createConnectionRequest(target).connect().whenComplete((result, failure) -> {
                if (failure != null || !result.isSuccessful())
                    logger.error("Smoketest backend switch failed marker={}", args[3], failure);
                else logger.info("Smoketest backend switch completed marker={} server={}", args[3], args[2]);
            });
            return;
        }
        if (args.length != 4 || !args[0].equals("cultflags"))
            throw new IllegalArgumentException("Expected cultflags player reset|snapshot marker");
        var player = proxy.getPlayer(args[1]).orElseThrow();
        if (args[2].equals("reset")) {
            flags.remove(player.getUniqueId());
            logger.info(
                    "Smoketest Cult event state marker={} player={} action=reset total=0",
                    args[3],
                    player.getUsername());
        } else if (args[2].equals("snapshot")) {
            List<Flag> observed = List.copyOf(flags.getOrDefault(player.getUniqueId(), new CopyOnWriteArrayList<>()));
            for (Flag flag : observed)
                logger.info(String.format(
                        Locale.ROOT,
                        "Smoketest Cult observed flag marker=%s player=%s check=%s violations=%.3f setback=%s",
                        args[3],
                        flag.player(),
                        flag.check(),
                        flag.violations(),
                        flag.setback()));
            logger.info(
                    "Smoketest Cult event snapshot marker={} player={} total={}",
                    args[3],
                    player.getUsername(),
                    observed.size());
        } else throw new IllegalArgumentException("Unknown flag action");
    }
}
