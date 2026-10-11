package ac.cult.cultac.utils.visibility;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConvexRegionTest {
    @Test
    void subtractingAPlaneThroughVerticesPreservesItsExactBoundary() {
        for (boolean strict : new boolean[] {false, true}) {
            var region = square().subtract(List.of(plane(1, 1, 0, strict)));
            assertEquals(1, region.size());
            var outside = region.getFirst();
            for (double[] point : new double[][] {{-1, 1}, {0, 0}, {1, -1}}) {
                assertEquals(strict, contains(outside, point[0], point[1]));
            }
            double tiny = Math.scalb(1, -45);
            assertTrue(contains(outside, tiny, 0));
            assertFalse(contains(outside, -tiny, 0));
            assertTrue(contains(outside, 1, 1));
            assertFalse(contains(outside, -1, -1));
        }
    }

    @Test
    void unchangedClosedVerticesStillRetainEveryOpenEdge() {
        var region = square().clip(plane(-1, 0, 0, true)); // x > 0
        region = region.clip(plane(0, 1, -1, true)); // y < 1, same closed vertices
        var outside = region.subtract(List.of(plane(0, -1, 0, false))).getFirst(); // y < 0
        double tiny = Math.scalb(1, -45);
        assertTrue(contains(outside, tiny, -tiny));
        assertFalse(contains(outside, 0, -tiny));
        assertFalse(contains(outside, tiny, 0));
        assertFalse(contains(region, 1, 1));
        assertTrue(contains(region, 1, 1 - tiny));
    }

    @Test
    void subtractingFromLinesAndPointsDoesNotInventOrDiscardBoundaryHits() {
        var line = square().clip(plane(1, 0, 0, false)).clip(plane(-1, 0, 0, false));
        var outside = line.subtract(List.of(plane(0, 1, 0, false))).getFirst();
        assertTrue(contains(outside, 0, Math.scalb(1, -45)));
        assertFalse(contains(outside, 0, 0));
        assertFalse(contains(outside, Math.scalb(1, -45), 1));
        var point = line.clip(plane(0, 1, 0, false)).clip(plane(0, -1, 0, false));
        assertTrue(point.subtract(List.of(plane(1, 0, 0, false))).isEmpty());
        assertTrue(contains(point.subtract(List.of(plane(1, 0, 0, true))).getFirst(), 0, 0));
    }

    private static ConvexRegion square() {
        return ConvexRegion.rectangle(q(-1), q(-1), q(1), q(1));
    }

    private static boolean contains(ConvexRegion region, double x, double y) {
        for (var plane : List.of(
                plane(1, 0, -x, false), plane(-1, 0, x, false), plane(0, 1, -y, false), plane(0, -1, y, false))) {
            region = region.clip(plane);
            if (region == null) return false;
        }
        return true;
    }

    private static ConvexRegion.HalfPlane plane(double a, double b, double c, boolean strict) {
        return new ConvexRegion.HalfPlane(q(a), q(b), q(c), strict);
    }

    private static ExactFraction q(double value) {
        return ExactFraction.of(value);
    }
}
