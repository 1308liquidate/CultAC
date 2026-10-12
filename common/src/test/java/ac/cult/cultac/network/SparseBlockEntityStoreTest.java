package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.latency.CompensatedBlockEntities;
import org.junit.jupiter.api.Test;

class SparseBlockEntityStoreTest {
    @Test
    void ordinaryBlockWritesDoNotAllocateAStore() throws Exception {
        var entities = new CompensatedBlockEntities(ac.cult.blocksim.data.InteractionRegistries::defaults);
        var registry = DataTables.defaults().registry();
        int stone = registry.block("minecraft:stone").defaultState();
        int air = registry.block("minecraft:air").defaultState();
        var field = CompensatedBlockEntities.class.getDeclaredField("entries");
        field.setAccessible(true);
        assertNull(field.get(entities));
        for (int i = 0; i < 1000; i++) {
            var pos = new BlockPos(i - 500, 64, -i);
            entities.stateChanged(pos, air, stone);
            assertNull(entities.snapshot(pos, stone));
        }
        entities.tick();
        entities.forgetChunk(-1, -1);
        assertNull(field.get(entities));
    }

    @Test
    void retainedAnimationsTickAndChunkCleanupUseTheSameSignedPositionKeys() throws Exception {
        var entities = new CompensatedBlockEntities(ac.cult.blocksim.data.InteractionRegistries::defaults);
        var registry = DataTables.defaults().registry();
        int shulker = registry.block("minecraft:shulker_box").defaultState();
        int stone = registry.block("minecraft:stone").defaultState();
        var first = new BlockPos(-17, -64, -33);
        var second = new BlockPos(32, 255, 16);
        entities.blockEvent(first, shulker, 1, 1);
        entities.blockEvent(second, shulker, 1, 1);
        var original = entities.snapshot(first, shulker);
        assertNotNull(original);
        entities.tick();
        var expected = ac.cult.blocksim.engine.ShulkerAnimation.tick(original);
        var actual = entities.snapshot(first, shulker);
        assertEquals(expected.type(), actual.type());
        assertEquals(expected.data().keys(), actual.data().keys());
        for (String key : expected.data().keys()) assertTrue(expected.data().sameValue(key, actual.data()), key);
        assertEquals(expected.savedData(), actual.savedData());
        entities.forgetChunk(first.getX() >> 4, first.getZ() >> 4);
        var field = CompensatedBlockEntities.class.getDeclaredField("entries");
        field.setAccessible(true);
        var retained = (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?>) field.get(entities);
        assertFalse(retained.containsKey(first.asLong()));
        assertTrue(retained.containsKey(second.asLong()));
        entities.stateChanged(second, shulker, stone);
        assertFalse(retained.containsKey(second.asLong()));
        entities.clear();
        assertNull(field.get(entities));
    }
}
