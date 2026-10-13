package ac.cult.cultac.checks.impl.prediction.checks;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.checks.impl.prediction.ItemUseBurstResult;
import ac.cult.cultac.checks.impl.prediction.ItemUseTickResult;
import ac.cult.cultac.checks.impl.prediction.PredVector;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.platform.api.sender.Sender;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.anticheat.update.PredictionComplete;
import ac.cult.cultac.utils.data.ClientItemUseState;
import ac.cult.cultac.utils.inventory.ItemUtil;
import ac.cult.cultac.utils.math.Vec3;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ItemUseBurstPolicyTest {
    @Test
    void repeatedPartialBurstsSetBackWithoutViolationsOrItemRelease() {
        OfflineCultTestBootstrap.installConfig();
        var channel = new EmbeddedChannel();
        var user = TestUsers.create(new User.Profile(UUID.randomUUID(), ".Use_Balance_Test"), channel);
        var original = new CultPlayer(user);
        try {
            var player = spy(original);
            player.gamemode = GameMode.SURVIVAL;
            var platform = mock(PlatformPlayer.class);
            player.platformPlayer = platform;
            var setbacks = mock(SetbackTeleportUtil.class);
            doReturn(setbacks).when(player).getSetbackTeleportUtil();
            var now = new AtomicLong(10_000_000_000L);
            var policy = new ItemUseDesync(player, now::get);
            var noSlow = player.checkManager.getListener(NoSlow.class);
            var sender = mock(Sender.class);
            when(sender.getUniqueId()).thenReturn(Sender.CONSOLE_UUID);
            when(sender.isValid()).thenReturn(true);
            when(sender.hasPermission("cult.debug")).thenReturn(true);
            var debug = player.checkManager.getDebugHandler();
            assertTrue(debug.toggleItemSlowListener(sender));
            double before = noSlow.violations;
            for (int use = 0; use < 5; use++) {
                player.clientItemUse.start(Hand.MAIN_HAND, ItemUtil.modelItems().stack("minecraft:golden_apple", 2));
                for (int i = 0; i < 24; i++) {
                    now.addAndGet(50_000_000L);
                    int tick = player.packetStateData.acceptedClientTick;
                    var state = new ClientItemUseState.Snapshot(use, tick, 1, 1, 0.2F, true, true, false);
                    var prediction = evidence(player, new ItemUseTickResult(state, 0, 0), null);
                    noSlow.onPredictionComplete(prediction);
                    policy.onPredictionComplete(prediction);
                    policy.onPlayerTickEnd(null);
                    player.packetStateData.acceptedClientTick++;
                }
                now.addAndGet(8 * 50_000_000L);
                policy.onUseCompleted();
                player.clientItemUse.release(); // Incoming source-proven completion, not enforcement.
                // Start another item before the correction: policy must preserve active use.
                player.clientItemUse.start(Hand.OFF_HAND, ItemUtil.modelItems().stack("minecraft:shield", 1));
                for (int i = 0; i < 8; i++) {
                    assertEquals(0.2F, policy.postUseMultiplier());
                    var prediction = evidence(player, null, new ItemUseBurstResult(0, 0.05));
                    noSlow.onPredictionComplete(prediction);
                    policy.onPredictionComplete(prediction);
                    // The correction is requested before tick-end can commit the
                    // rejected movement as a newer rollback anchor.
                    if (use == 4 && i == 7) verify(setbacks).executeNonSimulatingSetback();
                    else verifyNoInteractions(setbacks);
                    // Another prediction for this tick cannot erase observed unslowed movement.
                    policy.onPredictionComplete(evidence(player, null, new ItemUseBurstResult(0, 0)));
                    policy.onPlayerTickEnd(null);
                    policy.onPlayerTickEnd(null); // Accounting is once per finalized tick.
                    player.packetStateData.acceptedClientTick++;
                    assertTrue(player.clientItemUse.definitelyUsing());
                }
                player.clientItemUse.release();
                assertEquals(use + 1, policy.burstDebt(), 1e-9);
            }
            verify(setbacks).executeNonSimulatingSetback();
            assertEquals(before, noSlow.violations);
            verify(platform, never()).clearActiveItem();
            verify(platform, never()).getItemUseState();
            var messages = ArgumentCaptor.forClass(Component.class);
            verify(sender, times(155)).sendMessage(messages.capture());
            var plain = PlainTextComponentSerializer.plainText();
            assertTrue(plain.serialize(messages.getAllValues().getFirst())
                    .contains("tick=0 REWARD +0.250 (slowed tick) balance=0.000 -> 0.250 setbackBelow=-4.000"));
            assertTrue(messages.getAllValues().stream()
                    .map(plain::serialize)
                    .anyMatch(text -> text.contains("DEBIT -1.000 (unslowed burst) balance=6.000 -> 5.000")));
            assertTrue(plain.serialize(messages.getAllValues().getLast())
                    .contains("balance=-4.000 -> -5.000 setbackBelow=-4.000 setback=requested"));
            // Disabled or revoked listeners must neither receive output nor format diagnostics.
            assertFalse(debug.toggleItemSlowListener(sender));
            debug.relayItemSlow(() -> {
                throw new AssertionError("Disabled diagnostics were evaluated");
            });
            assertTrue(debug.toggleItemSlowListener(sender));
            when(sender.hasPermission("cult.debug")).thenReturn(false);
            assertFalse(debug.hasItemSlowListeners());
            debug.relayItemSlow(() -> {
                throw new AssertionError("Revoked diagnostics were evaluated");
            });
            verify(sender, times(155)).sendMessage(any(Component.class));
        } finally {
            original.onRemove();
            channel.finishAndReleaseAll();
        }
    }

    private static PredictionComplete evidence(CultPlayer player, ItemUseTickResult use, ItemUseBurstResult burst) {
        var realities = new ArrayList<PredictionResult>();
        var result =
                new PredictionResult(player, new PredVector(Vec3.ZERO), null, Vec3.ZERO, null, realities, realities);
        result.setItemUseResult(use);
        result.setItemUseBurstResult(burst);
        realities.add(result);
        return new PredictionComplete(result);
    }
}
