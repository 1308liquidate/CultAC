package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.CultAPI;
import ac.cult.cultac.bedrock.replay.offline.OfflinePlayers;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketDirection;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.utils.math.Vec3;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * A client in play that answers pings sent more than twice max-transaction-time plus a second after its last
 * client_tick_end is treated like one that withholds its pongs. Configuration and a tick end restart the span.
 */
class ClientTickEndTimeoutTest {
    private static final Vec3 SAFE = new Vec3(4, 64, 2);

    @Test
    void aClientWhoseClockPassesTheSpanWithoutATickEndIsWithheld() throws Exception {
        withPlayer(player -> {
            inPlay(player);
            noTickEndFor(player, span(player) + TimeUnit.SECONDS.toNanos(1));

            player.pollData();
            assertTrue(exempt(player));
        });
    }

    @Test
    void aFreezeWithinTheSpanIsNot() throws Exception {
        withPlayer(player -> {
            inPlay(player);
            // The drain after a freeze as long as the timeout answers every ping queued during it at once.
            noTickEndFor(player, span(player) - TimeUnit.SECONDS.toNanos(1));

            player.pollData();
            assertFalse(exempt(player));
        });
    }

    @Test
    void aTickEndRestartsTheSpan() throws Exception {
        withPlayer(player -> {
            inPlay(player);
            noTickEndFor(player, span(player) + TimeUnit.SECONDS.toNanos(1));
            player.clientTickEnded();

            player.pollData();
            assertFalse(exempt(player));
        });
    }

    @Test
    void configurationRestartsTheSpan() throws Exception {
        withPlayer(player -> {
            CultConnection connection = player.user.getCultConnection();
            connection.phase(PacketDirection.SERVERBOUND, ConnectionPhase.CONFIGURATION);
            connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.CONFIGURATION);
            player.lastTransSent = System.currentTimeMillis();
            noTickEndFor(player, span(player) + TimeUnit.SECONDS.toNanos(1));
            player.pollData();
            assertFalse(exempt(player), "the client has no level in configuration");

            connection.forwarded(ClientboundPackets.FINISH_CONFIGURATION, null);
            connection.forwarded(ServerboundPackets.FINISH_CONFIGURATION, null);
            player.pollData();
            assertFalse(exempt(player));
        });
    }

    private static void inPlay(CultPlayer player) {
        CultConnection connection = player.user.getCultConnection();
        connection.phase(PacketDirection.SERVERBOUND, ConnectionPhase.PLAY);
        connection.phase(PacketDirection.CLIENTBOUND, ConnectionPhase.PLAY);
        player.lastTransSent = System.currentTimeMillis();
    }

    private static long span(CultPlayer player) {
        try {
            Field field = CultPlayer.class.getDeclaredField("maxTransactionTime");
            field.setAccessible(true);
            return TimeUnit.SECONDS.toNanos(2L * field.getInt(player) + 1);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The client has just answered a ping, so the ping timeout holds, and its last tick end is {@code nanos} old. */
    private static void noTickEndFor(CultPlayer player, long nanos) {
        long now = System.nanoTime();
        set(player, "playerClockAtLeast", now);
        set(player, "pingsHeldAt", now);
        set(player, "clientTickEndedAt", now - nanos);
    }

    /** Observe-only stops inspecting the client; otherwise it is disconnected. */
    private static boolean exempt(CultPlayer player) {
        try {
            Field field = CultPlayer.class.getDeclaredField("hasDisconnected");
            field.setAccessible(true);
            return CultAPI.INSTANCE.getPlayerDataManager().isExemptUser(player.user)
                    || ((AtomicBoolean) field.get(player)).get();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void set(CultPlayer player, String name, long value) {
        try {
            Field field = CultPlayer.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(player, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void withPlayer(Consumer<CultPlayer> body) throws Exception {
        try (var services = new RecordConsumerServices()) {
            OfflinePlayers.withSafePlayer(SAFE, player -> {
                try {
                    body.accept(player);
                } finally {
                    CultAPI.INSTANCE.getPlayerDataManager().clearExemptions(player.user);
                }
            });
        }
    }
}
