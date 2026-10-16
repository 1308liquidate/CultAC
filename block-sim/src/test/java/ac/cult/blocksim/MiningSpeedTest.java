package ac.cult.blocksim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.blocksim.data.ItemRegistry;
import ac.cult.blocksim.engine.BlockSimClientVersion;
import ac.cult.blocksim.interaction.MiningSpeed;
import org.junit.jupiter.api.Test;

class MiningSpeedTest {
    private static final DataTables DATA;
    private static final ItemRegistry ITEMS;
    private static final MiningSpeed MINING;
    static {
        try {
            DATA = DataTables.load("26.3"); ITEMS = new ItemRegistry(DATA); MINING = new MiningSpeed(DATA);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static final int STONE = BlockIds.STONE.defaultState();

    private static MiningSpeed.State state(double efficiency, double breakSpeed, int fatigue) {
        return new MiningSpeed.State(true, false, efficiency, breakSpeed, .2, -1, -1, fatigue);
    }

    @Test
    void attributesFollowPlayerGetDestroySpeedOrder() {
        var pickaxe = ITEMS.stack("minecraft:iron_pickaxe", 1);
        assertEquals((6.0F + 26.0F) * 11.0F / 1.5F / 30,
            MINING.progress(pickaxe, STONE, state(26, 11, -1), BlockSimClientVersion.V26_3));
        // MINING_EFFICIENCY applies only above the item's default speed of 1.
        assertEquals(11.0F / 1.5F / 100,
            MINING.progress(ITEMS.empty(), STONE, state(26, 11, -1), BlockSimClientVersion.V26_3));
    }

    @Test
    void miningFatigueFormFollowsClientVersion() {
        var pickaxe = ITEMS.stack("minecraft:iron_pickaxe", 1);
        assertEquals(6.0F * (float)Math.pow(0.3, 3) / 1.5F / 30,
            MINING.progress(pickaxe, STONE, state(0, 1, 2), BlockSimClientVersion.V26_3));
        assertEquals(6.0F * 0.0027F / 1.5F / 30,
            MINING.progress(pickaxe, STONE, state(0, 1, 2), BlockSimClientVersion.V26_2));
    }
}
