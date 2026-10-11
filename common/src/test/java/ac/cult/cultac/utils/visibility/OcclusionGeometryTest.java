package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;
import java.util.List;
import org.junit.jupiter.api.Test;

class OcclusionGeometryTest {
    private static final Vec3 EYE = Vec3.ZERO;
    private static final SimpleCollisionBox TARGET = box(-1, -1, 4, 1, 1, 5);

    @Test
    void nearerWallCoversTargetButWallBehindItDoesNot() {
        assertTrue(OcclusionGeometry.fullyOccluded(EYE, TARGET, List.of(box(-2, -2, 2, 2, 2, 3))));
        assertFalse(OcclusionGeometry.fullyOccluded(EYE, TARGET, List.of(box(-2, -2, 6, 2, 2, 7))));
    }

    @Test
    void multiplePartialShadowsMustJointlyCoverTheTarget() {
        var left = box(-1, -1, 2, 0, 1, 3);
        var right = box(0, -1, 2, 1, 1, 3);
        assertFalse(OcclusionGeometry.fullyOccluded(EYE, TARGET, List.of(left)));
        assertFalse(OcclusionGeometry.fullyOccluded(EYE, TARGET, List.of(right)));
        assertTrue(OcclusionGeometry.fullyOccluded(EYE, TARGET, List.of(left, right)));
    }

    @Test
    void arbitrarilySmallPositiveOpeningRemainsVisible() {
        double slit = Math.scalb(1.0, -45);
        assertFalse(OcclusionGeometry.fullyOccluded(
                EYE, TARGET, List.of(box(-1, -1, 2, -slit, 1, 3), box(slit, -1, 2, 1, 1, 3))));
    }

    @Test
    void cameraPlaneCrossingDoesNotDiscardOccludersOrTargets() {
        assertTrue(OcclusionGeometry.fullyOccluded(EYE, TARGET, List.of(box(-2, -2, -1, 2, 2, 3))));
        var wideTarget = box(1, -100, -1, 2, 1, 1);
        assertFalse(OcclusionGeometry.fullyOccluded(EYE, wideTarget, List.of()));
        assertTrue(OcclusionGeometry.fullyOccluded(EYE, wideTarget, List.of(box(.5, -100, -2, .75, 100, 2))));
    }

    @Test
    void touchingAnOccluderDoesNotBlockRaysLeavingItsSurface() {
        assertFalse(OcclusionGeometry.fullyOccluded(EYE, TARGET, List.of(box(-1, -1, -1, 1, 1, 0))));
        assertTrue(OcclusionGeometry.fullyOccluded(EYE, TARGET, List.of(box(-1, -1, 0, 1, 1, 1))));
    }

    @Test
    void claimedBackFaceHasNoVisiblePointsEvenWithoutAWall() {
        assertFalse(OcclusionGeometry.visiblePoints(EYE, TARGET, Direction.NORTH, List.of())
                .isEmpty());
        assertTrue(OcclusionGeometry.visiblePoints(EYE, TARGET, Direction.SOUTH, List.of())
                .isEmpty());
    }

    @Test
    void exactClippingPreservesAVisibleLineAndPoint() {
        var line = ConvexRegion.rectangle(q(0), q(0), q(1), q(1))
                .clip(new ConvexRegion.HalfPlane(q(1), q(0), q(0), false));
        assertNotNull(line);
        var point = line.clip(new ConvexRegion.HalfPlane(q(0), q(1), q(0), false));
        assertNotNull(point);
        assertNull(point.clip(new ConvexRegion.HalfPlane(q(1), q(0), q(0), true)));
    }

    @Test
    void translationAndNegativeAxisViewsPreserveCoverage() {
        for (double offset : new double[] {-30_000_000, -100, 0, 100, 30_000_000}) {
            var eye = new Vec3(offset, offset, offset);
            var target = box(offset - 5, offset - 1, offset - 1, offset - 4, offset + 1, offset + 1);
            var wall = box(offset - 3, offset - 2, offset - 2, offset - 2, offset + 2, offset + 2);
            assertTrue(OcclusionGeometry.fullyOccluded(eye, target, List.of(wall)));
        }
    }

    private static ExactFraction q(double value) {
        return ExactFraction.of(value);
    }

    @Test
    void reachMustIntersectTheRemainingVisibleRegion() {
        var target = box(2, -2, 0, 2, 2, 4);
        var coverNearPart = box(1, -4, -1, 1.5, 4, 1);
        assertTrue(OcclusionGeometry.hasVisiblePoint(EYE, target, null, List.of(coverNearPart), null, 5));
        assertFalse(OcclusionGeometry.hasVisiblePoint(EYE, target, null, List.of(coverNearPart), null, 2.1));
        assertFalse(OcclusionGeometry.hasVisiblePoint(EYE, target, null, List.of(), null, 2));
    }

    @Test
    void aFaceOverrideMaskMustCrossTheRayBeforeTheOutline() {
        assertTrue(
                OcclusionGeometry.hasVisiblePoint(EYE, TARGET, null, List.of(), List.of(box(-1, -1, 2, 1, 1, 2)), 10));
        assertFalse(
                OcclusionGeometry.hasVisiblePoint(EYE, TARGET, null, List.of(), List.of(box(-1, -1, 8, 1, 1, 8)), 10));
    }

    private static SimpleCollisionBox box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new SimpleCollisionBox(x0, y0, z0, x1, y1, z1);
    }
}
