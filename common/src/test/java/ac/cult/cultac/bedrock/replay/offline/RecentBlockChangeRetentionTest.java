package ac.cult.cultac.bedrock.replay.offline;

import static org.junit.Assert.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.bedrock.MovementPlatform;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.latency.CompensatedWorld;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Test;

/** Block-change state that only client_tick_end expires stays bounded for a client that never ends a tick. */
public class RecentBlockChangeRetentionTest {
    @Test
    public void recentChangesKeepTheNewestWithoutTickEnds() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = javaPlayer();
        try {
            var world = player.compensatedWorld;
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            int water =
                    DataTables.defaults().registry().block("minecraft:water").defaultState();
            int cap = intConstant("MAX_RECENT_CLIENT_CHANGES");
            int count = cap + 64;
            world.ensureValidationChunkLoaded(0, 0);
            // Stone changes only collision and water only fluid, so each map gets its own count of changes.
            for (int i = 0; i < count; i++) {
                BlockPos pos = position(i);
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), stone);
                pos = position(count + i);
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), water);
            }

            assertEquals(cap, ((Map<?, ?>) field(world, "recentClientCollisionChanges")).size());
            assertEquals(cap, ((Map<?, ?>) field(world, "recentClientFluidChanges")).size());
            assertEquals(cap, ((Map<?, ?>) field(world, "recentClientFluidChangeKinds")).size());
            assertFalse(world.hasRecentClientCollisionChanges(box(position(0))));
            assertFalse(world.hasRecentClientWaterChanges(box(position(count))));
            assertTrue(world.hasRecentClientCollisionChanges(box(position(count - 1))));
            assertTrue(world.hasRecentClientWaterChanges(box(position(2 * count - 1))));

            for (int tick = 0; tick < 3; tick++) world.removeInvalidPistonLikeStuff();
            assertTrue(((Map<?, ?>) field(world, "recentClientCollisionChanges")).isEmpty());
            assertTrue(((Map<?, ?>) field(world, "recentClientFluidChanges")).isEmpty());
            assertTrue(((Map<?, ?>) field(world, "recentClientFluidChangeKinds")).isEmpty());
        } finally {
            OfflineBedrockReplayRunnerTest.closeOfflinePlayer(player);
        }
    }

    @Test
    public void selectionSnapshotStopsRecordingWithoutTickEnds() throws Exception {
        OfflineCultTestBootstrap.installConfig();
        CultPlayer player = javaPlayer();
        try {
            var world = player.compensatedWorld;
            int air = DataTables.defaults().registry().block("minecraft:air").defaultState();
            int stone =
                    DataTables.defaults().registry().block("minecraft:stone").defaultState();
            int cap = intConstant("MAX_SELECTION_ORIGINAL_STATES");
            int count = cap + 64;
            world.ensureValidationChunkLoaded(0, 0);
            for (int i = 0; i < count; i++) {
                BlockPos pos = position(i);
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), stone);
            }
            world.beginSelectionSnapshot();
            for (int i = 0; i < count; i++) {
                BlockPos pos = position(i);
                world.advanceClientPredictionSequence();
                world.startPredicting();
                world.updateBlock(pos.getX(), pos.getY(), pos.getZ(), air);
                world.stopPredicting(0);
            }

            assertEquals(cap, ((Map<?, ?>) field(world, "selectionOriginalStates")).size());
            assertEquals(stone, (int) world.withSelectionSnapshot(() -> world.getBlockStateIdAt(position(0))));
            assertEquals(stone, (int) world.withSelectionSnapshot(() -> world.getBlockStateIdAt(position(cap - 1))));

            world.endSelectionSnapshot();
            assertTrue(((Map<?, ?>) field(world, "selectionOriginalStates")).isEmpty());
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

    private static SimpleCollisionBox box(BlockPos pos) {
        return new SimpleCollisionBox(
                pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
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
