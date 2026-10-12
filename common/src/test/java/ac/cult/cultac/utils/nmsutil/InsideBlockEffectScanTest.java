package ac.cult.cultac.utils.nmsutil;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class InsideBlockEffectScanTest {
    @Test
    void everyCellInTheOrderedTraversalIsInsideTheAbsenceProofRegion() {
        Random random = new Random(0x1B51DE);
        int web = state("cobweb");
        for (int sample = 0; sample < 2400; sample++) {
            double x = random.nextInt(10) - 5 + (sample % 3 == 0 ? 1e-7 : random.nextDouble());
            double y = random.nextInt(10) - 5 + random.nextDouble();
            double z = random.nextInt(10) - 5 + (sample % 3 == 1 ? -1e-7 : random.nextDouble());
            var from = new SimpleCollisionBox(x, y, z, x + 0.6F, y + 1.8F, z + 0.6F);
            var delta = new Vec3(random.nextDouble() * 6 - 3, random.nextDouble() * 6 - 3, random.nextDouble() * 6 - 3);
            double width = sample % 5 == 0 ? 1.0E-6 : random.nextDouble() * 3;
            double height = sample % 7 == 0 ? 0.0 : random.nextDouble() * 3;
            var to = new SimpleCollisionBox(x + delta.x, y + delta.y, z + delta.z,
                    x + delta.x + width, y + delta.y + height, z + delta.z + width);
            var seen = new ArrayList<long[]>();
            Collisions.visitInsideBlocks26Dot2(from, to, sample % 2 == 0 ? delta : null, (a, b, pos, precise) -> {
                seen.add(new long[] {pos.getX(), pos.getY(), pos.getZ()});
                return true;
            });
            for (long[] cell : seen) assertTrue(InsideBlockEffectScan.mayHaveEffects(from, to, true,
                    (cx, cy, cz) -> cx == cell[0] && cy == cell[1] && cz == cell[2] ? web : 0));
        }
    }

    @Test
    void checksEveryMovementEffectFamilyAndDoesNotInventEffectsInOrdinaryBlocks() {
        var box = new SimpleCollisionBox(0.2, 64, 0.2, 0.8, 65.8, 0.8);
        for (String key : List.of("cobweb", "sweet_berry_bush", "powder_snow", "honey_block", "bubble_column")) {
            int block = state(key);
            assertTrue(InsideBlockEffectScan.mayHaveEffects(box, box, false, (x, y, z) -> block));
            assertEquals(!key.equals("honey_block") && !key.equals("bubble_column"),
                    InsideBlockEffectScan.mayHaveEffects(box, box, true, (x, y, z) -> block));
        }
        for (String key : List.of("air", "stone", "water", "oak_slab"))
            assertFalse(InsideBlockEffectScan.mayHaveEffects(box, box, false, (x, y, z) -> state(key)));
    }

    @Test
    void largeAndNonFiniteBoundsFallBackWithoutScanningOrIntegerOverflow() {
        var ordinary = new SimpleCollisionBox(0, 0, 0, 0.6, 1.8, 0.6);
        var fallback = List.of(ordinary.copy().offset(1000, 0, 0),
                new SimpleCollisionBox(Double.NaN, 0, 0, 1, 1, 1),
                new SimpleCollisionBox(Double.NEGATIVE_INFINITY, 0, 0, 1, 1, 1),
                new SimpleCollisionBox(Integer.MAX_VALUE - 1.0, 0, 0, Integer.MAX_VALUE - 0.5, 1, 1));
        for (var box : fallback) assertTrue(InsideBlockEffectScan.mayHaveEffects(box, ordinary, false,
                (x, y, z) -> { fail("Oversized or invalid region must use the original traversal"); return 0; }));
    }

    private static int state(String key) { return DataTables.defaults().registry().block("minecraft:" + key).defaultState(); }
}
