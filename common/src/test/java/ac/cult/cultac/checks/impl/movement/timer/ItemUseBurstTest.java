package ac.cult.cultac.checks.impl.movement.timer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.inventory.ItemUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ItemUseBurstTest {
    @Test
    void tickPeriodMatchesTheClientRateClampAndFloatRounding() {
        assertEquals(1_000_000_000L, ItemUseBurst.periodNanos(0, false));
        assertEquals(1_000_000_000L, ItemUseBurst.periodNanos(-10, false));
        assertEquals(50_000_000L, ItemUseBurst.periodNanos(200, false));
        assertEquals(50_000_000L, ItemUseBurst.periodNanos(5, true));
        long nativeNanos = (long) (1_000_000_000D / 6.0F);
        assertEquals((long) (((float) nativeNanos / 1_000_000F) * 1_000_000D), ItemUseBurst.periodNanos(6, false));
    }

    @Test
    void validatedUseProofRejectsImpossibleTicksAndSetsBackWithoutReleasingUse() {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        var user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".ItemUse_Burst_Test"), channel);
        var original = new CultPlayer(user);
        try {
            var player = spy(original);
            var setbacks = mock(SetbackTeleportUtil.class);
            doReturn(setbacks).when(player).getSetbackTeleportUtil();
            var now = new AtomicLong(10_000_000_000L);
            var check = new ItemUseBurst(player, now::get);
            check.setEnabled(true);
            player.clientItemUse.start(Hand.OFF_HAND, ItemUtil.modelItems().stack("minecraft:shield", 1));

            // Unsolicited acknowledgements cannot create or move the clock's proof.
            check.acknowledge(7, now.get());
            for (int i = 0; i < 20; i++) assertFalse(tick(check, player).isCancelled());
            assertEquals(0, check.violations);

            check.expectProof(8);
            check.acknowledge(8, now.get());
            for (int i = 0; i < 10; i++) assertFalse(tick(check, player).isCancelled());
            assertTrue(tick(check, player).isCancelled());
            assertEquals(1, check.violations);
            verify(setbacks).executeNonSimulatingSetback();
            assertTrue(player.clientItemUse.hasObligation());
            assertTrue(player.clientItemUse.definitelyUsing());

            now.addAndGet(50_000_000L);
            assertFalse(tick(check, player).isCancelled());
            assertEquals(1, check.violations);
        } finally {
            original.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    private static PacketReceiveEvent<Opaque> tick(ItemUseBurst check, CultPlayer player) {
        var packet = new Opaque(ServerboundPackets.CLIENT_TICK_END);
        var event =
                new PacketReceiveEvent<>(player.user, ConnectionPhase.PLAY, ServerboundPackets.CLIENT_TICK_END, packet);
        check.onTickEnd(event, player, packet);
        return event;
    }
}
