package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

class NativeInsideSelectionTest {
    @Test
    void sampleMustReachTheShapeWithTheClaimedDominantDirection() {
        var eye = new Vec3(0, 0, 0);
        var box = new SimpleCollisionBox(.002, -.001, -.001, .003, .001, .001);
        assertTrue(NativeInsideSelection.possible(eye, box, Direction.WEST, 0, .00001, .0045, 4.5));
        assertFalse(NativeInsideSelection.possible(eye, box, Direction.EAST, 0, .00001, .0045, 4.5));
        assertFalse(NativeInsideSelection.possible(eye, box, Direction.NORTH, 0, .00001, .0045, 4.5));
        assertFalse(NativeInsideSelection.possible(eye, box, Direction.WEST, 0, .00001, .001, 4.5));
    }

    @Test
    void minimumRayLengthAndInteractionRangeConstrainTheFeasibleShell() {
        var eye = new Vec3(0, 0, 0);
        var tiny = new SimpleCollisionBox(0, 0, 0, .000001, .000001, .000001);
        assertFalse(NativeInsideSelection.possible(eye, tiny, Direction.WEST, 0, .00001, .0045, 4.5));
        var far = new SimpleCollisionBox(.002, 0, 0, .003, .001, .001);
        assertFalse(NativeInsideSelection.possible(eye, far, Direction.WEST, 0, .00001, .0045, .001));
        assertTrue(NativeInsideSelection.possible(eye, far, Direction.WEST, 0, .00001, .0045, .004));
    }
}
