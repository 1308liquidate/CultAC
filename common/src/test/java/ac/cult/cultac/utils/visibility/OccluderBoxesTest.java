package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class OccluderBoxesTest {
    @Test
    void touchingPiecesRemainASolidWallAfterCoalescing() {
        var left = new SimpleCollisionBox(-1, -1, 2, 0, 1, 3);
        var right = new SimpleCollisionBox(0, -1, 2, 1, 1, 3);
        var target = new SimpleCollisionBox(-1, -1, 4, 1, 1, 5);
        assertTrue(OcclusionGeometry.fullyOccluded(Vec3.ZERO, target, OccluderBoxes.coalesce(List.of(left, right))));
        assertEquals(0, left.maxX, "Input geometry must remain unchanged");
    }

    @Test
    void aRealOpeningOfAnySizeIsNeverJoined() {
        double slit = Math.scalb(1.0, -45);
        var left = new SimpleCollisionBox(-1, -1, 2, -slit, 1, 3);
        var right = new SimpleCollisionBox(slit, -1, 2, 1, 1, 3);
        var target = new SimpleCollisionBox(-1, -1, 4, 1, 1, 5);
        assertFalse(OcclusionGeometry.fullyOccluded(Vec3.ZERO, target, OccluderBoxes.coalesce(List.of(left, right))));
    }
}
