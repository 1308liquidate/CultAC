package ac.cult.cultac.network;

import ac.cult.cultac.network.event.*;
import ac.cult.cultac.protocol.*;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPacket;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPacket;
import java.util.function.Consumer;

/** Transport-only callbacks bypass application policy; consumer fixtures use a real dispatcher. */
final class TestTransportRoutes {
    final PacketDispatcher dispatcher;
    private final ProtocolRuntime runtime;
    private final PacketDispatcher.Route[] entries;
    private volatile PacketRoutes snapshot;

    TestTransportRoutes(ProtocolRuntime runtime) {
        this.runtime = runtime;
        entries = new PacketDispatcher.Route[runtime.slotCount()];
        snapshot = new PacketRoutes(runtime, entries);
        dispatcher = new PacketDispatcher(runtime) {
            @Override
            public Route get(PacketDirection direction, ConnectionPhase phase, int id) {
                return snapshot.get(direction, phase, id);
            }

            @Override
            public Route get(PacketType<?> type) {
                return snapshot.get(type);
            }

            @Override
            public <R extends ServerboundPacket> void receive(
                    PacketReceiveEvent<R> event, ReceiveRoute<? super R> routes) {
                routes.dispatch(event, null);
            }

            @Override
            public void send(PacketSendEvent<?> event, PacketSendRoute<Object> route) {
                route.dispatch(event, null, event.getOriginalPacket());
            }
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <R extends ServerboundPacket> void receive(PacketType<R> type, Consumer<PacketReceiveEvent<R>> callback) {
        PacketReceiveHandler<Object> handler = (event, player, packet) -> callback.accept(event);
        var route = new PacketDispatcher.ReceiveRoute<ServerboundPacket>(
                PacketReceiveRoute.empty(),
                PacketReceiveRoute.of(new PacketReceiveHandler[] {handler}),
                PacketReceiveRoute.empty());
        entries[runtime.slot(type)] = new PacketDispatcher.Route(type, route, null);
        snapshot = new PacketRoutes(runtime, entries);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    <R extends ClientboundPacket> void send(PacketType<R> type, Consumer<PacketSendEvent<R>> callback) {
        PacketSendHandler<Object> handler = (event, player, packet) -> callback.accept((PacketSendEvent) event);
        // These routes dispatch without a player, so they are connection handlers.
        PacketSendHandler<Object>[] handlers = new PacketSendHandler[] {handler};
        entries[runtime.slot(type)] = new PacketDispatcher.Route(type, null, PacketSendRoute.of(handlers, handlers));
        snapshot = new PacketRoutes(runtime, entries);
    }
}
