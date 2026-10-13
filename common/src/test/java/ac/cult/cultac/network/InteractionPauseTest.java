package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.InventoryPackets;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSetCarriedItem;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundUseItemOn;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.Vec3d;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import ac.cult.cultac.utils.latency.InteractionPause;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InteractionPauseTest {
    @Test
    void aPauseDropsInteractionsUntilItResyncsFromTheServerViews() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            var inventory = f.player.getInventory();
            var stone = ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item("minecraft:stone");
            var authoritative = new ArrayList<SimItemStack>();
            for (int i = 0; i < inventory.menu.getSlots().size(); i++) authoritative.add(SimItemStack.EMPTY);
            authoritative.set(36, stone.copy());
            sendContent(f, new InventoryPackets.Content(0, 42, authoritative, SimItemStack.EMPTY));
            f.player.lastTransactionReceived.set(f.player.lastTransactionSent.get());
            f.player.latencyUtils.handleNettySyncTransaction(f.player.lastTransactionSent.get());

            List<Runnable> scheduled = new ArrayList<>();
            var pause = new InteractionPause(f.player, (task, millis) -> scheduled.add(task));
            pause.start(5_000);
            var place = new ServerboundUseItemOn(
                    Hand.MAIN_HAND, new BlockPos(0, 64, 0), Direction.UP, new Vec3d(0.5, 1, 0.5), false, false, 7);
            var click = new InventoryClick(
                    0, 42, 36, 0, WindowClickType.PICKUP, Map.of(36, SimItemStack.EMPTY), stone.copy());
            assertTrue(pause.discards(place));
            assertTrue(pause.discards(click));
            assertFalse(pause.discards(new ServerboundSetCarriedItem(1)), "only interactions are dropped");
            assertEquals(
                    "minecraft:stone",
                    inventory.inventory.getInventoryStorage().getItem(36).itemKey());

            int before = f.player.lastTransactionSent.get();
            assertEquals(1, scheduled.size());
            scheduled.getFirst().run();
            assertFalse(pause.isActive());
            // The block acknowledgement and the inventory resend each carry their own proof.
            assertEquals(before + 2, f.player.lastTransactionSent.get());
            assertFalse(pause.discards(click));
        }
    }

    @Test
    void aZeroDurationEndsAnActivePauseImmediately() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            List<Runnable> scheduled = new ArrayList<>();
            var pause = new InteractionPause(f.player, (task, millis) -> scheduled.add(task));
            pause.start(5_000);
            pause.start(0);
            assertFalse(pause.isActive());
            // The superseded timer must not resync again.
            int before = f.player.lastTransactionSent.get();
            scheduled.getFirst().run();
            assertEquals(before, f.player.lastTransactionSent.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static void sendContent(RecordReceiveFixture f, InventoryPackets.Content content) {
        var contentType = (PacketType<InventoryPackets.Content>)
                f.registrar.scanner().runtime().typeForRecord(InventoryPackets.Content.class);
        var event = new PacketSendEvent<>(f.user, ConnectionPhase.PLAY, contentType, content, false);
        f.player.getInventory().onContainerSetContent(event, f.player, content);
    }
}
