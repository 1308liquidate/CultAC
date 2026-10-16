package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.bedrock.replay.offline.OfflineCultTestBootstrap;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.utils.nmsutil.BlockBreakSpeed;
import org.junit.jupiter.api.Test;

class ReceivedMiningAttributesTest {
    @Test
    void miningUsesReceivedAttributeValues() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            player.packetStateData.packetPlayerOnGround = true;
            var pickaxe = OfflineCultTestBootstrap.item("minecraft:iron_pickaxe");
            int stone = ac.cult.blocksim.data.BlockIds.STONE.defaultState();
            assertEquals(6.0F / 1.5F / 30, BlockBreakSpeed.getBlockDamage(player, pickaxe, stone), 1e-8);

            // A block_break_speed modifier makes stone an instant break on the client.
            player.compensatedEntities.getSelf().blockBreakSpeed = 11.0;
            assertTrue(BlockBreakSpeed.getBlockDamage(player, pickaxe, stone) >= 1.0);

            player.compensatedEntities.getSelf().blockBreakSpeed = 1.0;
            player.compensatedEntities.getSelf().miningEfficiency = 26.0;
            assertEquals((6.0F + 26.0F) / 1.5F / 30, BlockBreakSpeed.getBlockDamage(player, pickaxe, stone), 1e-8);
        }
    }
}
