package ac.cult.cultac.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.events.packets.listeners.PacketPingListener;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundPong;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class WireIdTest {
    private static final int FOREIGN = Short.MIN_VALUE - 5;
    private static final int FRESH = Short.MIN_VALUE - 6;

    @Test
    void pingIdsAreNotSequential() {
        SecureRandom random = new SecureRandom();
        Set<Integer> seen = new HashSet<>();
        int steps = 0;
        int previous = CultPlayer.nextWireId(random);
        for (int i = 0; i < 1000; i++) {
            int id = CultPlayer.nextWireId(random);
            if (Math.abs((long) id - previous) <= 1) steps++;
            seen.add(id);
            previous = id;
        }
        assertTrue(steps < 3, "ids stepped by one " + steps + " times");
        assertTrue(seen.size() > 990, "only " + seen.size() + " distinct ids");
    }

    @Test
    void pingIdsStayBelowTheSignedShortRange() {
        assertEquals(
                Short.MIN_VALUE - 1, CultPlayer.nextWireId(scripted(-1, Short.MIN_VALUE, -12345, Short.MIN_VALUE - 1)));

        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 100_000; i++) {
            int id = CultPlayer.nextWireId(random);
            assertTrue(id <= Short.MIN_VALUE - 1, "id " + id);
        }
    }

    @Test
    void aBackendPingInFlightIsNeverReused() {
        withPlayer(player -> {
            player.foreignPingSent(FOREIGN);
            assertEquals(FRESH, player.nextTransactionId(scripted(FOREIGN, FRESH)));
        });
    }

    @Test
    void theOldestBackendPingIsForgottenPastTheBound() {
        withPlayer(player -> {
            player.foreignPingSent(FOREIGN);
            for (int i = 0; i < 1024; i++) player.foreignPingSent(FRESH - 1 - i);
            assertEquals(FOREIGN, player.nextTransactionId(scripted(FOREIGN)));
            assertEquals(FRESH, player.nextTransactionId(scripted(FRESH - 1, FRESH)));
        });
    }

    @Test
    void aBackendPingIsTrackedFromItsSendUntilItsPong() {
        withPlayer(player -> {
            var listener = new PacketPingListener();
            var ping = new ClientboundPing(FOREIGN);
            listener.onPing(
                    new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, ClientboundPackets.PING, ping, false),
                    player,
                    ping);
            assertEquals(FRESH, player.nextTransactionId(scripted(FOREIGN, FRESH)));

            var pong = new ServerboundPong(FOREIGN);
            listener.onPong(
                    new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.PONG, pong),
                    player,
                    pong);
            assertEquals(FOREIGN, player.nextTransactionId(scripted(FOREIGN)));
        });
    }

    private static SecureRandom scripted(int... script) {
        return new SecureRandom() {
            private int next;

            @Override
            public int nextInt() {
                return script[next++];
            }
        };
    }

    private static void withPlayer(Consumer<CultPlayer> body) {
        OfflinePlayers.installConfig();
        CultPlayer player = OfflinePlayers.player();
        try {
            body.accept(player);
        } finally {
            OfflinePlayers.close(player);
        }
    }
}
