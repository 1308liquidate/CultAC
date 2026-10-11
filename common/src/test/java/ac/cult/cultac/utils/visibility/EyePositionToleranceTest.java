package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class EyePositionToleranceTest {
    @Test
    void bufferStopsAtANearbyFaceInsteadOfMovingIntoTheBlock() {
        var eye = new Vec3(-.0005, .5, .5);
        var wall = box(0, 0, 0, 1, 1, 1);
        var bounds = EyePositionTolerance.bounds(eye, 0, List.of(wall));
        assertTrue(bounds.maxX < 0);
        assertEquals(eye.x - .001, bounds.minX);
        assertSafeCandidates(eye, bounds, List.of(wall));
    }

    @Test
    void bothSeparatingPlanesAreRetainedAtADiagonalCorner() {
        var eye = new Vec3(-.0005, .5, -.0005);
        var corner = box(0, 0, 0, 1, 1, 1);
        var bounds = EyePositionTolerance.bounds(eye, 0, List.of(corner));
        assertTrue(bounds.maxX < 0);
        assertTrue(bounds.maxZ < 0);
        assertSafeCandidates(eye, bounds, List.of(corner));
    }

    @Test
    void candidateCannotAppearOnTheOtherSideOfAThinWall() {
        var eye = new Vec3(0, .5, .5);
        var thinWall = box(.0002, 0, 0, .0003, 1, 1);
        var bounds = EyePositionTolerance.bounds(eye, 0, List.of(thinWall));
        assertTrue(bounds.maxX < thinWall.minX);
        assertSafeCandidates(eye, bounds, List.of(thinWall));
    }

    @Test
    void alreadyInsideOriginIsPreservedWithoutExpansion() {
        var eye = new Vec3(.0005, .5, .5);
        var wall = box(0, 0, 0, .001, 1, 1);
        var bounds = EyePositionTolerance.bounds(eye, 0, List.of(wall));
        assertEquals(eye, bounds.getCenter());
        assertTrue(EyePositionTolerance.candidates(eye, bounds).isEmpty());
    }

    @Test
    void clippedBufferIsIndependentOfShapeOrderAndWorldTranslation() {
        for (double offset : new double[] {-30_000_000, 0, 30_000_000}) {
            var eye = new Vec3(offset, offset, offset);
            var left = box(offset - 1, offset - 1, offset - 1, offset - .0004, offset + 1, offset + 1);
            var right = box(offset + .0004, offset - 1, offset - 1, offset + 1, offset + 1, offset + 1);
            var a = EyePositionTolerance.bounds(eye, .0002, List.of(left, right));
            var b = EyePositionTolerance.bounds(eye, .0002, List.of(right, left));
            assertEquals(a.minX, b.minX);
            assertEquals(a.maxX, b.maxX);
            assertSafeCandidates(eye, a, List.of(left, right));
        }
    }

    private static void assertSafeCandidates(Vec3 eye, SimpleCollisionBox bounds, List<SimpleCollisionBox> shapes) {
        for (Vec3 candidate : EyePositionTolerance.candidates(eye, bounds)) {
            var point = box(candidate.x, candidate.y, candidate.z, candidate.x, candidate.y, candidate.z);
            // Exact closed-box occlusion also rejects a segment that crosses a
            // corner or passes through a wall despite both endpoints being outside.
            assertFalse(OcclusionGeometry.fullyOccluded(eye, point, shapes), candidate.toString());
        }
    }

    private static SimpleCollisionBox box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new SimpleCollisionBox(x0, y0, z0, x1, y1, z1);
    }
}
