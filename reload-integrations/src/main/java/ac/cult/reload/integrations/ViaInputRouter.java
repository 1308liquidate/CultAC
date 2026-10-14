package ac.cult.reload.integrations;

import ac.cult.reload.api.HostContext;
import com.viaversion.viaversion.api.protocol.ProtocolPipeline;
import com.viaversion.viaversion.api.protocol.packet.Direction;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import com.viaversion.viaversion.api.protocol.packet.State;
import com.viaversion.viaversion.exception.CancelException;
import com.viaversion.viaversion.protocol.AbstractSimpleProtocol;

/**
 * Via keeps a connection's protocols until it closes, so only this host class enters its pipeline. The generation that
 * owns the connection observes serverbound play input through it, and a released generation leaves nothing behind.
 */
public final class ViaInputRouter extends AbstractSimpleProtocol {
    /** Returns whether to cancel the packet; it must not throw. */
    @FunctionalInterface
    public interface Observer {
        boolean reject(PacketWrapper wrapper);
    }

    private volatile Route route;

    private record Route(HostContext host, Observer observer) {}

    private ViaInputRouter() {}

    /** Routes the connection's input to this observer; call on the connection's event loop. */
    public static void route(ProtocolPipeline pipeline, HostContext host, Observer observer) {
        var router = pipeline.getProtocol(ViaInputRouter.class);
        if (router == null) {
            router = new ViaInputRouter();
            pipeline.add(router);
        }
        router.route = new Route(host, observer);
    }

    public static Observer observer(ProtocolPipeline pipeline) {
        var router = pipeline.getProtocol(ViaInputRouter.class);
        var current = router == null ? null : router.route;
        return current == null ? null : current.observer;
    }

    /** Stops routing to this observer unless another one replaced it; call on the connection's event loop. */
    public static void release(ProtocolPipeline pipeline, Observer observer) {
        var router = pipeline.getProtocol(ViaInputRouter.class);
        if (router != null && router.route != null && router.route.observer == observer) router.route = null;
    }

    @Override
    public boolean isBaseProtocol() {
        return true;
    }

    @Override
    public void transform(Direction direction, State state, PacketWrapper wrapper) throws CancelException {
        var current = route;
        if (current == null || direction != Direction.SERVERBOUND || state != State.PLAY) return;
        boolean[] rejected = {false};
        if (current.host == null) rejected[0] = current.observer.reject(wrapper);
        else current.host.resources().run(() -> rejected[0] = current.observer.reject(wrapper));
        if (rejected[0]) {
            wrapper.cancel();
            throw CancelException.generate();
        }
    }
}
