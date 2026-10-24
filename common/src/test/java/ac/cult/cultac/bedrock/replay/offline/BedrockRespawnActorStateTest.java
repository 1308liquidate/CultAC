package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.prediction.BedrockPredictionTrigger;
import ac.cult.cultac.bedrock.prediction.integration.BedrockFrameProcessor;
import ac.cult.cultac.bedrock.prediction.integration.BedrockNextTickStates;
import ac.cult.cultac.bedrock.prediction.integration.BedrockProfileState;
import ac.cult.cultac.bedrock.prediction.model.BlockMovementSlowdownState;
import ac.cult.cultac.bedrock.prediction.state.BedrockDolphinBoost;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementAttributeState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementUpdate;
import ac.cult.cultac.bedrock.protocol.BedrockAuthInputFrame;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.events.packets.listeners.PacketPlayerRespawn;
import ac.cult.cultac.network.codec.ModelRegistryNamesState;
import ac.cult.cultac.network.event.PacketSendEvent;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ConnectionPhase;
import ac.cult.cultac.protocol.packet.ClientboundPackets;
import ac.cult.cultac.protocol.packet.clientbound.ClientboundRespawn;
import ac.cult.cultac.protocol.value.GameMode;
import ac.cult.cultac.protocol.value.PlayerSpawnInfo;
import ac.cult.cultac.utils.math.Vec3;
import ac.cult.cultac.utils.nmsutil.GetBoundingBox;
import java.util.List;
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData;
import org.junit.Test;

/**
 * A Java respawn is not a Bedrock actor construction: Geyser keeps the LocalPlayer through ChangeDimension and a
 * respawn, and only StartGame creates one. Metadata and attributes change only through their own SetEntityData and
 * UpdateAttributes packets, so the actor state the client carries must survive the respawn and its placement teleport.
 */
public class BedrockRespawnActorStateTest {
    private static final Vec3 GROUND = new Vec3(1.5, 106, 1.5);

    @Test
    public void respawnInTheSameLevelKeepsTheActorState() {
        assertRespawnKeepsActorState("minecraft:overworld", (byte) 0);
        assertRespawnKeepsActorState("minecraft:overworld", (byte) 3);
    }

    @Test
    public void dimensionChangeKeepsTheActorState() {
        assertRespawnKeepsActorState("minecraft:the_nether", (byte) 0);
        assertRespawnKeepsActorState("minecraft:the_nether", (byte) 3);
    }

    private static void assertRespawnKeepsActorState(String dimension, byte kept) {
        OfflineCultTestBootstrap.installConfig();
        var player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        try {
            player.gamemode = GameMode.SURVIVAL;
            player.compensatedWorld.ensureValidationChunkLoaded(0, 0);
            for (int x = 0; x < 4; x++)
                for (int z = 0; z < 4; z++)
                    player.compensatedWorld.updateBlock(
                            x,
                            105,
                            z,
                            DataTables.defaults().registry().block("minecraft:stone").defaultState());
            player.x = player.lastX = GROUND.x;
            player.y = player.lastY = GROUND.y;
            player.z = player.lastZ = GROUND.z;
            player.onGround = player.lastOnGround = true;
            player.boundingBox = GetBoundingBox.getCollisionBoxForPlayer(player, GROUND.x, GROUND.y, GROUND.z);
            for (int tick = 1; tick <= 3; tick++) assertNotNull(process(player, frame(player, tick, -0.0784, false)));

            BedrockMovementState carried = inject(player);
            int id = ModelRegistryNamesState.defaults().id("minecraft:dimension_type", dimension);
            var respawn = new ClientboundRespawn(new PlayerSpawnInfo(id, dimension, GameMode.SURVIVAL), kept);
            new PacketPlayerRespawn()
                    .onRespawn(
                            new PacketSendEvent<>(
                                    player.user, ConnectionPhase.PLAY, ClientboundPackets.RESPAWN, respawn, false),
                            player,
                            respawn);
            player.lastTransactionReceived.set(player.lastTransactionSent.get());
            player.latencyUtils.handleNettySyncTransaction(player.lastTransactionSent.get());

            String label = dimension + " kept=" + kept;
            BedrockMovementState respawned = state(player);
            assertNotNull(label, respawned);
            assertEquals(label, carried.actor(), respawned.actor());
            assertEquals(label, carried.memory(), respawned.memory());
            assertEquals(label, carried.attributes(), respawned.attributes());

            // The placement teleport moves the same actor; it does not rebuild it.
            player.getSetbackTeleportUtil()
                    .addImmediateBedrockTransportTeleport(
                            GROUND, true, BedrockCoordinateFrame.IDENTITY, null, null, player.lastTransactionSent.get());
            process(player, frame(player, 4, 0, true));
            BedrockMovementState placed = state(player);
            assertNotNull(label, placed);
            assertEquals(label, carried.simulationTick() + 1, placed.simulationTick());
            assertEquals(label, carried.attributes(), placed.attributes());
            assertTrue(label, placed.riptideSpinActive());
            assertEquals(label, carried.riptideSpinTicks() + 1, placed.riptideSpinTicks());
            assertTrue(label, placed.sprinting());
            assertTrue(label, placed.swimming());
            assertTrue(label, placed.swimAmount() > carried.swimAmount());
            assertEquals(label, carried.dolphinBoost().scanTicks() - 1, placed.dolphinBoost().scanTicks());
            assertTrue(label, placed.dolphinBoost().boosted());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    /** Puts every state the issue lists into a non-default value on the carried actor. */
    private static BedrockMovementState inject(CultPlayer player) {
        var processor = player.checkManager.getSimulationProcessor();
        var entry = BedrockProfileState.profileEntries(processor.getCurrentPredictionCommit().carry())
                .getFirst();
        BedrockMovementState state = entry.state();
        BedrockMovementState carried = state.advance(new BedrockMovementUpdate(
                        state.physicalFeetPosition(),
                        state.velocity(),
                        state.inputFrame(),
                        state.collisionFlags(),
                        state.boundingBoxMode(),
                        state.playerDimensions(),
                        0.0F,
                        0L,
                        new BedrockMovementUpdate.Glide(false, false),
                        true,
                        0.5D,
                        new BedrockMovementUpdate.Riptide(0L, true, 4L),
                        new BedrockMovementUpdate.ItemUse(false, 0L)))
                .withPendingBlockMovementSlowdownState(BlockMovementSlowdownState.COBWEB)
                .withDolphinBoost(new BedrockDolphinBoost(17, 5, true))
                .withSprinting(true)
                .withMovementAttribute(BedrockMovementAttributeState.serverValue(0.13F, true));
        try {
            var field = processor.getClass().getDeclaredField("profileCarry");
            field.setAccessible(true);
            field.set(processor, new BedrockNextTickStates(List.of(entry.withState(carried))));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
        return carried;
    }

    private static BedrockMovementState state(CultPlayer player) {
        var commit = player.checkManager.getSimulationProcessor().getCurrentPredictionCommit();
        return commit == null ? null : BedrockProfileState.previousState(commit.carry());
    }

    private static Object process(CultPlayer player, BedrockAuthInputFrame frame) {
        return BedrockFrameProcessor.process(player, frame, BedrockPredictionTrigger.OFFLINE_REPLAY);
    }

    private static BedrockAuthInputFrame frame(CultPlayer player, long tick, double velocity, boolean teleport) {
        long flags = 1L << PlayerAuthInputData.VERTICAL_COLLISION.ordinal();
        if (teleport) flags |= 1L << PlayerAuthInputData.HANDLE_TELEPORT.ordinal();
        return BedrockAuthInputFrame.builder(player.playerUUID)
                .protocolVersion(2193)
                .clientTick(tick)
                .position(GROUND)
                .rotation(0, 0, 0)
                .moveVector(0, 0)
                .reportedEndOfTickVelocity(new Vec3(0, velocity, 0))
                .rawInputFlags(flags)
                .build();
    }
}
