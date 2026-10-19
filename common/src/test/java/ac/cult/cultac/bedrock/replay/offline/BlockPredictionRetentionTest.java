package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Test;

public class BlockPredictionRetentionTest {
    @Test
    public void repeatedSequenceSpamStaysBoundedAndRevertsTheOldest() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = javaPlayer();
        try {
            var world = player.compensatedWorld;
            int air = DataTables.defaults().registry().block("minecraft:air").defaultState();
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            int cap = intConstant("MAX_PENDING_PREDICTIONS");
            int count = cap + 64;
            world.ensureValidationChunkLoaded(0, 0);
            for (int i = 0; i < count; i++) {
                BlockPos pos = position(i);
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), air);
                world.advanceClientPredictionSequence();
                world.startPredicting();
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), stone);
                world.stopPredicting(0);
                world.handlePredictionConfirmation(0, player.lastTransactionSent.get());
            }
            BlockPos oldest = position(0);
            assertEquals(cap, ((Map<?, ?>) field(world, "serverIsCurrentlyProcessingThesePredictions")).size());
            assertEquals(stone, world.getBlockStateIdAt(oldest));
            player.latencyUtils.handleNettySyncTransaction(player.lastTransactionSent.get() + 1);

            assertEquals(cap, ((Map<?, ?>) field(world, "serverIsCurrentlyProcessingThesePredictions")).size());
            assertEquals(cap, ((Map<?, ?>) field(world, "originalServerBlocks")).size());
            assertFalse(world.hasPendingBlockPrediction(oldest));
            assertEquals(air, world.getBlockStateIdAt(oldest));
            BlockPos newest = position(count - 1);
            assertTrue(world.hasPendingBlockPrediction(newest));
            assertEquals(stone, world.getBlockStateIdAt(newest));
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void acknowledgedPlacementStillResolves() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = javaPlayer();
        try {
            var world = player.compensatedWorld;
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            BlockPos pos = position(0);
            world.ensureValidationChunkLoaded(0, 0);
            world.advanceClientPredictionSequence();
            world.startPredicting();
            world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), stone);
            world.stopPredicting(1);
            assertTrue(world.hasPendingBlockPrediction(pos));

            world.handleServerBlockUpdate(pos, stone, 10);
            world.handlePredictionConfirmation(1, 10);
            player.latencyUtils.handleNettySyncTransaction(10);

            assertFalse(world.hasPendingBlockPrediction(pos));
            assertEquals(stone, world.getBlockStateIdAt(pos));
            assertTrue(((Map<?, ?>) field(world, "serverIsCurrentlyProcessingThesePredictions")).isEmpty());
            assertTrue(((Map<?, ?>) field(world, "originalServerBlocks")).isEmpty());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    private static CultPlayer javaPlayer() {
        CultPlayer player = OfflineBedrockReplayRunnerTest.offlinePlayer();
        player.movementPlatform = MovementPlatform.JAVA;
        return player;
    }

    private static BlockPos position(int i) {
        return new BlockPos(i & 15, 64 + (i >> 8), (i >> 4) & 15);
    }

    private static Object field(CompensatedWorld world, String name) throws Exception {
        Field field = CompensatedWorld.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(world);
    }

    private static int intConstant(String name) throws Exception {
        Field field = CompensatedWorld.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
