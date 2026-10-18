package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.events.packets.listeners.PacketSelfMetadataListener;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.network.packet.EntityMetadata;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.PacketType;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.utils.enums.Pose;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import ac.cult.cultac.utils.nmsutil.WatchableIndexUtil;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelfSharedFlagsPoseTest {
    @Test
    void sharedFlagsKeepTheLocalPoseUntilTheTickEnds() throws Exception {
        try (var services = new RecordConsumerServices();
                var fixture = new RecordReceiveFixture(ProtocolVersion.V26_3)) {
            var player = fixture.player;
            var start = new Vec3(4.5, 64.0, 4.5);
            prepare(player, start);
            // A ceiling at 65.5 fits the crouching box but not the standing one, over every closest-space neighbour.
            var registry = DataTables.defaults().registry();
            int stone = registry.block("minecraft:stone").defaultState();
            int topSlab = registry.with(registry.block("minecraft:stone_slab").defaultState(), "type", "top");
            for (int x = 3; x <= 5; x++) {
                for (int z = 3; z <= 5; z++) {
                    player.compensatedWorld.updateBlock(new BlockPos(x, 63, z), stone);
                    player.compensatedWorld.updateBlock(new BlockPos(x, 65, z), topSlab);
                }
            }
            for (int tick = 0; tick < 3; tick++) player.compensatedWorld.removeInvalidPistonLikeStuff(0);
            assertEquals(Pose.STANDING, player.pose);

            sendSharedFlags(player, (byte) 0);
            assertEquals(Pose.STANDING, player.pose);
            assertEquals(Pose.STANDING.height, player.boundingBox.maxY - player.boundingBox.minY, 1.0E-9);

            var runner = player.checkManager.getSimulationProcessor();
            runner.seedStartingVelocity(Vec3.ZERO);
            runner.callPrediction(start, start, true, 0, 0);
            assertEquals(Pose.CROUCHING, player.pose);
        }
    }

    private static void sendSharedFlags(CultPlayer player, byte flags) {
        var packet = new EntityMetadata(
                player.entityID,
                List.of(new EntityMetadata.Entry(
                        WatchableIndexUtil.ENTITY_SHARED_FLAGS, flags, ByteBuffer.allocate(0))));
        var event = new PacketSendEvent<>(player.user, ConnectionPhase.PLAY, metadataType(), packet, false);
        new PacketSelfMetadataListener().onSetEntityData(event, player, packet);
        event.tasksAfter().forEach(Runnable::run);
        int proof = player.lastTransactionSent.get();
        player.lastTransactionReceived.set(proof);
        player.latencyUtils.handleNettySyncTransaction(proof);
    }

    @SuppressWarnings("unchecked")
    private static PacketType<EntityMetadata> metadataType() {
        return (PacketType<EntityMetadata>) TestProtocolRuntime.catalog().stream()
                .filter(type -> type.recordClass() == EntityMetadata.class)
                .findFirst()
                .orElseThrow();
    }

    private static void prepare(CultPlayer player, Vec3 start) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                player.compensatedWorld.ensureValidationChunkLoaded(x, z);
            }
        }
        player.entityID = 7;
        player.gamemode = GameMode.SURVIVAL;
        player.getSetbackTeleportUtil().hasFullyLoaded = true;
        player.checkManager.getSimulationProcessor().handleRespawn();
        player.x = player.lastX = start.x;
        player.y = player.lastY = start.y;
        player.z = player.lastZ = start.z;
        player.onGround = true;
        player.refreshPlayerPose();
        player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, start.x, start.y, start.z);
    }
}
