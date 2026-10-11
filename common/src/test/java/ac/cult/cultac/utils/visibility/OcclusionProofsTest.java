package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class OcclusionProofsTest {
    @Test
    void ordinaryBlockedAndClearPicksHaveCheapProofsInEveryAxis() {
        for (int axis = 0; axis < 3; axis++) {
            for (int sign : new int[] {-1, 1}) {
                for (double offset : new double[] {-30_000_000, -.12345, 0, 30_000_000}) {
                    var eye = new Vec3(offset, offset, offset);
                    var target = along(axis, sign, offset, -.3, .3, 3, 4);
                    var wall = along(axis, sign, offset, -1, 1, 1, 2);
                    assertEquals(Boolean.FALSE, proof(eye, target, null, List.of(wall)));
                    assertTrue(OcclusionGeometry.visiblePoints(eye, target, null, List.of(wall))
                            .isEmpty());
                    var behind = along(axis, sign, offset, -1, 1, 5, 6);
                    assertEquals(Boolean.TRUE, proof(eye, target, null, List.of(behind)));
                    assertFalse(OcclusionGeometry.visiblePoints(eye, target, null, List.of(behind))
                            .isEmpty());
                }
            }
        }
    }

    @Test
    void touchingCornersAndSubBufferOpeningsUseExactGeometry() {
        var eye = Vec3.ZERO;
        var target = box(-1, -1, 4, 1, 1, 5);
        for (double slit : new double[] {Math.scalb(1, -45), .00001, .001}) {
            var blockers = List.of(box(-1, -1, 2, -slit, 1, 3), box(slit, -1, 2, 1, 1, 3));
            assertTrue(OcclusionGeometry.hasVisiblePoint(eye, target, null, blockers, null, 10));
        }
        var point = box(2, 0, 2, 2, 0, 2);
        var corner = box(1, -1, 0, 2, 1, 1);
        assertNotEquals(Boolean.TRUE, proof(eye, point, null, List.of(corner)));
        assertTrue(OcclusionGeometry.fullyOccluded(eye, point, List.of(corner)));
        var beyond = box(2, -1, 2, 3, 1, 3);
        assertNotEquals(Boolean.TRUE, proof(eye, point, null, List.of(beyond)));
    }

    @Test
    void clearWitnessCannotIgnoreReachFaceOrInteractionMask() {
        var target = box(-1, -1, 4, 1, 1, 5);
        assertNull(OcclusionProofs.visible(Vec3.ZERO, target, null, List.of(), null, 4));
        assertNull(proof(Vec3.ZERO, target, Direction.SOUTH, List.of()));
        assertNull(OcclusionProofs.visible(Vec3.ZERO, target, null, List.of(), List.of(), 10));
        assertFalse(OcclusionGeometry.hasVisiblePoint(Vec3.ZERO, target, null, List.of(), List.of(), 10));
    }

    @Test
    void roundedProofsAgreeWithExactShadowsAcrossRandomGeometry() {
        var random = new Random(0x7a119ce);
        int proved = 0;
        for (int i = 0; i < 350; i++) {
            double offset = i % 3 == 0 ? 30_000_000 : i % 3 == 1 ? -30_000_000 : 0;
            var eye =
                    new Vec3(offset + random.nextDouble(), offset + random.nextDouble(), offset + random.nextDouble());
            var target = box(
                    offset + random.nextDouble() - .5,
                    offset + random.nextDouble() - .5,
                    offset + 3,
                    offset + 1.5,
                    offset + 1.5,
                    offset + 4);
            List<SimpleCollisionBox> blockers = new ArrayList<>();
            for (int j = 0, count = random.nextInt(5); j < count; j++) {
                double x = offset + random.nextDouble() * 3 - 1.5;
                double y = offset + random.nextDouble() * 3 - 1.5;
                double z = offset + random.nextDouble() * 4;
                blockers.add(box(x, y, z, x + random.nextDouble() * 2, y + random.nextDouble() * 2, z + .3));
            }
            Direction face = i % 4 == 0 ? Direction.values()[random.nextInt(6)] : null;
            Boolean proof = proof(eye, target, face, blockers);
            if (proof == null) continue;
            proved++;
            assertEquals(
                    !OcclusionGeometry.visiblePoints(eye, target, face, blockers)
                            .isEmpty(),
                    proof,
                    "scene " + i);
        }
        assertTrue(proved > 100, "Exercise both proof and fallback paths");
    }

    @Test
    void filteredPlanesRetainExactSignsAtAndAroundAnIntersection() {
        var random = new Random(0x51a9);
        for (int i = 0; i < 300; i++) {
            var a = ExactFraction.of(random.nextDouble() * 40 - 20);
            var b = ExactFraction.of(random.nextDouble() * 40 - 20);
            var point = new ConvexRegion.Point(
                    ExactFraction.of(random.nextDouble() * 30_000_000).divide(ExactFraction.of(3)),
                    ExactFraction.of(random.nextDouble() * 30_000_000).divide(ExactFraction.of(7)));
            var constant = a.multiply(point.x()).add(b.multiply(point.y())).negate();
            for (double shift : new double[] {-Math.scalb(1.0, -45), 0, Math.scalb(1.0, -45)}) {
                var plane = new ConvexRegion.HalfPlane(a, b, constant.add(ExactFraction.of(shift)), false);
                assertEquals(Double.compare(shift, 0), plane.signum(point));
                assertEquals(plane.value(point).signum(), plane.signum(point));
            }
        }
    }

    private static Boolean proof(
            Vec3 eye, SimpleCollisionBox target, Direction face, List<SimpleCollisionBox> blockers) {
        return OcclusionProofs.visible(eye, target, face, blockers, null, Double.POSITIVE_INFINITY);
    }

    private static SimpleCollisionBox along(
            int axis, int sign, double offset, double low, double high, double near, double far) {
        double[] min = {offset + low, offset + low, offset + low};
        double[] max = {offset + high, offset + high, offset + high};
        min[axis] = offset + (sign > 0 ? near : -far);
        max[axis] = offset + (sign > 0 ? far : -near);
        return box(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    private static SimpleCollisionBox box(double x, double y, double z, double xx, double yy, double zz) {
        return new SimpleCollisionBox(x, y, z, xx, yy, zz);
    }
}
