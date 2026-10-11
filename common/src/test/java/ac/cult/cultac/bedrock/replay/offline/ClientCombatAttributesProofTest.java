package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

import ac.cult.cultac.events.packets.listeners.CheckManagerListener;
import ac.cult.cultac.network.TestUsers;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.protocol.player.User;
import ac.cult.cultac.platform.api.player.PlatformPlayer;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundPing;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundUpdateAttributes;
import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.inventory.Inventory;
import ac.cult.cultac.utils.inventory.ItemUtil;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public final class ClientCombatAttributesProofTest {
    @Test
    public void heldItemChangesCannotReplaceSpeedBeforeItsBundleProofIncludingWhenMounted() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        for (boolean insideBundle : List.of(false, true)) {
            for (boolean mounted : List.of(false, true)) {
                User user =
                        TestUsers.create(new User.Profile(UUID.randomUUID(), "Combat_Proof"), new EmbeddedChannel());
                CultPlayer player = new CultPlayer(user);
                user.bind(mock(PlatformPlayer.class));
                try {
                    player.entityID = 7;
                    player.packetStateData.playerLoadedIntoLevel = true;
                    if (mounted) player.compensatedEntities.vehicles.serverPlayerVehicle = 12;
                    player.combatAttributes.onServerSnapshot(speed(-2.4000000953674316));
                    var packet = new ClientboundUpdateAttributes(player.entityID, List.of(speed(-3.0)));
                    var event = new PacketSendEvent<>(
                            user, ConnectionPhase.PLAY, ClientboundPackets.UPDATE_ATTRIBUTES, packet, insideBundle);
                    player.packetEntityReplication.onUpdateAttributes(event, player, packet);

                    assertEquals(!insideBundle, event.isBundleRequested());
                    assertEquals(1, event.writesAfter().size());
                    var ping = (ClientboundPing) event.writesAfter().getFirst().packet();
                    event.tasksAfter().forEach(Runnable::run);

                    // The held axe and subsequent swaps are visible before its attribute bundle is received.
                    var listener = new CheckManagerListener();
                    for (String item :
                            List.of("minecraft:diamond_axe", "minecraft:trident", "minecraft:diamond_pickaxe")) {
                        player.getInventory()
                                .inventory
                                .getInventoryStorage()
                                .setItem(
                                        Inventory.HOTBAR_OFFSET + 1,
                                        ItemUtil.modelItems().stack(item, 1));
                        player.getInventory().selectHotbarSlot(1);
                        listener.processClientTickEndReceive(RecordReceiveTestEvents.tickEnd(player), player);
                        assertEquals(1.5999999046325684, player.combatAttributes.attackSpeed(), 1e-9);
                    }
                    assertTrue(player.addTransactionResponse(ping.id()));
                    assertEquals(1.0, player.combatAttributes.attackSpeed(), 1e-9);
                    listener.processClientTickEndReceive(RecordReceiveTestEvents.tickEnd(player), player);
                    assertEquals(1.0, player.combatAttributes.attackSpeed(), 1e-9);
                } finally {
                    OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
                }
            }
        }
    }

    private static AttributeSnapshot speed(double modifier) {
        return new AttributeSnapshot(
                "minecraft:attack_speed",
                4.0,
                List.of(new AttributeModifier(
                        "minecraft:base_attack_speed", modifier, AttributeModifier.Operation.ADD_VALUE)));
    }
}
