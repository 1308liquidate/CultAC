package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.ProtocolRuntime;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ProtocolData;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundMovePlayer;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import java.util.List;
import org.junit.jupiter.api.Test;

class PacketHandlerScannerTest {
    private final PacketHandlerScanner scanner =
            new PacketHandlerScanner(ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V26_3)));

    @Test
    void aMovementFamilyRegistersOnceAndTheMethodReceivesItsDecodedVariant() {
        var listener = new Movement();
        var registrations = scanner.receiveHandlers(listener);
        assertEquals(1, registrations.size());
        assertSame(ServerboundPackets.MOVE_PLAYER, registrations.getFirst().packetType());
        for (boolean position : List.of(false, true))
            for (boolean rotation : List.of(false, true)) {
                var packet = new ServerboundMovePlayer(1, 2, 3, 4, 5, false, false, position, rotation);
                registrations.getFirst().handler().handle(null, null, packet);
                assertSame(packet, listener.packet);
            }
    }

    @Test
    void inheritedAndDefaultMethodsAreDiscoveredAndAnOverrideShadowsTheParent() {
        var listener = new Child();
        var registrations = scanner.receiveHandlers(listener);
        assertEquals(2, registrations.size());
        for (var registration : registrations) {
            if (registration.packetType() == ServerboundPackets.PONG)
                registration.handler().handle(null, null, new ServerboundPong(7));
            else
                registration
                        .handler()
                        .handle(null, null, new ServerboundMovePlayer(1, 2, 3, 4, 5, false, false, true, true));
        }
        assertEquals(1, listener.moves);
        assertEquals(1, listener.pongs);
    }

    @Test
    void duplicatesAreRejectedForTheWholeFamilyBeforeAnyCallbackIsPublished() {
        assertThrows(IllegalStateException.class, () -> scanner.receiveHandlers(new Duplicate()));
    }

    @Test
    void opaqueDeclarationsUseTheFamilyIdentityAndOptionalFamiliesMayBeAbsent() {
        var registration = scanner.receiveHandlers(new Loaded()).getFirst();
        assertSame(ServerboundPackets.PLAYER_LOADED, registration.packetType());
        registration.handler().handle(null, null, ServerboundPackets.PLAYER_LOADED.opaqueValue());
        var older = new PacketHandlerScanner(ProtocolRuntime.create(ProtocolData.load(ProtocolVersion.V1_21_3)));
        assertTrue(older.hasReceiveHandlerDeclaration(Loaded.class));
        assertTrue(older.receiveHandlers(new Loaded()).isEmpty());
    }

    @Test
    void unknownCatalogKeysWrongDirectionsAndForeignTypesAreRejected() {
        assertThrows(IllegalStateException.class, () -> scanner.receiveHandlers(new Unknown()));
        assertThrows(IllegalStateException.class, () -> scanner.receiveHandlers(new WrongDirection()));
        var source = ServerboundPackets.PONG;
        var foreign = new ac.cult.cultac.protocol.PacketType<>(
                source.key(),
                source.recordClass(),
                source.direction(),
                source.phases(),
                source.wireNames(),
                source.since(),
                source.codec());
        assertThrows(IllegalArgumentException.class, () -> scanner.supports(foreign));
        assertEquals(
                List.of(ServerboundPackets.MOVE_PLAYER),
                scanner.packetTypes(Movement.class, PacketDirection.SERVERBOUND));
    }

    @Test
    void eachDirectionBindsOnlyItsOwnCallbacks() {
        var listener = new Duplex();
        var receives = scanner.receiveHandlers(listener);
        var sends = scanner.sendHandlers(listener);
        assertEquals(
                List.of(ServerboundPackets.PONG),
                receives.stream()
                        .map(PacketHandlerScanner.ReceiveRegistration::packetType)
                        .toList());
        assertEquals(
                List.of(ClientboundPackets.PING),
                sends.stream()
                        .map(PacketHandlerScanner.SendRegistration::packetType)
                        .toList());
        receives.getFirst().handler().handle(null, null, new ServerboundPong(7));
        assertEquals(7, listener.received);
        assertEquals(0, listener.sent);
        sends.getFirst().handler().handle(null, null, new ClientboundPing(8));
        assertEquals(7, listener.received);
        assertEquals(8, listener.sent);
    }

    @Test
    void requestingOneDirectionStillValidatesDeclarationsInTheOtherDirection() {
        assertThrows(IllegalStateException.class, () -> scanner.receiveHandlers(new InvalidSend()));
        assertThrows(IllegalStateException.class, () -> scanner.sendHandlers(new InvalidReceive()));
    }

    static class Duplex {
        int received, sent;

        @CultPacketHandler
        void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
            received = packet.id();
        }

        @CultPacketHandler
        void ping(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) {
            sent = packet.id();
        }
    }

    static class InvalidSend extends Duplex {
        @CultPacketHandler
        void duplicatePing(PacketSendEvent<ClientboundPing> event, CultPlayer player, ClientboundPing packet) {}
    }

    static class InvalidReceive extends Duplex {
        @CultPacketHandler
        void duplicatePong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {}
    }

    static class Movement {
        ServerboundMovePlayer packet;

        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
            this.packet = packet;
        }
    }

    interface DefaultPong {
        void pongSeen();

        @CultPacketHandler
        default void pong(PacketReceiveEvent<ServerboundPong> event, CultPlayer player, ServerboundPong packet) {
            pongSeen();
        }
    }

    static class Parent {
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
            fail("Shadowed parent called");
        }
    }

    static class Child extends Parent implements DefaultPong {
        int moves, pongs;

        @Override
        @CultPacketHandler
        void move(PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {
            moves++;
        }

        @Override
        public void pongSeen() {
            pongs++;
        }
    }

    static class Duplicate {
        @CultPacketHandler
        void first(PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {}

        @CultPacketHandler
        void second(PacketReceiveEvent<ServerboundMovePlayer> event, CultPlayer player, ServerboundMovePlayer packet) {}
    }

    static class Loaded {
        @CultPacketHandler("serverbound.player_loaded")
        void loaded(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {
            assertSame(ServerboundPackets.PLAYER_LOADED, packet.type());
        }
    }

    static class Unknown {
        @CultPacketHandler("serverbound.unknown")
        void unknown(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {}
    }

    static class WrongDirection {
        @CultPacketHandler("clientbound.login_finished")
        void wrong(PacketReceiveEvent<Opaque> event, CultPlayer player, Opaque packet) {}
    }
}
