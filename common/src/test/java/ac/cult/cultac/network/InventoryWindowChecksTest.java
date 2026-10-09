package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.badpackets.BadPacketsWindow;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsH;
import ac.cult.cultac.checks.impl.multiactions.MultiActionsS;
import ac.cult.cultac.events.packets.listeners.PacketPlayerWindow;
import ac.cult.cultac.network.event.PacketReceiveEvent;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.Opaque;
import ac.cult.cultac.protocol.packet.ServerboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundOpenScreen;
import ac.cult.cultac.protocol.packet.serverbound.ServerboundSwing;
import ac.cult.cultac.utils.data.KnownInput;
import ac.cult.cultac.utils.inventory.InventoryClick;
import ac.cult.cultac.utils.inventory.inventory.MenuType;
import ac.cult.cultac.utils.inventory.inventory.WindowClickType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InventoryWindowChecksTest {
    @Test
    void acknowledgedServerOpenReplacesClosedAndPreviousWindowPossibilities() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var state = f.player.openWindow;
            assertFalse(state.mustBeOpen());
            serverOpen(f, 7);
            acknowledge(f, 1);
            assertFalse(state.mustBeOpen());
            assertTrue(state.getPossibilities().stream().anyMatch(w -> w != null && w.id() == 7));
            acknowledge(f, 2);
            assertTrue(state.mustBeOpen());
            serverOpen(f, 8);
            acknowledge(f, 3);
            assertEquals(2, state.getPossibilities().size());
            acknowledge(f, 4);
            assertEquals(8, state.getPossibilities().iterator().next().id());
        }
    }

    @Test
    void clientCloseCannotDiscardAServerWindowItHasNotProcessedYet() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            serverOpen(f, 7);
            acknowledge(f, 2);
            serverOpen(f, 8);
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            assertFalse(f.player.openWindow.mustBeOpen());
            acknowledge(f, 4);
            assertTrue(f.player.openWindow.mustBeOpen());
            assertEquals(
                    8, f.player.openWindow.getPossibilities().iterator().next().id());
            f.player.openWindow.onClientOpenOrCloseWindow(false);
            assertFalse(f.player.openWindow.mustBeOpen());
        }
    }

    @Test
    void serverCloseIsPossibleBeforeItsProofAndCertainAfterward() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            serverOpen(f, 7);
            acknowledge(f, 2);
            var packet = new Opaque(ClientboundPackets.CONTAINER_CLOSE);
            var event = new PacketSendEvent<>(
                    f.user, ConnectionPhase.PLAY, ClientboundPackets.CONTAINER_CLOSE, packet, true);
            f.player.openWindow.onServerWindow(event, 0, null);
            assertEquals(1, event.getWritesBeforeSend().size());
            assertEquals(1, event.getWritesAfterSend().size());
            assertTrue(f.player.openWindow.mustBeOpen());
            acknowledge(f, 3);
            assertFalse(f.player.openWindow.mustBeOpen());
            acknowledge(f, 4);
            assertEquals(1, f.player.openWindow.getPossibilities().size());
            assertTrue(f.player.openWindow.getPossibilities().contains(null));
        }
    }

    @Test
    void closedPlayerInventoryCanOpenThroughAFirstClickButAnotherWindowCannot() throws Exception {
        try (var services = new RecordConsumerServices();
                var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.user.bind(mock(PlatformPlayer.class));
            var state = f.player.openWindow;
            var click = click(0, 36, WindowClickType.PICKUP);
            var event = clickEvent(f, click);
            new PacketPlayerWindow().onContainerClick(event, f.player, click);
            assertTrue(state.mustBeOpen());
            var check = spy(new BadPacketsWindow(f.player));
            doReturn(true).when(check).alert(any(java.util.function.Supplier.class));
            doReturn(true).when(check).shouldModifyPackets();
            check.onContainerClick(event, f.player, click);
            assertFalse(event.isCancelled());
            var wrongClick = click(9, 0, WindowClickType.PICKUP);
            var wrongEvent = clickEvent(f, wrongClick);
            check.onContainerClick(wrongEvent, f.player, wrongClick);
            assertTrue(wrongEvent.isCancelled());
        }
    }

    @Test
    void inventoryInputChecksUseKeysRatherThanMomentum() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            var check = spy(new MultiActionsH(f.player));
            doReturn(true).when(check).flag();
            doReturn(true).when(check).shouldModifyPackets();
            f.player.packetStateData.knownInput = KnownInput.DEFAULT;
            check.onPlayerTickEnd(null);
            verify(check, never()).flag();
            f.player.packetStateData.knownInput = new KnownInput(true, false, false, false, false, false, false);
            check.onPlayerTickEnd(null);
            verify(check).flag();
        }
    }

    @Test
    void aVanillaInventoryDropSwingMustBeFollowedByADropClick() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            f.player.openWindow.onClientOpenOrCloseWindow(true);
            var check = spy(new MultiActionsS(f.player));
            doReturn(true).when(check).flag();
            var swing = new PacketReceiveEvent<>(
                    f.user, ConnectionPhase.PLAY, ServerboundPackets.SWING, ServerboundSwing.PUNCH);
            check.onDecodedPacketReceive(swing);
            check.onDecodedPacketReceive(clickEvent(f, click(0, 36, WindowClickType.THROW)));
            verify(check, never()).flag();
            check.onDecodedPacketReceive(swing);
            check.onDecodedPacketReceive(clickEvent(f, click(0, 36, WindowClickType.PICKUP)));
            verify(check).flag();
        }
    }

    @Test
    void rateLimitedClickStillCarriesValidatedClientPrediction() {
        try (var f = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var inventory = f.player.getInventory();
            var stone = ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap.item("minecraft:stone");
            inventory.inventory.getInventoryStorage().setItem(36, stone.copy());
            int[] slots = {36, 37, 37, 38};
            PacketReceiveEvent<InventoryClick> last = null;
            for (int i = 0; i < slots.length; i++) {
                boolean pickup = (i & 1) == 0;
                var packet = new InventoryClick(
                        0,
                        0,
                        slots[i],
                        0,
                        WindowClickType.PICKUP,
                        Map.of(slots[i], pickup ? SimItemStack.EMPTY : stone.copy()),
                        pickup ? stone.copy() : SimItemStack.EMPTY);
                last = clickEvent(f, packet);
                inventory.onContainerClick(last, f.player, packet);
                assertEquals(i == 3, last.isCancelled());
            }
            assertEquals(
                    "minecraft:stone",
                    inventory.inventory.getInventoryStorage().getItem(38).itemKey());
            assertTrue(inventory.menu.getCarried().isEmpty());
        }
    }

    private static void serverOpen(RecordReceiveFixture f, int id) {
        if (f.user.getPlayer() == null) f.user.bind(mock(PlatformPlayer.class));
        var packet = new ClientboundOpenScreen(id, "minecraft:generic_9x1");
        var event = new PacketSendEvent<>(f.user, ConnectionPhase.PLAY, ClientboundPackets.OPEN_SCREEN, packet, false);
        f.player.openWindow.onServerWindow(event, id, MenuType.GENERIC_9x1);
    }

    private static void acknowledge(RecordReceiveFixture f, int transaction) {
        f.player.lastTransactionReceived.set(transaction);
        f.player.latencyUtils.handleNettySyncTransaction(transaction);
    }

    private static InventoryClick click(int window, int slot, WindowClickType type) {
        return new InventoryClick(window, 0, slot, 0, type, Map.of(), SimItemStack.EMPTY);
    }

    @SuppressWarnings("unchecked")
    private static PacketReceiveEvent<InventoryClick> clickEvent(RecordReceiveFixture f, InventoryClick click) {
        PacketType<InventoryClick> type =
                (PacketType<InventoryClick>) f.registrar.scanner().runtime().typeForRecord(InventoryClick.class);
        return new PacketReceiveEvent<>(f.user, ConnectionPhase.PLAY, type, click);
    }
}
