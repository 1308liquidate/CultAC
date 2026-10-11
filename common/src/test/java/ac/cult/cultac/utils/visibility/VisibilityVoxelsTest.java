package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VisibilityVoxelsTest {
    @Test
    void includesEveryOwningVoxelAlongIndependentSegmentsInEveryOctant() {
        var random = new Random(0x51A17);
        var query = new SimpleCollisionBox(-16, -16, -16, 16, 16, 16);
        for (int scenario = 0; scenario < 160; scenario++) {
            var eye = new Vec3(random.nextDouble() * 4 - 2, random.nextDouble() * 4 - 2, random.nextDouble() * 4 - 2);
            double x = random.nextDouble() * 20 - 10,
                    y = random.nextDouble() * 20 - 10,
                    z = random.nextDouble() * 20 - 10;
            var target = new SimpleCollisionBox(x, y, z, x + .9, y + .7, z + .5);
            var visited = new HashSet<BlockPos>();
            VisibilityVoxels.visit(eye, target, 30, query, visited::add);
            for (int ray = 0; ray < 12; ray++) {
                var end = new Vec3(
                        x + random.nextDouble() * .9, y + random.nextDouble() * .7, z + random.nextDouble() * .5);
                // Dense independent segment samples validate broadphase coverage;
                // the implementation itself never samples rays.
                for (int step = 0; step <= 200; step++) {
                    double t = step / 200.0;
                    var cell = new BlockPos(
                            (int) Math.floor(eye.x + (end.x - eye.x) * t),
                            (int) Math.floor(eye.y + (end.y - eye.y) * t),
                            (int) Math.floor(eye.z + (end.z - eye.z) * t));
                    assertTrue(visited.contains(cell), "Missing " + cell + " for target " + target);
                }
            }
        }
    }

    @Test
    void remainsBoundedByReceivedChunkAndWorldHeight() {
        var visited = new HashSet<BlockPos>();
        VisibilityVoxels.visit(
                new Vec3(0, 64, 0),
                new SimpleCollisionBox(1, 64, 1, 1e9, 1e9, 1e9),
                1e9,
                new SimpleCollisionBox(0, 64, 0, 16, 80, 16),
                visited::add);
        assertFalse(visited.isEmpty());
        assertTrue(visited.size() <= 4096);
        for (var cell : visited)
            assertTrue(cell.x() >= 0
                    && cell.x() < 16
                    && cell.y() >= 64
                    && cell.y() < 80
                    && cell.z() >= 0
                    && cell.z() < 16);
    }
}
