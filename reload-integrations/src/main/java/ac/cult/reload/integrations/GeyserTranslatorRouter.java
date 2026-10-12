package ac.cult.reload.integrations;

import ac.cult.reload.api.HostContext;
import java.util.*;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;

/** The native registry only retains this host class, with one scoped route per generation. */
public final class GeyserTranslatorRouter<P extends org.geysermc.mcprotocollib.network.packet.Packet>
        extends PacketTranslator<P> {
    private static final Map<Class<?>, GeyserTranslatorRouter<?>> ROUTERS = new HashMap<>();
    private final PacketTranslator<P> original;
    private final Map<String, Route<P>> routes = new LinkedHashMap<>();

    private GeyserTranslatorRouter(PacketTranslator<P> original) {
        this.original = original;
    }

    @SuppressWarnings("unchecked")
    public static synchronized <P extends org.geysermc.mcprotocollib.network.packet.Packet> PacketTranslator<P> install(
            Class<P> type, HostContext host, PacketTranslator<P> implementation) {
        Object installed = Registries.JAVA_PACKET_TRANSLATORS.get(type);
        GeyserTranslatorRouter<P> router;
        if (installed instanceof GeyserTranslatorRouter<?> existing) router = (GeyserTranslatorRouter<P>) existing;
        else {
            if (installed == null) throw new IllegalStateException("Missing native Geyser translator " + type);
            router = new GeyserTranslatorRouter<>((PacketTranslator<P>) installed);
            // Geyser reload replaces the native table. Preserve routes until each generation refreshes its
            // implementation.
            var previous = (GeyserTranslatorRouter<P>) ROUTERS.get(type);
            if (previous != null) router.routes.putAll(previous.routes);
            ROUTERS.put(type, router);
            Registries.JAVA_PACKET_TRANSLATORS.register(type, router);
        }
        router.routes.put(host.generationId(), new Route<>(host, implementation));
        return router.original;
    }

    public static synchronized <P extends org.geysermc.mcprotocollib.network.packet.Packet> boolean installed(
            Class<P> type, HostContext host, Object implementation) {
        var router = ROUTERS.get(type);
        var route = router == null ? null : router.routes.get(host.generationId());
        return Registries.JAVA_PACKET_TRANSLATORS.get(type) == router
                && route != null
                && route.implementation == implementation;
    }

    public static synchronized <P extends org.geysermc.mcprotocollib.network.packet.Packet> void remove(
            Class<P> type, HostContext host, Object implementation) {
        var router = ROUTERS.get(type);
        if (router == null) return;
        var route = router.routes.get(host.generationId());
        if (route != null && route.implementation == implementation) router.routes.remove(host.generationId());
        if (router.routes.isEmpty()) {
            if (Registries.JAVA_PACKET_TRANSLATORS.get(type) == router)
                Registries.JAVA_PACKET_TRANSLATORS.register(type, router.original);
            ROUTERS.remove(type);
        }
    }

    @Override
    public void translate(GeyserSession session, P packet) {
        HostContext host;
        synchronized (GeyserTranslatorRouter.class) {
            host = routes.isEmpty() ? null : routes.values().iterator().next().host;
        }
        String owner = host == null ? null : host.ownerOf(session);
        Route<P> route;
        synchronized (GeyserTranslatorRouter.class) {
            route = routes.get(owner);
        }
        if (route != null) route.host.resources().run(() -> route.implementation.translate(session, packet));
        else original.translate(session, packet);
    }

    private record Route<P extends org.geysermc.mcprotocollib.network.packet.Packet>(
            HostContext host, PacketTranslator<P> implementation) {}
}
